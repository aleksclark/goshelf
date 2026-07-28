package handlers

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strconv"

	"github.com/aleksclark/goshelf/readarr"
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

// APIAuthor is the JSON representation of an author for the API.
type APIAuthor struct {
	ID          int    `json:"id"`
	Name        string `json:"name"`
	BookCount   int    `json:"bookCount"`
	HasCover    bool   `json:"hasCover"`
	FirstBookID int    `json:"firstBookId"`
}

// APISeries is the JSON representation of a series for the API.
type APISeries struct {
	Name        string `json:"name"`
	Slug        string `json:"slug"`
	BookCount   int    `json:"bookCount"`
	HasCover    bool   `json:"hasCover"`
	FirstBookID int    `json:"firstBookId"`
}

// APIAuthors returns a JSON list of all authors.
func (h *Handlers) APIAuthors(w http.ResponseWriter, r *http.Request) {
	authors, err := h.client.GetCachedAuthors()
	if err != nil {
		log.Printf("API: Error fetching authors: %v", err)
		http.Error(w, `{"error":"Failed to fetch authors"}`, http.StatusInternalServerError)
		return
	}

	result := make([]APIAuthor, 0, len(authors))
	for _, a := range authors {
		result = append(result, APIAuthor{
			ID:          a.ID,
			Name:        a.Name,
			BookCount:   a.BookCount,
			HasCover:    a.HasCover,
			FirstBookID: a.FirstBook,
		})
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(result)
}

// APIAuthorDetail returns detailed JSON for a single author including series and non-series books.
func (h *Handlers) APIAuthorDetail(w http.ResponseWriter, r *http.Request) {
	idStr := r.PathValue("id")
	id, err := strconv.Atoi(idStr)
	if err != nil {
		http.Error(w, `{"error":"Invalid author ID"}`, http.StatusBadRequest)
		return
	}

	books, authorName, err := h.client.GetCachedBooksByAuthor(id)
	if err != nil {
		log.Printf("API: Error fetching author %d: %v", id, err)
		http.Error(w, `{"error":"Author not found"}`, http.StatusNotFound)
		return
	}

	// Group books by series
	type seriesAgg struct {
		Name        string
		Slug        string
		BookCount   int
		HasCover    bool
		FirstBookID int
	}
	seriesMap := make(map[string]*seriesAgg)
	var nonSeriesBooks []APIBook

	for _, b := range books {
		seriesName := readarr.ParseSeriesName(b.SeriesTitle)
		if seriesName == "" {
			// Non-series book
			authorN := authorName
			if authorN == "" && b.Author != nil {
				authorN = b.Author.AuthorName
			}
			if authorN == "" {
				authorN = b.AuthorTitle
			}

			seriesInfo := b.SeriesTitle
			if seriesInfo == "" && len(b.SeriesLinks) > 0 {
				sl := b.SeriesLinks[0]
				seriesInfo = sl.Title
				if sl.Position != "" {
					seriesInfo += " #" + sl.Position
				}
			}

			nonSeriesBooks = append(nonSeriesBooks, APIBook{
				ID:        b.ID,
				Title:     b.Title,
				Author:    authorN,
				AuthorID:  b.AuthorID,
				Series:    seriesInfo,
				FileCount: b.Statistics.BookFileCount,
				TotalSize: b.Statistics.SizeOnDisk,
				HasCover:  len(b.Images) > 0,
			})
		} else {
			slug := readarr.SeriesSlug(seriesName)
			agg, exists := seriesMap[slug]
			if !exists {
				agg = &seriesAgg{
					Name: seriesName,
					Slug: slug,
				}
				seriesMap[slug] = agg
			}
			agg.BookCount++
			if len(b.Images) > 0 && !agg.HasCover {
				agg.HasCover = true
				agg.FirstBookID = b.ID
			}
		}
	}

	// Build series list
	seriesList := make([]APISeries, 0, len(seriesMap))
	for _, agg := range seriesMap {
		seriesList = append(seriesList, APISeries{
			Name:        agg.Name,
			Slug:        agg.Slug,
			BookCount:   agg.BookCount,
			HasCover:    agg.HasCover,
			FirstBookID: agg.FirstBookID,
		})
	}

	if nonSeriesBooks == nil {
		nonSeriesBooks = []APIBook{}
	}

	author := APIAuthor{
		ID:          id,
		Name:        authorName,
		BookCount:   len(books),
		HasCover:    len(books) > 0 && len(books[0].Images) > 0,
		FirstBookID: 0,
	}
	if len(books) > 0 {
		for _, b := range books {
			if len(b.Images) > 0 {
				author.FirstBookID = b.ID
				break
			}
		}
	}

	resp := struct {
		Author APIAuthor  `json:"author"`
		Series []APISeries `json:"series"`
		Books  []APIBook   `json:"books"`
	}{
		Author: author,
		Series: seriesList,
		Books:  nonSeriesBooks,
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}

// APISeriesList returns a JSON list of all series.
func (h *Handlers) APISeriesList(w http.ResponseWriter, r *http.Request) {
	series, err := h.client.GetCachedSeries()
	if err != nil {
		log.Printf("API: Error fetching series: %v", err)
		http.Error(w, `{"error":"Failed to fetch series"}`, http.StatusInternalServerError)
		return
	}

	result := make([]APISeries, 0, len(series))
	for _, s := range series {
		result = append(result, APISeries{
			Name:        s.Name,
			Slug:        s.Slug,
			BookCount:   s.BookCount,
			HasCover:    s.HasCover,
			FirstBookID: s.FirstBook,
		})
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(result)
}

// APISeriesDetail returns detailed JSON for a single series with its books.
func (h *Handlers) APISeriesDetail(w http.ResponseWriter, r *http.Request) {
	slug := r.PathValue("slug")

	books, seriesName, err := h.client.GetCachedBooksBySeries(slug)
	if err != nil {
		log.Printf("API: Error fetching series %s: %v", slug, err)
		http.Error(w, `{"error":"Series not found"}`, http.StatusNotFound)
		return
	}

	apiBooks := make([]APIBook, 0, len(books))
	for _, b := range books {
		authorName := b.AuthorTitle
		if authorName == "" && b.Author != nil {
			authorName = b.Author.AuthorName
		}

		// Show position within THIS series, not the full multi-series string
		seriesInfo := seriesName
		entries := readarr.ParseAllSeriesNames(b.SeriesTitle)
		targetSlug := readarr.SeriesSlug(seriesName)
		for _, e := range entries {
			if readarr.SeriesSlug(e.Name) == targetSlug {
				if e.Position < 9999 {
					seriesInfo = fmt.Sprintf("%s #%g", e.Name, e.Position)
				}
				break
			}
		}

		apiBooks = append(apiBooks, APIBook{
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

	resp := struct {
		Name  string    `json:"name"`
		Books []APIBook `json:"books"`
	}{
		Name:  seriesName,
		Books: apiBooks,
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}
