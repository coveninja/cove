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
	"sync"

	"github.com/coveninja/cove/internal/utils"
)

var sessionMu sync.Mutex

func filePath() (string, error) {
	// Route through utils.ConfigPath so the SetDataDir override (used by
	// mobile builds and COVE_DATA_DIR on desktop) propagates here too.
	return utils.ConfigPath("session.json")
}

func SetupHandlers(mux *http.ServeMux) {
	mux.HandleFunc("/api/client-session", utils.CorsMiddleware(handle))
}

func handle(w http.ResponseWriter, r *http.Request) {
	// Auth tokens must never be stored by an intermediary or browser cache.
	w.Header().Set("Cache-Control", "no-store")

	path, err := filePath()
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	switch r.Method {
	case http.MethodGet:
		sessionMu.Lock()
		data, err := os.ReadFile(path)
		sessionMu.Unlock()
		if os.IsNotExist(err) {
			http.NotFound(w, r)
			return
		}
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		if !json.Valid(data) {
			http.Error(w, "stored session is invalid", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(data)

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
		// ConfigPath has already created the parent directory. Serialize the
		// atomic replacement with reads/deletes so every request observes one
		// complete session state.
		sessionMu.Lock()
		err = utils.AtomicWriteFile(path, body, 0o600)
		sessionMu.Unlock()
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusNoContent)

	case http.MethodDelete:
		sessionMu.Lock()
		err := os.Remove(path)
		sessionMu.Unlock()
		if err != nil && !os.IsNotExist(err) {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusNoContent)

	default:
		w.Header().Set("Allow", "GET, POST, DELETE")
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}
