package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"regexp"
	"time"

	"cloud.google.com/go/storage"
	"github.com/jackc/pgx/v5"
)

type Control struct {
	ID         string    `json:"id"`
	Kind       string    `json:"kind"`
	Provider   string    `json:"provider"`
	Bucket     string    `json:"bucket"`
	ObjectKey  string    `json:"objectKey"`
	Version    string    `json:"version"`
	SHA256     string    `json:"sha256"`
	Size       int64     `json:"sizeBytes"`
	ArtifactID string    `json:"artifactId"`
	ExpiresAt  time.Time `json:"expiresAt"`
}
type ControlResult struct {
	Status string `json:"status"`
	Code   string `json:"code"`
	URL    string `json:"url"`
}
type CloudOperations interface {
	check(context.Context) error
	signedURL(context.Context, Control) (string, error)
}

var ErrSigningUnavailable = errors.New("signing identity unavailable")

func (c Control) validate() error {
	if !uuidPattern.MatchString(c.ID) || (c.Provider != "S3" && c.Provider != "GCS") ||
		!regexp.MustCompile(`^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$`).MatchString(c.Bucket) ||
		c.ExpiresAt.IsZero() || time.Until(c.ExpiresAt) > 5*time.Minute || len(c.Version) > 1024 {
		return fmt.Errorf("invalid cloud control")
	}
	if c.Kind == "CHECK" {
		if c.ObjectKey != "" || c.Version != "" || c.ArtifactID != "" || c.SHA256 != "" || c.Size != 0 {
			return fmt.Errorf("invalid check payload")
		}
		return nil
	}
	if c.Kind != "SIGN" {
		return fmt.Errorf("unknown control kind")
	}
	return (Task{ID: c.ArtifactID, ArtifactID: c.ArtifactID, Provider: c.Provider, Bucket: c.Bucket, DestinationRef: "signed-link", ObjectKey: c.ObjectKey, SHA256: c.SHA256, Size: c.Size}).validate()
}
func (w *Worker) executeControl(ctx context.Context, c Control) ControlResult {
	failed := func(code string) ControlResult { return ControlResult{Status: "FAILED", Code: code} }
	if !c.ExpiresAt.After(time.Now().Add(time.Second)) {
		return failed("EXPIRED")
	}
	store := w.stores[c.Provider]
	if store == nil || store.bucketName() != c.Bucket {
		return failed("DESTINATION_NOT_ALLOWED")
	}
	ops, ok := store.(CloudOperations)
	if !ok {
		return failed("PROVIDER_UNAVAILABLE")
	}
	if c.Kind == "CHECK" {
		if err := ops.check(ctx); err != nil {
			return failed("PROVIDER_UNAVAILABLE")
		}
		return ControlResult{Status: "SUCCEEDED", Code: "CHECK_OK"}
	}
	// Re-read bytes before signing. Version/generation must still match the delivery receipt.
	version, err := store.verify(ctx, Task{ObjectKey: c.ObjectKey, SHA256: c.SHA256, Size: c.Size})
	if err != nil {
		if errors.Is(err, ErrConflict) || errors.Is(err, ErrIntegrity) || missing(err) || errors.Is(err, storage.ErrObjectNotExist) {
			return failed("OBJECT_CHANGED")
		}
		return failed("PROVIDER_UNAVAILABLE")
	}
	if version != c.Version {
		return failed("OBJECT_CHANGED")
	}
	url, err := ops.signedURL(ctx, c)
	if errors.Is(err, ErrSigningUnavailable) {
		return failed("SIGNING_UNAVAILABLE")
	}
	if err != nil {
		return failed("PROVIDER_UNAVAILABLE")
	}
	return ControlResult{Status: "SUCCEEDED", Code: "SIGN_OK", URL: url}
}
func (w *Worker) controlTick(ctx context.Context) error {
	conn, err := w.db.Acquire(ctx)
	if err != nil {
		return err
	}
	defer conn.Release()
	var owned bool
	if err = conn.QueryRow(ctx, "SELECT pg_try_advisory_lock(hashtextextended(current_schema(),82931745))").Scan(&owned); err != nil || !owned {
		return err
	}
	defer func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_, _ = conn.Exec(cleanup, "SELECT pg_advisory_unlock(hashtextextended(current_schema(),82931745))")
	}()
	// URLs are bearer credentials: erase expired copies rather than retaining them in diagnostics.
	if _, err = conn.Exec(ctx, "UPDATE cloud_control SET result=NULL,acknowledged=true WHERE expires_at<=now() AND (result IS NOT NULL OR NOT acknowledged)"); err != nil {
		return err
	}
	response, err := w.core.request(ctx, "GET", "/controls", nil)
	if err != nil {
		return err
	}
	var controls []Control
	if response.StatusCode == 200 {
		err = json.NewDecoder(io.LimitReader(response.Body, 128*1024)).Decode(&controls)
	} else {
		err = fmt.Errorf("control poll status %d", response.StatusCode)
	}
	response.Body.Close()
	if err != nil {
		return err
	}
	for _, c := range controls {
		if err = c.validate(); err != nil {
			return err
		}
		if !c.ExpiresAt.After(time.Now()) {
			continue
		}
		payload, _ := json.Marshal(c)
		hash := sha256.Sum256(payload)
		fingerprint := hex.EncodeToString(hash[:])
		if _, err = conn.Exec(ctx, "INSERT INTO cloud_control(id,fingerprint,payload,expires_at) VALUES($1,$2,$3,$4) ON CONFLICT(id) DO NOTHING", c.ID, fingerprint, payload, c.ExpiresAt); err != nil {
			return err
		}
		var persisted string
		if err = conn.QueryRow(ctx, "SELECT fingerprint FROM cloud_control WHERE id=$1", c.ID).Scan(&persisted); err != nil {
			return err
		}
		if persisted != fingerprint {
			return fmt.Errorf("conflicting control fingerprint")
		}
	}
	var payload, result []byte
	var attempts int
	err = conn.QueryRow(ctx, "SELECT payload,result,attempts FROM cloud_control WHERE NOT acknowledged AND next_attempt<=now() AND expires_at>now() ORDER BY created_at LIMIT 1").Scan(&payload, &result, &attempts)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	var c Control
	if err = json.Unmarshal(payload, &c); err != nil {
		return err
	}
	if err = c.validate(); err != nil {
		return err
	}
	if result == nil {
		attempts++
		if _, err = conn.Exec(ctx, "UPDATE cloud_control SET attempts=$2 WHERE id=$1", c.ID, attempts); err != nil {
			return err
		}
		outcome := ControlResult{Status: "FAILED", Code: "PROVIDER_UNAVAILABLE"}
		if attempts <= 3 {
			operation, cancel := context.WithTimeout(ctx, 15*time.Second)
			outcome = w.executeControl(operation, c)
			cancel()
		}
		if outcome.Code == "PROVIDER_UNAVAILABLE" && attempts < 3 {
			_, err = conn.Exec(ctx, "UPDATE cloud_control SET next_attempt=now()+($2*interval '1 second') WHERE id=$1", c.ID, 1<<attempts)
			return err
		}
		result, _ = json.Marshal(outcome)
		if _, err = conn.Exec(ctx, "UPDATE cloud_control SET result=$2 WHERE id=$1", c.ID, result); err != nil {
			return err
		}
	}
	response, err = w.core.request(ctx, "POST", "/controls/"+c.ID+"/result", bytes.NewReader(result))
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != 204 {
		return fmt.Errorf("control result rejected: %d", response.StatusCode)
	}
	_, err = conn.Exec(ctx, "UPDATE cloud_control SET acknowledged=true WHERE id=$1", c.ID)
	return err
}
