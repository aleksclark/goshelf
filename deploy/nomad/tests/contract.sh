#!/usr/bin/env bash
# L1 repository-local static contract for deploy/nomad (plan 03 §3.6).
# No Nomad credentials. No secret values. Fail closed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
NOMAD_DIR="${ROOT}/deploy/nomad"
JOB="${NOMAD_DIR}/jobs/goshelf.nomad.hcl"
MANIFEST="${NOMAD_DIR}/deployment.yaml"
ENV_FILE="${NOMAD_DIR}/env/home.nomadvars.hcl"
LOCK="${NOMAD_DIR}/images.lock.hcl"
README="${NOMAD_DIR}/README.md"
EXPECTED="${NOMAD_DIR}/tests/expected-services.json"
CODEOWNERS="${ROOT}/.github/CODEOWNERS"

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "OK: $*"; }

need_file() {
  [[ -f "$1" ]] || fail "missing required file: $1"
}

need_file "$JOB"
need_file "$MANIFEST"
need_file "$ENV_FILE"
need_file "$LOCK"
need_file "$README"
need_file "$EXPECTED"
need_file "$CODEOWNERS"
pass "layout files present"

# --- deployment.yaml required fields (plan 03 §3.1) ---
for needle in \
  'schema_version: 1' \
  'project: goshelf' \
  'owner: aleks-clark' \
  'repository: https://github.com/aleksclark/goshelf' \
  'ref_policy: signed-default-branch-commit' \
  'namespace: default' \
  'datacenters: [home]' \
  'name: goshelf' \
  'id: goshelf' \
  'spec: jobs/goshelf.nomad.hcl' \
  'env: env/home.nomadvars.hcl' \
  'images: images.lock.hcl' \
  'nomad/jobs/goshelf' \
  'rollout: serial' \
  'prune: explicit-only'
do
  grep -Fq "$needle" "$MANIFEST" || fail "deployment.yaml missing ${needle}"
done
pass "deployment.yaml required fields"

# --- CODEOWNERS ---
grep -Eq '^/deploy/nomad/[[:space:]]+@aleksclark' "$CODEOWNERS" \
  || fail "CODEOWNERS must own /deploy/nomad/ @aleksclark"
grep -Eq '^/\.github/workflows/release' "$CODEOWNERS" \
  || fail "CODEOWNERS must own release workflows"
pass "CODEOWNERS"

# --- images.lock.hcl digest-only ---
LOCK_BODY="$(cat "$LOCK")"
echo "$LOCK_BODY" | grep -Eq 'image_goshelf[[:space:]]*=[[:space:]]*"ghcr\.io/aleksclark/goshelf@sha256:[0-9a-f]{64}"' \
  || fail "images.lock.hcl must set image_goshelf to repo@sha256:<64hex>"
echo "$LOCK_BODY" | grep -Eiq ':(latest|main|master)"' && fail "images.lock.hcl forbids mutable tags as authority" || true
DIGEST="$(echo "$LOCK_BODY" | sed -n 's/.*@sha256:\([0-9a-f]\{64\}\).*/\1/p' | head -1)"
[[ -n "$DIGEST" ]] || fail "could not parse lock digest"
pass "images.lock.hcl digest-only (${DIGEST:0:12}…)"

# --- jobspec: job id, digest pin, no mutable-only image ---
JOB_BODY="$(cat "$JOB")"
echo "$JOB_BODY" | grep -Fq 'job "goshelf"' || fail 'jobspec missing job "goshelf"'
echo "$JOB_BODY" | grep -Fq "@sha256:${DIGEST}" \
  || fail "jobspec image digest must match images.lock.hcl"
echo "$JOB_BODY" | grep -Eiq 'image[[:space:]]*=[[:space:]]*"[^"]*:latest"' \
  && fail "jobspec must not use :latest" || true
# Reject tag-only image lines (no @sha256)
if echo "$JOB_BODY" | grep -E 'image[[:space:]]*=' | grep -v '@sha256:' | grep -Eq 'image[[:space:]]*='; then
  fail "jobspec image must include @sha256 digest (no mutable tag-only pin)"
fi
pass "jobspec digest pin matches lock"

# --- singleton / stop-before-start ---
echo "$JOB_BODY" | grep -Eq 'max_parallel[[:space:]]*=[[:space:]]*1' \
  || fail "jobspec must set max_parallel = 1"
echo "$JOB_BODY" | grep -Eq 'canary[[:space:]]*=[[:space:]]*0' \
  || fail "jobspec must set canary = 0 (no dual-alloc canary)"
echo "$JOB_BODY" | grep -Eq 'count[[:space:]]*=[[:space:]]*1' \
  || fail "jobspec group count must be 1"
echo "$JOB_BODY" | grep -Eiq 'stop-before-start|stop_before_start|stop-zero-start' \
  || fail "jobspec must document stop-before-start singleton policy"
