package handlers

import (
	"encoding/json"
	"log"
	"net/http"
	"strconv"
)

// APIBook is the JSON representation of a book for the API.
type APIBook struct {
	ID        int    `json:"id"`
	Title     string `json:"title"`
	Author    string `json:"author"`
	AuthorID  int    `json:"authorId"`
	Series    string `json:"series,omitempty"`
	Overview  string `json:"overview,omitempty"`
	FileCount int    `json:"fileCount"`
	TotalSize int64  `json:"totalSize"`
	HasCover  bool   `json:"hasCover"`
}

// APIBookFile is the JSON representation of a book file.
type APIBookFile struct {
	Name string `json:"name"`
	Size int64  `json:"size"`
}

// APIBookDetail is the detailed JSON representation of a book.
type APIBookDetail struct {
	ID        int           `json:"id"`
	Title     string        `json:"title"`
	Author    string        `json:"author"`
	AuthorID  int           `json:"authorId"`
	Series    string        `json:"series,omitempty"`
	Overview  string        `json:"overview,omitempty"`
	HasCover  bool          `json:"hasCover"`
	Files     []APIBookFile `json:"files"`
	TotalSize int64         `json:"totalSize"`
}

// APIBooks returns a JSON list of all books.
func (h *Handlers) APIBooks(w http.ResponseWriter, r *http.Request) {
	books, authorMap, err := h.client.GetCachedBooks()
	if err != nil {
		log.Printf("API: Error fetching books: %v", err)
		http.Error(w, `{"error":"Failed to fetch library"}`, http.StatusInternalServerError)
		return
	}

	result := make([]APIBook, 0, len(books))
	for _, b := range books {
		authorName := authorMap[b.AuthorID]
		if authorName == "" && b.Author != nil {
			authorName = b.Author.AuthorName
		}
		if authorName == "" {
			authorName = b.AuthorTitle
		}

		seriesInfo := b.SeriesTitle
		if seriesInfo == "" && len(b.SeriesLinks) > 0 {
			sl := b.SeriesLinks[0]
			seriesInfo = sl.Title
			if sl.Position != "" {
				seriesInfo += " #" + sl.Position
			}
		}

		result = append(result, APIBook{
			ID:        b.ID,
			Title:     b.Title,
			Author:    authorName,
			AuthorID:  b.AuthorID,
			Series:    seriesInfo,
			FileCount: b.Statistics.BookFileCount,
			TotalSize: b.Statistics.SizeOnDisk,
			HasCover:  len(b.Images) > 0,
		})
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(result)
}

// APIBookDetail returns detailed JSON for a single book.
func (h *Handlers) APIBookDetailJSON(w http.ResponseWriter, r *http.Request) {
	idStr := r.PathValue("id")
	id, err := strconv.Atoi(idStr)
	if err != nil {
		http.Error(w, `{"error":"Invalid book ID"}`, http.StatusBadRequest)
		return
	}

	book, err := h.client.GetBook(id)
	if err != nil {
		log.Printf("API: Error fetching book %d: %v", id, err)
		http.Error(w, `{"error":"Book not found"}`, http.StatusNotFound)
		return
	}

	files, err := h.client.GetBookFiles(id)
	if err != nil {
		log.Printf("API: Error fetching book files %d: %v", id, err)
	}

	authorName := ""
	if book.Author != nil {
		authorName = book.Author.AuthorName
	} else if book.AuthorTitle != "" {
		authorName = book.AuthorTitle
	}

	seriesInfo := book.SeriesTitle
	if seriesInfo == "" && len(book.SeriesLinks) > 0 {
		sl := book.SeriesLinks[0]
		seriesInfo = sl.Title
		if sl.Position != "" {
			seriesInfo += " #" + sl.Position
		}
	}

	var totalSize int64
	apiFiles := make([]APIBookFile, 0, len(files))
	for _, f := range files {
		totalSize += f.Size
		// Extract just the filename from the full path
		name := f.Path
		for i := len(name) - 1; i >= 0; i-- {
			if name[i] == '/' || name[i] == '\\' {
				name = name[i+1:]
				break
			}
		}
		apiFiles = append(apiFiles, APIBookFile{
			Name: name,
			Size: f.Size,
		})
	}

	detail := APIBookDetail{
		ID:        book.ID,
		Title:     book.Title,
		Author:    authorName,
		AuthorID:  book.AuthorID,
		Series:    seriesInfo,
		Overview:  book.Overview,
		HasCover:  len(book.Images) > 0,
		Files:     apiFiles,
		TotalSize: totalSize,
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(detail)
}
