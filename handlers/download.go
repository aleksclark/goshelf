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

	// Set headers for zip download
	zipFilename := sanitizeFilename(book.Title) + ".zip"
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, zipFilename))

	// Stream zip directly to response
	zw := zip.NewWriter(w)
	defer zw.Close()

	for _, f := range files {
		localPath := h.resolveFilePath(f.Path)

		file, err := os.Open(localPath)
		if err != nil {
			log.Printf("Error opening file %s: %v", localPath, err)
			continue
		}

		// Use just the filename in the zip (strip directory path)
		entryName := filepath.Base(f.Path)

		writer, err := zw.Create(entryName)
		if err != nil {
			file.Close()
			log.Printf("Error creating zip entry %s: %v", entryName, err)
			continue
		}

		_, err = io.Copy(writer, file)
		file.Close()
		if err != nil {
			log.Printf("Error writing zip entry %s: %v", entryName, err)
			return // Can't continue if write failed
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
