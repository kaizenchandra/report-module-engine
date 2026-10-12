package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	iamcredentials "cloud.google.com/go/iam/credentials/apiv1"
	"cloud.google.com/go/iam/credentials/apiv1/credentialspb"
	"cloud.google.com/go/storage"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/jackc/pgx/v5/pgxpool"
)

func handler(readiness ...func(context.Context) error) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health/live", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"status":"UP"}`))
	})
	mux.HandleFunc("GET /health/ready", func(w http.ResponseWriter, r *http.Request) {
		if len(readiness) == 0 {
			http.Error(w, "not configured", http.StatusServiceUnavailable)
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), time.Second)
		defer cancel()
		if err := readiness[0](ctx); err != nil {
			http.Error(w, "database unavailable", http.StatusServiceUnavailable)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"status":"UP"}`))
	})
	return mux
}
func required(key string) string {
	v := os.Getenv(key)
	if v == "" {
		panic("Missing required environment: " + key)
	}
	return v
}
func run() error {
	initialize := flag.Bool("init-local-bucket", false, "Create the loopback emulator bucket and exit")
	flag.Parse()
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	cfg, err := config.LoadDefaultConfig(ctx, config.WithRetryMaxAttempts(1))
	if err != nil {
		return err
	}
	endpoint := os.Getenv("S3_ENDPOINT")
	client := s3.NewFromConfig(cfg, func(o *s3.Options) {
		if endpoint != "" {
			o.BaseEndpoint = aws.String(endpoint)
			o.UsePathStyle = true
		}
	})
	store := &S3Store{client: client, bucket: required("S3_BUCKET")}
	if *initialize {
		if endpoint != "http://localhost:9000" {
			return fmt.Errorf("bucket initialization only allowed against local emulator")
		}
		_, err = client.HeadBucket(ctx, &s3.HeadBucketInput{Bucket: aws.String(store.bucket)})
		if err == nil {
			return nil
		}
		_, err = client.CreateBucket(ctx, &s3.CreateBucketInput{Bucket: aws.String(store.bucket)})
		return err
	}
	gcs, err := storage.NewClient(ctx)
	if err != nil {
		return err
	}
	defer gcs.Close()
	gcsStore := &GCSStore{client: gcs, bucket: required("GCS_BUCKET")}
	if account := os.Getenv("GCS_SIGNING_SERVICE_ACCOUNT"); account != "" {
		if os.Getenv("STORAGE_EMULATOR_HOST") != "" {
			return fmt.Errorf("cloud signing identity cannot be enabled with the GCS emulator")
		}
		signer, err := iamcredentials.NewIamCredentialsRESTClient(ctx)
		if err != nil {
			return err
		}
		defer signer.Close()
		gcsStore.signingAccount = account
		gcsStore.signBytes = func(ctx context.Context, payload []byte) ([]byte, error) {
			response, err := signer.SignBlob(ctx, &credentialspb.SignBlobRequest{Name: "projects/-/serviceAccounts/" + account, Payload: payload})
			if err != nil {
				return nil, err
			}
			return response.SignedBlob, nil
		}
	}
	poolConfig, err := pgxpool.ParseConfig(required("DELIVERY_DATABASE_URL"))
	if err != nil {
		return err
	}
	poolConfig.MaxConns = 4
	db, err := pgxpool.NewWithConfig(ctx, poolConfig)
	if err != nil {
		return err
	}
	defer db.Close()
	if err = migrate(ctx, db); err != nil {
		return err
	}
	core := &Core{base: required("CORE_URL"), tokenURL: required("DELIVERY_TOKEN_URL"), secret: required("DELIVERY_CLIENT_SECRET"), client: &http.Client{Timeout: 70 * time.Second, CheckRedirect: func(req *http.Request, via []*http.Request) error { return http.ErrUseLastResponse }}}
	worker := &Worker{db: db, core: core, stores: map[string]ObjectStore{"S3": store, "GCS": gcsStore}}
	done := make(chan struct{})
	go func() { defer close(done); worker.run(ctx) }()
	server := &http.Server{Addr: ":8090", Handler: handler(db.Ping), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 30 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16384}
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = server.Shutdown(shutdown)
	}()
	slog.Info("delivery service listening", "port", 8090)
	err = server.ListenAndServe()
	stop()
	<-done
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}
func main() {
	if err := run(); err != nil {
		slog.Error("delivery service failed", "kind", fmt.Sprintf("%T", err))
		os.Exit(1)
	}
}
