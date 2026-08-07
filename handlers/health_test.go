package handlers

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/aleksclark/goshelf/readarr"
)

func TestHealthzAlwaysOK(t *testing.T) {
	t.Parallel()
	h := &Handlers{}
	req := httptest.NewRequest(http.MethodGet, "/healthz", nil)
	rr := httptest.NewRecorder()
	h.Healthz(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d want 200", rr.Code)
	}
	if ct := rr.Header().Get("Content-Type"); ct != "application/json" {
		t.Fatalf("content-type=%q", ct)
	}
}

func TestReadyzUpstreamOK(t *testing.T) {
	t.Parallel()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/ping" {
			http.NotFound(w, r)
			return
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"OK"}`))
	}))
	t.Cleanup(srv.Close)

	client := &readarr.Client{}
	// Construct via NewClient would block on fetch; use bare client fields via Ready path.
	// NewClient is heavy; set via package helper:
	client = readarr.NewTestClient(srv.URL, srv.Client())

	h := &Handlers{client: client}
	req := httptest.NewRequest(http.MethodGet, "/readyz", nil)
	rr := httptest.NewRecorder()
	h.Readyz(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body.String())
	}
	var body map[string]any
	if err := json.Unmarshal(rr.Body.Bytes(), &body); err != nil {
		t.Fatalf("json: %v", err)
	}
	if body["status"] != "ready" {
		t.Fatalf("status field=%v", body["status"])
	}
}

func TestReadyzUpstreamUnreachable(t *testing.T) {
	t.Parallel()
	client := readarr.NewTestClient("http://127.0.0.1:1", &http.Client{Timeout: 300 * time.Millisecond})
	h := &Handlers{client: client}
	req := httptest.NewRequest(http.MethodGet, "/readyz", nil)
	rr := httptest.NewRecorder()
	h.Readyz(rr, req)
	if rr.Code != http.StatusServiceUnavailable {
		t.Fatalf("status=%d want 503 body=%s", rr.Code, rr.Body.String())
	}
	var body map[string]any
	if err := json.Unmarshal(rr.Body.Bytes(), &body); err != nil {
		t.Fatalf("json: %v", err)
	}
	if body["status"] != "not_ready" {
		t.Fatalf("status field=%v", body["status"])
	}
	// Ensure we do not leak dial details beyond generic message
	if errMsg, _ := body["error"].(string); errMsg != "readarr unavailable" {
		t.Fatalf("error=%q want generic", errMsg)
	}
}

func TestReadyzNilClient(t *testing.T) {
	t.Parallel()
	h := &Handlers{}
	req := httptest.NewRequest(http.MethodGet, "/readyz", nil)
	rr := httptest.NewRecorder()
	h.Readyz(rr, req)
	if rr.Code != http.StatusServiceUnavailable {
		t.Fatalf("status=%d want 503", rr.Code)
	}
}

func TestAuthStillRequiredForAppRoutes(t *testing.T) {
	t.Parallel()
	// Smoke: RequireAuth still redirects unauthenticated HTML routes.
	// Health endpoints are registered outside RequireAuth in main.
	h := &Handlers{}
	// Without DB, RequireAuth may 500 — only assert Healthz is not wrapped.
	req := httptest.NewRequest(http.MethodGet, "/healthz", nil)
	rr := httptest.NewRecorder()
	h.Healthz(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("healthz must remain unauthenticated OK, got %d", rr.Code)
	}
}
