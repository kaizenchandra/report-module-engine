package main

import (
	"context"
	"fmt"
	"net/url"
	"time"

	"cloud.google.com/go/storage"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/s3"
)

func (s *S3Store) check(ctx context.Context) error {
	_, err := s.client.HeadBucket(ctx, &s3.HeadBucketInput{Bucket: aws.String(s.bucket)})
	return err
}
func (s *S3Store) signedURL(ctx context.Context, c Control) (string, error) {
	expires := time.Until(c.ExpiresAt) - time.Second
	if expires <= 0 || expires > 5*time.Minute {
		return "", fmt.Errorf("invalid signature deadline")
	}
	input := &s3.GetObjectInput{Bucket: aws.String(s.bucket), Key: aws.String(c.ObjectKey), ResponseContentType: aws.String(mediaType(c.ObjectKey)), ResponseContentDisposition: aws.String("attachment; filename=report-" + c.ArtifactID + artifactExtension(c.ObjectKey))}
	if c.Version != "" {
		input.VersionId = aws.String(c.Version)
	}
	result, err := s3.NewPresignClient(s.client).PresignGetObject(ctx, input, func(o *s3.PresignOptions) { o.Expires = expires })
	if err != nil {
		return "", err
	}
	return result.URL, nil
}
func (s *GCSStore) check(ctx context.Context) error {
	_, err := s.client.Bucket(s.bucket).Retryer(storage.WithPolicy(storage.RetryNever)).Attrs(ctx)
	return err
}
func (s *GCSStore) signedURL(ctx context.Context, c Control) (string, error) {
	if s.signBytes == nil || s.signingAccount == "" {
		return "", ErrSigningUnavailable
	}
	if time.Until(c.ExpiresAt) <= time.Second || time.Until(c.ExpiresAt) > 5*time.Minute {
		return "", fmt.Errorf("invalid signature deadline")
	}
	if c.Version == "" {
		return "", fmt.Errorf("generation required")
	}
	return storage.SignedURL(s.bucket, c.ObjectKey, &storage.SignedURLOptions{
		GoogleAccessID: s.signingAccount, Scheme: storage.SigningSchemeV4, Method: "GET", Expires: c.ExpiresAt,
		SignBytes:       func(b []byte) ([]byte, error) { return s.signBytes(ctx, b) },
		QueryParameters: url.Values{"generation": {c.Version}, "response-content-type": {mediaType(c.ObjectKey)}, "response-content-disposition": {"attachment; filename=report-" + c.ArtifactID + artifactExtension(c.ObjectKey)}},
	})
}
