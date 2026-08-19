#!/usr/bin/env bash
# GoShelf Stacklane compose lifecycle.
# Always uses: docker compose -p "goshelf-<instance>" -f "$ROOT/docker-compose.yml"
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_SLUG="goshelf"

die() { echo "error: $*" >&2; exit 1; }
info() { echo "goshelf-compose: $*" >&2; }

# Neutralize accidental ambient Compose controls unless they are documented inputs.
# Do this before assigning the stack compose path so COMPOSE_FILE is not clobbered.
unset COMPOSE_FILE COMPOSE_PROFILES COMPOSE_PROJECT_NAME 2>/dev/null || true
STACK_COMPOSE_FILE="${ROOT}/docker-compose.yml"

# sanitize_instance: lowercase, non [a-z0-9-] → -, collapse dashes, trim, max 48, fallback dev
sanitize_instance() {
  local s="${1:-}"
  s="$(printf '%s' "$s" | tr '[:upper:]' '[:lower:]')"
  s="$(printf '%s' "$s" | sed -E 's/[^a-z0-9-]+/-/g; s/-+/-/g; s/^-+//; s/-+$//')"
  if [[ ${#s} -gt 48 ]]; then
    s="${s:0:48}"
    s="$(printf '%s' "$s" | sed -E 's/-+$//')"
  fi
  if [[ -z "$s" ]]; then
    s="dev"
  fi
  printf '%s' "$s"
}

derive_instance() {
  if [[ -n "${STACKLANE_INSTANCE:-}" ]]; then
    sanitize_instance "$STACKLANE_INSTANCE"
    return
  fi
  local wt
  wt="$(basename "$ROOT")"
  if [[ -n "$wt" && "$wt" != "." && "$wt" != "/" && "$wt" != "$PROJECT_SLUG" ]]; then
    sanitize_instance "$wt"
    return
  fi
  local branch=""
  if command -v git >/dev/null 2>&1; then
    branch="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
  fi
  if [[ -n "$branch" && "$branch" != "HEAD" ]]; then
    sanitize_instance "$branch"
    return
  fi
  sanitize_instance "dev"
}

require_docker() {
  command -v docker >/dev/null 2>&1 || die "docker not found"
  docker compose version >/dev/null 2>&1 || die "docker compose not available"
  [[ -f "$STACK_COMPOSE_FILE" ]] || die "missing $STACK_COMPOSE_FILE"
}

compose() {
  docker compose -p "$COMPOSE_PROJECT" -f "$STACK_COMPOSE_FILE" "$@"
}

detect_base_domain() {
  if [[ -n "${STACKLANE_BASE_DOMAIN:-}" ]]; then
    printf '%s' "$STACKLANE_BASE_DOMAIN"
    return
  fi
  if ! command -v stacklane >/dev/null 2>&1; then
    printf 'test'
    return
  fi
  local detected=""
  detected="$(timeout 5s stacklane status -o json 2>/dev/null | python3 -c 'import json,sys
try:
    d=json.load(sys.stdin)
    v=d.get("base_domain") or ""
    if isinstance(v,str) and v and "." not in v[:1] and " " not in v:
        print(v)
except Exception:
    pass
' 2>/dev/null || true)"
  if [[ -n "$detected" ]]; then
    printf '%s' "$detected"
    return
  fi
  printf 'test'
}

export_stack_env() {
  INSTANCE="$(derive_instance)"
  COMPOSE_PROJECT="${PROJECT_SLUG}-${INSTANCE}"
  export STACKLANE_INSTANCE="$INSTANCE"
  export COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT"
  STACKLANE_BASE_DOMAIN="$(detect_base_domain)"
  export STACKLANE_BASE_DOMAIN
}

host_port_for() {
  local svc="$1"
  local target="$2"
  local mapping
  mapping="$(compose port "$svc" "$target" 2>/dev/null || true)"
  if [[ -z "$mapping" ]]; then
    printf ''
    return
  fi
  printf '%s' "${mapping##*:}"
}

stacklane_status_line() {
  if ! command -v stacklane >/dev/null 2>&1; then
    printf 'stacklane: BLOCKED (daemon/cli absent — direct loopback ports still work)\n'
    return
  fi
  if timeout 5s stacklane status >/dev/null 2>&1; then
    local web_fqdn="web.${INSTANCE}.${PROJECT_SLUG}.${STACKLANE_BASE_DOMAIN:-test}"
    if timeout 5s stacklane resolve "$web_fqdn" >/dev/null 2>&1; then
      printf 'stacklane: OK\n'
    else
      printf 'stacklane: degraded (daemon up; %s not resolved yet)\n' "$web_fqdn"
    fi
  else
    printf 'stacklane: BLOCKED (daemon not reachable)\n'
  fi
}

print_endpoints() {
  local web_hp base
  web_hp="$(host_port_for web 8080)"
  base="${STACKLANE_BASE_DOMAIN:-test}"

  echo "web.${INSTANCE}.${PROJECT_SLUG}.${base}:8080  (via Stacklane VIP)"
  if [[ -n "$web_hp" ]]; then
    echo "direct web:  http://127.0.0.1:${web_hp}/"
  else
    echo "direct web:  (not published — stack down?)"
  fi
  stacklane_status_line
  echo "instance: ${INSTANCE}"
  echo "compose project: ${COMPOSE_PROJECT}"
  echo "stacklane base_domain: ${base}"
}

wait_healthy() {
  local timeout_s="${1:-300}"
  local start now elapsed
  start="$(date +%s)"
  info "waiting for web healthy (timeout ${timeout_s}s)…"
  while true; do
    now="$(date +%s)"
    elapsed=$((now - start))
    if (( elapsed > timeout_s )); then
      compose ps || true
      die "services not healthy within ${timeout_s}s"
    fi
    local web_h
    web_h="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${COMPOSE_PROJECT}-web-1" 2>/dev/null || echo missing)"
    if [[ "$web_h" == "healthy" ]]; then
      info "web healthy"
      return 0
    fi
    sleep 2
  done
}

# Fail-closed check: render compose config into a mode-0600 file under a mode-0700
# temp dir. Never print the full JSON (may contain interpolated env).
# Runs in a subshell so the cleanup trap cannot leak into `up`.
cmd_check() {
  require_docker
  command -v python3 >/dev/null 2>&1 || die "python3 required for JSON parse"
  export_stack_env

  (
  umask 077
  tmpdir="$(mktemp -d "${TMPDIR:-/tmp}/goshelf-compose-check.XXXXXX")"
  chmod 700 "$tmpdir"
  # shellcheck disable=SC2064
  trap 'rm -rf "'"$tmpdir"'"' EXIT INT TERM
  out="${tmpdir}/compose.config.json"
  touch "$out"
  chmod 600 "$out"

  info "rendering compose config for instance=${INSTANCE} project=${COMPOSE_PROJECT}"
  if ! compose config --format json >"$out"; then
    die "compose config render failed"
  fi
  python3 - "$out" "$INSTANCE" "$COMPOSE_PROJECT" <<'PY'
import json, sys, re

path, expect_instance, expect_project = sys.argv[1], sys.argv[2], sys.argv[3]
with open(path, "r", encoding="utf-8") as f:
    cfg = json.load(f)

errors = []

def err(msg):
    errors.append(msg)

services = cfg.get("services") or {}
if "web" not in services:
    err("missing service web")

name = cfg.get("name") or ""
if name and name != expect_project:
    err(f"compose name {name!r} != expected project {expect_project!r}")

volumes_top = cfg.get("volumes") or {}
vol_keys = set(volumes_top.keys())
named_required = (
    "goshelf_go_mod_cache",
    "goshelf_go_build_cache",
    "goshelf_air_tmp",
    "goshelf_data",
)
for req in named_required:
    if req not in vol_keys and not any(k.endswith(req) or k == req for k in vol_keys):
        err(f"missing named volume {req}")

def labels_map(sc):
    labels = sc.get("labels") or {}
    if isinstance(labels, list):
        out = {}
        for item in labels:
            if isinstance(item, str) and "=" in item:
                k, v = item.split("=", 1)
                out[k] = v
        return out
    if isinstance(labels, dict):
        return {str(k): str(v) for k, v in labels.items()}
    return {}

def check_ports(svc_name, sc, expect_target):
    ports = sc.get("ports") or []
    if not ports:
        err(f"{svc_name}: no published ports")
        return
    for p in ports:
        if not isinstance(p, dict):
            err(f"{svc_name}: port entry not object")
            continue
        hip = p.get("host_ip")
        if hip != "127.0.0.1":
            err(f"{svc_name}: host_ip must be 127.0.0.1, got {hip!r}")
        published = p.get("published")
        if published not in (None, "", 0, "0"):
            err(f"{svc_name}: published must be ephemeral empty/0, got {published!r}")
        target = p.get("target")
        try:
            target_i = int(target)
        except (TypeError, ValueError):
            err(f"{svc_name}: target port missing")
            continue
        if target_i != int(expect_target):
            err(f"{svc_name}: target port want {expect_target}, got {target!r}")

def check_isolation(svc_name, sc):
    if (sc.get("network_mode") or "") == "host":
        err(f"{svc_name}: network_mode=host forbidden")
    pid = sc.get("pid")
    if pid is not None and str(pid).strip().lower() in ("host", '"host"'):
        err(f"{svc_name}: pid=host forbidden")
    priv = sc.get("privileged")
    if priv is True or (isinstance(priv, str) and priv.strip().lower() in ("true", "1", "yes", "on")):
        err(f"{svc_name}: privileged=true forbidden")

def volume_entries(sc):
    return sc.get("volumes") or []

def has_bind(sc, target_suffix):
    for v in volume_entries(sc):
        if isinstance(v, dict):
            tgt = v.get("target") or v.get("destination") or ""
            typ = (v.get("type") or "").lower()
            if typ == "bind" and (tgt == target_suffix or tgt.endswith(target_suffix)):
                return True
        elif isinstance(v, str):
            if f":{target_suffix}" in v:
                return True
    return False

def has_named(sc, name_part):
    for v in volume_entries(sc):
        if isinstance(v, dict):
            src = str(v.get("source") or "")
            typ = (v.get("type") or "").lower()
            if name_part in src:
                return True
        elif isinstance(v, str) and name_part in v:
            return True
    return False

web = services.get("web") or {}
check_ports("web", web, 8080)
check_isolation("web", web)
wl = labels_map(web)
for k, want in {
    "stacklane.enable": "true",
    "stacklane.project": "goshelf",
    "stacklane.instance": expect_instance,
    "stacklane.endpoint": "web",
    "stacklane.port": "8080",
}.items():
    got = str(wl.get(k, ""))
    if got != want:
        err(f"web label {k}: got {got!r} want {want!r}")

# public == container; target_port optional. If present it must match 8080.
tp = str(wl.get("stacklane.target_port", "")).strip()
if tp and tp != "8080":
    err(f"web label stacklane.target_port={tp!r} want 8080 or omitted")

if not has_bind(web, "/src"):
    err("web: missing source bind mount to /src")
for nv in named_required:
    if not has_named(web, nv):
        err(f"web: missing named volume involving {nv}")

env = web.get("environment") or {}
if isinstance(env, list):
    env = {e.split("=", 1)[0]: (e.split("=", 1)[1] if "=" in e else "") for e in env if isinstance(e, str)}
env = {str(k): str(v) for k, v in env.items()}
if env.get("LISTEN_ADDR") != ":8080":
    err(f"web LISTEN_ADDR={env.get('LISTEN_ADDR')!r} want :8080")
if env.get("DB_PATH") != "/data/goshelf.db":
    err(f"web DB_PATH={env.get('DB_PATH')!r} want /data/goshelf.db")

# No real secrets materialized. Empty READARR_* is OK; non-empty values must not look like
# committed sample tokens. We only fail if a well-known placeholder leak pattern appears.
for key in ("READARR_API_KEY", "READARR_URL"):
    val = env.get(key, "")
    if re.search(r"(?i)(sk-|ghp_|xox[baprs]-)", val or ""):
        err(f"web env {key} looks like a real token (do not materialize secrets)")

hc = web.get("healthcheck") or {}
test = hc.get("test") or []
test_s = test if isinstance(test, str) else " ".join(str(x) for x in test)
if "/healthz" not in test_s:
    err(f"web healthcheck must hit /healthz, got {test_s!r}")

# No .local domains in compose-derived labels/env defaults.
blob = json.dumps({"labels": wl, "env": env})
if re.search(r"\.local\b", blob, re.I):
    err("compose-derived config must not use .local domains")

if errors:
    for e in errors:
        print(f"compose-dev-check: FAIL: {e}", file=sys.stderr)
    sys.exit(1)
print(f"compose-dev-check: ok: instance={expect_instance} project={expect_project}", file=sys.stderr)
sys.exit(0)
PY
  info "check passed (instance=${INSTANCE} project=${COMPOSE_PROJECT})"
  )
}

cmd_up() {
  require_docker
  export_stack_env
  cmd_check
  info "building images (project=${COMPOSE_PROJECT} instance=${INSTANCE})…"
  compose build
  info "starting stack…"
  compose up -d --remove-orphans
  wait_healthy 360
  print_endpoints
}

cmd_status() {
  require_docker
  export_stack_env
  compose ps
  echo
  print_endpoints
}

cmd_endpoints() {
  require_docker
  export_stack_env
  print_endpoints
}

cmd_logs() {
  require_docker
  export_stack_env
  # Ctrl-C stops following logs; it does not tear the stack down.
  compose logs -f "$@"
}

cmd_down() {
  require_docker
  export_stack_env
  info "stopping stack (volumes preserved; never uses -v)…"
  compose down --remove-orphans
}

cmd_destroy() {
  require_docker
  export_stack_env
  local expect="${COMPOSE_PROJECT}-destroy"
  if [[ "${CONFIRM:-}" != "$expect" ]]; then
    die "refusing destroy: set CONFIRM=${expect} to remove volumes for project ${COMPOSE_PROJECT}"
  fi
  info "destroying stack AND volumes for ${COMPOSE_PROJECT}…"
  compose down -v --remove-orphans
}

usage() {
  cat <<'EOF'
Usage: scripts/compose-dev.sh <command>

Commands:
  check       Fail-closed Stacklane/compose contract validation
  up          check + build + up -d + wait healthy + print endpoints
  status      compose ps + endpoint table
  endpoints   print FQDNs + direct loopback mappings
  logs        follow service logs (Ctrl-C leaves stack running)
  down        compose down (never -v; volumes preserved)
  destroy     compose down -v (requires CONFIRM=<compose-project>-destroy)

Environment:
  STACKLANE_INSTANCE     override instance slug (else worktree dirname / branch / dev)
  STACKLANE_BASE_DOMAIN  FQDN base (default: host daemon base_domain, else test)
  READARR_URL            optional Readarr base URL (no secret default)
  READARR_API_KEY        optional Readarr API key (no secret default)
  CONFIRM                required for destroy: <project>-<instance>-destroy

Notes:
  - Host `go run .` (or `make dev` / `task dev`) is unchanged and separate.
  - SQLite only. No provider tokens in compose samples.
  - Stacklane daemon is optional; direct 127.0.0.1 ephemeral ports always work.
  - Compose project is always goshelf-<instance> via docker compose -p.
EOF
}

main() {
  local cmd="${1:-}"
  shift || true
  case "$cmd" in
    check) cmd_check "$@" ;;
    up) cmd_up "$@" ;;
    status) cmd_status "$@" ;;
    endpoints) cmd_endpoints "$@" ;;
    logs) cmd_logs "$@" ;;
    down) cmd_down "$@" ;;
    destroy) cmd_destroy "$@" ;;
    -h|--help|help|"") usage; [[ -n "$cmd" ]] || exit 1 ;;
    *) die "unknown command: $cmd (try --help)" ;;
  esac
}

main "$@"
