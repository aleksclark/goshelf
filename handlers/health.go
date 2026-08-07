package handlers

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/aleksclark/goshelf/readarr"
)

// Healthz is a liveness probe: process is up and serving HTTP.
// It does not check upstream dependencies.
func (h *Handlers) Healthz(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"status":"ok"}`))
}

// Readyz is a readiness probe: configured Readarr durable /ping is reachable.
// Returns non-200 when the dependency is unavailable. Does not require auth.
func (h *Handlers) Readyz(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")

	if h == nil || h.client == nil {
		writeReadyErr(w, http.StatusServiceUnavailable, "readarr client not configured")
		return
	}

	timeout := readarr.DefaultReadyTimeout
	if err := h.client.Ready(timeout); err != nil {
		// Do not include upstream error details that might embed host internals beyond class.
		writeReadyErr(w, http.StatusServiceUnavailable, "readarr unavailable")
		return
	}

	w.WriteHeader(http.StatusOK)
	_ = json.NewEncoder(w).Encode(map[string]any{
		"status": "ready",
		"checks": map[string]string{
			"readarr": "ok",
		},
		// Bound observed by design; value-free.
		"timeout_ms": int(timeout / time.Millisecond),
	})
}

func writeReadyErr(w http.ResponseWriter, code int, msg string) {
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(map[string]any{
		"status": "not_ready",
		"error":  msg,
	})
}
