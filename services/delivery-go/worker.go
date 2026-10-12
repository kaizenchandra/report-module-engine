package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"regexp"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

//go:embed migrations/001_delivery.sql
var migration string

//go:embed migrations/002_attempts.sql
var attemptsMigration string

//go:embed migrations/003_cloud_controls.sql
var controlsMigration string
var uuidPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
var checksumPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

func (t Task) validate() error {
	if !regexp.MustCompile(`^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$`).MatchString(t.Bucket) ||
		(t.Provider != "S3" && t.Provider != "GCS") || !uuidPattern.MatchString(t.ID) || !uuidPattern.MatchString(t.ArtifactID) ||
		!regexp.MustCompile(`^[a-z][a-z0-9-]{2,63}$`).MatchString(t.DestinationRef) ||
		!regexp.MustCompile(`^([A-Za-z0-9_-]+/)*`+regexp.QuoteMeta(t.ArtifactID)+`\.(pdf|xlsx|csv|docx|html\.zip)$`).MatchString(t.ObjectKey) ||
		len(t.ObjectKey) > 245 || !checksumPattern.MatchString(t.SHA256) || t.Size < 1 || t.Size > 10*1024*1024 {
		return fmt.Errorf("invalid delivery task")
	}
	return nil
}

type Worker struct {
	db          *pgxpool.Pool
	core        *Core
	stores      map[string]ObjectStore
	lastCleanup time.Time
}

func (w *Worker) store(t Task) ObjectStore {
	return w.stores[t.Provider]
}

