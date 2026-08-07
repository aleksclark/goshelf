package readarr

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// DefaultReadyTimeout bounds readiness probes against Readarr.
const DefaultReadyTimeout = 3 * time.Second

// Ping checks the configured Readarr base URL's durable /ping endpoint.
// It does not require an API key and never logs response bodies.
func (c *Client) Ping(ctx context.Context) error {
	if c == nil {
		return fmt.Errorf("readarr client is nil")
	}
	base := strings.TrimRight(c.baseURL, "/")
	if base == "" {
		return fmt.Errorf("readarr base URL is empty")
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, base+"/ping", nil)
	if err != nil {
		return fmt.Errorf("build ping request: %w", err)
	}

	client := c.httpClient
	if client == nil {
		client = &http.Client{Timeout: DefaultReadyTimeout}
	}

	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("ping readarr: %w", err)
	}
	defer resp.Body.Close()
	// Drain lightly without retaining body content.
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 512))

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("ping readarr: status %d", resp.StatusCode)
	}
	return nil
}

// Ready reports whether the configured Readarr dependency is reachable within timeout.
func (c *Client) Ready(timeout time.Duration) error {
	if timeout <= 0 {
		timeout = DefaultReadyTimeout
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	return c.Ping(ctx)
}
