package readarr

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestPingSuccess(t *testing.T) {
	t.Parallel()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/ping" {
			t.Errorf("unexpected path %q", r.URL.Path)
		}
		if r.Header.Get("X-Api-Key") != "" {
			t.Error("ping must not send API key")
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"OK"}`))
	}))
	t.Cleanup(srv.Close)

	c := &Client{
		baseURL:    srv.URL,
		httpClient: srv.Client(),
	}
	if err := c.Ready(2 * time.Second); err != nil {
		t.Fatalf("Ready: %v", err)
	}
}

func TestPingUnreachable(t *testing.T) {
	t.Parallel()
	// Closed listener port — connection refused class.
	c := &Client{
		baseURL: "http://127.0.0.1:1",
		httpClient: &http.Client{
			Timeout: 500 * time.Millisecond,
		},
	}
	err := c.Ready(500 * time.Millisecond)
	if err == nil {
		t.Fatal("expected readiness failure for unreachable upstream")
	}
}

func TestPingNonOK(t *testing.T) {
	t.Parallel()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	t.Cleanup(srv.Close)

	c := &Client{
		baseURL:    srv.URL,
		httpClient: srv.Client(),
	}
	if err := c.Ready(2 * time.Second); err == nil {
		t.Fatal("expected readiness failure for non-200 ping")
	}
}

func TestPingEmptyBaseURL(t *testing.T) {
	t.Parallel()
	c := &Client{baseURL: ""}
	if err := c.Ready(time.Second); err == nil {
		t.Fatal("expected error for empty base URL")
	}
}

func TestPingStaleHostClass(t *testing.T) {
	t.Parallel()
	// Simulates stale node/port dependency: server never binds the requested path host.
	// Use a black-hole high port with short timeout.
	c := &Client{
		baseURL: "http://192.0.2.1:8787", // TEST-NET-1, unroutable
		httpClient: &http.Client{
			Timeout: 400 * time.Millisecond,
		},
	}
	start := time.Now()
	err := c.Ready(400 * time.Millisecond)
	elapsed := time.Since(start)
	if err == nil {
		t.Fatal("expected readiness failure for stale/unroutable upstream")
	}
	if elapsed > 2*time.Second {
		t.Fatalf("readiness timeout not bounded: took %s", elapsed)
	}
}