func migrate(ctx context.Context, db *pgxpool.Pool) error {
	tx, err := db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err = tx.Exec(ctx, "SELECT pg_advisory_xact_lock(82931744)"); err != nil {
		return err
	}
	if _, err = tx.Exec(ctx, "CREATE TABLE IF NOT EXISTS schema_migration(version integer PRIMARY KEY, checksum text NOT NULL)"); err != nil {
		return err
	}
	for i, sql := range []string{migration, attemptsMigration, controlsMigration} {
		version := i + 1
		sum := sha256.Sum256([]byte(sql))
		digest := hex.EncodeToString(sum[:])
		var saved string
		err = tx.QueryRow(ctx, "SELECT checksum FROM schema_migration WHERE version=$1", version).Scan(&saved)
		if errors.Is(err, pgx.ErrNoRows) {
			if _, err = tx.Exec(ctx, sql); err != nil {
				return err
			}
			if _, err = tx.Exec(ctx, "INSERT INTO schema_migration VALUES($1,$2)", version, digest); err != nil {
				return err
			}
		} else if err != nil {
			return err
		} else if saved != digest {
			return fmt.Errorf("migration %d checksum mismatch", version)
		}
	}
	return tx.Commit(ctx)
}
func (w *Worker) poll(ctx context.Context) error {
	response, err := w.core.request(ctx, "GET", "", nil)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		return fmt.Errorf("core polling returned %d", response.StatusCode)
	}
	var tasks []Task
	if err = json.NewDecoder(io.LimitReader(response.Body, 128*1024)).Decode(&tasks); err != nil {
		return err
	}
	for _, t := range tasks {
		if err = t.validate(); err != nil {
			return err
		}
		store := w.store(t)
		if store == nil || store.bucketName() != t.Bucket {
			return fmt.Errorf("destination bucket does not match deployment allowlist")
		}
		payload, _ := json.Marshal(t)
		sum := sha256.Sum256(payload)
		hash := hex.EncodeToString(sum[:])
		_, err = w.db.Exec(ctx, "INSERT INTO delivery_task(id,fingerprint,payload) VALUES($1,$2,$3) ON CONFLICT(id) DO NOTHING", t.ID, hash, payload)
		if err != nil {
			return err
		}
		var persisted string
		if err = w.db.QueryRow(ctx, "SELECT fingerprint FROM delivery_task WHERE id=$1", t.ID).Scan(&persisted); err != nil {
			return err
		}
		if persisted != hash {
			return fmt.Errorf("conflicting task fingerprint")
		}
	}
	return nil
}
func (w *Worker) upload(ctx context.Context, t Task) (string, error) {
	response, err := w.core.request(ctx, "GET", "/"+t.ID+"/artifact", nil)
	if err != nil {
		return "", err
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		return "", fmt.Errorf("artifact unavailable")
	}
	file, err := os.CreateTemp(scratchRoot(), "report-delivery-*.pdf")
	if err != nil {
		return "", err
	}
	defer os.Remove(file.Name())
	defer file.Close()
	h := sha256.New()
	n, err := io.Copy(io.MultiWriter(file, h), io.LimitReader(response.Body, t.Size+1))
	if err != nil {
		return "", err
	}
	if n != t.Size || hex.EncodeToString(h.Sum(nil)) != t.SHA256 {
		return "", ErrIntegrity
	}
	return w.store(t).deliver(ctx, t, file)
}
func (w *Worker) tick(ctx context.Context) error {
	if time.Since(w.lastCleanup) > time.Hour {
		if err := cleanScratch(scratchRoot(), time.Now()); err != nil {
			return err
		}
		w.lastCleanup = time.Now()
	}
	// Single delivery worker per database in this slice; a held connection lock releases on crash.
	conn, err := w.db.Acquire(ctx)
	if err != nil {
		return err
	}
	defer conn.Release()
	var owned bool
	if err = conn.QueryRow(ctx, "SELECT pg_try_advisory_lock(hashtextextended(current_schema(),82931743))").Scan(&owned); err != nil || !owned {
		return err
	}
	defer func() {
		unlock, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, _ = conn.Exec(unlock, "SELECT pg_advisory_unlock(hashtextextended(current_schema(),82931743))")
	}()
	if err = w.poll(ctx); err != nil {
		slog.Warn("delivery poll failed", "error", err.Error())
	}
	var payload []byte
	var receiptBytes []byte
	var attempts int
	var status string
	err = conn.QueryRow(ctx, "SELECT payload,attempts,status,receipt FROM delivery_task WHERE NOT acknowledged AND next_attempt<=now() ORDER BY created_at LIMIT 1").Scan(&payload, &attempts, &status, &receiptBytes)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	var task Task
	if err = json.Unmarshal(payload, &task); err != nil {
		return err
	}
	if err := task.validate(); err != nil {
		return err
	}
	if status == "PENDING" {
		if store := w.store(task); store == nil || store.bucketName() != task.Bucket {
			return fmt.Errorf("persisted destination no longer matches deployment allowlist")
		}

		attempts++
		// Atomic attempt claim: budget and history survive a crash together.
		tx, err := conn.Begin(ctx)
		if err != nil {
			return err
		}
		defer tx.Rollback(ctx)
		if _, err = tx.Exec(ctx, "UPDATE delivery_attempt SET status='INTERRUPTED',code='WORKER_INTERRUPTED',ended_at=now() WHERE task_id=$1 AND status='STARTED' AND number<6", task.ID); err != nil {
			return err
		}
		// Reconciliation is read-only and may repeat after another crash; keep one bounded record.
		if attempts > 6 {
			attempts = 6
		}
		if _, err = tx.Exec(ctx, "UPDATE delivery_task SET attempts=$2,updated_at=now() WHERE id=$1", task.ID, attempts); err != nil {
			return err
		}
		phase := "UPLOAD"
		if attempts > 5 {
			phase = "RECONCILE"
		}
		if _, err = tx.Exec(ctx, "INSERT INTO delivery_attempt(task_id,number,phase,status) VALUES($1,$2,$3,'STARTED') ON CONFLICT(task_id,number) DO NOTHING", task.ID, attempts, phase); err != nil {
			return err
		}
		if err = tx.Commit(ctx); err != nil {
			return err
		}
		// Telemetry cannot consume upload retries. A full snapshot is replayed before the receipt.
		_ = w.publishAttempts(ctx, task.ID)
		operation, cancel := context.WithTimeout(ctx, 60*time.Second)
		var version string
		var uploadErr error
		if attempts <= 5 {
			version, uploadErr = w.upload(operation, task)
		} else {
			version, uploadErr = w.store(task).verify(operation, task)
		}
		cancel()
		receipt := Receipt{Status: "VERIFIED", SHA256: task.SHA256, Version: version, Code: "NONE", Attempts: min(attempts, 5)}
		if uploadErr != nil {
			permanent := errors.Is(uploadErr, ErrIntegrity) || errors.Is(uploadErr, ErrConflict)
			if attempts < 5 && !permanent {
				if _, err = conn.Exec(ctx, "UPDATE delivery_attempt SET status='RETRYING',code='UPLOAD_RETRY',ended_at=now() WHERE task_id=$1 AND number=$2", task.ID, attempts); err != nil {
					return err
				}
				_ = w.publishAttempts(ctx, task.ID)
			}
			if attempts < 5 && !permanent {
				_, err = conn.Exec(ctx, "UPDATE delivery_task SET next_attempt=now()+($2*interval '1 second'),updated_at=now() WHERE id=$1", task.ID, 1<<attempts)
				return err
			}
			receipt.Status = "FAILED"
			receipt.Code = "UPLOAD_UNVERIFIED"
			if errors.Is(uploadErr, ErrIntegrity) {
				receipt.Code = "INTEGRITY_MISMATCH"
			}
			if errors.Is(uploadErr, ErrConflict) {
				receipt.Code = "CONFLICT"
			}
		}
		if _, err = conn.Exec(ctx, "UPDATE delivery_attempt SET status=$3,code=$4,ended_at=now() WHERE task_id=$1 AND number=$2", task.ID, attempts, receipt.Status, receipt.Code); err != nil {
			return err
		}
		receiptBytes, _ = json.Marshal(receipt)
		if _, err = conn.Exec(ctx, "UPDATE delivery_task SET status=$2,receipt=$3,updated_at=now() WHERE id=$1", task.ID, receipt.Status, receiptBytes); err != nil {
			return err
		}
	}
	if err := w.publishAttempts(ctx, task.ID); err != nil {
		return err
	}
	response, err := w.core.request(ctx, "POST", "/"+task.ID+"/receipt", bytes.NewReader(receiptBytes))
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != 204 {
		return fmt.Errorf("receipt rejected: %d", response.StatusCode)
	}
	_, err = conn.Exec(ctx, "UPDATE delivery_task SET acknowledged=true,updated_at=now() WHERE id=$1", task.ID)
	return err
}
func (w *Worker) run(ctx context.Context) {
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			operation, cancel := context.WithTimeout(ctx, 90*time.Second)
			if err := w.tick(operation); err != nil && !errors.Is(err, context.Canceled) {
				slog.Warn("delivery cycle failed", "kind", fmt.Sprintf("%T", err))
			}
			cancel()
			control, stop := context.WithTimeout(ctx, 25*time.Second)
			if err := w.controlTick(control); err != nil && !errors.Is(err, context.Canceled) {
				slog.Warn("cloud control cycle failed", "kind", fmt.Sprintf("%T", err))
			}
			stop()
		}
	}
}
