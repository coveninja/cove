package trakt

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"sync"
	"time"

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

	// Device-flow backend polling state. All fields guarded by flowMu.
	flowMu     sync.Mutex
	flowState  string             // "idle" | "pending" | "authorized" | "expired" | "denied"
	flowCancel context.CancelFunc // cancels an in-flight runDeviceFlow goroutine
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
// Any in-flight device-flow polling loop is cancelled — the flow was for the
// old profile and must not authorize the new one.
func (s *Server) SetProfile(profileID string) error {
	s.cancelFlow("idle")
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
// Also starts a backend polling goroutine so the flow survives a backgrounded WebView.
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

	// Cancel any previous in-flight poll loop and start a fresh one.
	s.flowMu.Lock()
	if s.flowCancel != nil {
		s.flowCancel()
	}
	ctx, cancel := context.WithCancel(context.Background())
	s.flowCancel = cancel
	s.flowState = "pending"
	s.flowMu.Unlock()

	go s.runDeviceFlow(ctx, code.DeviceCode, code.Interval, code.ExpiresIn)

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
// Response: {connected: bool, username: string, expires_at: time.Time, flow_state: string}
// flow_state is one of: "idle" | "pending" | "authorized" | "expired" | "denied"
func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	st := s.store.Get()
	s.flowMu.Lock()
	fs := s.flowState
	if fs == "" {
		fs = "idle"
	}
	s.flowMu.Unlock()
	jsonOK(w, map[string]any{
		"connected":  st.AccessToken != "",
		"username":   st.Username,
		"expires_at": st.ExpiresAt,
		"flow_state": fs,
	})
}

// POST /api/trakt/unlink — revokes the token (best-effort) and clears the sidecar.
func (s *Server) handleUnlink(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	// Cancel any in-flight device flow before clearing the store.
	s.cancelFlow("idle")
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

// ── Device-flow backend polling ───────────────────────────────────────────────

// cancelFlow cancels any in-flight runDeviceFlow goroutine and sets flowState
// to state. Safe to call from any goroutine; acquires flowMu internally.
func (s *Server) cancelFlow(state string) {
	s.flowMu.Lock()
	defer s.flowMu.Unlock()
	if s.flowCancel != nil {
		s.flowCancel()
		s.flowCancel = nil
	}
	s.flowState = state
}

// setFlowState sets flowState under flowMu.
func (s *Server) setFlowState(state string) {
	s.flowMu.Lock()
	s.flowState = state
	s.flowMu.Unlock()
}

// runDeviceFlow polls Trakt for device-flow authorization until the code
// expires, the user authorizes/denies, or ctx is cancelled. It runs in its
// own goroutine and updates flowState accordingly. Token saving is handled
// inside PollDeviceFlow on a 200 response.
func (s *Server) runDeviceFlow(ctx context.Context, deviceCode string, intervalSec, expiresInSec int) {
	interval := time.Duration(max(intervalSec, 1)+1) * time.Second
	deadline := time.NewTimer(time.Duration(expiresInSec) * time.Second)
	defer deadline.Stop()
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-deadline.C:
			s.setFlowState("expired")
			return
		case <-ticker.C:
			result, err := s.client.PollDeviceFlow(deviceCode)
			if err != nil {
				// Transient network error — keep trying until expiry.
				log.Println("trakt: backend poll error:", err)
				continue
			}
			switch result.Status {
			case PollAuthorized:
				// Token already saved by PollDeviceFlow.
				s.flowMu.Lock()
				s.flowState = "authorized"
				s.flowCancel = nil
				s.flowMu.Unlock()
				return
			case PollSlowDown:
				// Trakt asked us to back off; increase interval by 5s, cap at 30s.
				ticker.Stop()
				interval += 5 * time.Second
				if interval > 30*time.Second {
					interval = 30 * time.Second
				}
				ticker = time.NewTicker(interval)
			case PollExpired:
				s.setFlowState("expired")
				return
			case PollDenied, PollInvalid:
				s.setFlowState("denied")
				return
			case PollPending:
				// Not yet — keep looping.
			}
		}
	}
}

// ── Helpers ───────────────────────────────────────────────────────────────────

func jsonOK(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(v); err != nil {
		log.Println("trakt: json encode:", err)
	}
}
