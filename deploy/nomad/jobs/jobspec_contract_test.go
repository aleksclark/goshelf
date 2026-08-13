package deploycontract_test

import (
	"encoding/json"
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

func readFile(t *testing.T, rel ...string) string {
	t.Helper()
	p := filepath.Join(append([]string{repoRoot(t)}, rel...)...)
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatalf("read %s: %v", p, err)
	}
	return string(b)
}

func readJob(t *testing.T) string {
	t.Helper()
	return readFile(t, "deploy", "nomad", "jobs", "goshelf.nomad.hcl")
}

func TestJobspecExistsAndJobID(t *testing.T) {
	body := readJob(t)
	if !strings.Contains(body, `job "goshelf"`) {
		t.Fatal(`missing job "goshelf"`)
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
	if !strings.Contains(body, `canary            = 0`) && !regexp.MustCompile(`canary\s*=\s*0`).MatchString(body) {
		t.Fatal("missing canary = 0")
	}
	if !strings.Contains(body, "stop-before-start") {
		t.Fatal("missing stop-before-start singleton policy marker")
	}
	if !regexp.MustCompile(`count\s*=\s*1`).MatchString(body) {
		t.Fatal("group count must be 1")
	}
}

func TestJobspecProvenanceMeta(t *testing.T) {
	body := readJob(t)
	for _, key := range []string{
		"managed_by",
		"source_repo",
		"source_path",
		"source_revision",
		"deployment_owner",
		"release_set",
	} {
		if !strings.Contains(body, key) {
			t.Fatalf("meta missing %q", key)
		}
	}
	if !strings.Contains(body, `managed_by                 = "fleet-reconciler"`) &&
		!strings.Contains(body, `managed_by = "fleet-reconciler"`) &&
		!strings.Contains(body, `managed_by = "fleet-pull-reconciler"`) {
		// Accept either spacing; require canonical or legacy value.
		if !regexp.MustCompile(`managed_by\s*=\s*"(fleet-reconciler|fleet-pull-reconciler)"`).MatchString(body) {
			t.Fatal(`managed_by must be "fleet-reconciler" or legacy "fleet-pull-reconciler"`)
		}
	}
	if !regexp.MustCompile(`deployment_owner\s*=\s*"aleks-clark"`).MatchString(body) {
		t.Fatal(`deployment_owner must be "aleks-clark"`)
	}
	if !regexp.MustCompile(`release_set\s*=\s*"goshelf"`).MatchString(body) {
		t.Fatal(`release_set must be "goshelf"`)
	}
	if !strings.Contains(body, "deploy/nomad/jobs/goshelf.nomad.hcl") {
		t.Fatal("source_path must reference deploy/nomad/jobs/goshelf.nomad.hcl")
	}
}

func TestJobspecImageDigestPinned(t *testing.T) {
	body := readJob(t)
	lock := readFile(t, "deploy", "nomad", "images.lock.hcl")
	re := regexp.MustCompile(`@sha256:([0-9a-f]{64})`)
	lockDigests := re.FindAllStringSubmatch(lock, -1)
	if len(lockDigests) == 0 {
		t.Fatal("images.lock.hcl missing sha256 digest")
	}
	digest := lockDigests[0][1]
	if !strings.Contains(body, "@sha256:"+digest) {
		t.Fatalf("jobspec image must match lock digest %s", digest[:12])
	}
	if regexp.MustCompile(`image\s*=\s*"[^"]*:latest"`).MatchString(body) {
		t.Fatal("jobspec must not use :latest")
	}
	// Every image assignment must include a digest.
	for _, line := range strings.Split(body, "\n") {
		trim := strings.TrimSpace(line)
		if !strings.HasPrefix(trim, "image") || !strings.Contains(trim, "=") {
			continue
		}
		if !strings.Contains(trim, "@sha256:") {
			t.Fatalf("image line missing digest: %s", trim)
		}
	}
}

func TestDeploymentManifestContract(t *testing.T) {
	text := readFile(t, "deploy", "nomad", "deployment.yaml")
	for _, key := range []string{
		"schema_version: 1",
		"project: goshelf",
		"owner: aleks-clark",
		"repository: https://github.com/aleksclark/goshelf",
		"ref_policy: signed-default-branch-commit",
		"namespace: default",
		"datacenters: [home]",
		"name: goshelf",
		"id: goshelf",
		"spec: jobs/goshelf.nomad.hcl",
		"env: env/home.nomadvars.hcl",
		"images: images.lock.hcl",
		"nomad/jobs/goshelf",
		"rollout: serial",
		"prune: explicit-only",
	} {
		if !strings.Contains(text, key) {
			t.Fatalf("deployment.yaml missing %q", key)
		}
	}
	if regexp.MustCompile(`(?i)postgres://[^:]+:[^@]+@`).MatchString(text) {
		t.Fatal("deployment.yaml appears to contain a DSN with credentials")
	}
}

func TestImagesLockDigestOnly(t *testing.T) {
	text := readFile(t, "deploy", "nomad", "images.lock.hcl")
	re := regexp.MustCompile(`image_goshelf\s*=\s*"ghcr\.io/aleksclark/goshelf@sha256:[0-9a-f]{64}"`)
	if !re.MatchString(text) {
		t.Fatal("images.lock.hcl must set image_goshelf to digest-only ref")
	}
	if regexp.MustCompile(`:(latest|main|master)"`).MatchString(text) {
		t.Fatal("images.lock.hcl forbids mutable tags as authority")
	}
}

func TestEnvOverlayNonSecret(t *testing.T) {
	text := readFile(t, "deploy", "nomad", "env", "home.nomadvars.hcl")
	if regexp.MustCompile(`(?i)api[_-]?key\s*=`).MatchString(text) {
		t.Fatal("env overlay must not assign api keys")
	}
	if regexp.MustCompile(`(?i)password\s*=`).MatchString(text) {
		t.Fatal("env overlay must not assign passwords")
	}
	for _, needle := range []string{
		"moosefs-media",
		"moosefs-configs",
		"readarr.fleet.clark.team",
		"/configs/goshelf/goshelf.db",
	} {
		if !strings.Contains(text, needle) {
			t.Fatalf("env overlay missing %q", needle)
		}
	}
}

func TestCODEOWNERS(t *testing.T) {
	text := readFile(t, ".github", "CODEOWNERS")
	if !regexp.MustCompile(`(?m)^/deploy/nomad/\s+@aleksclark\s*$`).MatchString(text) {
		t.Fatal("CODEOWNERS must own /deploy/nomad/ @aleksclark")
	}
	if !strings.Contains(text, "/.github/workflows/release") {
		t.Fatal("CODEOWNERS must own release workflows")
	}
}

func TestExpectedServicesJSON(t *testing.T) {
	raw := readFile(t, "deploy", "nomad", "tests", "expected-services.json")
	var data map[string]any
	if err := json.Unmarshal([]byte(raw), &data); err != nil {
		t.Fatalf("expected-services.json: %v", err)
	}
	if data["job_id"] != "goshelf" {
		t.Fatalf("job_id=%v", data["job_id"])
	}
	groups, ok := data["groups"].([]any)
	if !ok || len(groups) == 0 {
		t.Fatal("expected groups")
	}
	g0, ok := groups[0].(map[string]any)
	if !ok {
		t.Fatal("group[0] shape")
	}
	if g0["count"] != float64(1) {
		t.Fatalf("count=%v", g0["count"])
	}
	upd, ok := g0["update"].(map[string]any)
	if !ok {
		t.Fatal("missing update")
	}
	if upd["max_parallel"] != float64(1) || upd["canary"] != float64(0) {
		t.Fatalf("update=%v", upd)
	}
}

func TestREADMEOperatorNotes(t *testing.T) {
	text := readFile(t, "deploy", "nomad", "README.md")
	for _, needle := range []string{
		"/configs/goshelf/goshelf.db",
		"WAL",
		"backup",
		"NEVER",
		"stop-before-start",
		"SQLite",
	} {
		if !strings.Contains(text, needle) {
			t.Fatalf("README missing %q", needle)
		}
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
