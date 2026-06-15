package main

import (
	"encoding/json"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"github.com/Arcadyi/cove/internal/addons"
	"github.com/Arcadyi/cove/internal/player"
	"github.com/Arcadyi/cove/internal/settings"
	"github.com/Arcadyi/cove/internal/tmdb"
	"github.com/Arcadyi/cove/internal/utils"
	"github.com/joho/godotenv"
)

func main() {
	ex, envErr := os.Executable()
	if envErr == nil {
		envErr := godotenv.Load(filepath.Join(filepath.Dir(ex), ".env"))
		if envErr != nil {
			return
		}
	} else {
		envErr := godotenv.Load()
		if envErr != nil {
			return
		}
	}
	apiKey := os.Getenv("TMDB_API_KEY")
	_, err := addons.AddAddon("https://torrentio.strem.fun")
	if err != nil {
		log.Fatal(err)
		return
	}
	_, err = addons.AddAddon("https://opensubtitles-v3.strem.io")
	if err != nil {
		log.Println("opensubtitles addon unavailable:", err)
	}
	if err := settings.InitSettings(); err != nil {
		log.Println("could not load settings:", err)
	}

	addons.SetupHandlers()
	tmdb.SetupHandlers(apiKey)
	player.SetupHandlers(apiKey)
	settings.SetupHandlers()

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

	log.Println("Server Running on: 6969")
	log.Fatal(http.ListenAndServe(":6969", nil))
}
