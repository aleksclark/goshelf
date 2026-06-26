package readarr

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

type Client struct {
	baseURL    string
	apiKey     string
	httpClient *http.Client
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
	ID             int    `json:"id"`
	Position       string `json:"position"`
	SeriesID       int    `json:"seriesId"`
	Title          string `json:"title"`
}

type Book struct {
	ID          int          `json:"id"`
	Title       string       `json:"title"`
	SeriesTitle string       `json:"seriesTitle"`
	AuthorID    int          `json:"authorId"`
	AuthorTitle string       `json:"authorTitle"`
	Overview    string       `json:"overview"`
	Images      []Image      `json:"images"`
	Author      *Author      `json:"author"`
	SeriesLinks []SeriesLink `json:"seriesLinks"`
	PageCount   int          `json:"pageCount"`
	Added       time.Time    `json:"added"`
}

type BookFile struct {
	ID       int    `json:"id"`
	Path     string `json:"path"`
	Size     int64  `json:"size"`
	BookID   int    `json:"bookId"`
	AuthorID int    `json:"authorId"`
}

func NewClient(baseURL, apiKey string) *Client {
	return &Client{
		baseURL: baseURL,
		apiKey:  apiKey,
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
		},
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

func (c *Client) GetAuthors() ([]Author, error) {
	resp, err := c.doRequest("/api/v1/author")
	if err != nil {
		return nil, fmt.Errorf("get authors: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("get authors: status %d", resp.StatusCode)
	}

	var authors []Author
	if err := json.NewDecoder(resp.Body).Decode(&authors); err != nil {
		return nil, fmt.Errorf("decode authors: %w", err)
	}
	return authors, nil
}

func (c *Client) GetBooks() ([]Book, error) {
	resp, err := c.doRequest("/api/v1/book")
	if err != nil {
		return nil, fmt.Errorf("get books: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("get books: status %d", resp.StatusCode)
	}

	var books []Book
	if err := json.NewDecoder(resp.Body).Decode(&books); err != nil {
		return nil, fmt.Errorf("decode books: %w", err)
	}
	return books, nil
}

func (c *Client) GetBook(id int) (*Book, error) {
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
	path := fmt.Sprintf("/api/v1/MediaCover/Books/%d/cover.jpg", bookID)
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
