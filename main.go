package main

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
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
	"github.com/coveninja/cove/internal/updater"
	"github.com/coveninja/cove/internal/utils"
	"github.com/joho/godotenv"
)

// Version is injected at build time via -ldflags "-X main.Version=vX.Y.Z".
// The zero value "dev" disables the auto-update check on development builds.
var Version = "dev"

// TmdbApiKey is injected at build time via -ldflags "-X main.TmdbApiKey=...".
// Release builds have it compiled in so no .env or runtime env var is needed.
// During local development, set TMDB_API_KEY in a .env file instead.
var TmdbApiKey = ""

// Supabase credentials are injected at build time via -ldflags for release
// builds. During local development, set them in a .env file instead. Only the
// project URL and the publishable anon key are ever compiled in — anything
// stronger (service key, JWT secret) must never ship inside a user binary.
var SupabaseURL = ""
var SupabaseAnonKey = ""

func main() {
	// Load .env if present — for local development only.
	if ex, err := os.Executable(); err == nil {
		// Clean up stale .new / .old sidecars left by a previous self-update.
		os.Remove(ex + ".new")
		os.Remove(ex + ".old")
		if err := godotenv.Load(filepath.Join(filepath.Dir(ex), ".env")); err != nil {
			log.Println("no .env next to binary; relying on the environment:", err)
		}
	} else if err := godotenv.Load(); err != nil {
		log.Println("no .env in working dir; relying on the environment:", err)
	}

	// Env var overrides the compiled-in key (useful for dev/testing).
	apiKey := os.Getenv("TMDB_API_KEY")
	if apiKey == "" {
		apiKey = TmdbApiKey
	}
	if apiKey == "" {
		log.Println("warning: TMDB_API_KEY is not set — TMDB metadata requests will fail")
	}

	// Profiles must be initialised first — all other packages are profile-scoped.
	var addonMgr *addons.Manager
	var nuvioMgr *nuvio.Manager
	var st *settings.Store
	var lib *library.Library
	var act *activity.Store

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
	})
	if err != nil {
		log.Fatal("could not init profiles:", err)
	}
	activeID := profileStore.ActiveProfileID()

	tmdbClient := tmdb.New(apiKey)

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
	p, err := player.New(tmdbClient, addonMgr, nuvioMgr)
	if err != nil {
		log.Fatal("could not init torrent client:", err)
	}

	// Disk-cache proxy for TMDB images (F4) — not profile-scoped (images
	// aren't per-profile data), so unlike the stores above it's constructed
	// once here rather than reloaded on profile switch. Only failure mode is
	// a broken os.UserCacheDir()/mkdir (permissions, no home dir) — not worth
	// crashing the whole app over; images just 404 through /api/img/ if so.
	imgCache, err := imgcache.New()
	if err != nil {
		log.Println("could not init image cache:", err)
	}

	mux := http.DefaultServeMux

	addonMgr.SetupHandlers(mux)
	tmdbClient.SetupHandlers(mux, addonMgr)
	nuvioMgr.SetupHandlers(mux)
	p.SetupHandlers(mux)
	st.SetupHandlers(mux)
	lib.SetupHandlers(mux)
	act.SetupHandlers(mux)
	profileStore.SetupHandlers(mux)
	updater.SetupHandlers(mux, Version)
	if imgCache != nil {
		imgCache.SetupHandlers(mux)
	}

	// Supabase auth + sync (no-op if SUPABASE_URL is not set).
	// Env vars take precedence; compiled-in ldflags values are the fallback for
	// release builds where no .env file is present.
	supaCfg := supapkg.ConfigFromEnv(SupabaseURL, SupabaseAnonKey)
	supaServer := supapkg.NewServer(supaCfg, profileStore, lib, st, addonMgr)
	supaServer.SetupHandlers(mux)
	profileStore.SetOnDelete(func(profileID string, uid *string, jwt string) {
		if err := supaServer.CleanupDeletedProfile(jwt, profileID, uid); err != nil {
			log.Printf("profiles: remote cleanup for %s: %v", profileID, err)
		}
	})

	disc := discover.New(tmdbClient, lib, st)
	disc.SetupHandlers(mux)

	clientsession.SetupHandlers(mux)

	// Predictive stream prefetch (Phase E): warms addon/Nuvio caches for
	// continue-watching titles and next episodes so /api/streams answers
	// from cache by the time the user actually presses play. Off entirely
	// when Settings.PrefetchStreams is false (checked every cycle).
	prefetchWorker := prefetch.New(lib, tmdbClient, addonMgr, nuvioMgr, st)
	lib.SetOnNearComplete(prefetchWorker.Notify)
	lib.SetOnProgressSave(act.OnProgressSave)
	go prefetchWorker.Run(context.Background())

	go func() {
		ticker := time.NewTicker(30 * time.Minute)
		defer ticker.Stop()
		for range ticker.C {
			p.CleanupTorrents()
		}
	}()

	// Flush the library's debounced writes (D3) on a clean shutdown so the
	// last mutation before exit isn't lost to the ~1s debounce window the
	// process didn't live to see. The Qt shell sends SIGTERM on normal quit
	// (main.cpp's aboutToQuit handler); SIGINT covers `./cove` run directly
	// in a terminal during development.
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-sigCh
		lib.Flush()
		act.Flush()
		os.Exit(0)
	}()

	mux.HandleFunc("/api/ping", utils.CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		err := json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
		if err != nil {
			log.Println(err)
			return
		}
	}))

	srv := &http.Server{
		Addr:              "127.0.0.1:6969",
		ReadHeaderTimeout: 10 * time.Second,
		// Don't set WriteTimeout — torrent streaming is long-lived
	}

	// Chromium may resolve "localhost" to ::1, so also serve on the IPv6
	// loopback when available. Best-effort: failure to bind is not fatal.
	if l6, err := net.Listen("tcp", "[::1]:6969"); err == nil {
		go func() {
			if err := srv.Serve(l6); err != nil && !errors.Is(err, http.ErrServerClosed) {
				log.Println("ipv6 loopback listener:", err)
			}
		}()
	}

	log.Println("Server Running on: 127.0.0.1:6969")
	log.Fatal(srv.ListenAndServe())
}
