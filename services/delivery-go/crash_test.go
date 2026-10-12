package main

import (
	"context"
	"net/http"
	"os"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Only linked into the test binary. Terminate after the provider has verified the
// object, before the worker can persist its receipt or run deferred cleanup.
type crashAfterUpload struct{ ObjectStore }

func (s *crashAfterUpload) deliver(ctx context.Context, t Task, f *os.File) (string, error) {
	version, err := s.ObjectStore.deliver(ctx, t, f)
	if err == nil {
		os.Exit(86)
	}
	return version, err
}
func TestCrashWorkerHelper(t *testing.T) {
	if os.Getenv("REPORT_CRASH_HELPER") != "1" {
		t.Skip("subprocess entry point exercised by TestDurableReceiptRecovery")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	cfg, err := pgxpool.ParseConfig(os.Getenv("REPORT_CRASH_DATABASE"))
	if err != nil {
		t.Fatal("invalid test database configuration")
	}
	cfg.ConnConfig.RuntimeParams["search_path"] = os.Getenv("REPORT_CRASH_SCHEMA")
	db, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatal("test database connection failed")
	}
	defer db.Close()
	awsConfig, err := config.LoadDefaultConfig(ctx, config.WithRegion("us-east-1"), config.WithCredentialsProvider(credentials.NewStaticCredentialsProvider("emulator", "emulator", "")), config.WithRetryMaxAttempts(1))
	if err != nil {
		t.Fatal(err)
	}
	client := s3.NewFromConfig(awsConfig, func(o *s3.Options) { o.BaseEndpoint = aws.String("http://localhost:9000"); o.UsePathStyle = true })
	base := os.Getenv("REPORT_CRASH_CORE")
	worker := Worker{db: db, core: &Core{base: base, tokenURL: base + "/token", secret: "synthetic", client: &http.Client{Timeout: 10 * time.Second}}, stores: map[string]ObjectStore{"S3": &crashAfterUpload{ObjectStore: &S3Store{client: client, bucket: "report-results"}}}}
	if err := worker.tick(ctx); err != nil {
		t.Fatal("crash fixture failed before provider verification")
	}
	t.Fatal("crash fixture unexpectedly returned")
}
