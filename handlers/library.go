package handlers

import (
	"log"
	"net/http"
	"sort"
	"strconv"
	"strings"

	"github.com/aleksclark/goshelf/readarr"
	"github.com/aleksclark/goshelf/templates"
)

const booksPerPage = 36
const authorsPerPage = 60
const seriesPerPage = 60

// Library is the default route — shows authors list.
func (h *Handlers) Library(w http.ResponseWriter, r *http.Request) {
	authors, err := h.client.GetCachedAuthors()
	if err != nil {
		log.Printf("Error fetching authors: %v", err)
		http.Error(w, "Failed to fetch library", http.StatusInternalServerError)
		return
	}

	query := r.URL.Query().Get("q")
	page := getPage(r)

	filtered := filterAuthors(authors, query)
	paged, totalPages := paginateAuthors(filtered, page)

	username := r.Header.Get("X-Username")

	if r.Header.Get("HX-Request") == "true" {
		templates.AuthorGridWithPagination(paged, page, totalPages, query).Render(r.Context(), w)
		return
	}

	templates.AuthorsPage(paged, username, query, page, totalPages).Render(r.Context(), w)
}

// AuthorBooks shows all books by a specific author.
func (h *Handlers) AuthorBooks(w http.ResponseWriter, r *http.Request) {
	idStr := r.PathValue("id")
	id, err := strconv.Atoi(idStr)
	if err != nil {
		http.Error(w, "Invalid author ID", http.StatusBadRequest)
		return
	}

	books, authorName, err := h.client.GetCachedBooksByAuthor(id)
	if err != nil {
		log.Printf("Error fetching books for author %d: %v", id, err)
		http.Error(w, "Failed to fetch books", http.StatusInternalServerError)
		return
	}

	displayBooks := makeDisplayBooks(books, authorName, id)
	username := r.Header.Get("X-Username")
	templates.AuthorBooksPage(displayBooks, authorName, username).Render(r.Context(), w)
}

// SeriesList shows all series.
func (h *Handlers) SeriesList(w http.ResponseWriter, r *http.Request) {
	series, err := h.client.GetCachedSeries()
	if err != nil {
		log.Printf("Error fetching series: %v", err)
		http.Error(w, "Failed to fetch series", http.StatusInternalServerError)
		return
	}

	query := r.URL.Query().Get("q")
	page := getPage(r)

	filtered := filterSeries(series, query)
	paged, totalPages := paginateSeries(filtered, page)

	username := r.Header.Get("X-Username")

	if r.Header.Get("HX-Request") == "true" {
		templates.SeriesGridWithPagination(paged, page, totalPages, query).Render(r.Context(), w)
		return
	}

	templates.SeriesListPage(paged, username, query, page, totalPages).Render(r.Context(), w)
}

// SeriesBooks shows all books in a specific series.
func (h *Handlers) SeriesBooks(w http.ResponseWriter, r *http.Request) {
	slug := r.PathValue("slug")

	books, seriesName, err := h.client.GetCachedBooksBySeries(slug)
	if err != nil {
		log.Printf("Error fetching series %s: %v", slug, err)
		http.Error(w, "Series not found", http.StatusNotFound)
		return
	}

	_, authorMap, _ := h.client.GetCachedBooks()

	displayBooks := make([]templates.BookDisplayData, 0, len(books))
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

		displayBooks = append(displayBooks, templates.BookDisplayData{
			ID:          b.ID,
			Title:       b.Title,
			Author:      authorName,
			AuthorID:    b.AuthorID,
			SeriesTitle: seriesInfo,
			SeriesSlug:  slug,
			HasCover:    len(b.Images) > 0,
			Added:       b.Added,
		})
	}

	username := r.Header.Get("X-Username")
	templates.SeriesBooksPage(displayBooks, seriesName, username).Render(r.Context(), w)
}

// AllBooks shows all books in a flat grid with search and sort.
func (h *Handlers) AllBooks(w http.ResponseWriter, r *http.Request) {
	books, authorMap, err := h.client.GetCachedBooks()
	if err != nil {
		log.Printf("Error fetching books: %v", err)
		http.Error(w, "Failed to fetch library", http.StatusInternalServerError)
		return
	}

	query := r.URL.Query().Get("q")
	sortBy := r.URL.Query().Get("sort")
	page := getPage(r)

	allBooks := filterAndSort(books, authorMap, query, sortBy)
	paged, totalPages := paginate(allBooks, page)

	username := r.Header.Get("X-Username")

	if r.Header.Get("HX-Request") == "true" {
		templates.BookGridWithPagination(paged, page, totalPages, query, sortBy).Render(r.Context(), w)
		return
	}

	templates.BooksPage(paged, username, query, sortBy, page, totalPages).Render(r.Context(), w)
}

