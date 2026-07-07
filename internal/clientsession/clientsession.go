// Package clientsession persists opaque client-side session data (auth tokens)
// to a JSON file in the OS user-config directory. This is more reliable than
// browser localStorage in a Qt WebEngine host, where storage may be in-memory
// depending on the profile configuration.
package clientsession

import (
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"

	"github.com/coveninja/cove/internal/utils"
)

func filePath() (string, error) {
	// Route through utils.ConfigPath so the SetDataDir override (used by
	// mobile builds and COVE_DATA_DIR on desktop) propagates here too.
	return utils.ConfigPath("session.json")
}

func SetupHandlers(mux *http.ServeMux) {
	mux.HandleFunc("/api/client-session", utils.CorsMiddleware(handle))
}

func handle(w http.ResponseWriter, r *http.Request) {
	path, err := filePath()
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	switch r.Method {
	case http.MethodGet:
		data, err := os.ReadFile(path)
		if os.IsNotExist(err) {
			http.NotFound(w, r)
			return
		}
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write(data)

	case http.MethodPost:
		// Bound the incoming body to 1 MiB — session payloads are small JSON
		// blobs; anything larger is almost certainly a mistake or an attempted
		// resource exhaustion. MaxBytesReader causes io.ReadAll below to return
		// an error that flows through to the existing 400 response path.
		r.Body = http.MaxBytesReader(w, r.Body, 1<<20)
		body, err := io.ReadAll(r.Body)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		if !json.Valid(body) {
			http.Error(w, "invalid JSON", http.StatusBadRequest)
			return
		}
		if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		if err := os.WriteFile(path, body, 0o600); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusNoContent)

	case http.MethodDelete:
		os.Remove(path) // ignore error — file may not exist
		w.WriteHeader(http.StatusNoContent)
	}
}
