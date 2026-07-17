// Package server wires every Cove subsystem together and starts the HTTP
// server. Splitting this out of main() makes the backend embeddable as a
// library (gomobile Android) while keeping desktop behaviour byte-for-byte
// identical when called from main.go.
package server

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/coveninja/cove/internal/activity"
	"github.com/coveninja/cove/internal/addons"
	"github.com/coveninja/cove/internal/clientsession"
	"github.com/coveninja/cove/internal/discover"
	"github.com/coveninja/cove/internal/imgcache"
	"github.com/coveninja/cove/internal/library"
	"github.com/coveninja/cove/internal/nuvio"
	"github.com/coveninja/cove/internal/player"
	"github.com/coveninja/cove/internal/prefetch"
	"github.com/coveninja/cove/internal/profiles"
	"github.com/coveninja/cove/internal/settings"
	supapkg "github.com/coveninja/cove/internal/supabase"
	"github.com/coveninja/cove/internal/tmdb"
	traktpkg "github.com/coveninja/cove/internal/trakt"
	"github.com/coveninja/cove/internal/updater"
	"github.com/coveninja/cove/internal/utils"
	"github.com/coveninja/cove/internal/webstatic"
)

// Config holds all startup parameters for the Cove backend. Every field is a
// flat scalar string (gomobile constraint — no structs, interfaces, or slices
// may cross the JNI boundary). Empty strings mean "use the platform default".
type Config struct {
	// BindAddr is the TCP address the main HTTP server listens on.
	// Default: "127.0.0.1:6969". On desktop this is loopback-only; the Qt
	// shell, mpv, and the browser all connect on loopback and are never
	// auth-gated.
	BindAddr string

	// RemoteBindAddr is the TCP address for the remote-access LAN listener
	// opened when Settings.RemoteAccessEnabled is true. Default: "0.0.0.0"
	// with the main port + 1 (e.g. 0.0.0.0:6970 when BindAddr is :6969).
	// Override via env COVE_REMOTE_ADDR or this field.
	RemoteBindAddr string

	// DataDir, when non-empty, overrides os.UserConfigDir()/cove as the root
	// for all per-profile JSON data files. Required on Android where
	// os.UserConfigDir is unavailable.
	DataDir string

	// CacheDir, when non-empty, overrides os.UserCacheDir()/cove/images as
	// the image-cache directory. Required on Android.
	CacheDir string

	// TorrentDir, when non-empty, overrides os.TempDir()/cove-torrents as
	// the directory where downloaded torrent pieces are stored. Required on
	// Android where /tmp is unavailable.
	TorrentDir string

	// TMDBAPIKey is the TMDB v3 API key. The env-var/ldflags resolution is
	// handled by the caller (main.go); Start logs a warning when this is empty
	// and all TMDB metadata requests will fail.
	TMDBAPIKey string

	// SupabaseURL is the Supabase project URL. Empty disables Supabase
	// auth/sync entirely.
	SupabaseURL string

	// SupabaseAnonKey is the publishable Supabase anon key. Empty disables
	// Supabase auth/sync.
	SupabaseAnonKey string

	// TraktClientID and TraktClientSecret are the Trakt API application
	// credentials. When either is empty all /api/trakt/* endpoints return 503.
	TraktClientID     string
	TraktClientSecret string

	// Version is the current build version string (injected via ldflags).
	// Used by the updater to decide whether a newer release is available.
	// The zero value "dev" disables auto-update.
	Version string
}

// remoteListenAddr returns the TCP address for the remote-access LAN listener.
// If cfg.RemoteBindAddr is set it is used verbatim; otherwise the port is
// derived as the main port + 1 on 0.0.0.0 (e.g. 0.0.0.0:6970 for :6969).
func remoteListenAddr(cfg Config) string {
	if cfg.RemoteBindAddr != "" {
		return cfg.RemoteBindAddr
	}
	_, portStr, err := net.SplitHostPort(cfg.BindAddr)
	if err != nil {
		return "0.0.0.0:6970" // safe fallback
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		return "0.0.0.0:6970"
	}
	return net.JoinHostPort("0.0.0.0", strconv.Itoa(port+1))
}