// Search handles the search box — searches across all books (HTMX endpoint).
func (h *Handlers) Search(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query().Get("q")
	if query == "" {
		// Empty search: show authors grid
		authors, err := h.client.GetCachedAuthors()
		if err != nil {
			http.Error(w, "Failed to fetch library", http.StatusInternalServerError)
			return
		}
		templates.AuthorGridWithPagination(authors[:min(len(authors), authorsPerPage)], 1, (len(authors)+authorsPerPage-1)/authorsPerPage, "").Render(r.Context(), w)
		return
	}

	books, authorMap, err := h.client.GetCachedBooks()
	if err != nil {
		log.Printf("Error fetching books: %v", err)
		http.Error(w, "Failed to fetch library", http.StatusInternalServerError)
		return
	}

	sortBy := r.URL.Query().Get("sort")
	page := getPage(r)

	allBooks := filterAndSort(books, authorMap, query, sortBy)
	paged, totalPages := paginate(allBooks, page)

	templates.BookGridWithPagination(paged, page, totalPages, query, sortBy).Render(r.Context(), w)
}

// SearchAuthors handles HTMX pagination for the authors grid.
func (h *Handlers) SearchAuthors(w http.ResponseWriter, r *http.Request) {
	authors, err := h.client.GetCachedAuthors()
	if err != nil {
		http.Error(w, "Failed to fetch authors", http.StatusInternalServerError)
		return
	}

	query := r.URL.Query().Get("q")
	page := getPage(r)

	filtered := filterAuthors(authors, query)
	paged, totalPages := paginateAuthors(filtered, page)

	templates.AuthorGridWithPagination(paged, page, totalPages, query).Render(r.Context(), w)
}

func (h *Handlers) BookDetail(w http.ResponseWriter, r *http.Request) {
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

	files, err := h.client.GetBookFiles(id)
	if err != nil {
		log.Printf("Error fetching book files %d: %v", id, err)
		files = []readarr.BookFile{}
	}

	// Get author name
	authorName := ""
	if book.Author != nil {
		authorName = book.Author.AuthorName
	} else if book.AuthorTitle != "" {
		authorName = book.AuthorTitle
	}

	// Calculate total size
	var totalSize int64
	for _, f := range files {
		totalSize += f.Size
	}

	// Get series info
	seriesInfo := book.SeriesTitle
	if seriesInfo == "" && len(book.SeriesLinks) > 0 {
		sl := book.SeriesLinks[0]
		seriesInfo = sl.Title
		if sl.Position != "" {
			seriesInfo += " #" + sl.Position
		}
	}

	hasCover := len(book.Images) > 0
	username := r.Header.Get("X-Username")

	templates.BookDetailPage(book.ID, book.Title, authorName, seriesInfo, book.Overview, files, totalSize, hasCover, username).Render(r.Context(), w)
}

func getPage(r *http.Request) int {
	pageStr := r.URL.Query().Get("page")
	page, err := strconv.Atoi(pageStr)
	if err != nil || page < 1 {
		return 1
	}
	return page
}

func paginate(books []templates.BookDisplayData, page int) ([]templates.BookDisplayData, int) {
	total := len(books)
	totalPages := (total + booksPerPage - 1) / booksPerPage
	if totalPages < 1 {
		totalPages = 1
	}
	if page > totalPages {
		page = totalPages
	}

	start := (page - 1) * booksPerPage
	end := start + booksPerPage
	if end > total {
		end = total
	}

	return books[start:end], totalPages
}

func paginateAuthors(authors []readarr.AuthorDisplay, page int) ([]readarr.AuthorDisplay, int) {
	total := len(authors)
	totalPages := (total + authorsPerPage - 1) / authorsPerPage
	if totalPages < 1 {
		totalPages = 1
	}
	if page > totalPages {
		page = totalPages
	}

	start := (page - 1) * authorsPerPage
	end := start + authorsPerPage
	if end > total {
		end = total
	}

	return authors[start:end], totalPages
}

