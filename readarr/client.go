package readarr

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
)

type Client struct {
	baseURL    string
	apiKey     string
	httpClient *http.Client

	mu          sync.RWMutex
	authors     []Author
	books       []Book
	authorMap   map[int]string // authorID -> name
	booksByAuth map[int][]Book // authorID -> books
	bookByID    map[int]*Book  // bookID -> book
	loaded      bool
}

type Author struct {
	ID         int     `json:"id"`
	AuthorName string  `json:"authorName"`
	Path       string  `json:"path"`
	Images     []Image `json:"images"`
}

type Image struct {
	URL       string `json:"url"`
	CoverType string `json:"coverType"`
	RemoteURL string `json:"remoteUrl"`
}

type SeriesLink struct {
	ID       int    `json:"id"`
	Position string `json:"position"`
	SeriesID int    `json:"seriesId"`
	Title    string `json:"title"`
}

type BookStatistics struct {
	BookFileCount  int   `json:"bookFileCount"`
	SizeOnDisk     int64 `json:"sizeOnDisk"`
	PercentOfBooks int   `json:"percentOfBooks"`
}

type Book struct {
	ID          int            `json:"id"`
	Title       string         `json:"title"`
	SeriesTitle string         `json:"seriesTitle"`
	AuthorID    int            `json:"authorId"`
	AuthorTitle string         `json:"authorTitle"`
	Overview    string         `json:"overview"`
	Images      []Image        `json:"images"`
	Author      *Author        `json:"author"`
	SeriesLinks []SeriesLink   `json:"seriesLinks"`
	PageCount   int            `json:"pageCount"`
	Added       time.Time      `json:"added"`
	Statistics  BookStatistics `json:"statistics"`
}

type BookFile struct {
	ID       int    `json:"id"`
	Path     string `json:"path"`
	Size     int64  `json:"size"`
	BookID   int    `json:"bookId"`
	AuthorID int    `json:"authorId"`
}

// AuthorDisplay is a pre-computed view of an author for the UI.
type AuthorDisplay struct {
	ID        int
	Name      string
	BookCount int
	HasCover  bool // true if at least one book has a cover
	FirstBook int  // ID of first book (for cover image)
}

const refreshInterval = 10 * time.Minute

func NewClient(baseURL, apiKey string) *Client {
	c := &Client{
		baseURL: baseURL,
		apiKey:  apiKey,
		httpClient: &http.Client{
			Timeout: 60 * time.Second,
		},
		authorMap:   make(map[int]string),
		booksByAuth: make(map[int][]Book),
		bookByID:    make(map[int]*Book),
	}

	// Initial load (blocking)
	if err := c.fetchAll(); err != nil {
		log.Printf("WARNING: initial Readarr fetch failed: %v", err)
	}

	// Background refresh
	go c.backgroundRefresh()

	return c
}

func (c *Client) backgroundRefresh() {
	ticker := time.NewTicker(refreshInterval)
	defer ticker.Stop()
	for range ticker.C {
		if err := c.fetchAll(); err != nil {
			log.Printf("Background refresh failed: %v", err)
		} else {
			log.Printf("Background refresh complete")
		}
	}
}

func (c *Client) doRequest(path string) (*http.Response, error) {
	url := c.baseURL + path
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("X-Api-Key", c.apiKey)
	return c.httpClient.Do(req)
}

