package handlers

import (
	"archive/zip"
	"bufio"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	zipTempPrefix   = "goshelf_zip_"
	zipCacheMaxAge  = 2 * time.Hour
	zipCleanupEvery = 30 * time.Minute
	fileStatTimeout = 15 * time.Second
	zipWriteBuffer  = 1 << 20
)

// downloadInfoWait bounds how long an async download-info request waits for
// the server-side ZIP build before answering 202 so clients can poll instead
// of tripping proxy timeouts on large books.
var downloadInfoWait = 20 * time.Second

// beforeZipBuild is a test seam invoked at the start of every ZIP build.
var beforeZipBuild = func() {}

var (
	errNoAccessibleFiles = errors.New("no accessible files")
	errZipPreparing      = errors.New("zip is still being prepared")
)

// validFile holds info about a validated book file on disk.
type validFile struct {
	localPath string
	entryName string
	size      int64
	modTime   time.Time
}

// zipArtifact describes a fully built, cached ZIP. ETag identifies the exact
// bytes on disk: it is derived from the files actually written into the ZIP,
// so a rebuild that includes a different file set always gets a new ETag.
type zipArtifact struct {
	Path      string `json:"-"`
	ETag      string `json:"etag"`
	Size      int64  `json:"size"`
	FileCount int    `json:"fileCount"`
	Skipped   int    `json:"skipped"`
}

type zipBuild struct {
	done chan struct{}
	art  *zipArtifact
	err  error
}

var (
	zipBuildsMu sync.Mutex
	zipBuilds   = map[string]*zipBuild{}
)

// StartZipCleanup launches a background goroutine that deletes stale temp ZIP files every 30 minutes.
func StartZipCleanup() {
	go func() {
		for {
			cleanupTempZips(os.TempDir(), zipCacheMaxAge)
			time.Sleep(zipCleanupEvery)
		}
	}()
}

// cleanupTempZips removes cached ZIPs (and their manifests) and abandoned
// partial builds that have not been touched within maxAge.
func cleanupTempZips(dir string, maxAge time.Duration) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		log.Printf("zip cleanup: error reading temp dir: %v", err)
		return
	}
	now := time.Now()
	for _, entry := range entries {
		name := entry.Name()
		if entry.IsDir() || !strings.HasPrefix(name, zipTempPrefix) {
			continue
		}
		info, err := entry.Info()
		if err != nil || now.Sub(info.ModTime()) <= maxAge {
			continue
		}
		path := filepath.Join(dir, name)
		switch {
		case strings.HasSuffix(name, ".zip"):
			removeZipArtifact(path)
			log.Printf("zip cleanup: removed stale %s", name)
		case strings.HasSuffix(name, ".partial"):
			os.Remove(path)
		case strings.HasSuffix(name, ".json"):
			if _, err := os.Stat(strings.TrimSuffix(path, ".json")); errors.Is(err, os.ErrNotExist) {
				os.Remove(path)
			}
		}
	}
}

func removeZipArtifact(zipPath string) {
	if err := os.Remove(zipPath); err != nil && !errors.Is(err, os.ErrNotExist) {
		log.Printf("zip cleanup: failed to remove %s: %v", zipPath, err)
	}
	os.Remove(zipPath + ".json")
}

// computeCacheKey computes a hash from bookID + all file sizes and modification times.
func computeCacheKey(bookID int, files []validFile) string {
	h := sha256.New()
	fmt.Fprintf(h, "book:%d\n", bookID)
	sorted := make([]validFile, len(files))
	copy(sorted, files)
	sortFiles(sorted)
	for _, f := range sorted {
		fmt.Fprintf(h, "%s:%d:%d\n", f.entryName, f.size, f.modTime.UnixNano())
	}
	return hex.EncodeToString(h.Sum(nil))[:16]
}

// sortFiles gives ZIP entries a deterministic order so a rebuilt ZIP with the
// same ETag is byte-identical, which Range resume depends on.
func sortFiles(files []validFile) {
	sort.SliceStable(files, func(i, j int) bool {
		if files[i].entryName != files[j].entryName {
			return files[i].entryName < files[j].entryName
		}
		return files[i].localPath < files[j].localPath
	})
}

// zipTempPath returns the cache path for a book ZIP built from a given file set.
func zipTempPath(dir string, bookID int, key string) string {
	return filepath.Join(dir, fmt.Sprintf("%s%d_%s.zip", zipTempPrefix, bookID, key))
}

