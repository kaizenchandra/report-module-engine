package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/aws-sdk-go-v2/service/s3/types"
	"github.com/aws/smithy-go"
)

var ErrConflict = errors.New("destination conflict")
var ErrIntegrity = errors.New("integrity mismatch")

type S3Store struct {
	client *s3.Client
	bucket string
}

func (s *S3Store) bucketName() string { return s.bucket }
func (s *S3Store) verify(ctx context.Context, t Task) (string, error) {
	output, err := s.client.GetObject(ctx, &s3.GetObjectInput{Bucket: aws.String(s.bucket), Key: aws.String(t.ObjectKey)})
	if err != nil {
		return "", err
	}
	defer output.Body.Close()
	h := sha256.New()
	n, err := io.Copy(h, io.LimitReader(output.Body, t.Size+1))
	if err != nil {
		return "", err
	}
	if n != t.Size || hex.EncodeToString(h.Sum(nil)) != t.SHA256 {
		return "", ErrConflict
	}
	return aws.ToString(output.VersionId), nil
}
func missing(err error) bool {
	var api smithy.APIError
	return errors.As(err, &api) && (api.ErrorCode() == "NoSuchKey" || api.ErrorCode() == "NotFound")
}
func (s *S3Store) deliver(ctx context.Context, t Task, file *os.File) (string, error) {
	// Reconcile before every retry. A previous attempt may have uploaded successfully but lost its receipt.
	version, err := s.verify(ctx, t)
	if err == nil {
		return version, nil
	}
	if !missing(err) {
		return "", err
	}
	if _, err = file.Seek(0, io.SeekStart); err != nil {
		return "", err
	}
	_, putErr := s.client.PutObject(ctx, &s3.PutObjectInput{Bucket: aws.String(s.bucket), Key: aws.String(t.ObjectKey), Body: file,
		ServerSideEncryption: types.ServerSideEncryptionAes256,
		ContentLength:        aws.Int64(t.Size), ContentType: aws.String(mediaType(t.ObjectKey)), IfNoneMatch: aws.String("*"),
		Metadata: map[string]string{"sha256": t.SHA256, "delivery-id": t.ID}})
	// Even an ambiguous PUT failure is followed by a read and byte-level checksum verification.
	version, verifyErr := s.verify(ctx, t)
	if verifyErr == nil {
		return version, nil
	}
	if putErr != nil {
		return "", fmt.Errorf("upload not verified: %w", putErr)
	}
	return "", verifyErr
}
