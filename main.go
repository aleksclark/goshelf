package main

import (
	"log"
	"net/http"
	"os"

	"github.com/aleksclark/goshelf/handlers"
	"github.com/aleksclark/goshelf/models"
	"github.com/aleksclark/goshelf/readarr"
)

var Version = "dev"

func main() {
	// Configuration from environment — no secret defaults.
	// READARR_URL should be the durable fleet hostname (e.g. https://readarr.fleet.clark.team).
	readarrURL := mustEnv("READARR_URL")
	readarrAPIKey := mustEnv("READARR_API_KEY")
	// MEDIA_PATH is the local mount root of the same tree Readarr stores under READARR_MEDIA_ROOT.
	// Example: Readarr /media/ebooks/... and /media/audiobooks/... map to /audiobooks/ebooks/... and /audiobooks/audiobooks/...
	mediaPath := getEnv("MEDIA_PATH", "/audiobooks")
	readarrMediaRoot := getEnv("READARR_MEDIA_ROOT", "/media")
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
	h := handlers.New(db, client, mediaPath, readarrMediaRoot)

	// Start background cleanup of stale temp ZIP files
	handlers.StartZipCleanup()

	// Setup routes using Go 1.22+ enhanced routing
	mux := http.NewServeMux()

	// Liveness / readiness (unauthenticated; readiness checks Readarr /ping)
	mux.HandleFunc("GET /healthz", h.Healthz)
	mux.HandleFunc("GET /readyz", h.Readyz)

	// Auth routes
	mux.HandleFunc("GET /login", h.LoginPage)
	mux.HandleFunc("POST /login", h.LoginSubmit)
	mux.HandleFunc("GET /logout", h.Logout)
	mux.HandleFunc("GET /register", h.RegisterPage)
	mux.HandleFunc("POST /register", h.RegisterSubmit)

	// Library routes (protected)
	mux.HandleFunc("GET /", h.RequireAuth(h.Library))
	mux.HandleFunc("GET /authors/{id}", h.RequireAuth(h.AuthorBooks))
	mux.HandleFunc("GET /books", h.RequireAuth(h.AllBooks))
	mux.HandleFunc("GET /books/{id}", h.RequireAuth(h.BookDetail))
	mux.HandleFunc("GET /books/{id}/download.zip", h.RequireAuth(h.DownloadZipResumable))
	mux.HandleFunc("GET /books/{id}/download-stream.zip", h.RequireAuth(h.DownloadZip))
	mux.HandleFunc("GET /series", h.RequireAuth(h.SeriesList))
	mux.HandleFunc("GET /series/{slug}", h.RequireAuth(h.SeriesBooks))
	mux.HandleFunc("GET /search", h.RequireAuth(h.Search))
	mux.HandleFunc("GET /search-authors", h.RequireAuth(h.SearchAuthors))

	// JSON API (for mobile apps)
	mux.HandleFunc("GET /api/books", h.RequireAuth(h.APIBooks))
	mux.HandleFunc("GET /api/books/{id}", h.RequireAuth(h.APIBookDetailJSON))
	mux.HandleFunc("GET /api/books/{id}/download-info", h.RequireAuth(h.APIDownloadInfo))
	mux.HandleFunc("GET /api/authors", h.RequireAuth(h.APIAuthors))
	mux.HandleFunc("GET /api/authors/{id}", h.RequireAuth(h.APIAuthorDetail))
	mux.HandleFunc("GET /api/series", h.RequireAuth(h.APISeriesList))
	mux.HandleFunc("GET /api/series/{slug}", h.RequireAuth(h.APISeriesDetail))

	// Cover proxy
	mux.HandleFunc("GET /covers/{id}", h.RequireAuth(h.CoverProxy))

	// Admin routes (require admin role)
	mux.HandleFunc("GET /admin/users", h.RequireAdmin(h.AdminUsers))
	mux.HandleFunc("POST /admin/users", h.RequireAdmin(h.AdminCreateUser))
	mux.HandleFunc("POST /admin/users/{id}/delete", h.RequireAdmin(h.AdminDeleteUser))
	mux.HandleFunc("POST /admin/users/{id}/toggle-admin", h.RequireAdmin(h.AdminToggleAdmin))

	log.Printf("GoShelf %s starting on %s", Version, listenAddr)
	log.Printf("Readarr URL: %s", readarrURL)
	log.Printf("Media path: %s (readarr root: %s)", mediaPath, readarrMediaRoot)

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

func mustEnv(key string) string {
	val := os.Getenv(key)
	if val == "" {
		log.Fatalf("required environment variable %s is not set", key)
	}
	return val
}
