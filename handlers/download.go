package handlers

import (
	"archive/zip"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	zipTempPrefix   = "goshelf_zip_"
	zipCacheMaxAge  = 2 * time.Hour
	zipCleanupEvery = 30 * time.Minute
)

// validFile holds info about a validated book file on disk.
type validFile struct {
	localPath string
	entryName string
	size      int64
	modTime   time.Time
}

// StartZipCleanup launches a background goroutine that deletes stale temp ZIP files every 30 minutes.
func StartZipCleanup() {
	go func() {
		for {
			cleanupTempZips()
			time.Sleep(zipCleanupEvery)
		}
	}()
}

func cleanupTempZips() {
	tmpDir := os.TempDir()
	entries, err := os.ReadDir(tmpDir)
	if err != nil {
		log.Printf("zip cleanup: error reading temp dir: %v", err)
		return
	}
	now := time.Now()
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		if !strings.HasPrefix(entry.Name(), zipTempPrefix) || !strings.HasSuffix(entry.Name(), ".zip") {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			continue
		}
		if now.Sub(info.ModTime()) > zipCacheMaxAge {
			path := filepath.Join(tmpDir, entry.Name())
			if err := os.Remove(path); err != nil {
				log.Printf("zip cleanup: failed to remove %s: %v", path, err)
			} else {
				log.Printf("zip cleanup: removed stale %s", entry.Name())
			}
		}
	}
}

// computeCacheKey computes a hash from bookID + all file sizes and modification times.
func computeCacheKey(bookID int, files []validFile) string {
	h := sha256.New()
	fmt.Fprintf(h, "book:%d\n", bookID)
	// Sort files by name for deterministic hash
	sorted := make([]validFile, len(files))
	copy(sorted, files)
	sort.Slice(sorted, func(i, j int) bool {
		return sorted[i].entryName < sorted[j].entryName
	})
	for _, f := range sorted {
		fmt.Fprintf(h, "%s:%d:%d\n", f.entryName, f.size, f.modTime.UnixNano())
	}
	return hex.EncodeToString(h.Sum(nil))[:16]
}

// zipTempPath returns the temp file path for a cached ZIP.
func zipTempPath(bookID int, hash string) string {
	return filepath.Join(os.TempDir(), fmt.Sprintf("%s%d_%s.zip", zipTempPrefix, bookID, hash))
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

// getBookValidFiles retrieves and validates files for a book.
// It verifies each file is actually readable (not just stat-able) to catch
// MooseFS chunk loss / I/O errors before attempting to build a ZIP.
// Returns valid files, number of skipped files, and any error.
func (h *Handlers) getBookValidFiles(id int) ([]validFile, int, error) {
	files, err := h.client.GetBookFiles(id)
	if err != nil || len(files) == 0 {
		return nil, 0, fmt.Errorf("no files found for book %d: %v", id, err)
	}

	var result []validFile
	var skipped int
	for _, f := range files {
		localPath := h.resolveFilePath(f.Path)
		info, err := os.Stat(localPath)
		if err != nil {
			log.Printf("Skipping inaccessible file %s: %v", localPath, err)
			skipped++
			continue
		}
		// Verify file is actually readable (catches MooseFS unrecoverable chunks)
		fp, err := os.Open(localPath)
		if err != nil {
			log.Printf("Skipping unopenable file %s: %v", localPath, err)
			skipped++
			continue
		}
		buf := make([]byte, 1)
		_, err = fp.Read(buf)
		fp.Close()
		if err != nil && err != io.EOF {
			log.Printf("Skipping unreadable file %s: %v", localPath, err)
			skipped++
			continue
		}
		result = append(result, validFile{
			localPath: localPath,
			entryName: filepath.Base(f.Path),
			size:      info.Size(),
			modTime:   info.ModTime(),
		})
	}
	if skipped > 0 {
		log.Printf("Book %d: skipped %d unreadable files, %d files available", id, skipped, len(result))
	}
	return result, skipped, nil
}

// buildZipFile creates a ZIP temp file with all book files using Store method.
func buildZipFile(path string, files []validFile) error {
	tmpFile, err := os.Create(path)
	if err != nil {
		return fmt.Errorf("creating temp file: %w", err)
	}
	defer tmpFile.Close()

	zw := zip.NewWriter(tmpFile)
	defer zw.Close()

	for _, f := range files {
		file, err := os.Open(f.localPath)
		if err != nil {
			return fmt.Errorf("opening %s: %w", f.localPath, err)
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

		_, err = io.Copy(writer, file)
		file.Close()
		if err != nil {
			return fmt.Errorf("writing zip entry %s: %w", f.entryName, err)
		}
	}
	return nil
}

// DownloadZipResumable serves a book as a ZIP with HTTP Range request support.
// It builds the ZIP to a temp file (cached by content hash) and uses http.ServeContent.
func (h *Handlers) DownloadZipResumable(w http.ResponseWriter, r *http.Request) {
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

	// Get and validate book files
	validFiles, _, err := h.getBookValidFiles(id)
	if err != nil || len(validFiles) == 0 {
		log.Printf("Error fetching files for book %d: %v", id, err)
		http.Error(w, "No accessible files found", http.StatusNotFound)
		return
	}

	// Compute cache key based on file sizes + mod times
	hash := computeCacheKey(id, validFiles)
	tmpPath := zipTempPath(id, hash)

	// Check if cached ZIP already exists
	if _, err := os.Stat(tmpPath); err != nil {
		// Need to build the ZIP - first clean up any old ZIPs for this book
		cleanupBookZips(id, hash)

		log.Printf("Building ZIP for book %d (hash=%s)", id, hash)
		if err := buildZipFile(tmpPath, validFiles); err != nil {
			log.Printf("Error building ZIP for book %d: %v", id, err)
			http.Error(w, "Failed to build ZIP", http.StatusInternalServerError)
			// Clean up partial file
			os.Remove(tmpPath)
			return
		}
		log.Printf("ZIP built for book %d: %s", id, tmpPath)
	}

	// Open the cached ZIP file
	f, err := os.Open(tmpPath)
	if err != nil {
		log.Printf("Error opening cached ZIP %s: %v", tmpPath, err)
		http.Error(w, "Failed to read ZIP", http.StatusInternalServerError)
		return
	}
	defer f.Close()

	// Touch the file to keep it in cache
	now := time.Now()
	os.Chtimes(tmpPath, now, now)

	// Set Content-Disposition for the filename
	zipFilename := sanitizeFilename(book.Title) + ".zip"
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, zipFilename))
	w.Header().Set("Content-Type", "application/zip")
	// Prevent proxies from transforming the response
	w.Header().Set("Cache-Control", "no-transform")

	// http.ServeContent handles Range, If-Modified-Since, ETag automatically
	stat, _ := f.Stat()
	http.ServeContent(w, r, zipFilename, stat.ModTime(), f)
}

