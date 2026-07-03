package templates

import "time"
import "github.com/aleksclark/goshelf/readarr"

type BookDisplayData struct {
	ID          int
	Title       string
	Author      string
	AuthorID    int
	SeriesTitle string
	SeriesSlug  string // URL-safe series identifier
	HasCover    bool
	Added       time.Time
}

type SeriesDisplay struct {
	Name      string
	Slug      string
	BookCount int
	HasCover  bool
	FirstBook int // ID of first book (for cover image)
}

// Re-export for use in handlers
type BookFile = readarr.BookFile