func (h *Handlers) zipDir() string {
	if h.zipCacheDir != "" {
		return h.zipCacheDir
	}
	return os.TempDir()
}

// calculateZipSize calculates exact ZIP size for Store method (no compression).
func calculateZipSize(files []validFile) int64 {
	zipSize := int64(0)
	for _, f := range files {
		nameLen := int64(len(f.entryName))
		// Local file header: 30 bytes + filename
		zipSize += 30 + nameLen
		// File data (stored, no compression)
		zipSize += f.size
		// Data descriptor: 16 bytes (Go's zip writer always writes these)
		zipSize += 16
		// Central directory entry: 46 bytes + filename
		zipSize += 46 + nameLen
	}
	// End of central directory record: 22 bytes
	zipSize += 22
	return zipSize
}

// statBookFiles lists a book's files from Readarr and stats them on the local
// mount. Stat calls are bounded because the network filesystem can hang.
// Returned files are sorted deterministically. The second value is the number
// of files that could not be mapped or stat'ed.
func (h *Handlers) statBookFiles(id int) ([]validFile, int, error) {
	files, err := h.client.GetBookFiles(id)
	if err != nil {
		return nil, 0, fmt.Errorf("list files for book %d: %w", id, err)
	}
	if len(files) == 0 {
		return nil, 0, errNoAccessibleFiles
	}

	type statResult struct {
		file validFile
		ok   bool
	}
	results := make(chan statResult, len(files))
	for _, f := range files {
		go func(readarrPath string) {
			localPath, ok := h.resolveFilePath(readarrPath)
			if !ok {
				results <- statResult{}
				return
			}
			info, err := os.Stat(localPath)
			if err != nil || !info.Mode().IsRegular() {
				results <- statResult{}
				return
			}
			results <- statResult{ok: true, file: validFile{
				localPath: localPath,
				entryName: filepath.Base(readarrPath),
				size:      info.Size(),
				modTime:   info.ModTime(),
			}}
		}(f.Path)
	}

	deadline := time.NewTimer(fileStatTimeout)
	defer deadline.Stop()
	var valid []validFile
	received := 0
collect:
	for received < len(files) {
		select {
		case r := <-results:
			received++
			if r.ok {
				valid = append(valid, r.file)
			}
		case <-deadline.C:
			log.Printf("Book %d: %d file stat(s) timed out", id, len(files)-received)
			break collect
		}
	}
	skipped := len(files) - len(valid)
	if skipped > 0 {
		log.Printf("Book %d: skipped %d inaccessible files, %d files available", id, skipped, len(valid))
	}
	sortFiles(valid)
	return valid, skipped, nil
}

// prepareZip returns the cached ZIP for a book, building it if needed. Builds
// run detached from the request and are shared between concurrent callers, so
// a client that times out and retries joins the in-flight build instead of
// starting over. If ctx ends before the build finishes, errZipPreparing is
// returned and the build keeps running.
func (h *Handlers) prepareZip(ctx context.Context, id int) (*zipArtifact, error) {
	files, statSkipped, err := h.statBookFiles(id)
	if err != nil {
		return nil, err
	}
	if len(files) == 0 {
		return nil, errNoAccessibleFiles
	}
	key := computeCacheKey(id, files)
	art, err := waitForZip(ctx, h.zipDir(), id, key, files)
	if err != nil {
		return nil, err
	}
	out := *art
	out.Skipped += statSkipped
	return &out, nil
}

func waitForZip(ctx context.Context, dir string, id int, key string, files []validFile) (*zipArtifact, error) {
	path := zipTempPath(dir, id, key)

	zipBuildsMu.Lock()
	build, inFlight := zipBuilds[path]
	if !inFlight {
		if art, err := loadZipArtifact(path); err == nil {
			zipBuildsMu.Unlock()
			touchZipArtifact(path)
			return art, nil
		}
		build = &zipBuild{done: make(chan struct{})}
		zipBuilds[path] = build
		go func() {
			build.art, build.err = buildZipArtifact(dir, id, key, path, files)
			zipBuildsMu.Lock()
			delete(zipBuilds, path)
			zipBuildsMu.Unlock()
			close(build.done)
		}()
	}
	zipBuildsMu.Unlock()

	select {
	case <-build.done:
		if build.err != nil {
			return nil, build.err
		}
		touchZipArtifact(path)
		return build.art, nil
	case <-ctx.Done():
		return nil, errZipPreparing
	}
}

