package main

import (
	"net/http/httptest"
	"testing"
)

func TestLivenessAndUnknownRoute(t *testing.T) {
	for _, test := range []struct {
		path   string
		status int
	}{{"/health/live", 200}, {"/deliveries", 404}} {
		w := httptest.NewRecorder()
		handler().ServeHTTP(w, httptest.NewRequest("GET", test.path, nil))
		if w.Code != test.status {
			t.Fatalf("%s: got %d", test.path, w.Code)
		}
	}
}