// cleanupBookZips removes old cached ZIPs for a specific book (different hash).
func cleanupBookZips(bookID int, currentHash string) {
	prefix := fmt.Sprintf("%s%d_", zipTempPrefix, bookID)
	tmpDir := os.TempDir()
	entries, err := os.ReadDir(tmpDir)
	if err != nil {
		return
	}
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		name := entry.Name()
		if strings.HasPrefix(name, prefix) && strings.HasSuffix(name, ".zip") {
			// Don't delete the current hash
			if strings.Contains(name, currentHash) {
				continue
			}
			path := filepath.Join(tmpDir, name)
			os.Remove(path)
			log.Printf("Removed old zip cache: %s", name)
		}
	}
}

// APIDownloadInfo returns JSON with ZIP download metadata (size, etag, filename).
func (h *Handlers) APIDownloadInfo(w http.ResponseWriter, r *http.Request) {
	idStr := r.PathValue("id")
	id, err := strconv.Atoi(idStr)
	if err != nil {
		http.Error(w, `{"error":"Invalid book ID"}`, http.StatusBadRequest)
		return
	}

	// Get book metadata
	book, err := h.client.GetBook(id)
	if err != nil {
		log.Printf("API: Error fetching book %d: %v", id, err)
		http.Error(w, `{"error":"Book not found"}`, http.StatusNotFound)
		return
	}

	// Get and validate files
	validFiles, skipped, err := h.getBookValidFiles(id)
	if err != nil || len(validFiles) == 0 {
		log.Printf("API: Error fetching files for book %d: %v", id, err)
		http.Error(w, `{"error":"No accessible files found"}`, http.StatusNotFound)
		return
	}

	// Calculate exact zip size
	zipSize := calculateZipSize(validFiles)
	hash := computeCacheKey(id, validFiles)
	filename := sanitizeFilename(book.Title) + ".zip"

	resp := struct {
		TotalSize    int64  `json:"totalSize"`
		ETag         string `json:"etag"`
		Filename     string `json:"filename"`
		FileCount    int    `json:"fileCount"`
		SkippedFiles int    `json:"skippedFiles,omitempty"`
	}{
		TotalSize:    zipSize,
		ETag:         `"` + hash + `"`,
		Filename:     filename,
		FileCount:    len(validFiles),
		SkippedFiles: skipped,
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
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
		localPath := h.resolveFilePath(f.Path)
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

// resolveFilePath converts Readarr's absolute path to local filesystem path
// Readarr paths look like: /media/audiobooks/Author/Book/file.mp3
// We strip the Readarr prefix and prepend MEDIA_PATH
func (h *Handlers) resolveFilePath(readarrPath string) string {
	// Common Readarr root prefixes to strip
	prefixes := []string{
		"/media/audiobooks/",
		"/media/audiobooks",
	}

	relativePath := readarrPath
	for _, prefix := range prefixes {
		if strings.HasPrefix(readarrPath, prefix) {
			relativePath = strings.TrimPrefix(readarrPath, prefix)
			break
		}
	}

	// If path still starts with /, try to strip up to the 3rd segment
	// (handles arbitrary Readarr root folder configurations)
	if strings.HasPrefix(relativePath, "/") {
		parts := strings.SplitN(relativePath, "/", 4)
		if len(parts) >= 4 {
			// /media/audiobooks/Author/Book/file -> Author/Book/file
			relativePath = strings.Join(parts[3:], "/")
		}
	}

	return filepath.Join(h.mediaPath, relativePath)
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