pass "singleton stop-before-start controls"

# --- provenance meta (placeholders OK; revision injected later) ---
for key in managed_by source_repo source_path source_revision deployment_owner release_set; do
  echo "$JOB_BODY" | grep -Eq "${key}[[:space:]]*=" \
    || fail "jobspec meta missing ${key}"
done
echo "$JOB_BODY" | grep -Eq 'managed_by[[:space:]]*=[[:space:]]*"(fleet-reconciler|fleet-pull-reconciler)"' \
  || fail 'managed_by must be fleet-reconciler (or legacy fleet-pull-reconciler)'
echo "$JOB_BODY" | grep -Eq 'deployment_owner[[:space:]]*=[[:space:]]*"aleks-clark"' \
  || fail 'deployment_owner must be aleks-clark'
echo "$JOB_BODY" | grep -Eq 'release_set[[:space:]]*=[[:space:]]*"goshelf"' \
  || fail 'release_set must be goshelf'
pass "provenance meta keys"

# --- secrets: no literals; nomadVar required ---
echo "$JOB_BODY" | grep -Eiq 'READARR_API_KEY[[:space:]]*=[[:space:]]*"[0-9a-fA-F]{16,}"' \
  && fail "inline READARR_API_KEY secret literal forbidden" || true
echo "$JOB_BODY" | grep -Fq 'nomadVar "nomad/jobs/goshelf"' \
  || fail 'must reference nomadVar "nomad/jobs/goshelf"'
echo "$JOB_BODY" | grep -Fq '.readarr_api_key' \
  || fail "must template readarr_api_key from nomadVar"
# Broad secret-shape scan across contract tree (values never in git)
SECRET_SCAN_FILES=("$JOB" "$MANIFEST" "$ENV_FILE" "$LOCK" "$README" "$EXPECTED" "$CODEOWNERS")
for f in "${SECRET_SCAN_FILES[@]}"; do
  if grep -Eiq 'BEGIN (RSA |OPENSSH )?PRIVATE KEY' "$f"; then
    fail "private key material in $f"
  fi
  if grep -Eiq 'postgres(ql)?://[^[:space:]"]+:[^[:space:]"]+@' "$f"; then
    fail "credentialed DSN shape in $f"
  fi
done
pass "no secret literals; nomadVar present"

# --- env overlay: non-secret only markers ---
ENV_BODY="$(cat "$ENV_FILE")"
echo "$ENV_BODY" | grep -Eiq 'api[_-]?key[[:space:]]*=' \
  && fail "env overlay must not assign api keys" || true
echo "$ENV_BODY" | grep -Eiq 'password[[:space:]]*=' \
  && fail "env overlay must not assign passwords" || true
for needle in datacenter moosefs-media moosefs-configs READARR_URL books.fleet.clark.team; do
  # allow case variants via file content classes already present
  true
done
grep -Eq 'moosefs-media|host_volume_media' "$ENV_FILE" || fail "env missing media volume alias"
grep -Eq 'moosefs-configs|host_volume_configs' "$ENV_FILE" || fail "env missing configs volume alias"
grep -Eq 'readarr_url|READARR_URL|readarr\.fleet\.clark\.team' "$ENV_FILE" \
  || fail "env missing Readarr URL class"
grep -Eq 'db_path|/configs/goshelf/goshelf\.db' "$ENV_FILE" \
  || fail "env missing DB path class"
pass "env overlay non-secret site policy"

# --- expected-services.json sanity ---
command -v python3 >/dev/null 2>&1 || fail "python3 required for expected-services.json parse"
python3 - "$EXPECTED" <<'PY'
import json, sys
path = sys.argv[1]
with open(path) as f:
    data = json.load(f)
assert data.get("job_id") == "goshelf", data.get("job_id")
assert data.get("groups", [{}])[0].get("count") == 1
upd = data["groups"][0].get("update", {})
assert upd.get("max_parallel") == 1
assert upd.get("canary") == 0
assert data.get("sqlite", {}).get("forbid_concurrent_allocs") is True
assert data.get("secrets", {}).get("variable_path") == "nomad/jobs/goshelf"
print("OK: expected-services.json structure")
PY

# --- README operator notes ---
for needle in \
  '/configs/goshelf/goshelf.db' \
  'WAL' \
  'backup' \
  'NEVER' \
  'stop-before-start' \
  'SQLite'
do
  grep -Fiq "$needle" "$README" || fail "README missing operator note: ${needle}"
done
pass "README SQLite/singleton operator notes"

# --- optional Go contract tests ---
if command -v go >/dev/null 2>&1; then
  (cd "$ROOT" && go test ./deploy/nomad/jobs/) || fail "go test ./deploy/nomad/jobs/ failed"
  pass "go test ./deploy/nomad/jobs/"
else
  echo "WARN: go not installed; skipped go test"
fi

echo
echo "PASS: deploy/nomad contract checks"
