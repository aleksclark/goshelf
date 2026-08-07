# GoShelf deployment contract

Project-owned singleton. Canonical jobspec: `deploy/nomad/jobs/goshelf.nomad.hcl`.

## Ownership

- Repo: `aleksclark/goshelf`
- Job id: `goshelf`
- Classification: project-owned, stateful singleton
- Secret path: `nomad/jobs/goshelf` → key `readarr_api_key`
- Fleet ledger tracks ownership; **do not** duplicate the jobspec in fleet-iac.

## Runtime contract

| Item | Value |
|------|--------|
| Image | `ghcr.io/aleksclark/goshelf:<calver>` pinned to linux/amd64 digest after release |
| Port | host static `8580` (`http`) |
| Routes | Traefik Host `books.fleet.clark.team` \|\| `books.clark.team` (websecure + LE) |
| DB | `/configs/goshelf/goshelf.db` on host volume `moosefs-configs` → `/mnt/moosefs/configs` |
| Media mount | host vol `moosefs-media` → container `/audiobooks` (ro); host `/mnt/moosefs/media` |
| `MEDIA_PATH` | **`/audiobooks`** (mount root — not the `audiobooks/` leaf) |
| `READARR_MEDIA_ROOT` | **`/media`** — Readarr path root rewritten onto `MEDIA_PATH` |
| Path map | `/media/ebooks/…`→`/audiobooks/ebooks/…`; `/media/audiobooks/…`→`/audiobooks/audiobooks/…` |
| Readarr URL | **`https://readarr.fleet.clark.team`** (never node IP, never Speakarr `:8787`) |
| API key | Nomad Variable via workload identity template — never inline Env |
| Liveness | HTTP `GET /healthz` → 200 |
| Readiness | HTTP `GET /readyz` → 200 only when Readarr `/ping` is reachable |

## Health semantics

- `/healthz` — process liveness only (no upstream).
- `/readyz` — bounded Readarr durable `/ping` check; **503** when dependency down.
- App routes remain auth-gated (`/login` 200, `/` → 303 login when unauthenticated).
- Nomad must not report healthy when Readarr is unreachable (readiness check fails).

## Release / deploy

1. Merge to `master` → CI (lint/test/build) + Release workflow publishes multi-arch GHCR image + CalVer tag.
2. Capture linux/amd64 image digest; pin `image = "ghcr.io/aleksclark/goshelf:<tag>@sha256:…"` in jobspec (follow-up commit or same release PR when known).
3. Ensure Variable `nomad/jobs/goshelf` exists with `readarr_api_key` (length-only verify).
4. Ensure job-linked workload ACL can read that variable path.
5. **Project-owned** jobs are not fleet-iac GHA-wrapped: use reviewed manual `nomad job plan` + `nomad job run -check-index` (CAS) from this HCL.
6. Plan must show only intended image/env-template/health-check deltas — no DB/media/route/port drift.

## Pre-deploy backup

Root-only stamp under `/mnt/moosefs/backups/app-state/goshelf/<UTC>/` using SQLite online backup (or consistent DB+WAL). Preserve modes. Never purge DB/media. Record user/session **counts** only.

## Post-deploy verify (value-free)

- Deployment successful, Stable, alloc Restarts=0, exact image digest.
- Counts preserved; mounts healthy.
- `/healthz` 200, `/readyz` 200, `/login` 200, `/` → 303.
- Readarr durable connectivity; cover proxy and book-file lookup succeed for sampled IDs (no titles/paths in logs/audit).
- Ebook/audiobook download-info + bounded Range ZIP succeed when metadata `fileCount>0` (path map must resolve under mount root).
- Path mapping rejects traversal/outside-root; no arbitrary segment-strip fallback.
- No bulk download/index trigger.

## Audit hygiene

Record endpoint **class**, HTTP status, digest, counts, JMI/version, PR/SHA. Never API keys, titles, media paths, DB contents, or Variable values.