func loadZipArtifact(path string) (*zipArtifact, error) {
	data, err := os.ReadFile(path + ".json")
	if err != nil {
		return nil, err
	}
	var art zipArtifact
	if err := json.Unmarshal(data, &art); err != nil {
		return nil, err
	}
	info, err := os.Stat(path)
	if err != nil {
		return nil, err
	}
	if info.Size() != art.Size || art.ETag == "" {
		return nil, fmt.Errorf("zip manifest mismatch for %s", filepath.Base(path))
	}
	art.Path = path
	return &art, nil
}

func touchZipArtifact(path string) {
	now := time.Now()
	os.Chtimes(path, now, now)
	os.Chtimes(path+".json", now, now)
}

// buildZipArtifact verifies every file is fully readable, writes the ZIP to a
// partial file, and atomically publishes it with a manifest. The manifest's
// size and ETag describe the bytes actually written, never the candidate set.
func buildZipArtifact(dir string, id int, key, path string, files []validFile) (*zipArtifact, error) {
	beforeZipBuild()
	cleanupBookZips(dir, id, key)
	start := time.Now()

	verified := make([]validFile, 0, len(files))
	for _, f := range files {
		if isFileFullyReadable(f.localPath, f.size) {
			verified = append(verified, f)
		} else {
			log.Printf("ZIP build: book %d: skipping unreadable file", id)
		}
	}
	if len(verified) == 0 {
		return nil, errNoAccessibleFiles
	}

	tmp, err := os.CreateTemp(dir, fmt.Sprintf("%s%d_%s_*.partial", zipTempPrefix, id, key))
	if err != nil {
		return nil, fmt.Errorf("creating temp file: %w", err)
	}
	tmpPath := tmp.Name()
	if err := writeStoreZip(tmp, verified); err != nil {
		tmp.Close()
		os.Remove(tmpPath)
		return nil, err
	}
	if err := tmp.Close(); err != nil {
		os.Remove(tmpPath)
		return nil, fmt.Errorf("closing zip: %w", err)
	}
	info, err := os.Stat(tmpPath)
	if err != nil {
		os.Remove(tmpPath)
		return nil, err
	}
	if want := calculateZipSize(verified); info.Size() != want {
		log.Printf("ZIP build: book %d: size %d differs from calculated %d", id, info.Size(), want)
	}

	art := &zipArtifact{
		Path:      path,
		ETag:      computeCacheKey(id, verified),
		Size:      info.Size(),
		FileCount: len(verified),
		Skipped:   len(files) - len(verified),
	}
	manifest, err := json.Marshal(art)
	if err != nil {
		os.Remove(tmpPath)
		return nil, err
	}
	if err := writeFileAtomic(path+".json", manifest); err != nil {
		os.Remove(tmpPath)
		return nil, fmt.Errorf("writing zip manifest: %w", err)
	}
	if err := os.Rename(tmpPath, path); err != nil {
		os.Remove(tmpPath)
		os.Remove(path + ".json")
		return nil, fmt.Errorf("publishing zip: %w", err)
	}
	log.Printf("ZIP built for book %d: %d files, %d bytes in %v", id, art.FileCount, art.Size, time.Since(start).Round(time.Millisecond))
	return art, nil
}

func writeFileAtomic(path string, data []byte) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		os.Remove(tmp)
		return err
	}
	return nil
}

// writeStoreZip writes files into a ZIP using the Store method. Exactly
// f.size bytes are copied per entry so the output matches calculateZipSize.
func writeStoreZip(w io.Writer, files []validFile) error {
	bw := bufio.NewWriterSize(w, zipWriteBuffer)
	zw := zip.NewWriter(bw)
	for _, f := range files {
		file, err := os.Open(f.localPath)
		if err != nil {
			return fmt.Errorf("opening %s: %w", f.entryName, err)
		}
		header := &zip.FileHeader{
			Name:   f.entryName,
			Method: zip.Store,
		}
		header.UncompressedSize64 = uint64(f.size)
		writer, err := zw.CreateHeader(header)
		if err != nil {
			file.Close()
			return fmt.Errorf("creating zip entry %s: %w", f.entryName, err)
		}
		_, err = io.CopyN(writer, file, f.size)
		file.Close()
		if err != nil {
			return fmt.Errorf("writing zip entry %s: %w", f.entryName, err)
		}
	}
	if err := zw.Close(); err != nil {
		return err
	}
	return bw.Flush()
}

