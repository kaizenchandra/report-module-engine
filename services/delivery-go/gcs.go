package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"strconv"

	"cloud.google.com/go/storage"
)

type ObjectStore interface {
	bucketName() string
	verify(context.Context, Task) (string, error)
	deliver(context.Context, Task, *os.File) (string, error)
}
type GCSStore struct {
	client         *storage.Client
	bucket         string
	signingAccount string
	signBytes      func(context.Context, []byte) ([]byte, error)
}

func (s *GCSStore) bucketName() string { return s.bucket }
func (s *GCSStore) object(t Task) *storage.ObjectHandle {
	return s.client.Bucket(s.bucket).Object(t.ObjectKey).Retryer(storage.WithPolicy(storage.RetryNever))
}
func (s *GCSStore) verify(ctx context.Context, t Task) (string, error) {
	object := s.object(t)
	attrs, err := object.Attrs(ctx)
	if err != nil {
		return "", err
	}
	// Bind verification to the observed generation so concurrent writes cannot change the bytes.
	reader, err := object.Generation(attrs.Generation).NewReader(ctx)
	if err != nil {
		return "", err
	}
	defer reader.Close()
	hash := sha256.New()
	n, err := io.Copy(hash, io.LimitReader(reader, t.Size+1))
	if err != nil {
		return "", err
	}
	if n != t.Size || hex.EncodeToString(hash.Sum(nil)) != t.SHA256 {
		return "", ErrConflict
	}
	return strconv.FormatInt(attrs.Generation, 10), nil
}
func (s *GCSStore) deliver(ctx context.Context, t Task, file *os.File) (string, error) {
	version, err := s.verify(ctx, t)
	if err == nil {
		return version, nil
	}
	if !errors.Is(err, storage.ErrObjectNotExist) {
		return "", err
	}
	if _, err = file.Seek(0, io.SeekStart); err != nil {
		return "", err
	}
	writer := s.object(t).If(storage.Conditions{DoesNotExist: true}).NewWriter(ctx)
	writer.ContentType = mediaType(t.ObjectKey)
	writer.Metadata = map[string]string{"sha256": t.SHA256, "delivery-id": t.ID}
	writer.ChunkSize = 0 // bounded <=10 MiB single upload; no resumable-session cleanup required
	_, copyErr := io.Copy(writer, file)
	closeErr := writer.Close()
	version, verifyErr := s.verify(ctx, t)
	if verifyErr == nil {
		return version, nil
	}
	if copyErr != nil {
		return "", copyErr
	}
	if closeErr != nil {
		return "", fmt.Errorf("upload not verified: %w", closeErr)
	}
	return "", verifyErr
}
