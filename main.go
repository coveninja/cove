package main

import (
	"encoding/json"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"github.com/Arcadyi/cove/internal/addons"
	"github.com/Arcadyi/cove/internal/library"
	"github.com/Arcadyi/cove/internal/player"
	"github.com/Arcadyi/cove/internal/settings"
	"github.com/Arcadyi/cove/internal/tmdb"
	"github.com/Arcadyi/cove/internal/utils"
	"github.com/joho/godotenv"
)

func main() {
	// Load .env if present. A missing .env is NOT fatal: env vars may be set
	// externally (Docker, systemd, CI), and returning here would ignore them
	// and silently kill startup with no log line. Treat a load failure as a
	// warning only.
	if ex, err := os.Executable(); err == nil {
		if err := godotenv.Load(filepath.Join(filepath.Dir(ex), ".env")); err != nil {
			log.Println("no .env next to binary; relying on the environment:", err)
		}
	} else if err := godotenv.Load(); err != nil {
		log.Println("no .env in working dir; relying on the environment:", err)
	}

	apiKey := os.Getenv("TMDB_API_KEY")
	if apiKey == "" {
		log.Println("warning: TMDB_API_KEY is not set — TMDB metadata requests will fail")
	}

	// Addon registration is best-effort. A transient network failure reaching
	// an addon at startup must not prevent the server from booting — the addon
	// is re-contacted on each stream request and can recover then.
	if _, err := addons.AddAddon("https://torrentio.strem.fun"); err != nil {
		log.Println("torrentio addon unavailable:", err)
	}
	if _, err := addons.AddAddon("https://opensubtitles-v3.strem.io"); err != nil {
		log.Println("opensubtitles addon unavailable:", err)
	}

	if err := settings.InitSettings(); err != nil {
		log.Println("could not load settings:", err)
	}
	if err := library.Init(); err != nil {
		log.Println("could not load library:", err)
	}

	addons.SetupHandlers()
	tmdb.SetupHandlers(apiKey)
	player.SetupHandlers(apiKey)
	settings.SetupHandlers()
	library.SetupHandlers()

	// The torrent client is core functionality — if it can't start, there's
	// nothing to stream, so this one stays fatal.
	if err := player.Init(); err != nil {
		log.Fatal("could not init torrent client:", err)
	}

	go func() {
		ticker := time.NewTicker(30 * time.Minute)
		defer ticker.Stop()
		for range ticker.C {
			player.CleanupHLSSessions()
		}
	}()

	http.HandleFunc("/api/ping", utils.CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		err := json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
		if err != nil {
			log.Println(err)
			return
		}
	}))

	srv := &http.Server{
		Addr:              ":6969",
		ReadHeaderTimeout: 10 * time.Second,
		// Don't set WriteTimeout — HLS segment serving and torrent streaming are long-lived
	}

	log.Println("Server Running on: 6969")
	log.Fatal(srv.ListenAndServe())
}