// isFileFullyReadable reads the entire file to verify all chunks are accessible.
// Uses a timeout proportional to file size (minimum 10s, ~1MB/s for slow network FS).
func isFileFullyReadable(path string, size int64) bool {
	timeout := time.Duration(size/(1024*1024))*time.Second + 10*time.Second
	if timeout > 2*time.Minute {
		timeout = 2 * time.Minute
	}

	done := make(chan bool, 1)
	go func() {
		f, err := os.Open(path)
		if err != nil {
			done <- false
			return
		}
		defer f.Close()
		_, err = io.Copy(io.Discard, f)
		done <- (err == nil)
	}()

	select {
	case ok := <-done:
		return ok
	case <-time.After(timeout):
		log.Printf("Timeout reading file (%d MB, timeout %v)", size/(1024*1024), timeout)
		return false
	}
}

// cleanupBookZips removes cached ZIPs for a book built from a different file set.
func cleanupBookZips(dir string, bookID int, currentKey string) {
	prefix := fmt.Sprintf("%s%d_", zipTempPrefix, bookID)
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	for _, entry := range entries {
		name := entry.Name()
		if entry.IsDir() || !strings.HasPrefix(name, prefix) || !strings.HasSuffix(name, ".zip") {
			continue
		}
		if name == fmt.Sprintf("%s%s.zip", prefix, currentKey) {
			continue
		}
		removeZipArtifact(filepath.Join(dir, name))
		log.Printf("Removed old zip cache: %s", name)
	}
}

// DownloadZipResumable serves a book as a ZIP with HTTP Range request support.
// The ZIP is served with a strong ETag so clients can resume with If-Range and
// get a full 200 response instead of mismatched bytes if the ZIP changed.
func (h *Handlers) DownloadZipResumable(w http.ResponseWriter, r *http.Request) {
	idStr := r.PathValue("id")
	id, err := strconv.Atoi(idStr)
	if err != nil {
		http.Error(w, "Invalid book ID", http.StatusBadRequest)
		return
	}

	book, err := h.client.GetBook(id)
	if err != nil {
		log.Printf("Error fetching book %d: %v", id, err)
		http.Error(w, "Book not found", http.StatusNotFound)
		return
	}

	art, err := h.prepareZip(r.Context(), id)
	if err != nil {
		if errors.Is(err, errZipPreparing) {
			return
		}
		log.Printf("Error preparing ZIP for book %d: %v", id, err)
		if errors.Is(err, errNoAccessibleFiles) {
			http.Error(w, "No accessible files found", http.StatusNotFound)
			return
		}
		http.Error(w, "Failed to build ZIP", http.StatusInternalServerError)
		return
	}

	f, err := os.Open(art.Path)
	if err != nil {
		log.Printf("Error opening cached ZIP for book %d: %v", id, err)
		http.Error(w, "Failed to read ZIP", http.StatusInternalServerError)
		return
	}
	defer f.Close()

	zipFilename := sanitizeFilename(book.Title) + ".zip"
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, zipFilename))
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("ETag", `"`+art.ETag+`"`)
	// Prevent proxies from transforming the response
	w.Header().Set("Cache-Control", "no-transform")

	// Zero modtime: validation is ETag-only because the cache file's mtime is
	// touched on access for cleanup aging.
	http.ServeContent(w, r, zipFilename, time.Time{}, f)
}

