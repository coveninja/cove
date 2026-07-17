package main

import (
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"

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
	// process didn't live to see. The Qt shell sends SIGTERM on normal quit
	// (main.cpp's aboutToQuit handler); SIGINT covers `./cove` run directly
	// in a terminal during development.
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-sigCh
		handle.Stop()
		os.Exit(0)
	}()

	// Block forever while the server runs — main must not return.
	select {}
}
