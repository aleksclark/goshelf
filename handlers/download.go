package handlers

import (
	"archive/zip"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

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
	type validFile struct {
		localPath string
		entryName string
		size      int64
	}
	var validFiles []validFile

	for _, f := range files {
		localPath := h.resolveFilePath(f.Path)
		info, err := os.Stat(localPath)
		if err != nil {
			log.Printf("Error accessing file %s: %v", localPath, err)
			continue
		}
		validFiles = append(validFiles, validFile{
			localPath: localPath,
			entryName: filepath.Base(f.Path),
			size:      info.Size(),
		})
	}

	if len(validFiles) == 0 {
		http.Error(w, "No accessible files found", http.StatusNotFound)
		return
	}

	// Calculate exact zip size with Store method (no compression).
	// ZIP format per file: local file header (30 + name len) + data
	//   + data descriptor (16) + central directory entry (46 + name len)
	// Plus end of central directory record (22).
	zipSize := int64(0)
	for _, f := range validFiles {
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

	// Set headers for zip download
	zipFilename := sanitizeFilename(book.Title) + ".zip"
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, zipFilename))
	w.Header().Set("Content-Length", strconv.FormatInt(zipSize, 10))

	// Stream zip directly to response using Store (no compression)
	zw := zip.NewWriter(w)
	defer zw.Close()

	for _, f := range validFiles {
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