// APIDownloadInfo returns JSON with ZIP download metadata (size, etag, filename).
// The ZIP is built (or reused) before answering so totalSize and etag describe
// the exact bytes download.zip will serve. With ?async=1 the handler answers
// 202 {"status":"preparing"} if the build takes longer than downloadInfoWait;
// clients poll until they get 200.
func (h *Handlers) APIDownloadInfo(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")

	idStr := r.PathValue("id")
	id, err := strconv.Atoi(idStr)
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, "Invalid book ID")
		return
	}

	book, err := h.client.GetBook(id)
	if err != nil {
		log.Printf("API: Error fetching book %d: %v", id, err)
		writeJSONError(w, http.StatusNotFound, "Book not found")
		return
	}

	ctx := r.Context()
	if r.URL.Query().Get("async") == "1" {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, downloadInfoWait)
		defer cancel()
	}

	art, err := h.prepareZip(ctx, id)
	if err != nil {
		switch {
		case errors.Is(err, errZipPreparing):
			retryAfter := 5
			w.Header().Set("Retry-After", strconv.Itoa(retryAfter))
			w.WriteHeader(http.StatusAccepted)
			json.NewEncoder(w).Encode(map[string]any{
				"status":     "preparing",
				"retryAfter": retryAfter,
			})
		case errors.Is(err, errNoAccessibleFiles):
			log.Printf("API: No accessible files for book %d", id)
			writeJSONError(w, http.StatusNotFound, "No accessible files found")
		default:
			log.Printf("API: Error preparing ZIP for book %d: %v", id, err)
			writeJSONError(w, http.StatusBadGateway, "Failed to prepare download")
		}
		return
	}

	resp := struct {
		Status       string `json:"status"`
		TotalSize    int64  `json:"totalSize"`
		ETag         string `json:"etag"`
		Filename     string `json:"filename"`
		FileCount    int    `json:"fileCount"`
		SkippedFiles int    `json:"skippedFiles,omitempty"`
	}{
		Status:       "ready",
		TotalSize:    art.Size,
		ETag:         `"` + art.ETag + `"`,
		Filename:     sanitizeFilename(book.Title) + ".zip",
		FileCount:    art.FileCount,
		SkippedFiles: art.Skipped,
	}
	json.NewEncoder(w).Encode(resp)
}

func writeJSONError(w http.ResponseWriter, code int, msg string) {
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(map[string]string{"error": msg})
}

// DownloadZip streams a ZIP on-the-fly (legacy fallback, no Range support).
func (h *Handlers) DownloadZip(w http.ResponseWriter, r *http.Request) {
	idStr := r.PathValue("id")
	id, err := strconv.Atoi(idStr)
	if err != nil {
		http.Error(w, "Invalid book ID", http.StatusBadRequest)
		return
	}

	// Get book metadata for filename
	book, err := h.client.GetBook(id)
	if err != nil {
		log.Printf("Error fetching book %d: %v", id, err)
		http.Error(w, "Book not found", http.StatusNotFound)
		return
	}

	// Get book files
	files, err := h.client.GetBookFiles(id)
	if err != nil || len(files) == 0 {
		log.Printf("Error fetching files for book %d: %v", id, err)
		http.Error(w, "No files found", http.StatusNotFound)
		return
	}

	// Verify all files exist and calculate total size for Content-Length.
	// Using zip.Store (no compression) since audio files are already compressed.
	// This lets us pre-calculate the exact zip size and set Content-Length,
	// which prevents Cloudflare/proxy timeout issues and lets clients show progress.
	var vFiles []validFile

	for _, f := range files {
		localPath, ok := h.resolveFilePath(f.Path)
		if !ok {
			log.Printf("Error accessing file: rejected path mapping")
			continue
		}
		info, err := os.Stat(localPath)
		if err != nil {
			log.Printf("Error accessing file %s: %v", localPath, err)
			continue
		}
		vFiles = append(vFiles, validFile{
			localPath: localPath,
			entryName: filepath.Base(f.Path),
			size:      info.Size(),
			modTime:   info.ModTime(),
		})
	}

	if len(vFiles) == 0 {
		http.Error(w, "No accessible files found", http.StatusNotFound)
		return
	}

	// Calculate exact zip size with Store method (no compression).
	zipSize := calculateZipSize(vFiles)

	// Set headers for zip download
	zipFilename := sanitizeFilename(book.Title) + ".zip"
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, zipFilename))
	w.Header().Set("Content-Length", strconv.FormatInt(zipSize, 10))
	// Prevent Cloudflare/proxies from applying gzip encoding which strips Content-Length
	w.Header().Set("Content-Encoding", "identity")
	w.Header().Set("Cache-Control", "no-transform")

	// Stream zip directly to response using Store (no compression)
	zw := zip.NewWriter(w)
	defer zw.Close()

	for _, f := range vFiles {
		file, err := os.Open(f.localPath)
		if err != nil {
			log.Printf("Error opening file %s: %v", f.localPath, err)
			// Can't skip now - Content-Length is already sent. Abort.
			return
		}

		// Use Store method - audio files are already compressed,
		// Deflate just wastes CPU and adds latency/timeout risk.
		header := &zip.FileHeader{
			Name:   f.entryName,
			Method: zip.Store,
		}
		header.UncompressedSize64 = uint64(f.size)

		writer, err := zw.CreateHeader(header)
		if err != nil {
			file.Close()
			log.Printf("Error creating zip entry %s: %v", f.entryName, err)
			return
		}

		_, err = io.Copy(writer, file)
		file.Close()
		if err != nil {
			log.Printf("Error writing zip entry %s: %v", f.entryName, err)
			return
		}
	}
}

