package main

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"github.com/jackc/pgx/v5/pgxpool"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"
)

func signingControl() Control {
	id := "b4928e1f-63af-41a2-8f96-bfd91c207071"
	return Control{ID: id, Kind: "SIGN", Provider: "GCS", Bucket: "report-results", ObjectKey: "reports/" + id + ".pdf", ArtifactID: id, Version: "12345", SHA256: strings.Repeat("a", 64), Size: 120, ExpiresAt: time.Now().Add(4 * time.Minute).Truncate(time.Second)}
}
func TestControlValidationAndFixedSigningDeadlines(t *testing.T) {
	c := signingControl()
	if err := c.validate(); err != nil {
		t.Fatal(err)
	}
	invalid := c
	invalid.ExpiresAt = time.Now().Add(6 * time.Minute)
	if invalid.validate() == nil {
		t.Fatal("accepted excessive lifetime")
	}
	invalid = c
	invalid.ObjectKey = "../private.pdf"
	if invalid.validate() == nil {
		t.Fatal("accepted arbitrary object key")
	}
	// These signing tests verify SDK output locally, not cloud enforcement.
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	var signed []byte
	store := GCSStore{bucket: c.Bucket, signingAccount: "synthetic@example.iam.gserviceaccount.com", signBytes: func(ctx context.Context, b []byte) ([]byte, error) {
		signed = append([]byte{}, b...)
		hash := sha256.Sum256(b)
		return rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, hash[:])
	}}
	link, err := store.signedURL(context.Background(), c)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err := url.Parse(link)
	if err != nil {
		t.Fatal(err)
	}
	query := parsed.Query()
	signature, err := hex.DecodeString(query.Get("X-Goog-Signature"))
	if err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256(signed)
	if err := rsa.VerifyPKCS1v15(&key.PublicKey, crypto.SHA256, digest[:], signature); err != nil {
		t.Fatal("invalid V4 RSA signature")
	}
	start, err := time.Parse("20060102T150405Z", query.Get("X-Goog-Date"))
	if err != nil {
		t.Fatal(err)
	}
	ttl, err := strconv.Atoi(query.Get("X-Goog-Expires"))
	if err != nil {
		t.Fatal(err)
	}
	if start.Add(time.Duration(ttl)*time.Second).After(c.ExpiresAt) || ttl > 300 || query.Get("generation") != c.Version {
		t.Fatal("generation or fixed expiry was not signed")
	}
	if query.Get("X-Goog-Algorithm") != "GOOG4-RSA-SHA256" || !strings.Contains(query.Get("response-content-disposition"), "attachment") {
		t.Fatal("missing V4 download settings")
	}
	c.Provider = "S3"
	awsStore := S3Store{bucket: c.Bucket, client: s3.NewFromConfig(aws.Config{Region: "us-east-1", Credentials: credentials.NewStaticCredentialsProvider("synthetic-key", "synthetic-secret", "")})}
	link, err = awsStore.signedURL(context.Background(), c)
	if err != nil {
		t.Fatal(err)
	}
	parsed, err = url.Parse(link)
	if err != nil {
		t.Fatal(err)
	}
	query = parsed.Query()
	start, err = time.Parse("20060102T150405Z", query.Get("X-Amz-Date"))
	if err != nil {
		t.Fatal(err)
	}
	ttl, err = strconv.Atoi(query.Get("X-Amz-Expires"))
	if err != nil {
		t.Fatal(err)
	}
	if start.Add(time.Duration(ttl)*time.Second).After(c.ExpiresAt) || ttl > 300 || query.Get("versionId") != c.Version || query.Get("X-Amz-Signature") == "" {
		t.Fatal("S3 version or bounded signature missing")
	}
	for _, extension := range []string{"docx", "html.zip"} {
		c.ObjectKey = "reports/" + c.ArtifactID + "." + extension
		if err := c.validate(); err != nil {
			t.Fatal(err)
		}
		for _, provider := range []CloudOperations{&store, &awsStore} {
			link, err := provider.signedURL(context.Background(), c)
			if err != nil {
				t.Fatal(err)
			}
			parsed, err := url.Parse(link)
			if err != nil {
				t.Fatal(err)
			}
			query := parsed.Query()
			if query.Get("response-content-disposition") != "attachment; filename=report-"+c.ArtifactID+"."+extension {
				t.Fatal("signed filename lost format/package suffix")
			}
			if query.Get("response-content-type") != mediaType(c.ObjectKey) {
				t.Fatal("wrong signed media type")
			}
		}
	}
	c.ExpiresAt = time.Now().Add(-time.Second)
	if _, err = awsStore.signedURL(context.Background(), c); err == nil {
		t.Fatal("signed an expired request")
	}
	if _, err = (&GCSStore{}).signedURL(context.Background(), c); err != ErrSigningUnavailable {
		t.Fatal("missing identity reported success")
	}
}