func (c *Client) fetchAll() error {
	// Fetch authors
	resp, err := c.doRequest("/api/v1/author")
	if err != nil {
		return fmt.Errorf("get authors: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("get authors: status %d", resp.StatusCode)
	}
	var authors []Author
	if err := json.NewDecoder(resp.Body).Decode(&authors); err != nil {
		return fmt.Errorf("decode authors: %w", err)
	}

	// Fetch books
	resp2, err := c.doRequest("/api/v1/book")
	if err != nil {
		return fmt.Errorf("get books: %w", err)
	}
	defer resp2.Body.Close()
	if resp2.StatusCode != http.StatusOK {
		return fmt.Errorf("get books: status %d", resp2.StatusCode)
	}
	var allBooks []Book
	if err := json.NewDecoder(resp2.Body).Decode(&allBooks); err != nil {
		return fmt.Errorf("decode books: %w", err)
	}

	// Filter: only books that have files on disk
	books := make([]Book, 0, len(allBooks)/3)
	for _, b := range allBooks {
		if b.Statistics.BookFileCount > 0 {
			books = append(books, b)
		}
	}

	log.Printf("Readarr: %d authors, %d total books, %d with files", len(authors), len(allBooks), len(books))

	// Build indexes
	authorMap := make(map[int]string, len(authors))
	for _, a := range authors {
		authorMap[a.ID] = a.AuthorName
	}

	booksByAuth := make(map[int][]Book)
	bookByID := make(map[int]*Book)
	for i := range books {
		b := &books[i]
		booksByAuth[b.AuthorID] = append(booksByAuth[b.AuthorID], *b)
		bookByID[b.ID] = b
	}

	c.mu.Lock()
	c.authors = authors
	c.books = books
	c.authorMap = authorMap
	c.booksByAuth = booksByAuth
	c.bookByID = bookByID
	c.loaded = true
	c.mu.Unlock()

	return nil
}

// GetCachedAuthors returns sorted author display list from cache.
func (c *Client) GetCachedAuthors() ([]AuthorDisplay, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	if !c.loaded {
		return nil, fmt.Errorf("data not yet loaded")
	}

	result := make([]AuthorDisplay, 0, len(c.authors))
	for _, a := range c.authors {
		books := c.booksByAuth[a.ID]
		if len(books) == 0 {
			continue
		}

		hasCover := false
		firstBook := books[0].ID
		for _, b := range books {
			if len(b.Images) > 0 {
				hasCover = true
				firstBook = b.ID
				break
			}
		}

		result = append(result, AuthorDisplay{
			ID:        a.ID,
			Name:      a.AuthorName,
			BookCount: len(books),
			HasCover:  hasCover,
			FirstBook: firstBook,
		})
	}

	sort.Slice(result, func(i, j int) bool {
		return strings.ToLower(result[i].Name) < strings.ToLower(result[j].Name)
	})

	return result, nil
}

// GetCachedBooksByAuthor returns books for a specific author from cache.
func (c *Client) GetCachedBooksByAuthor(authorID int) ([]Book, string, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	if !c.loaded {
		return nil, "", fmt.Errorf("data not yet loaded")
	}

	name := c.authorMap[authorID]
	books := c.booksByAuth[authorID]

	// Sort by series+position, then title
	sorted := make([]Book, len(books))
	copy(sorted, books)
	sort.Slice(sorted, func(i, j int) bool {
		si := seriesKey(sorted[i])
		sj := seriesKey(sorted[j])
		if si != sj {
			return si < sj
		}
		return strings.ToLower(sorted[i].Title) < strings.ToLower(sorted[j].Title)
	})

	return sorted, name, nil
}

// GetCachedBooks returns all books from cache (for search).
func (c *Client) GetCachedBooks() ([]Book, map[int]string, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	if !c.loaded {
		return nil, nil, fmt.Errorf("data not yet loaded")
	}

	return c.books, c.authorMap, nil
}

// HasCover checks if a book has a cover image in readarr.
func (c *Client) HasCover(bookID int) bool {
	c.mu.RLock()
	defer c.mu.RUnlock()

	if b, ok := c.bookByID[bookID]; ok {
		return len(b.Images) > 0
	}
	return false
}

func seriesKey(b Book) string {
	if len(b.SeriesLinks) > 0 {
		sl := b.SeriesLinks[0]
		pos := sl.Position
		if len(pos) == 1 {
			pos = "0" + pos // zero-pad for sorting
		}
		return strings.ToLower(sl.Title) + "|" + pos
	}
	return "\xff" // sort non-series books last
}

func (c *Client) GetBook(id int) (*Book, error) {
	// Check cache first
	c.mu.RLock()
	if b, ok := c.bookByID[id]; ok {
		c.mu.RUnlock()
		return b, nil
	}
	c.mu.RUnlock()

	// Fall back to API for uncached books
	resp, err := c.doRequest(fmt.Sprintf("/api/v1/book/%d", id))
	if err != nil {
		return nil, fmt.Errorf("get book: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("get book: status %d", resp.StatusCode)
	}

	var book Book
	if err := json.NewDecoder(resp.Body).Decode(&book); err != nil {
		return nil, fmt.Errorf("decode book: %w", err)
	}

	// Don't serve missing books
	if book.Statistics.BookFileCount == 0 {
		return nil, fmt.Errorf("book has no files")
	}

	return &book, nil
}

func (c *Client) GetBookFiles(bookID int) ([]BookFile, error) {
	resp, err := c.doRequest(fmt.Sprintf("/api/v1/bookfile?bookId=%d", bookID))
	if err != nil {
		return nil, fmt.Errorf("get book files: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("get book files: status %d", resp.StatusCode)
	}

	var files []BookFile
	if err := json.NewDecoder(resp.Body).Decode(&files); err != nil {
		return nil, fmt.Errorf("decode book files: %w", err)
	}
	return files, nil
}

func (c *Client) ProxyCover(bookID int, w http.ResponseWriter) error {
	// Readarr serves covers at /MediaCover/Books/{id}/cover.jpg (no /api/v1 prefix)
	path := fmt.Sprintf("/MediaCover/Books/%d/cover.jpg", bookID)
	resp, err := c.doRequest(path)
	if err != nil {
		return fmt.Errorf("proxy cover: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("proxy cover: status %d", resp.StatusCode)
	}

	w.Header().Set("Content-Type", resp.Header.Get("Content-Type"))
	w.Header().Set("Cache-Control", "public, max-age=86400")
	io.Copy(w, resp.Body)
	return nil
}
