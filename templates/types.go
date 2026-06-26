package templates

import "time"
import "github.com/aleksclark/goshelf/readarr"

type BookDisplayData struct {
	ID          int
	Title       string
	Author      string
	SeriesTitle string
	HasCover    bool
	Added       time.Time
}

// Re-export for use in handlers
type BookFile = readarr.BookFile