// lanTokenMiddleware is applied exclusively on the separate remote-access LAN
// listener (never on the main loopback listener). Design goals:
//
//   - When remote access is disabled the LAN listener is simply closed, so a
//     port scan gets connection-refused — no live HTTP surface, no 403s.
//   - Every request arriving on the LAN listener must carry a valid token.
//     There is no loopback bypass: nothing legitimate arrives loopback on the
//     LAN port, so skipping auth there would only widen the attack surface.
//   - Token supplied via X-Cove-Token header (preferred; stays out of logs) or
//     ?token= query param (needed for mpv on Android, which cannot set headers;
//     token appears in access logs — documented trade-off).
//   - An empty expected token (RemoteAccessEnabled set without a token) always
//     returns 401 rather than granting open access.
//
// The token comparison uses crypto/subtle.ConstantTimeCompare to prevent
// timing side-channels.
func lanTokenMiddleware(st *settings.Store, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		s := st.Get()

		clientToken := r.Header.Get("X-Cove-Token")
		if clientToken == "" {
			clientToken = r.URL.Query().Get("token")
		}

		expected := s.RemoteAccessToken
		// Empty expected token means enabled without a token — refuse rather
		// than granting open access.
		if expected == "" || subtle.ConstantTimeCompare([]byte(expected), []byte(clientToken)) != 1 {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusUnauthorized)
			_ = json.NewEncoder(w).Encode(map[string]string{
				"error": "unauthorized: missing or invalid X-Cove-Token",
			})
			return
		}

		next.ServeHTTP(w, r)
	})
}

// Handle is the running server instance returned by Start. Call Stop() to
// flush pending writes and shut down the HTTP server cleanly.
type Handle struct {
	cancel   context.CancelFunc
	srv      *http.Server
	lib      *library.Library
	act      *activity.Store
	stopOnce sync.Once

	// Remote-access LAN listener — nil when closed. Protected by lanMu.
	lanMu  sync.Mutex
	lanSrv *http.Server
}

// startLAN opens the remote-access LAN listener on addr if it is not already
// running. Safe to call concurrently; redundant calls are no-ops.
func (h *Handle) startLAN(addr string, handler http.Handler) {
	h.lanMu.Lock()
	defer h.lanMu.Unlock()
	if h.lanSrv != nil {
		return // already running
	}
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		log.Println("remote access: bind LAN listener:", err)
		return
	}
	srv := &http.Server{
		Addr:              addr,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
		// ReadTimeout bounds request reads only; long-lived streaming responses
		// (SSE, /api/play) are unaffected.
		ReadTimeout: 30 * time.Second,
		// Don't set WriteTimeout — torrent streaming is long-lived.
	}
	h.lanSrv = srv
	go func() {
		if err := srv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Println("remote access LAN listener:", err)
		}
	}()
	log.Printf("Remote access enabled: LAN listener on %s (token required)", addr)
}

// stopLAN shuts down the remote-access LAN listener if it is running.
// Safe to call when the listener is already closed.
func (h *Handle) stopLAN() {
	h.lanMu.Lock()
	defer h.lanMu.Unlock()
	if h.lanSrv == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := h.lanSrv.Shutdown(ctx); err != nil {
		log.Println("remote access: LAN shutdown:", err)
	}
	h.lanSrv = nil
	log.Println("Remote access disabled: LAN listener closed")
}

