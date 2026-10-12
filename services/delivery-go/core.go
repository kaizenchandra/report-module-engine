package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type Task struct {
	Provider       string `json:"provider"`
	Bucket         string `json:"bucket"`
	ID             string `json:"id"`
	ArtifactID     string `json:"artifact_id"`
	DestinationRef string `json:"destination_ref"`
	ObjectKey      string `json:"object_key"`
	SHA256         string `json:"sha256"`
	Size           int64  `json:"size_bytes"`
}
type Receipt struct {
	Status   string `json:"status"`
	SHA256   string `json:"sha256"`
	Version  string `json:"version"`
	Code     string `json:"code"`
	Attempts int    `json:"attempts"`
}
type Core struct {
	base, tokenURL, secret string
	client                 *http.Client
	token                  string
	expiry                 time.Time
}

func (c *Core) request(ctx context.Context, method, path string, body io.Reader) (*http.Response, error) {
	if time.Now().After(c.expiry) {
		values := url.Values{"grant_type": {"client_credentials"}, "client_id": {"delivery-worker"}, "client_secret": {c.secret}}
		req, err := http.NewRequestWithContext(ctx, "POST", c.tokenURL, strings.NewReader(values.Encode()))
		if err != nil {
			return nil, err
		}
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		res, err := c.client.Do(req)
		if err != nil {
			return nil, err
		}
		defer res.Body.Close()
		if res.StatusCode != 200 {
			return nil, fmt.Errorf("identity provider returned %d", res.StatusCode)
		}
		var token struct {
			Access  string `json:"access_token"`
			Expires int    `json:"expires_in"`
		}
		if err = json.NewDecoder(io.LimitReader(res.Body, 65536)).Decode(&token); err != nil {
			return nil, err
		}
		if token.Access == "" || token.Expires < 30 {
			return nil, fmt.Errorf("invalid service token response")
		}
		c.token = token.Access
		c.expiry = time.Now().Add(time.Duration(token.Expires-20) * time.Second)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.base+"/backend/api/v1/internal/deliveries"+path, body)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+c.token)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	response, err := c.client.Do(req)
	if err == nil && response.StatusCode == http.StatusUnauthorized {
		c.expiry = time.Time{}
	}
	return response, err
}