// MapReadarrPath maps a Readarr absolute path onto the local media mount root.
//
// Contract: Readarr stores paths under readarrRoot (e.g. /media/ebooks/...,
// /media/audiobooks/...). The same tree is mounted locally at mediaRoot
// (e.g. /audiobooks). Mapping is a strict root rewrite:
//
//	/media/ebooks/X      → /audiobooks/ebooks/X
//	/media/audiobooks/X  → /audiobooks/audiobooks/X
//
// Returns ("", false) when the input is empty, relative, escapes the configured
// root via ".." / unclean segments, or would land outside mediaRoot. There is
// no arbitrary "strip Nth path segment" fallback.
func MapReadarrPath(readarrRoot, mediaRoot, readarrPath string) (string, bool) {
	readarrRoot = filepath.Clean(strings.TrimSpace(readarrRoot))
	mediaRoot = filepath.Clean(strings.TrimSpace(mediaRoot))
	readarrPath = strings.TrimSpace(readarrPath)
	if readarrRoot == "" || readarrRoot == "." || mediaRoot == "" || mediaRoot == "." || readarrPath == "" {
		return "", false
	}
	if !filepath.IsAbs(readarrRoot) || !filepath.IsAbs(mediaRoot) {
		return "", false
	}
	cleaned := filepath.Clean(readarrPath)
	if !filepath.IsAbs(cleaned) {
		return "", false
	}
	if !pathWithinRoot(cleaned, readarrRoot) {
		return "", false
	}
	rel, err := filepath.Rel(readarrRoot, cleaned)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(os.PathSeparator)) {
		return "", false
	}
	if filepath.IsAbs(rel) {
		return "", false
	}
	// Reject any remaining ".." after Rel (defensive; Clean+Rel should prevent).
	for _, seg := range strings.Split(rel, string(os.PathSeparator)) {
		if seg == ".." {
			return "", false
		}
	}
	out := filepath.Clean(filepath.Join(mediaRoot, rel))
	if !pathWithinRoot(out, mediaRoot) {
		return "", false
	}
	return out, true
}

// pathWithinRoot reports whether path is root or a descendant of root after Clean.
func pathWithinRoot(path, root string) bool {
	path = filepath.Clean(path)
	root = filepath.Clean(root)
	if path == root {
		return true
	}
	sep := string(os.PathSeparator)
	return strings.HasPrefix(path, root+sep)
}

// resolveFilePath converts a Readarr absolute path to a local filesystem path
// using READARR_MEDIA_ROOT → MEDIA_PATH root mapping. Returns ("", false) when
// the path is rejected (outside root / traversal). When the path exists,
// symlink targets must also remain under MEDIA_PATH.
func (h *Handlers) resolveFilePath(readarrPath string) (string, bool) {
	local, ok := MapReadarrPath(h.readarrMediaRoot, h.mediaPath, readarrPath)
	if !ok {
		return "", false
	}
	// If the path (or an intermediate link) resolves, require the real path stay in-root.
	if real, err := filepath.EvalSymlinks(local); err == nil {
		if !pathWithinRoot(real, h.mediaPath) {
			return "", false
		}
		return real, true
	}
	// Also reject when a parent exists as a symlink escape (EvalSymlinks on missing leaf).
	dir := filepath.Dir(local)
	if realDir, err := filepath.EvalSymlinks(dir); err == nil {
		// Reconstruct with real parent; ensure under media root.
		candidate := filepath.Join(realDir, filepath.Base(local))
		if !pathWithinRoot(candidate, h.mediaPath) && !pathWithinRoot(realDir, h.mediaPath) {
			return "", false
		}
		// If parent escaped, reject.
		if !pathWithinRoot(realDir, h.mediaPath) {
			return "", false
		}
	}
	return local, true
}

func sanitizeFilename(s string) string {
	replacer := strings.NewReplacer(
		"/", "_",
		"\\", "_",
		":", "_",
		"*", "_",
		"?", "_",
		"\"", "_",
		"<", "_",
		">", "_",
		"|", "_",
	)
	return replacer.Replace(s)
}
