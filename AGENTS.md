# AGENTS.md

GoShelf: self-hosted audiobook/ebook sharing web app. Single Go binary (net/http + templ + HTMX + Tailwind CDN) that reads library metadata from a Readarr API, serves files from a local media mount as ZIPs, and stores users/sessions in SQLite. Also contains an Android client (`android/`) and a Nomad deployment contract (`deploy/nomad/`).

## Essential commands

templ output (`*_templ.go`) is **gitignored**. A fresh checkout does not compile until you generate it (`undefined: templates.AdminPage` etc. means you skipped this step).

```bash
# Generate templates (required before build/vet/test). Pinned version matches CI/Dockerfiles.
templ generate
# If the asdf `templ` shim fails ("No version is set for command templ"), use:
go run github.com/a-h/templ/cmd/templ@v0.3.1020 generate

go vet ./...
staticcheck ./...                 # CI installs honnef.co/go/tools/cmd/staticcheck@latest; not installed locally by default
go test -race ./...               # CI: go test -race -coverprofile=coverage.out ./...
CGO_ENABLED=0 go build -ldflags="-s -w" -o goshelf .

go run .                          # host dev (also: make dev / task dev). Listens on :8080, DB ./goshelf.db
```

Re-run `templ generate` after every `.templ` edit; the generated Go is what compiles.

Deploy contract (static, no Nomad creds needed):

```bash
./deploy/nomad/tests/contract.sh   # also runs go test ./deploy/nomad/jobs/
```

Android (from `android/`, JDK 17): `./gradlew assembleDebug`, `./gradlew testDebugUnitTest`, `./gradlew connectedDebugAndroidTest` (instrumented; CI job is `continue-on-error` due to Hilt/emulator issues).

### Compose dev stack (Stacklane)

All via `scripts/compose-dev.sh <check|up|status|endpoints|logs|down|destroy>` (aliased by `make <cmd>`, `task compose:<cmd>`, and `paseo.json`).

- Compose project is always `goshelf-<instance>` via `docker compose -p`; instance = `$STACKLANE_INSTANCE`, else worktree dir name, else branch, else `dev`. Never hardcode a project name in `docker-compose.yml`.
- Ports must be published as `127.0.0.1::8080` (ephemeral loopback). `check` fails closed on fixed host ports, `0.0.0.0`, host networking, privileged, `.local` domains, missing named volumes/labels, or token-looking `READARR_*` values. Run `check` after editing `docker-compose.yml`.
- `down` never removes volumes. `destroy` requires `CONFIRM=goshelf-<instance>-destroy`.
- The container runs Air (`.air.toml`), which runs `templ generate && go build -buildvcs=false ...`. `-buildvcs=false` is intentional (bind-mounted `.git` ownership mismatch).

## Architecture

```
main.go          env config, route table (Go 1.22+ "METHOD /path/{param}" patterns), wiring
handlers/        HTTP handlers; one Handlers struct holding db, readarr client, media roots
models/          SQLite (modernc.org/sqlite, pure Go, CGO off): users, sessions, schema migration
readarr/         Readarr HTTP client + in-memory catalog cache
templates/       templ components (+ plain Go helpers/types in helpers.go, types.go)
android/         Kotlin/Compose client using /api/* and /books/{id}/download.zip
deploy/nomad/    Project-owned Nomad jobspec, image lock, contract tests
```

### Data flow