// Stop flushes the library's debounced writes and activity log, shuts down
// both HTTP servers (main + LAN if open), and cancels the background context.
// Stop is idempotent — multiple calls are safe.
//
// The Qt shell sends SIGTERM on normal quit (main.cpp's aboutToQuit handler);
// SIGINT covers `./cove` run directly in a terminal during development. Both
// signal handlers call Stop before os.Exit so the last mutation before exit
// isn't lost to the ~1s debounce window the process didn't live long enough
// to see.
func (h *Handle) Stop() {
	h.stopOnce.Do(func() {
		h.cancel()
		h.stopLAN()
		if h.lib != nil {
			h.lib.Flush()
		}
		if h.act != nil {
			h.act.Flush()
		}
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		if err := h.srv.Shutdown(ctx); err != nil {
			log.Println("server shutdown:", err)
		}
	})
}

// Start initialises every subsystem and starts the HTTP server. It returns a
// *Handle on success. Initialisation errors that were previously fatal in
// main() (profiles, torrent client) are returned as errors here so Start is
// safe to call from a gomobile binding that cannot tolerate os.Exit. Errors
// that main() previously logged-and-continued (settings, library, activity,
// imgcache) are still logged-and-continued; Start only fails hard on the two
// that make the server useless.
func Start(cfg Config) (*Handle, error) {
	// DataDir must be set before any package calls utils.ConfigPath — all
	// profile-scoped stores (library, settings, addons, activity, nuvio) and
	// clientsession route through it.
	if cfg.DataDir != "" {
		utils.SetDataDir(cfg.DataDir)
	}

	if cfg.BindAddr == "" {
		cfg.BindAddr = "127.0.0.1:6969"
	}
	// Record the bind address so image-proxy URL builders (tmdb.imgURL,
	// library.rewritePosterURL) produce correct absolute URLs even when
	// COVE_BIND_ADDR differs from the default.
	utils.SetLocalAddr(cfg.BindAddr)

	if cfg.TMDBAPIKey == "" {
		log.Println("warning: TMDB_API_KEY is not set — TMDB metadata requests will fail")
	}

	// Profiles must be initialised first — all other packages are profile-scoped.
	var addonMgr *addons.Manager
	var nuvioMgr *nuvio.Manager
	var st *settings.Store
	var lib *library.Library
	var act *activity.Store
	var traktSrv *traktpkg.Server

	profileStore, err := profiles.New(func(profileID string) {
		// Reload all data stores when the active profile switches.
		if err := lib.SetProfile(profileID); err != nil {
			log.Println("profile switch: reload library:", err)
		}
		if err := st.SetProfile(profileID); err != nil {
			log.Println("profile switch: reload settings:", err)
		}
		if err := addonMgr.SetProfile(profileID); err != nil {
			log.Println("profile switch: reload addons:", err)
		}
		if err := nuvioMgr.SetProfile(profileID); err != nil {
			log.Println("profile switch: reload nuvio repos:", err)
		}
		if err := act.SetProfile(profileID); err != nil {
			log.Println("profile switch: reload activity:", err)
		}
		if traktSrv != nil {
			if err := traktSrv.SetProfile(profileID); err != nil {
				log.Println("profile switch: reload trakt:", err)
			}
		}
	})
	if err != nil {
		return nil, fmt.Errorf("could not init profiles: %w", err)
	}
	activeID := profileStore.ActiveProfileID()

	tmdbClient := tmdb.New(cfg.TMDBAPIKey)

	addonMgr = addons.New(activeID, func(tmdbID int) string {
		id, err := tmdbClient.GetTVIMDBId(tmdbID)
		if err != nil {
			return ""
		}
		return id
	})
	nuvioMgr = nuvio.New(activeID)

	st, err = settings.New(activeID)
	if err != nil {
		log.Println("could not load settings:", err)
	}
	lib, err = library.New(activeID)
	if err != nil {
		log.Println("could not load library:", err)
	}

	// Activity log — per-profile watch-time bucketed by date/hour. Non-fatal:
	// a failure here degrades the insights page but doesn't break playback.
	act, err = activity.New(activeID)
	if err != nil {
		log.Println("could not load activity log:", err)
	}
	// Seed historical data from existing progress rows (idempotent; skips if
	// already done). Must run after library.New and before serving requests.
	act.Backfill(lib.AllProgress())
	// Wire the library to enrich TitlesWatchedThisYear with title/poster data.
	act.SetLibrary(lib)

	// The torrent client is core functionality — if it can't start, there's
	// nothing to stream, so a New failure is fatal.
	p, err := player.New(tmdbClient, addonMgr, nuvioMgr, cfg.TorrentDir, st)
	if err != nil {
		return nil, fmt.Errorf("could not init torrent client: %w", err)
	}

	// Disk-cache proxy for TMDB images (F4) — not profile-scoped (images
	// aren't per-profile data), so unlike the stores above it's constructed
	// once here rather than reloaded on profile switch. Only failure mode is
	// a broken cache dir (permissions, no home dir) — not worth crashing the
	// whole app over; images just 404 through /api/img/ if so.
	imgCache, err := imgcache.New(cfg.CacheDir)
	if err != nil {
		log.Println("could not init image cache:", err)
	}

	mux := http.NewServeMux()

	addonMgr.SetupHandlers(mux)
	tmdbClient.SetupHandlers(mux, addonMgr)
	nuvioMgr.SetupHandlers(mux)
	p.SetupHandlers(mux)
	st.SetupHandlers(mux)
	lib.SetupHandlers(mux)
	act.SetupHandlers(mux)
	profileStore.SetupHandlers(mux)
	updater.SetupHandlers(mux, cfg.Version)
	if imgCache != nil {
		imgCache.SetupHandlers(mux)
	}

	// Supabase auth + sync (no-op if SUPABASE_URL is not set).
	// Env vars take precedence; compiled-in ldflags values are the fallback for
	// release builds where no .env file is present.
	supaCfg := supapkg.ConfigFromEnv(cfg.SupabaseURL, cfg.SupabaseAnonKey)
	supaServer := supapkg.NewServer(supaCfg, profileStore, lib, st, addonMgr, nuvioMgr, act)
	supaServer.SetupHandlers(mux)
	profileStore.SetOnDelete(func(profileID string, uid *string, jwt string) {
		if err := supaServer.CleanupDeletedProfile(jwt, profileID, uid); err != nil {
			log.Printf("profiles: remote cleanup for %s: %v", profileID, err)
		}
	})

	// Trakt.tv integration (no build tag — always compiled). Endpoints return
	// 503 when TraktClientID is not set, mirroring the Supabase noop pattern.
	// RunSync is launched below after ctx is created.
	traktCfg := traktpkg.Config{
		ClientID:     cfg.TraktClientID,
		ClientSecret: cfg.TraktClientSecret,
	}
	traktSrv = traktpkg.New(traktCfg, activeID, lib, st, tmdbClient)
	traktSrv.SetupHandlers(mux)

	disc := discover.New(tmdbClient, lib, st)
	disc.SetupHandlers(mux)

	clientsession.SetupHandlers(mux)
	webstatic.Mount(mux)

	mux.HandleFunc("/api/ping", utils.CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		if err := json.NewEncoder(w).Encode(map[string]string{"status": "ok"}); err != nil {
			log.Println(err)
		}
	}))

	ctx, cancel := context.WithCancel(context.Background())

	// Predictive stream prefetch (Phase E): warms addon/Nuvio caches for
	// continue-watching titles and next episodes so /api/streams answers
	// from cache by the time the user actually presses play. Off entirely
	// when Settings.PrefetchStreams is false (checked every cycle).
	prefetchWorker := prefetch.New(lib, tmdbClient, addonMgr, nuvioMgr, st)
	lib.SetOnNearComplete(prefetchWorker.Notify)
	lib.SetOnProgressSave(act.OnProgressSave)
	go prefetchWorker.Run(ctx)

	// Trakt background sync — runs after ctx exists so RunSync can select on
	// ctx.Done(). The worker respects TraktSyncEnabled each cycle.
	go traktSrv.RunSync(ctx)

	// Reap idle torrents every 5 minutes. The ticker is context-aware so
	// Stop() cancels the loop immediately rather than waiting up to 5 minutes
	// for the next tick.
	go func() {
		ticker := time.NewTicker(5 * time.Minute)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				p.CleanupTorrents()
			}
		}
	}()

	// ── Main listener (loopback) ───────────────────────────────────────────
	// The main listener binds exactly as configured (default 127.0.0.1:6969).
	// It carries NO auth gate: the Qt shell, mpv, and the Android embedded
	// backend all connect on loopback and must never be interrupted. Loopback
	// is inherently network-isolated — only processes on the same host can reach
	// it, so no auth is needed here.
	//
	// Security posture when remote access is DISABLED: only 127.0.0.1:6969
	// (and optionally [::1]:6969) are open. A LAN port scan gets
	// connection-refused on every port — no HTTP surface is visible at all.
	//
	// When remote access is ENABLED: a separate LAN listener opens on
	// 0.0.0.0:<port+1> (see startLAN below) wrapped in lanTokenMiddleware.
	// Disabling closes it; the main listener is never touched.
	host, port, _ := net.SplitHostPort(cfg.BindAddr)

	srv := &http.Server{
		Addr:              cfg.BindAddr,
		Handler:           mux, // no gate — loopback is trusted
		ReadHeaderTimeout: 10 * time.Second,
		// ReadTimeout bounds request reads only; long-lived streaming responses
		// (SSE /api/progress/stream, video via /api/play) are unaffected.
		ReadTimeout: 30 * time.Second,
		// Don't set WriteTimeout — torrent streaming is long-lived.
	}

	// Bind the listener synchronously so Start returns an error immediately
	// on a busy port rather than silently serving nothing from a goroutine.
	ln, err := net.Listen("tcp", cfg.BindAddr)
	if err != nil {
		cancel()
		return nil, fmt.Errorf("bind %s: %w", cfg.BindAddr, err)
	}

	// Chromium may resolve "localhost" to ::1, so also serve on the IPv6
	// loopback when available. Best-effort: failure to bind is not fatal.
	// Only added when BindAddr is 127.0.0.1 (the desktop default) — not
	// relevant for Android or custom bind addresses.
	if host == "127.0.0.1" {
		if l6, err := net.Listen("tcp", net.JoinHostPort("::1", port)); err == nil {
			go func() {
				if err := srv.Serve(l6); err != nil && !errors.Is(err, http.ErrServerClosed) {
					log.Println("ipv6 loopback listener:", err)
				}
			}()
		}
	}

	go func() {
		if err := srv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Println("http server:", err)
		}
	}()

	log.Println("Server running on:", cfg.BindAddr)

	handle := &Handle{
		cancel: cancel,
		srv:    srv,
		lib:    lib,
		act:    act,
	}

	// ── Remote-access LAN listener (Phase 5) ──────────────────────────────
	// A separate TCP socket on 0.0.0.0:<port+1> (default 6970). It only exists
	// while Settings.RemoteAccessEnabled is true — if disabled at startup the
	// port is never opened, and port scans get connection-refused.
	//
	// The OnChange hook fires in a goroutine after every settings write, so
	// toggling remote access takes effect without a restart.
	lanAddr := remoteListenAddr(cfg)
	lanHandler := lanTokenMiddleware(st, mux)

	st.SetOnChange(func(snap settings.Settings) {
		if snap.RemoteAccessEnabled {
			handle.startLAN(lanAddr, lanHandler)
		} else {
			handle.stopLAN()
		}
	})

	// Start the LAN listener now if remote access is already enabled.
	if st.Get().RemoteAccessEnabled {
		handle.startLAN(lanAddr, lanHandler)
	}

	return handle, nil
}
