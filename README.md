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

## Configuration

Environment variables:
- `READARR_URL` - Readarr/Speakarr API base URL
- `READARR_API_KEY` - Readarr API key (read-only access)
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
