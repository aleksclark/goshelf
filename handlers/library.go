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

func (h *Handlers) Library(w http.ResponseWriter, r *http.Request) {
	books, err := h.client.GetBooks()
	if err != nil {
		log.Printf("Error fetching books: %v", err)
		http.Error(w, "Failed to fetch library", http.StatusInternalServerError)
		return
	}

	// Build author map
	authors, err := h.client.GetAuthors()
	if err != nil {
		log.Printf("Error fetching authors: %v", err)
	}
	authorMap := make(map[int]string)
	for _, a := range authors {
		authorMap[a.ID] = a.AuthorName
	}

	query := r.URL.Query().Get("q")
	sortBy := r.URL.Query().Get("sort")
	page := getPage(r)

	allBooks := filterAndSort(books, authorMap, query, sortBy)
	paged, totalPages := paginate(allBooks, page)

	username := r.Header.Get("X-Username")

	// If HTMX request, return only the book grid + pagination
	if r.Header.Get("HX-Request") == "true" {
		templates.BookGridWithPagination(paged, page, totalPages, query, sortBy).Render(r.Context(), w)
		return
	}

	templates.LibraryPage(paged, username, query, sortBy, page, totalPages).Render(r.Context(), w)
}

func (h *Handlers) Search(w http.ResponseWriter, r *http.Request) {
	books, err := h.client.GetBooks()
	if err != nil {
		log.Printf("Error fetching books: %v", err)
		http.Error(w, "Failed to fetch library", http.StatusInternalServerError)
		return
	}

	authors, err := h.client.GetAuthors()
	if err != nil {
		log.Printf("Error fetching authors: %v", err)
	}
	authorMap := make(map[int]string)
	for _, a := range authors {
		authorMap[a.ID] = a.AuthorName
	}

	query := r.URL.Query().Get("q")
	sortBy := r.URL.Query().Get("sort")
	page := getPage(r)

	allBooks := filterAndSort(books, authorMap, query, sortBy)
	paged, totalPages := paginate(allBooks, page)

	templates.BookGridWithPagination(paged, page, totalPages, query, sortBy).Render(r.Context(), w)
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

	username := r.Header.Get("X-Username")

	templates.BookDetailPage(book.ID, book.Title, authorName, seriesInfo, book.Overview, files, totalSize, username).Render(r.Context(), w)
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

		result = append(result, templates.BookDisplayData{
			ID:          b.ID,
			Title:       b.Title,
			Author:      authorName,
			SeriesTitle: seriesInfo,
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