type controlStore struct {
	ObjectStore
	checks int
}

func (s *controlStore) check(context.Context) error { s.checks++; return nil }
func (s *controlStore) signedURL(context.Context, Control) (string, error) {
	return "", ErrSigningUnavailable
}
func TestControlResultSurvivesLostAcknowledgement(t *testing.T) {
	dsn := os.Getenv("DELIVERY_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("requires local PostgreSQL")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	admin, err := pgxpool.New(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer admin.Close()
	var random [16]byte
	_, _ = rand.Read(random[:])
	schema := "control_" + hex.EncodeToString(random[:])
	if _, err = admin.Exec(ctx, "CREATE SCHEMA "+schema); err != nil {
		t.Fatal(err)
	}
	defer admin.Exec(context.Background(), "DROP SCHEMA "+schema+" CASCADE")
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		t.Fatal(err)
	}
	cfg.ConnConfig.RuntimeParams["search_path"] = schema
	db, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	if err = migrate(ctx, db); err != nil {
		t.Fatal(err)
	}
	c := signingControl()
	c.Kind = "CHECK"
	c.ObjectKey = ""
	c.Version = ""
	c.SHA256 = ""
	c.Size = 0
	c.ArtifactID = ""
	callbacks := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/token":
			fmt.Fprint(w, `{"access_token":"synthetic","expires_in":300}`)
		case r.URL.Path == "/backend/api/v1/internal/deliveries/controls":
			_ = json.NewEncoder(w).Encode([]Control{c})
		case strings.HasSuffix(r.URL.Path, "/result"):
			callbacks++
			if callbacks == 1 {
				w.WriteHeader(503)
			} else {
				w.WriteHeader(204)
			}
		default:
			w.WriteHeader(404)
		}
	}))
	defer server.Close()
	store := &controlStore{ObjectStore: &GCSStore{bucket: c.Bucket}}
	worker := func() *Worker {
		return &Worker{db: db, stores: map[string]ObjectStore{"GCS": store}, core: &Core{base: server.URL, tokenURL: server.URL + "/token", secret: "synthetic", client: server.Client()}}
	}
	if err = worker().controlTick(ctx); err == nil {
		t.Fatal("expected lost callback acknowledgement")
	}
	db.Close()
	db, err = pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err = worker().controlTick(ctx); err != nil {
		t.Fatal(err)
	}
	if store.checks != 1 || callbacks != 2 {
		t.Fatal("callback retry repeated provider check")
	}
	c.Bucket = "changed-bucket"
	if err = worker().controlTick(ctx); err == nil {
		t.Fatal("accepted conflicting operation fingerprint")
	}
	c.Bucket = "report-results"
	if _, err = db.Exec(ctx, "UPDATE cloud_control SET expires_at=now()-interval '1 second' WHERE id=$1", c.ID); err != nil {
		t.Fatal(err)
	}
	if err = worker().controlTick(ctx); err != nil {
		t.Fatal(err)
	}
	var removed bool
	if err = db.QueryRow(ctx, "SELECT result IS NULL FROM cloud_control WHERE id=$1", c.ID).Scan(&removed); err != nil {
		t.Fatal(err)
	}
	if !removed {
		t.Fatal("expired bearer result was retained")
	}
}

type failedVerification struct {
	*controlStore
	failure error
}

func (s *failedVerification) verify(context.Context, Task) (string, error) { return "", s.failure }
func TestLinkVerificationClassifiesTransientFailure(t *testing.T) {
	c := signingControl()
	for _, scenario := range []struct {
		failure error
		code    string
	}{{context.DeadlineExceeded, "PROVIDER_UNAVAILABLE"}, {ErrConflict, "OBJECT_CHANGED"}} {
		store := &failedVerification{controlStore: &controlStore{ObjectStore: &GCSStore{bucket: c.Bucket}}, failure: scenario.failure}
		worker := Worker{stores: map[string]ObjectStore{"GCS": store}}
		if result := worker.executeControl(context.Background(), c); result.Code != scenario.code {
			t.Fatalf("unexpected classification: %s", result.Code)
		}
	}
}
