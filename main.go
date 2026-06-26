package main

import (
	"log"
	"net/http"
	"os"

	"github.com/aleksclark/goshelf/handlers"
	"github.com/aleksclark/goshelf/models"
	"github.com/aleksclark/goshelf/readarr"
)

func main() {
	// Configuration from environment
	readarrURL := getEnv("READARR_URL", "http://192.168.0.24:8787")
	readarrAPIKey := getEnv("READARR_API_KEY", "124c86cb3f13445c8f20e951919fb896")
	mediaPath := getEnv("MEDIA_PATH", "/audiobooks")
	listenAddr := getEnv("LISTEN_ADDR", ":8080")
	dbPath := getEnv("DB_PATH", "./goshelf.db")

	// Initialize database
	db, err := models.InitDB(dbPath)
	if err != nil {
		log.Fatalf("Failed to initialize database: %v", err)
	}
	defer db.Close()

	// Initialize Readarr client
	client := readarr.NewClient(readarrURL, readarrAPIKey)

	// Initialize handlers
	h := handlers.New(db, client, mediaPath)

	// Setup routes using Go 1.22+ enhanced routing
	mux := http.NewServeMux()

	// Auth routes
	mux.HandleFunc("GET /login", h.LoginPage)
	mux.HandleFunc("POST /login", h.LoginSubmit)
	mux.HandleFunc("GET /logout", h.Logout)
	mux.HandleFunc("GET /register", h.RegisterPage)
	mux.HandleFunc("POST /register", h.RegisterSubmit)

	// Library routes (protected)
	mux.HandleFunc("GET /", h.RequireAuth(h.Library))
	mux.HandleFunc("GET /books/{id}", h.RequireAuth(h.BookDetail))
	mux.HandleFunc("GET /books/{id}/download.zip", h.RequireAuth(h.DownloadZip))
	mux.HandleFunc("GET /search", h.RequireAuth(h.Search))

	// Cover proxy
	mux.HandleFunc("GET /covers/{id}", h.RequireAuth(h.CoverProxy))

	// Admin routes
	mux.HandleFunc("GET /admin/users", h.RequireAuth(h.AdminUsers))
	mux.HandleFunc("POST /admin/users", h.RequireAuth(h.AdminCreateUser))
	mux.HandleFunc("POST /admin/users/{id}/delete", h.RequireAuth(h.AdminDeleteUser))

	log.Printf("GoShelf starting on %s", listenAddr)
	log.Printf("Readarr URL: %s", readarrURL)
	log.Printf("Media path: %s", mediaPath)

	if err := http.ListenAndServe(listenAddr, mux); err != nil {
		log.Fatalf("Server failed: %v", err)
	}
}

func getEnv(key, fallback string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return fallback
}
