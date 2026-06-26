package handlers

import (
	"log"
	"net/http"
	"strconv"
)

func (h *Handlers) CoverProxy(w http.ResponseWriter, r *http.Request) {
	idStr := r.PathValue("id")
	id, err := strconv.Atoi(idStr)
	if err != nil {
		http.Error(w, "Invalid book ID", http.StatusBadRequest)
		return
	}

	if err := h.client.ProxyCover(id, w); err != nil {
		log.Printf("Error proxying cover for book %d: %v", id, err)
		http.Error(w, "Cover not found", http.StatusNotFound)
		return
	}
}