- **Catalog**: `readarr.NewClient` does a **blocking** initial fetch of `/api/v1/author` + `/api/v1/book`, then refreshes every 10 min in a goroutine. Only books with `Statistics.BookFileCount > 0` are kept. Indexes (by author, by ID, by series slug) are swapped in under a `sync.RWMutex`. Handlers read via `GetCached*`; `GetBook` falls back to the live API on cache miss. `GetBookFiles` and `ProxyCover` always hit Readarr live.
- **Series**: derived from the `seriesTitle` string (`"Name #6; Other #1"`), not `seriesLinks`. Books are indexed under **all** series in the string (`ParseAllSeriesNames`); routes use `SeriesSlug(name)`.
- **No Readarr URL**: if `READARR_URL` is empty, the client marks itself loaded with an empty catalog (no background refresh) so pages render empty instead of 500. `/readyz` returns 503.
- **Auth**: `RequireAuth` redirects to `/register` when zero users exist (first user is created as admin; registration is closed after that). Sessions are a `session` cookie mapped to the `sessions` table. User identity is passed downstream by **setting request headers** `X-User-ID`, `X-Username`, `X-Is-Admin` ("1"/"0") on `r`; handlers read those (`isAdmin(r)`). `/api/*` paths get JSON 401 instead of redirects.
- **HTMX**: list handlers check `HX-Request: true` and render only the grid partial (e.g. `AuthorGridWithPagination`) instead of the full page.
- **Migrations**: `models.migrate` is idempotent `CREATE TABLE IF NOT EXISTS` plus hand-written column checks via `pragma_table_info`. Add new columns the same way; there is no migration framework.

### Downloads (`handlers/download.go`)

- `prepareZip` stats the book's files (bounded timeout), keys the cache on the sorted file set, and builds the ZIP once in a detached goroutine shared by concurrent callers (`zipBuilds` map). A build writes `*.partial`, then atomically publishes `goshelf_zip_<book>_<key>.zip` plus a `.zip.json` manifest (`etag`, `size`, `fileCount`, `skipped`). A `.zip` without a manifest is never served; it gets rebuilt.
- ETag = hash of the files **actually written** (unreadable files are dropped during the full-read check), so size/ETag always describe the served bytes. Entries are sorted (`sortFiles`) so a rebuild with the same ETag is byte-identical; Range resume depends on this.
- `GET /books/{id}/download.zip` (`DownloadZipResumable`): serves the cached ZIP via `http.ServeContent` with an explicit `ETag` and zero modtime (If-Range is ETag-only; the file mtime is touched for cleanup aging).
- `GET /api/books/{id}/download-info`: builds/reuses the ZIP before answering. With `?async=1` it returns `202 {"status":"preparing"}` + `Retry-After` after `downloadInfoWait` (20s); the Android client polls. Without it, it blocks.
- `GET /books/{id}/download-stream.zip` (`DownloadZip`): legacy on-the-fly stream, no Range, no cache.
- ZIPs always use `zip.Store`; `calculateZipSize` hardcodes the layout and `download_test.go` checks it. Test seams: `Handlers.zipCacheDir`, `beforeZipBuild`, `downloadInfoWait` (see `zipcache_test.go`).

### Android download pipeline (`android/.../data/worker/DownloadWorker.kt`)

- No WorkManager network constraint on purpose (constraint loss stops the worker on screen lock / Wi-Fi handoff); the worker waits via `NetworkMonitor`, holds wake + Wi-Fi locks, and falls back to background work if a foreground service cannot start.
- Partial data (`DownloadStore`: `cacheDir/downloads/book_<id>.part|.download`) is never deleted on stop; only UI Cancel, an ETag/size change, or a CRC failure discards it. Metadata `formatVersion` < 2 is not resumable.
- Resume = one streamed request with `Range` + `If-Range`; responses are validated by `classifyRangeResponse` (pure, unit-tested in `src/test`). A per-book `Mutex` prevents two workers appending to the same part file.
- Pure logic lives in `data/download/DownloadProtocol.kt` (JVM unit tests); worker behaviour is covered by `androidTest/DownloadWorkerTest.kt` (MockWebServer, `file://` output folder seam).
- Instrumented tests: install APKs and run `adb -s <emulator> shell am instrument -w -e class ... com.goshelf.app.test/com.goshelf.app.PlainTestRunner` to target only an emulator; `connectedDebugAndroidTest` runs on every attached device. Gradle needs JDK 17 (`JAVA_HOME=/usr/lib/jvm/java-17-openjdk` locally).

