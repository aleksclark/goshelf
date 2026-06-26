package handlers

import (
	"log"
	"net/http"
	"strconv"
	"strings"

	"github.com/aleksclark/goshelf/models"
	"github.com/aleksclark/goshelf/templates"
)

func (h *Handlers) AdminUsers(w http.ResponseWriter, r *http.Request) {
	users, err := models.GetAllUsers(h.db)
	if err != nil {
		http.Error(w, "Failed to load users", http.StatusInternalServerError)
		return
	}

	username := r.Header.Get("X-Username")
	templates.AdminPage(users, username, "").Render(r.Context(), w)
}

func (h *Handlers) AdminCreateUser(w http.ResponseWriter, r *http.Request) {
	r.ParseForm()
	username := strings.TrimSpace(r.FormValue("username"))
	password := r.FormValue("password")

	if username == "" || password == "" {
		users, _ := models.GetAllUsers(h.db)
		currentUser := r.Header.Get("X-Username")
		templates.AdminPage(users, currentUser, "Username and password are required").Render(r.Context(), w)
		return
	}

	if len(password) < 6 {
		users, _ := models.GetAllUsers(h.db)
		currentUser := r.Header.Get("X-Username")
		templates.AdminPage(users, currentUser, "Password must be at least 6 characters").Render(r.Context(), w)
		return
	}

	_, err := models.CreateUser(h.db, username, password)
	if err != nil {
		log.Printf("Error creating user: %v", err)
		users, _ := models.GetAllUsers(h.db)
		currentUser := r.Header.Get("X-Username")
		templates.AdminPage(users, currentUser, "Failed to create user: "+err.Error()).Render(r.Context(), w)
		return
	}

	http.Redirect(w, r, "/admin/users", http.StatusSeeOther)
}

func (h *Handlers) AdminDeleteUser(w http.ResponseWriter, r *http.Request) {
	idStr := r.PathValue("id")
	id, err := strconv.ParseInt(idStr, 10, 64)
	if err != nil {
		http.Error(w, "Invalid user ID", http.StatusBadRequest)
		return
	}

	// Don't allow deleting yourself
	currentUsername := r.Header.Get("X-Username")
	users, _ := models.GetAllUsers(h.db)
	for _, u := range users {
		if u.ID == id && u.Username == currentUsername {
			templates.AdminPage(users, currentUsername, "Cannot delete your own account").Render(r.Context(), w)
			return
		}
	}

	if err := models.DeleteUser(h.db, id); err != nil {
		log.Printf("Error deleting user: %v", err)
		http.Error(w, "Failed to delete user", http.StatusInternalServerError)
		return
	}

	http.Redirect(w, r, "/admin/users", http.StatusSeeOther)
}
