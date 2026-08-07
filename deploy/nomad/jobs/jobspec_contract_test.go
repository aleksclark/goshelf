package deploycontract_test

import (
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"testing"
)

func repoRoot(t *testing.T) string {
	t.Helper()
	_, file, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller failed")
	}
	// deploy/nomad/jobs -> repo root is ../../..
	return filepath.Clean(filepath.Join(filepath.Dir(file), "..", "..", ".."))
}

func readJob(t *testing.T) string {
	t.Helper()
	p := filepath.Join(repoRoot(t), "deploy", "nomad", "jobs", "goshelf.nomad.hcl")
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatalf("read jobspec: %v", err)
	}
	return string(b)
}

func TestJobspecExistsAndJobID(t *testing.T) {
	body := readJob(t)
	if !strings.Contains(body, `job "goshelf"`) {
		t.Fatal("missing job \"goshelf\"")
	}
}

func TestJobspecDurableReadarrURL(t *testing.T) {
	body := readJob(t)
	if !strings.Contains(body, `READARR_URL = "https://readarr.fleet.clark.team"`) {
		t.Fatal("READARR_URL must be durable https://readarr.fleet.clark.team")
	}
	// Reject stale Speakarr/node patterns in the configured URL assignment.
	if strings.Contains(body, "192.168.0.24") {
		t.Fatal("jobspec must not hardcode node IP for Readarr")
	}
	urlLine := ""
	for _, line := range strings.Split(body, "\n") {
		if strings.Contains(line, "READARR_URL") && strings.Contains(line, "=") {
			urlLine = line
			break
		}
	}
	if urlLine == "" {
		t.Fatal("missing READARR_URL assignment")
	}
	if strings.Contains(urlLine, "8787") {
		t.Fatalf("READARR_URL must not use alternate product port 8787: %s", strings.TrimSpace(urlLine))
	}
}

func TestJobspecNoInlineAPIKey(t *testing.T) {
	body := readJob(t)
	// No hex-looking 32-char assignment to READARR_API_KEY
	re := regexp.MustCompile(`(?i)READARR_API_KEY\s*=\s*"[0-9a-f]{16,}"`)
	if re.MatchString(body) {
		t.Fatal("inline READARR_API_KEY secret literal forbidden")
	}
	if !strings.Contains(body, `nomadVar "nomad/jobs/goshelf"`) {
		t.Fatal("must reference nomadVar nomad/jobs/goshelf")
	}
	if !strings.Contains(body, ".readarr_api_key") {
		t.Fatal("must template readarr_api_key from nomadVar")
	}
	if !strings.Contains(body, "identity {") {
		t.Fatal("must declare workload identity")
	}
}

func TestJobspecHealthChecks(t *testing.T) {
	body := readJob(t)
	if !strings.Contains(body, `path     = "/healthz"`) {
		t.Fatal("missing /healthz liveness check")
	}
	if !strings.Contains(body, `path     = "/readyz"`) {
		t.Fatal("missing /readyz readiness check")
	}
	// Old login-only health is insufficient alone as readiness
	if strings.Count(body, `path     = "/login"`) > 0 && !strings.Contains(body, "/readyz") {
		t.Fatal("login-only health without readyz")
	}
}

func TestJobspecRoutesAndPort(t *testing.T) {
	body := readJob(t)
	if !strings.Contains(body, "books.fleet.clark.team") {
		t.Fatal("missing fleet Host route")
	}
	if !strings.Contains(body, "books.clark.team") {
		t.Fatal("missing public Host route")
	}
	if !strings.Contains(body, "static = 8580") {
		t.Fatal("missing static http port 8580")
	}
}

func TestJobspecPersistentMounts(t *testing.T) {
	body := readJob(t)
	for _, needle := range []string{
		`volume "moosefs-media"`,
		`volume "moosefs-configs"`,
		`destination = "/audiobooks"`,
		`destination = "/configs"`,
		`DB_PATH     = "/configs/goshelf/goshelf.db"`,
		// MEDIA_PATH is the mount root (not the audiobooks leaf) so ebooks + audiobooks map.
		`MEDIA_PATH = "/audiobooks"`,
		`READARR_MEDIA_ROOT = "/media"`,
	} {
		if !strings.Contains(body, needle) {
			t.Fatalf("missing mount/path contract %q", needle)
		}
	}
	// Reject legacy leaf-only MEDIA_PATH that broke ebook downloads.
	if strings.Contains(body, `MEDIA_PATH = "/audiobooks/audiobooks"`) ||
		strings.Contains(body, `MEDIA_PATH  = "/audiobooks/audiobooks"`) {
		t.Fatal("MEDIA_PATH must be mount root /audiobooks, not /audiobooks/audiobooks leaf")
	}
}

func TestJobspecUpdateRollback(t *testing.T) {
	body := readJob(t)
	if !strings.Contains(body, "auto_revert") {
		t.Fatal("missing auto_revert")
	}
	if !strings.Contains(body, `max_parallel      = 1`) {
		t.Fatal("missing max_parallel = 1")
	}
}

func TestMainHasNoHardcodedSecrets(t *testing.T) {
	p := filepath.Join(repoRoot(t), "main.go")
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatal(err)
	}
	s := string(b)
	if strings.Contains(s, "192.168.0.24:8787") {
		t.Fatal("main.go still has stale Readarr default URL")
	}
	re := regexp.MustCompile(`READARR_API_KEY\",\s*\"[0-9a-fA-F]{16,}\"`)
	if re.MatchString(s) {
		t.Fatal("main.go still has hardcoded API key fallback")
	}
	if !strings.Contains(s, "/healthz") || !strings.Contains(s, "/readyz") {
		t.Fatal("main.go must register /healthz and /readyz")
	}
}
