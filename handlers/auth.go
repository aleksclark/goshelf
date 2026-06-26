package handlers

import (
	"net/http"
	"time"

	"github.com/aleksclark/goshelf/models"
	"github.com/aleksclark/goshelf/templates"
)

func (h *Handlers) LoginPage(w http.ResponseWriter, r *http.Request) {
	// If no users exist, redirect to register
	count, err := models.UserCount(h.db)
	if err != nil {
		http.Error(w, "Database error", http.StatusInternalServerError)
		return
	}
	if count == 0 {
		http.Redirect(w, r, "/register", http.StatusSeeOther)
		return
	}

	templates.Login("", "").Render(r.Context(), w)
}

func (h *Handlers) LoginSubmit(w http.ResponseWriter, r *http.Request) {
	r.ParseForm()
	username := r.FormValue("username")
	password := r.FormValue("password")

	user, err := models.AuthenticateUser(h.db, username, password)
	if err != nil {
		templates.Login("Invalid username or password", username).Render(r.Context(), w)
		return
	}

	token, err := models.CreateSession(h.db, user.ID)
	if err != nil {
		http.Error(w, "Session error", http.StatusInternalServerError)
		return
	}

	http.SetCookie(w, &http.Cookie{
		Name:     "session",
		Value:    token,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		MaxAge:   86400 * 30, // 30 days
	})

	http.Redirect(w, r, "/", http.StatusSeeOther)
}

func (h *Handlers) Logout(w http.ResponseWriter, r *http.Request) {
	cookie, err := r.Cookie("session")
	if err == nil {
		models.DeleteSession(h.db, cookie.Value)
	}

	http.SetCookie(w, &http.Cookie{
		Name:     "session",
		Value:    "",
		Path:     "/",
		HttpOnly: true,
		MaxAge:   -1,
		Expires:  time.Unix(0, 0),
	})

	http.Redirect(w, r, "/login", http.StatusSeeOther)
}

func (h *Handlers) RegisterPage(w http.ResponseWriter, r *http.Request) {
	count, err := models.UserCount(h.db)
	if err != nil {
		http.Error(w, "Database error", http.StatusInternalServerError)
		return
	}
	// Only allow registration if no users exist
	if count > 0 {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}

	templates.Register("").Render(r.Context(), w)
}

func (h *Handlers) RegisterSubmit(w http.ResponseWriter, r *http.Request) {
	count, err := models.UserCount(h.db)
	if err != nil {
		http.Error(w, "Database error", http.StatusInternalServerError)
		return
	}
	if count > 0 {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}

	r.ParseForm()
	username := r.FormValue("username")
	password := r.FormValue("password")
	confirm := r.FormValue("confirm")

	if username == "" || password == "" {
		templates.Register("Username and password are required").Render(r.Context(), w)
		return
	}
	if password != confirm {
		templates.Register("Passwords do not match").Render(r.Context(), w)
		return
	}
	if len(password) < 6 {
		templates.Register("Password must be at least 6 characters").Render(r.Context(), w)
		return
	}

	user, err := models.CreateUser(h.db, username, password)
	if err != nil {
		templates.Register("Failed to create user: "+err.Error()).Render(r.Context(), w)
		return
	}

	// Auto-login after registration
	token, err := models.CreateSession(h.db, user.ID)
	if err != nil {
		http.Redirect(w, r, "/login", http.StatusSeeOther)
		return
	}

	http.SetCookie(w, &http.Cookie{
		Name:     "session",
		Value:    token,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		MaxAge:   86400 * 30,
	})

	http.Redirect(w, r, "/", http.StatusSeeOther)
}

// GetCurrentUser returns nil if not authenticated (used by non-protected routes)
func (h *Handlers) GetCurrentUser(r *http.Request) *models.User {
	cookie, err := r.Cookie("session")
	if err != nil {
		return nil
	}
	user, err := models.GetUserBySession(h.db, cookie.Value)
	if err != nil {
		return nil
	}
	return user
}
