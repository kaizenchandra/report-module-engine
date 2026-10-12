package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"mime"
	"net/http"
	"os"
	"strings"
	"testing"
	"time"

	"cloud.google.com/go/storage"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"
)

func TestProviderContracts(t *testing.T) {
	if os.Getenv("RUN_PROVIDER_TESTS") != "true" {
		t.Skip("requires loopback S3Mock and fake-gcs-server; run scripts/provider-tests.sh")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	cfg, err := config.LoadDefaultConfig(ctx, config.WithRegion("us-east-1"), config.WithCredentialsProvider(credentials.NewStaticCredentialsProvider("emulator", "emulator", "")), config.WithRetryMaxAttempts(1))
	if err != nil {
		t.Fatal(err)
	}
	s3client := s3.NewFromConfig(cfg, func(o *s3.Options) { o.BaseEndpoint = aws.String("http://localhost:9000"); o.UsePathStyle = true })
	t.Setenv("STORAGE_EMULATOR_HOST", "http://localhost:4443")
	gcs, err := storage.NewClient(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer gcs.Close()
	stores := map[string]ObjectStore{"S3": &S3Store{s3client, "report-results"}, "GCS": &GCSStore{client: gcs, bucket: "report-results"}}
	for name, store := range stores {
		t.Run(name, func(t *testing.T) {
			for _, extension := range []string{"pdf", "csv", "xlsx", "docx", "html.zip"} {
				content := []byte("%PDF-1.4\nsynthetic provider contract fixture\n%%EOF\n")
				hash := sha256.Sum256(content)
				var id [16]byte
				_, _ = rand.Read(id[:])
				task := Task{ID: hex.EncodeToString(id[:]), ObjectKey: "contracts/" + hex.EncodeToString(id[:]) + "." + extension, SHA256: hex.EncodeToString(hash[:]), Size: int64(len(content))}
				file, err := os.CreateTemp(t.TempDir(), "artifact")
				if err != nil {
					t.Fatal(err)
				}
				defer file.Close()
				if _, err = file.Write(content); err != nil {
					t.Fatal(err)
				}
				version, err := store.deliver(ctx, task, file)
				if err != nil {
					t.Fatal(err)
				}
				if name == "S3" {
					attrs, err := s3client.HeadObject(ctx, &s3.HeadObjectInput{Bucket: aws.String("report-results"), Key: aws.String(task.ObjectKey)})
					if err != nil || !sameMediaType(aws.ToString(attrs.ContentType), mediaType(task.ObjectKey)) {
						t.Fatalf("wrong S3 content type for %s: got %q, want %q", extension, aws.ToString(attrs.ContentType), mediaType(task.ObjectKey))
					}
				} else {
					attrs, err := gcs.Bucket("report-results").Object(task.ObjectKey).Attrs(ctx)
					if err != nil || !sameMediaType(attrs.ContentType, mediaType(task.ObjectKey)) {
						t.Fatal("wrong GCS content type")
					}
				}
				ops := store.(CloudOperations)
				if err := ops.check(ctx); err != nil {
					t.Fatalf("metadata check failed: %v", err)
				}
				if name == "S3" {
					link, err := ops.signedURL(ctx, Control{ObjectKey: task.ObjectKey, ArtifactID: task.ID, Version: version, ExpiresAt: time.Now().Add(time.Minute)})
					if err != nil {
						t.Fatal(err)
					}
					// The emulator serves the URL but does not enforce its signature or expiration.
					request, err := http.NewRequestWithContext(ctx, "GET", link, nil)
					if err != nil {
						t.Fatal(err)
					}
					response, err := http.DefaultClient.Do(request)
					if err != nil {
						t.Fatal("presigned emulator request failed")
					}
					body, err := io.ReadAll(io.LimitReader(response.Body, task.Size+1))
					response.Body.Close()
					if err != nil || response.StatusCode != 200 || sha256.Sum256(body) != hash {
						t.Fatal("signed emulator URL returned wrong bytes")
					}
				}
				again, err := store.deliver(ctx, task, file)
				if err != nil {
					t.Fatalf("lost-receipt reconciliation failed: %v", err)
				}
				if again != version {
					t.Fatal("idempotent delivery changed object version")
				}
				corrupted := task
				corrupted.SHA256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
				if _, err = store.deliver(ctx, corrupted, file); !errors.Is(err, ErrConflict) {
					t.Fatalf("expected conflict, got %v", err)
				}
				if _, err = store.verify(ctx, task); err != nil {
					t.Fatal("conflict mutated original object", err)
				}
				cancelled, stop := context.WithCancel(ctx)
				stop()
				if _, err = store.verify(cancelled, task); err == nil {
					t.Fatal("cancelled operation succeeded")
				}
				if name == "S3" {
					_, _ = s3client.DeleteObject(ctx, &s3.DeleteObjectInput{Bucket: aws.String("report-results"), Key: aws.String(task.ObjectKey)})
				} else {
					_ = gcs.Bucket("report-results").Object(task.ObjectKey).Delete(ctx)
				}
			}
		})
	}
}

func sameMediaType(actual, expected string) bool {
	a, ap, err := mime.ParseMediaType(actual)
	if err != nil {
		return false
	}
	b, bp, err := mime.ParseMediaType(expected)
	if err != nil {
		return false
	}
	return a == b && strings.EqualFold(ap["charset"], bp["charset"])
}
