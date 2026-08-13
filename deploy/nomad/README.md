# Nomad deployment contract (goshelf)

Authoritative project-owned definition for the **goshelf** singleton shipped
from `aleksclark/goshelf`. Layout matches plan 03 §3.

## Layout

| Path | Role |
|---|---|
| `deployment.yaml` | Source/ownership/reconcile manifest (schema_version 1) |
| `jobs/goshelf.nomad.hcl` | Portable jobspec (one top-level job) |
| `env/home.nomadvars.hcl` | Non-secret home-fleet overlay |
| `images.lock.hcl` | Immutable image digest lock |
| `tests/` | Static contract tests (no secrets, no live Nomad) |

## Ownership

- Project: `goshelf`
- Owner identity (manifest): `aleks-clark`
- GitHub CODEOWNERS: `@aleksclark` on `/deploy/nomad/` and release workflows
- Job ID: `goshelf` (must stay stable)
- Classification: project-owned, **stateful singleton**
- Secret path: `nomad/jobs/goshelf` → key `readarr_api_key` (values never in Git)
- Fleet ledger tracks ownership; **do not** duplicate this jobspec in fleet-iac

## SQLite single-writer (CRITICAL)

GoShelf stores state in SQLite:

| Item | Value |
|---|---|
| DB file | `/configs/goshelf/goshelf.db` |
| Host volume | `moosefs-configs` → container `/configs` (rw) |
| Host path class | `/mnt/moosefs/configs/goshelf/` |
| WAL/SHM | `goshelf.db-wal`, `goshelf.db-shm` beside the DB |

### NEVER two allocations concurrent on the same SQLite DB

- Group `count = 1`.
- Update: `max_parallel = 1`, `canary = 0` (no dual-alloc canary).
- Operator policy: **stop-before-start** (stop old alloc to zero, then start new).
- Rolling create-before-destroy against the same MooseFS SQLite path is **forbidden**.
- `max_parallel = 1` alone is not dual-writer safety — always drain to zero writers first.

### Backup / restore

1. Prefer SQLite online backup API (or consistent copy of DB + WAL + SHM while writers are stopped).
2. Stamp under `/mnt/moosefs/backups/app-state/goshelf/<UTC>/` (root-only).
3. Preserve modes/ownership. Never purge DB or media.
4. Verify restore with `PRAGMA integrity_check` in isolation before cutover.
5. Record user/session **counts** only in audits — never titles, paths, or DB contents.

### Rollback

- Prefer prior proven image digest from `images.lock.hcl` history.
- Schema-compatible digests only; otherwise restore the documented SQLite backup first.
- Do not purge host volumes or Nomad Variables on rollback.

## Runtime contract

| Item | Value |
|---|---|
| Image | `ghcr.io/aleksclark/goshelf` pinned by digest in `images.lock.hcl` + jobspec |
| Port | host static `8580` (`http`) |
| Routes | Traefik Host `books.fleet.clark.team` \|\| `books.clark.team` (websecure + LE) |
| Media mount | host vol `moosefs-media` → `/audiobooks` (ro) |
| `MEDIA_PATH` | `/audiobooks` (mount root) |
| `READARR_MEDIA_ROOT` | `/media` |
| Readarr URL | `https://readarr.fleet.clark.team` |
| API key | Nomad Variable via workload identity — never inline |
| Liveness | `GET /healthz` |
| Readiness | `GET /readyz` (Readarr `/ping`) |

## Secrets

Create Nomad Variable path `nomad/jobs/goshelf` with key name only:

- `readarr_api_key`

Values never belong in git, overlays, image locks, plan output, or logs.

## Image authority

1. Release workflow publishes `ghcr.io/aleksclark/goshelf:<calver>` and emits a digest.
2. Pin PR updates `images.lock.hcl` and the jobspec image line to the same digest.
3. Never treat `:latest` or floating tags as deploy authority.

## Validation (source-only)

```bash
# L1 contract (no Nomad credentials)
./deploy/nomad/tests/contract.sh

# Existing Go contract tests
go test ./deploy/nomad/jobs/
```

Live enroll / authority flip is **out of scope** for this source contract PR (S0→S1).

## Audit hygiene

Record endpoint class, HTTP status, digest, counts, PR/SHA. Never API keys,
titles, media paths, DB contents, or Variable values.
