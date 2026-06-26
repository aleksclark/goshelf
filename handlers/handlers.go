package handlers

import (
	"database/sql"
	"fmt"
	"net/http"

	"github.com/aleksclark/goshelf/models"
	"github.com/aleksclark/goshelf/readarr"
)

type Handlers struct {
	db        *sql.DB
	client    *readarr.Client
	mediaPath string
}

func New(db *sql.DB, client *readarr.Client, mediaPath string) *Handlers {
	return &Handlers{
		db:        db,
		client:    client,
		mediaPath: mediaPath,
	}
}

func (h *Handlers) RequireAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		// Check if any users exist (first-run setup)
		count, err := models.UserCount(h.db)
		if err != nil {
			http.Error(w, "Database error", http.StatusInternalServerError)
			return
		}
		if count == 0 {
			http.Redirect(w, r, "/register", http.StatusSeeOther)
			return
		}

		// Check session cookie
		cookie, err := r.Cookie("session")
		if err != nil {
			http.Redirect(w, r, "/login", http.StatusSeeOther)
			return
		}

		user, err := models.GetUserBySession(h.db, cookie.Value)
		if err != nil {
			http.Redirect(w, r, "/login", http.StatusSeeOther)
			return
		}

		// Store user in request context via header (simple approach)
		r.Header.Set("X-User-ID", fmt.Sprintf("%d", user.ID))
		r.Header.Set("X-Username", user.Username)
		next(w, r)
	}
}
