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

	cfg := server.Config{
		BindAddr:        os.Getenv("COVE_BIND_ADDR"),
		RemoteBindAddr:  os.Getenv("COVE_REMOTE_ADDR"),
		DataDir:         os.Getenv("COVE_DATA_DIR"),
		CacheDir:        os.Getenv("COVE_CACHE_DIR"),
		TorrentDir:      os.Getenv("COVE_TORRENT_DIR"),
		TMDBAPIKey:      apiKey,
		SupabaseURL:     SupabaseURL,
		SupabaseAnonKey: SupabaseAnonKey,
		Version:         Version,
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
