package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/jackc/pgx/v5/pgxpool"
)

type countedStore struct {
	ObjectStore
	uploads int
}

func (s *countedStore) deliver(ctx context.Context, t Task, f *os.File) (string, error) {
	s.uploads++
	return s.ObjectStore.deliver(ctx, t, f)
}
func TestDurableReceiptRecovery(t *testing.T) {
	dsn := os.Getenv("DELIVERY_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("requires isolated local delivery PostgreSQL and S3Mock")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	admin, err := pgxpool.New(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close()
	var random [16]byte
	_, _ = rand.Read(random[:])
	schema := "test_" + hex.EncodeToString(random[:])
	if _, err = admin.Exec(ctx, "CREATE SCHEMA "+schema); err != nil {
		t.Fatal(err)
	}
	defer admin.Exec(context.Background(), "DROP SCHEMA "+schema+" CASCADE")
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		t.Fatal(err)
	}
	cfg.ConnConfig.RuntimeParams["search_path"] = schema
	cfg.MaxConns = 4
	db, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	if err = migrate(ctx, db); err != nil {
		t.Fatal(err)
	}
	id := fmt.Sprintf("%x-%x-%x-%x-%x", random[0:4], random[4:6], random[6:8], random[8:10], random[10:16])
	content := []byte("%PDF-1.4\nretained retry fixture\n%%EOF\n")
	sum := sha256.Sum256(content)
	task := Task{ID: id, ArtifactID: id, Provider: "S3", Bucket: "report-results", DestinationRef: "finance-archive", ObjectKey: "finance/2026/" + id + ".pdf", SHA256: hex.EncodeToString(sum[:]), Size: int64(len(content))}
	artifactReads := 0
	receipts := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.URL.Path == "/token":
			fmt.Fprint(w, `{"access_token":"test-service-token","expires_in":300}`)
		case r.URL.Path == "/backend/api/v1/internal/deliveries":
			_ = json.NewEncoder(w).Encode([]Task{task})
		case strings.HasSuffix(r.URL.Path, "/attempts"):
			w.WriteHeader(204)
		case strings.HasSuffix(r.URL.Path, "/artifact"):
			artifactReads++
			_, _ = w.Write(content)
		case strings.HasSuffix(r.URL.Path, "/receipt"):
			receipts++
			if receipts == 1 {
				w.WriteHeader(503)
			} else {
				w.WriteHeader(204)
			}
		default:
			w.WriteHeader(404)
		}
	}))
	defer server.Close()
	awsConfig, err := config.LoadDefaultConfig(ctx, config.WithRegion("us-east-1"), config.WithCredentialsProvider(credentials.NewStaticCredentialsProvider("emulator", "emulator", "")), config.WithRetryMaxAttempts(1))
	if err != nil {
		t.Fatal(err)
	}
	client := s3.NewFromConfig(awsConfig, func(o *s3.Options) { o.BaseEndpoint = aws.String("http://localhost:9000"); o.UsePathStyle = true })
	defer client.DeleteObject(ctx, &s3.DeleteObjectInput{Bucket: aws.String(task.Bucket), Key: aws.String(task.ObjectKey)})
	store := &countedStore{ObjectStore: &S3Store{client, task.Bucket}}
	makeWorker := func(pool *pgxpool.Pool) *Worker {
		return &Worker{db: pool, core: &Core{base: server.URL, tokenURL: server.URL + "/token", secret: "synthetic", client: server.Client()}, stores: map[string]ObjectStore{"S3": store}}
	}
	t.Setenv("DELIVERY_SCRATCH_ROOT", filepath.Join(t.TempDir(), "scratch"))
	child := exec.CommandContext(ctx, os.Args[0], "-test.run=^TestCrashWorkerHelper$")
	child.Env = append(os.Environ(), "REPORT_CRASH_HELPER=1", "REPORT_CRASH_DATABASE="+dsn, "REPORT_CRASH_SCHEMA="+schema, "REPORT_CRASH_CORE="+server.URL)
	err = child.Run()
	var exited *exec.ExitError
	if !errors.As(err, &exited) || exited.ExitCode() != 86 {
		t.Fatal("worker subprocess did not crash at the verified-upload boundary")
	}
	if err = makeWorker(db).tick(ctx); err == nil {
		t.Fatal("expected simulated callback failure")
	}
	var status string
	var acknowledged bool
	var attempts int
	if err = db.QueryRow(ctx, "SELECT status,acknowledged,attempts FROM delivery_task WHERE id=$1", id).Scan(&status, &acknowledged, &attempts); err != nil {
		t.Fatal(err)
	}
	if status != "VERIFIED" || acknowledged || attempts != 2 {
		t.Fatalf("receipt not durable: %s %t %d", status, acknowledged, attempts)
	}
	db.Close() // Recreate process-owned state; retain only PostgreSQL records and the provider object.
	db, err = pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err = makeWorker(db).tick(ctx); err != nil {
		t.Fatal(err)
	}
	if artifactReads != 2 || store.uploads != 1 {
		t.Fatal("callback retry repeated artifact transfer or upload")
	}
	// Simulate a crash on the last upload attempt before persisting its successful receipt.
	if _, err = db.Exec(ctx, "UPDATE delivery_task SET status='PENDING',attempts=5,receipt=NULL,acknowledged=false WHERE id=$1", id); err != nil {
		t.Fatal(err)
	}
	if err = makeWorker(db).tick(ctx); err != nil {
		t.Fatal(err)
	}
	if err = db.QueryRow(ctx, "SELECT status,acknowledged FROM delivery_task WHERE id=$1", id).Scan(&status, &acknowledged); err != nil {
		t.Fatal(err)
	}
	if status != "VERIFIED" || !acknowledged || store.uploads != 1 || artifactReads != 2 {
		t.Fatal("final-attempt reconciliation failed")
	}
	var interrupted int
	if err = db.QueryRow(ctx, "SELECT count(*) FROM delivery_attempt WHERE task_id=$1 AND status='INTERRUPTED'", id).Scan(&interrupted); err != nil {
		t.Fatal(err)
	}
	if interrupted != 1 {
		t.Fatal("process crash was not recorded as an interrupted attempt")
	}
	var recorded int
	if err = db.QueryRow(ctx, "SELECT count(*) FROM delivery_attempt WHERE task_id=$1 AND status='VERIFIED'", id).Scan(&recorded); err != nil {
		t.Fatal(err)
	}
	if recorded != 2 {
		t.Fatalf("expected upload and reconciliation history, got %d", recorded)
	}
	// A deployment allowlist change cannot redirect an already accepted task.
	store.ObjectStore = &S3Store{client, "different-bucket"}
	if _, err = db.Exec(ctx, "UPDATE delivery_task SET status='PENDING',acknowledged=false WHERE id=$1", id); err != nil {
		t.Fatal(err)
	}
	if err = makeWorker(db).tick(ctx); err == nil {
		t.Fatal("accepted persisted task against another bucket")
	}
	if store.uploads != 1 {
		t.Fatal("changed bucket triggered an upload")
	}

}
