package main

import (
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/coveninja/cove/internal/server"
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

// TraktClientID and TraktClientSecret are injected at build time via -ldflags.
// They correspond to a Trakt API application registered at
// https://trakt.tv/oauth/applications (redirect_uri "urn:ietf:wg:oauth:2.0:oob").
// During development, set TRAKT_CLIENT_ID / TRAKT_CLIENT_SECRET in .env instead.
// Embedding the secret in installed-app binaries is normal practice for device-
// code / out-of-band flows — the secret does not grant elevated privileges.
var TraktClientID = ""
var TraktClientSecret = ""

// managedParentPID parses the COVE_PARENT_PID environment variable that the
// Compose desktop app sets before spawning the backend. Returns (0, false) for
// an absent value, non-numeric input, or a pid that is not positive.
func managedParentPID(env string) (int, bool) {
	s := strings.TrimSpace(env)
	if s == "" {
		return 0, false
	}
	pid, err := strconv.Atoi(s)
	if err != nil || pid <= 0 {
		return 0, false
	}
	return pid, true
}

// parentHasExited reports whether the process we consider our parent is gone.
// Getppid catches Linux re-parenting to init (pid 1) in the window before the
// old parent is reaped; processAlive catches PID reuse and covers Windows,
// where Getppid is not meaningful. Both checks are needed — either alone would
// miss one of those two races.
func parentHasExited(expectedPID int) bool {
	return os.Getppid() != expectedPID || !processAlive(expectedPID)
}

// monitorParent polls until expectedPID is gone, then calls onExit exactly
// once and returns. interval is kept as a parameter so tests can use a short
// tick without sleeping for a real second.
func monitorParent(pid int, interval time.Duration, onExit func()) {
	t := time.NewTicker(interval)
	defer t.Stop()
	for range t.C {
		if parentHasExited(pid) {
			onExit()
			return
		}
	}
}

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

	traktClientID := os.Getenv("TRAKT_CLIENT_ID")
	if traktClientID == "" {
		traktClientID = TraktClientID
	}
	traktClientSecret := os.Getenv("TRAKT_CLIENT_SECRET")
	if traktClientSecret == "" {
		traktClientSecret = TraktClientSecret
	}

	cfg := server.Config{
		BindAddr:          os.Getenv("COVE_BIND_ADDR"),
		RemoteBindAddr:    os.Getenv("COVE_REMOTE_ADDR"),
		DataDir:           os.Getenv("COVE_DATA_DIR"),
		CacheDir:          os.Getenv("COVE_CACHE_DIR"),
		TorrentDir:        os.Getenv("COVE_TORRENT_DIR"),
		TMDBAPIKey:        apiKey,
		SupabaseURL:       SupabaseURL,
		SupabaseAnonKey:   SupabaseAnonKey,
		TraktClientID:     traktClientID,
		TraktClientSecret: traktClientSecret,
		Version:           Version,
	}

	handle, err := server.Start(cfg)
	if err != nil {
		log.Fatal(err)
	}

	// Flush the library's debounced writes (D3) on a clean shutdown so the
	// last mutation before exit isn't lost to the ~1s debounce window the
	// process didn't live to see. The Compose desktop app sends SIGTERM on
	// normal quit; SIGINT covers `./cove` run directly in a terminal during
	// development. When the app spawns the backend it also sets COVE_PARENT_PID
	// so the backend can detect and exit on its own if the parent is killed
	// without a chance to send SIGTERM — see monitorParent below.
	var shutdownOnce sync.Once
	shutdown := func(reason string) {
		shutdownOnce.Do(func() {
			log.Println("shutting down:", reason)
			// Hard-kill guard: if handle.Stop() wedges, we still exit.
			time.AfterFunc(5*time.Second, func() { os.Exit(1) })
			handle.Stop()
			os.Exit(0)
		})
	}

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)
	go func() {
		sig := <-sigCh
		shutdown("signal " + sig.String())
	}()

	// When the Compose desktop app spawns us it sets COVE_PARENT_PID to its
	// own pid. If the parent is killed without sending SIGTERM (crash, OOM
	// kill, force-quit), the monitor detects the disappearance and initiates
	// shutdown so we don't orphan port 6969.
	if pid, ok := managedParentPID(os.Getenv("COVE_PARENT_PID")); ok {
		go monitorParent(pid, time.Second, func() {
			shutdown("parent process exited")
		})
	}

	// Block forever while the server runs — main must not return.
	select {}
}
