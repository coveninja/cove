package trakt

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"os"

	"github.com/coveninja/cove/internal/library"
	"github.com/coveninja/cove/internal/settings"
	"github.com/coveninja/cove/internal/tmdb"
	"github.com/coveninja/cove/internal/utils"
)

// Config holds the Trakt API credentials. Both fields must be non-empty for
// the integration to be active; when either is empty all endpoints return 503.
type Config struct {
	ClientID     string
	ClientSecret string
}

// ConfigFromEnv resolves Config from compiled-in ldflags values (fallback) or
// TRAKT_CLIENT_ID / TRAKT_CLIENT_SECRET env vars (override). Returns Config
// with empty fields if neither source provides values — callers guard with
// cfg.ClientID == "".
func ConfigFromEnv(clientID, clientSecret string) Config {
	if id := os.Getenv("TRAKT_CLIENT_ID"); id != "" {
		clientID = id
	}
	if secret := os.Getenv("TRAKT_CLIENT_SECRET"); secret != "" {
		clientSecret = secret
	}
	return Config{ClientID: clientID, ClientSecret: clientSecret}
}

// Server wires together the Trakt client, token store, sync worker, and all
// HTTP handlers. Embed in the server package and call SetupHandlers(mux) and
// RunSync(ctx) at startup.
type Server struct {
	cfg        Config
	client     *Client
	store      *Store
	lib        *library.Library
	settings   *settings.Store
	syncWorker *SyncWorker
}

// New creates a Server for the given active profile. Errors loading the sidecar
// token file are logged-and-continued (same pattern as settings.New / library.New).
func New(cfg Config, profileID string, lib *library.Library, st *settings.Store, tmdbClient *tmdb.Client) *Server {
	store, err := newStore(profileID)
	if err != nil {
		log.Println("trakt: load token store:", err)
		store = &Store{} // fallback: in-memory only; writes will fail until path is set
	}
	client := newClient(cfg.ClientID, cfg.ClientSecret, store)
	sw := newSyncWorker(client, store, lib, st, tmdbClient)
	return &Server{
		cfg:        cfg,
		client:     client,
		store:      store,
		lib:        lib,
		settings:   st,
		syncWorker: sw,
	}
}

// SetProfile reloads the token sidecar for the newly active profile.
func (s *Server) SetProfile(profileID string) error {
	if err := s.store.SetProfile(profileID); err != nil {
		return err
	}
	return nil
}

// RunSync starts the background sync worker. Call as `go s.RunSync(ctx)`.
func (s *Server) RunSync(ctx context.Context) {
	s.syncWorker.Run(ctx)
}

// SetupHandlers registers all /api/trakt/* endpoints on mux. When no client
// credentials are configured, all endpoints return 503 so the frontend can
// show a clear error instead of a CORS failure or timeout.
func (s *Server) SetupHandlers(mux *http.ServeMux) {
	if s.cfg.ClientID == "" {
		stub := utils.CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
			http.Error(w, "Trakt integration not configured (TRAKT_CLIENT_ID not set)", http.StatusServiceUnavailable)
		})
		for _, path := range []string{
			"/api/trakt/device-code",
			"/api/trakt/poll",
			"/api/trakt/status",
			"/api/trakt/unlink",
			"/api/trakt/scrobble",
			"/api/trakt/sync",
		} {
			mux.HandleFunc(path, stub)
		}
		return
	}

	mux.HandleFunc("/api/trakt/device-code", utils.CorsMiddleware(s.handleDeviceCode))
	mux.HandleFunc("/api/trakt/poll", utils.CorsMiddleware(s.handlePoll))
	mux.HandleFunc("/api/trakt/status", utils.CorsMiddleware(s.handleStatus))
	mux.HandleFunc("/api/trakt/unlink", utils.CorsMiddleware(s.handleUnlink))
	mux.HandleFunc("/api/trakt/scrobble", utils.CorsMiddleware(s.handleScrobble))
	mux.HandleFunc("/api/trakt/sync", utils.CorsMiddleware(s.handleSync))
}

// ── Handlers ─────────────────────────────────────────────────────────────────

// POST /api/trakt/device-code — starts the device-code flow.
// Response: {device_code, user_code, verification_url, expires_in, interval}
func (s *Server) handleDeviceCode(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	code, err := s.client.StartDeviceFlow()
	if err != nil {
		log.Println("trakt: device-code:", err)
		http.Error(w, "could not start device flow: "+err.Error(), http.StatusBadGateway)
		return
	}
	jsonOK(w, code)
}

// POST /api/trakt/poll  {device_code: "..."}
// Response: {status: "pending"|"authorized"|"expired"|"denied"|"invalid"|"slow_down", username?: "..."}
func (s *Server) handlePoll(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var body struct {
		DeviceCode string `json:"device_code"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.DeviceCode == "" {
		http.Error(w, "device_code required", http.StatusBadRequest)
		return
	}
	result, err := s.client.PollDeviceFlow(body.DeviceCode)
	if err != nil {
		log.Println("trakt: poll:", err)
		http.Error(w, "poll failed: "+err.Error(), http.StatusBadGateway)
		return
	}
	jsonOK(w, map[string]any{
		"status":   string(result.Status),
		"username": result.Username,
	})
}

// GET /api/trakt/status — returns connection state from the local sidecar (no Trakt call).
// Response: {connected: bool, username: string, expires_at: time.Time}
func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	st := s.store.Get()
	jsonOK(w, map[string]any{
		"connected":  st.AccessToken != "",
		"username":   st.Username,
		"expires_at": st.ExpiresAt,
	})
}

// POST /api/trakt/unlink — revokes the token (best-effort) and clears the sidecar.
func (s *Server) handleUnlink(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	// Best-effort revoke — fire and forget; we clear locally regardless.
	go s.client.RevokeToken()
	if err := s.store.Clear(); err != nil {
		log.Println("trakt: unlink clear store:", err)
		http.Error(w, "could not clear token: "+err.Error(), http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// POST /api/trakt/scrobble — records a playback event (start/pause/stop).
// Returns 204 when not connected or scrobbling disabled; 202 when accepted.
func (s *Server) handleScrobble(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	// No-op when disabled or not connected — don't even parse the body.
	st := s.settings.Get()
	if !st.TraktScrobbleEnabled || !s.store.IsConnected() {
		w.WriteHeader(http.StatusNoContent)
		return
	}

	var req ScrobbleRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid body: "+err.Error(), http.StatusBadRequest)
		return
	}
	switch req.Action {
	case "start", "pause", "stop":
	default:
		http.Error(w, "action must be start, pause, or stop", http.StatusBadRequest)
		return
	}

	// Fire async — never block the player on a network call.
	go func() {
		if err := s.client.EnsureValidToken(); err != nil {
			log.Println("trakt: scrobble ensure token:", err)
			return
		}
		if err := s.client.scrobble(req.Action, req); err != nil {
			log.Println("trakt: scrobble:", err)
		}
	}()

	w.WriteHeader(http.StatusAccepted)
}

// POST /api/trakt/sync — triggers an immediate background sync cycle.
// Returns 202; the actual sync runs asynchronously.
func (s *Server) handleSync(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	s.syncWorker.Notify()
	w.WriteHeader(http.StatusAccepted)
}

// ── Helpers ───────────────────────────────────────────────────────────────────

func jsonOK(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(v); err != nil {
		log.Println("trakt: json encode:", err)
	}
}
