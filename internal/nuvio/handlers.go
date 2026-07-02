package nuvio

import (
	"encoding/json"
	"log"
	"net/http"

	"github.com/coveninja/cove/internal/utils"
)

// SetupHandlers registers the Nuvio repo/scraper management endpoints:
//
//	GET    /api/nuvio/repos                        — list all repos
//	POST   /api/nuvio/repos                         (body: {"url":"..."})
//	PATCH  /api/nuvio/repos?id=X                    (body: {"enabled":true})
//	DELETE /api/nuvio/repos?id=X
//	POST   /api/nuvio/repos/refresh?id=X
//	PATCH  /api/nuvio/scrapers?repoId=X&scraperId=Y (body: {"enabled":true})
func (m *Manager) SetupHandlers(mux *http.ServeMux) {
	mux.HandleFunc("/api/nuvio/repos", utils.CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodGet:
			w.Header().Set("Content-Type", "application/json")
			if err := json.NewEncoder(w).Encode(m.GetRepos()); err != nil {
				log.Println("nuvio repos list:", err)
			}

		case http.MethodPost:
			var body struct {
				URL string `json:"url"`
			}
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.URL == "" {
				http.Error(w, `body must be {"url":"..."}`, http.StatusBadRequest)
				return
			}
			repo, err := m.AddRepo(body.URL)
			if err != nil {
				http.Error(w, "could not add repo: "+err.Error(), http.StatusBadRequest)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			if err := json.NewEncoder(w).Encode(repo); err != nil {
				log.Println("nuvio repos add:", err)
			}

		case http.MethodPatch:
			id := r.URL.Query().Get("id")
			if id == "" {
				http.Error(w, "missing ?id=", http.StatusBadRequest)
				return
			}
			var body struct {
				Enabled bool `json:"enabled"`
			}
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				http.Error(w, "invalid body", http.StatusBadRequest)
				return
			}
			if err := m.SetRepoEnabled(id, body.Enabled); err != nil {
				http.Error(w, err.Error(), http.StatusNotFound)
				return
			}
			w.WriteHeader(http.StatusNoContent)

		case http.MethodDelete:
			id := r.URL.Query().Get("id")
			if id == "" {
				http.Error(w, "missing ?id=", http.StatusBadRequest)
				return
			}
			if err := m.RemoveRepo(id); err != nil {
				http.Error(w, err.Error(), http.StatusBadRequest)
				return
			}
			w.WriteHeader(http.StatusNoContent)

		default:
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		}
	}))

	mux.HandleFunc("/api/nuvio/repos/refresh", utils.CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		id := r.URL.Query().Get("id")
		if id == "" {
			http.Error(w, "missing ?id=", http.StatusBadRequest)
			return
		}
		if err := m.RefreshRepo(id); err != nil {
			http.Error(w, "could not refresh repo: "+err.Error(), http.StatusBadRequest)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}))

	mux.HandleFunc("/api/nuvio/scrapers", utils.CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPatch {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		repoID := r.URL.Query().Get("repoId")
		scraperID := r.URL.Query().Get("scraperId")
		if repoID == "" || scraperID == "" {
			http.Error(w, "missing ?repoId= or ?scraperId=", http.StatusBadRequest)
			return
		}
		var body struct {
			Enabled bool `json:"enabled"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, "invalid body", http.StatusBadRequest)
			return
		}
		if err := m.SetScraperEnabled(repoID, scraperID, body.Enabled); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}))
}