func paginateSeries(series []readarr.SeriesInfo, page int) ([]readarr.SeriesInfo, int) {
	total := len(series)
	totalPages := (total + seriesPerPage - 1) / seriesPerPage
	if totalPages < 1 {
		totalPages = 1
	}
	if page > totalPages {
		page = totalPages
	}

	start := (page - 1) * seriesPerPage
	end := start + seriesPerPage
	if end > total {
		end = total
	}

	return series[start:end], totalPages
}

func filterAuthors(authors []readarr.AuthorDisplay, query string) []readarr.AuthorDisplay {
	if query == "" {
		return authors
	}
	queryLower := strings.ToLower(query)
	var result []readarr.AuthorDisplay
	for _, a := range authors {
		if strings.Contains(strings.ToLower(a.Name), queryLower) {
			result = append(result, a)
		}
	}
	return result
}

func filterSeries(series []readarr.SeriesInfo, query string) []readarr.SeriesInfo {
	if query == "" {
		return series
	}
	queryLower := strings.ToLower(query)
	var result []readarr.SeriesInfo
	for _, s := range series {
		if strings.Contains(strings.ToLower(s.Name), queryLower) {
			result = append(result, s)
		}
	}
	return result
}

func filterAndSort(books []readarr.Book, authorMap map[int]string, query, sortBy string) []templates.BookDisplayData {
	var result []templates.BookDisplayData

	queryLower := strings.ToLower(query)

	for _, b := range books {
		authorName := authorMap[b.AuthorID]
		if authorName == "" && b.Author != nil {
			authorName = b.Author.AuthorName
		}
		if authorName == "" {
			authorName = b.AuthorTitle
		}

		// Filter
		if query != "" {
			titleMatch := strings.Contains(strings.ToLower(b.Title), queryLower)
			authorMatch := strings.Contains(strings.ToLower(authorName), queryLower)
			if !titleMatch && !authorMatch {
				continue
			}
		}

		seriesInfo := b.SeriesTitle
		if seriesInfo == "" && len(b.SeriesLinks) > 0 {
			sl := b.SeriesLinks[0]
			seriesInfo = sl.Title
			if sl.Position != "" {
				seriesInfo += " #" + sl.Position
			}
		}

		seriesSlug := ""
		seriesName := readarr.ParseSeriesName(b.SeriesTitle)
		if seriesName != "" {
			seriesSlug = readarr.SeriesSlug(seriesName)
		}

		result = append(result, templates.BookDisplayData{
			ID:          b.ID,
			Title:       b.Title,
			Author:      authorName,
			AuthorID:    b.AuthorID,
			SeriesTitle: seriesInfo,
			SeriesSlug:  seriesSlug,
			HasCover:    len(b.Images) > 0,
			Added:       b.Added,
		})
	}

	// Sort
	switch sortBy {
	case "author":
		sort.Slice(result, func(i, j int) bool {
			return strings.ToLower(result[i].Author) < strings.ToLower(result[j].Author)
		})
	case "added":
		sort.Slice(result, func(i, j int) bool {
			return result[i].Added.After(result[j].Added)
		})
	default: // "title" or empty
		sort.Slice(result, func(i, j int) bool {
			return strings.ToLower(result[i].Title) < strings.ToLower(result[j].Title)
		})
	}

	return result
}

// makeDisplayBooks converts readarr.Book slice to BookDisplayData with author/series links.
func makeDisplayBooks(books []readarr.Book, authorName string, authorID int) []templates.BookDisplayData {
	displayBooks := make([]templates.BookDisplayData, 0, len(books))
	for _, b := range books {
		seriesInfo := b.SeriesTitle
		if seriesInfo == "" && len(b.SeriesLinks) > 0 {
			sl := b.SeriesLinks[0]
			seriesInfo = sl.Title
			if sl.Position != "" {
				seriesInfo += " #" + sl.Position
			}
		}

		seriesSlug := ""
		seriesName := readarr.ParseSeriesName(b.SeriesTitle)
		if seriesName != "" {
			seriesSlug = readarr.SeriesSlug(seriesName)
		}

		displayBooks = append(displayBooks, templates.BookDisplayData{
			ID:          b.ID,
			Title:       b.Title,
			Author:      authorName,
			AuthorID:    authorID,
			SeriesTitle: seriesInfo,
			SeriesSlug:  seriesSlug,
			HasCover:    len(b.Images) > 0,
			Added:       b.Added,
		})
	}
	return displayBooks
}
