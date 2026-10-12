package main

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestRevokedTokenRefreshesOnNextRequest(t *testing.T) {
	tokens := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/token" {
			tokens++
			fmt.Fprintf(w, `{"access_token":"synthetic-%d","expires_in":300}`, tokens)
			return
		}
		if r.Header.Get("Authorization") == "Bearer synthetic-1" {
			w.WriteHeader(401)
			return
		}
		w.WriteHeader(200)
	}))
	defer server.Close()
	core := Core{base: server.URL, tokenURL: server.URL + "/token", secret: "synthetic", client: server.Client()}
	for _, expected := range []int{401, 200} {
		response, err := core.request(context.Background(), "GET", "", nil)
		if err != nil {
			t.Fatal(err)
		}
		response.Body.Close()
		if response.StatusCode != expected {
			t.Fatalf("unexpected status %d", response.StatusCode)
		}
	}
	if tokens != 2 {
		t.Fatal("revoked token was cached")
	}
}
