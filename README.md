# GoShelf

Minimal self-hosted audiobook sharing app. Provides a clean, dark-mode UI for browsing and downloading audiobooks from a Readarr-managed library.

## Features

- Browse audiobooks with cover art and metadata (sourced from Readarr API)
- Multi-user authentication (SQLite-backed)
- Download entire books as a single ZIP file (even multi-file books)
- Dark mode UI driven by templ + HTMX + Tailwind CSS
- No external service dependencies

## Stack

- Go (net/http)
- templ (type-safe HTML templates)
- HTMX (dynamic interactions without JS)
- Tailwind CSS (styling)
- SQLite (user accounts)
- Readarr API (read-only metadata access)

## Local development

Host fallback (no Docker):

```bash
go run .
# or: make dev / task dev
```

Defaults to `LISTEN_ADDR=:8080`. `READARR_URL` and `READARR_API_KEY` are optional; without them the process still serves `/healthz` and auth/SQLite, and library metadata stays empty.

Stacklane Compose (parallel worktrees; ephemeral loopback publish):

```bash
bash scripts/compose-dev.sh check
bash scripts/compose-dev.sh up
bash scripts/compose-dev.sh status
bash scripts/compose-dev.sh endpoints
bash scripts/compose-dev.sh logs    # Ctrl-C leaves the stack running
bash scripts/compose-dev.sh down    # never -v; SQLite/data volumes kept
CONFIRM=goshelf-<instance>-destroy bash scripts/compose-dev.sh destroy
```

Make/Task aliases: `make check|up|status|endpoints|logs|down|destroy` and `task compose:*`.
Compose project is always `goshelf-${STACKLANE_INSTANCE}` via `docker compose -p`.
Instance defaults to the worktree directory name, then git branch, then `dev`.
FQDN: `web.<instance>.goshelf.test:8080` (base domain from `stacklane status` when present).
Direct access: `http://127.0.0.1:<ephemeral>/` from `docker compose port`.
Stacklane daemon is optional; `status` reports `OK` / `degraded` / `BLOCKED` and loopback still works.

## Configuration

Environment variables:
- `READARR_URL` - Readarr/Speakarr API base URL (optional for local/compose)
- `READARR_API_KEY` - Readarr API key (optional; read-only access)
- `MEDIA_PATH` - Local mount root of the media tree (default `/audiobooks`)
- `READARR_MEDIA_ROOT` - Readarr absolute path root rewritten onto `MEDIA_PATH` (default `/media`)
- `LISTEN_ADDR` - Address to listen on (default `:8080`)
- `DB_PATH` - Path to SQLite database (default `./goshelf.db`)

### Media path mapping

Readarr stores book file paths under its root (typically `/media/...`). GoShelf mounts the same host tree at `MEDIA_PATH` and rewrites:

| Readarr path class | Local path class |
| --- | --- |
| `/media/ebooks/...` | `$MEDIA_PATH/ebooks/...` |
| `/media/audiobooks/...` | `$MEDIA_PATH/audiobooks/...` |

Mapping is a strict root rewrite with path cleaning. Paths outside `READARR_MEDIA_ROOT`, relative paths, and `..` traversal are rejected. There is no arbitrary “strip Nth segment” fallback.
