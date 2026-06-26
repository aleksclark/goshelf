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
- `MEDIA_PATH` - Path to audiobook files on disk
- `LISTEN_ADDR` - Address to listen on (default `:8080`)
- `DB_PATH` - Path to SQLite database (default `./goshelf.db`)