### Media path mapping (security-sensitive)

Readarr paths (under `READARR_MEDIA_ROOT`, default `/media`) are rewritten onto `MEDIA_PATH` (default `/audiobooks`) by `MapReadarrPath`: strict root rewrite, rejects relative paths, `..`, and anything outside the root. `resolveFilePath` additionally rejects symlinks resolving outside `MEDIA_PATH`. Do not add "strip N segments" style fallbacks (explicitly forbidden by README/deploy docs). Tests: `handlers/pathmap_test.go`.

## Configuration

| Env | Default | Notes |
|---|---|---|
| `READARR_URL` | empty | optional; no secret defaults anywhere |
| `READARR_API_KEY` | empty | sent as `X-Api-Key` |
| `MEDIA_PATH` | `/audiobooks` | mount **root**, not the `audiobooks/` leaf |
| `READARR_MEDIA_ROOT` | `/media` | |
| `LISTEN_ADDR` | `:8080` | |
| `DB_PATH` | `./goshelf.db` | Docker image sets `/data/goshelf.db` |

`main.Version` is set by release via `-ldflags "-X main.Version=..."`.

## Testing patterns

- Standard library `testing` + `httptest` only; no assertion libraries.
- Do not call `readarr.NewClient` in tests (it blocks on a network fetch and starts a goroutine). Use `readarr.NewTestClient(baseURL, httpClient)` against an `httptest.Server`, or construct `&Handlers{...}` directly in package `handlers` tests.
- DB tests use `models.InitDB(filepath.Join(t.TempDir(), "test.db"))`.
- `deploy/nomad/jobs/jobspec_contract_test.go` is a Go test over the HCL text (runs in `go test ./...`). Editing the jobspec, image lock, CODEOWNERS, or deploy READMEs can break it and `contract.sh`.

## Conventions and gotchas

- Health: `/healthz` is liveness only (never checks upstream). `/readyz` checks Readarr `/ping` with a 3s bound and must not leak upstream error details.
- Logging/audit hygiene: never log API keys, book titles, media paths, or DB contents in health/deploy code paths. Existing path-mapping rejection logs deliberately omit the path.
- JSON API errors are written as literal JSON strings through `http.Error` (content type is text/plain there); `RequireAuth` sets JSON content type explicitly.
- Tailwind and HTMX load from CDNs in `templates/layout.templ`; there is no frontend build step.
- Production Docker image is `CGO_ENABLED=0` alpine; keep dependencies pure Go (hence `modernc.org/sqlite`).
- Go version is pinned to 1.26.6 in `go.mod`, CI, `Dockerfile`, `Dockerfile.dev`; templ to v0.3.1020 in the same places. Bump them together.
- `.dockerignore` excludes `android/`, `deploy/`, `*.md`, and `*_templ.go` from the image build context.

## Release and deploy

- Push/merge to `master` triggers CI and the Release workflow: CalVer tag `vYYYY.M.<patch>`, multi-arch GHCR image (`ghcr.io/aleksclark/goshelf:<tag>` and `:latest`), and linux/darwin binaries.
- Production runs on Nomad from `deploy/nomad/jobs/goshelf.nomad.hcl`. After a release, a follow-up commit pins the linux/amd64 digest in both `images.lock.hcl` and the jobspec (see `chore: pin goshelf image ...` commits). Never deploy floating tags.
- SQLite single-writer: group `count = 1`, `max_parallel = 1`, `canary = 0`, stop-before-start. Never introduce dual allocations/canaries against the same DB.
- Readarr API key comes from Nomad Variable `nomad/jobs/goshelf` key `readarr_api_key`; never inline secrets in HCL, overlays, or compose files.
- `/deploy/nomad/` and release workflows are CODEOWNERS-owned by `@aleksclark`.
