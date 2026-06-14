package main

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/Arcadyi/cove/internal/addons"
	"github.com/Arcadyi/cove/internal/player"
	"github.com/Arcadyi/cove/internal/tmdb"
	"github.com/joho/godotenv"
)

func corsMiddleware(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type")

		// Answer preflight and stop — don't call next
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}

		next(w, r)
	}
}

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

	http.HandleFunc("/api/subtitles", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		tmdbID := r.URL.Query().Get("id")
		mediaType := r.URL.Query().Get("type")
		id := 0
		if _, err := fmt.Sscanf(tmdbID, "%d", &id); err != nil {
			http.Error(w, "invalid id", http.StatusBadRequest)
			return
		}

		var imdbID string
		var err error
		if mediaType == "tv" {
			imdbID, err = tmdb.GetTVIMDBId(id, apiKey)
		} else {
			imdbID, err = tmdb.GetIMDBId(id, apiKey)
		}
		if err != nil || imdbID == "" {
			http.Error(w, "could not get IMDB id", http.StatusInternalServerError)
			return
		}

		stremioID := imdbID
		if mediaType == "tv" {
			season := r.URL.Query().Get("season")
			episode := r.URL.Query().Get("episode")
			if season != "" && episode != "" {
				stremioID = fmt.Sprintf("%s:%s:%s", imdbID, season, episode)
			}
		}

		var allSubs []addons.Subtitle
		for _, addon := range addons.GetAddons() {
			subs, err := addons.FetchSubtitles(addon.URL, mediaType, stremioID)
			if err != nil {
				continue
			}
			allSubs = append(allSubs, subs...)
		}
		if allSubs == nil {
			allSubs = []addons.Subtitle{}
		}
		w.Header().Set("Content-Type", "application/json")
		err = json.NewEncoder(w).Encode(allSubs)
		if err != nil {
			log.Println(err)
			return
		}
	}))

	http.HandleFunc("/api/debug", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		configured := addons.GetAddons()
		imdbID, err := tmdb.GetIMDBId(27205, apiKey)
		err = json.NewEncoder(w).Encode(map[string]interface{}{
			"addons":   configured,
			"imdb_id":  imdbID,
			"imdb_err": fmt.Sprintf("%v", err),
		})
		if err != nil {
			log.Println(err)
			return
		}
	}))

	http.HandleFunc("/api/addons", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		err := json.NewEncoder(w).Encode(addons.GetAddons())
		if err != nil {
			log.Println(err)
			return
		}
	}))

	http.HandleFunc("/api/addons/add", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		url := r.URL.Query().Get("url")
		addon, err := addons.AddAddon(url)
		if err != nil {
			http.Error(w, "could not fetch addon manifest: "+err.Error(), http.StatusBadRequest)
			return
		}
		err = json.NewEncoder(w).Encode(addon)
		if err != nil {
			log.Println(err)
			return
		}
	}))

	// /api/streams?id=<tmdbID>&type=movie|tv[&season=N&episode=N]
	http.HandleFunc("/api/streams", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		tmdbID := r.URL.Query().Get("id")
		mediaType := r.URL.Query().Get("type")
		if mediaType == "" {
			mediaType = "movie"
		}

		id := 0
		_, err := fmt.Sscanf(tmdbID, "%d", &id)
		if err != nil {
			http.Error(w, "invalid id", http.StatusBadRequest)
			return
		}

		// Resolve IMDB ID based on media type
		var imdbID string
		if mediaType == "tv" {
			imdbID, err = tmdb.GetTVIMDBId(id, apiKey)
		} else {
			imdbID, err = tmdb.GetIMDBId(id, apiKey)
		}
		if err != nil || imdbID == "" {
			http.Error(w, "could not get IMDB id", http.StatusInternalServerError)
			return
		}

		// For TV, append season:episode to build the Stremio stream ID
		stremioID := imdbID
		if mediaType == "tv" {
			season := r.URL.Query().Get("season")
			episode := r.URL.Query().Get("episode")
			if season == "" || episode == "" {
				http.Error(w, "season and episode are required for tv streams", http.StatusBadRequest)
				return
			}
			stremioID = fmt.Sprintf("%s:%s:%s", imdbID, season, episode)
		}

		streams, err := addons.GetAllStreams(mediaType, stremioID)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		if streams == nil {
			streams = []addons.Stream{}
		}
		err = json.NewEncoder(w).Encode(streams)
		if err != nil {
			log.Println(err)
			return
		}
	}))

	http.HandleFunc("/api/ping", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		err := json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
		if err != nil {
			log.Println(err)
			return
		}
	}))

	http.HandleFunc("/api/keywords", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		query := r.URL.Query().Get("q")
		if query == "" {
			http.Error(w, "missing query", http.StatusBadRequest)
			return
		}
		keywords, err := tmdb.SuggestKeywords(query, apiKey)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		if err := json.NewEncoder(w).Encode(keywords); err != nil {
			log.Println(err)
		}
	}))

	http.HandleFunc("/api/quality/batch", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		idsParam := r.URL.Query().Get("ids")
		if idsParam == "" {
			http.Error(w, "missing ids", http.StatusBadRequest)
			return
		}

		idStrs := strings.Split(idsParam, ",")
		sem := make(chan struct{}, 5)

		type entry struct {
			ID      string `json:"id"`
			Quality string `json:"quality"`
		}

		w.Header().Set("Content-Type", "application/x-ndjson")
		w.Header().Set("X-Accel-Buffering", "no")
		flusher, canFlush := w.(http.Flusher)

		var mu sync.Mutex
		var wg sync.WaitGroup
		enc := json.NewEncoder(w)

		for _, s := range idStrs {
			id, err := strconv.Atoi(strings.TrimSpace(s))
			if err != nil {
				continue
			}
			wg.Add(1)
			go func(tmdbID int) {
				defer wg.Done()
				sem <- struct{}{}
				defer func() { <-sem }()

				imdbID, err := tmdb.GetIMDBId(tmdbID, apiKey)
				if err != nil || imdbID == "" {
					return
				}
				streams, err := addons.GetAllStreams("movie", imdbID)
				if err != nil || len(streams) == 0 {
					return
				}
				q := addons.GetMaxQuality(streams)
				if q == "" {
					return
				}
				mu.Lock()
				err = enc.Encode(entry{ID: strconv.Itoa(tmdbID), Quality: q})
				if err != nil {
					log.Println(err)
				}
				if canFlush {
					flusher.Flush()
				}
				mu.Unlock()
			}(id)
		}

		wg.Wait()
	}))

	http.HandleFunc("/api/search", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		query := r.URL.Query().Get("q")
		if query == "" {
			http.Error(w, "missing query", http.StatusBadRequest)
			return
		}

		regular, err := tmdb.Search(query, apiKey)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		byKeyword, _ := tmdb.SearchByKeywords(query, apiKey)

		seen := make(map[string]bool)
		merged := make([]tmdb.Media, 0, len(regular)+len(byKeyword))
		for _, m := range regular {
			key := fmt.Sprintf("%d-%s", m.ID, m.MediaType)
			seen[key] = true
			merged = append(merged, m)
		}
		for _, m := range byKeyword {
			key := fmt.Sprintf("%d-%s", m.ID, m.MediaType)
			if !seen[key] {
				seen[key] = true
				merged = append(merged, m)
			}
		}

		w.Header().Set("Content-Type", "application/json")
		if err := json.NewEncoder(w).Encode(merged); err != nil {
			log.Println(err)
		}
	}))

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

	http.HandleFunc("/api/probe", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		hash := r.URL.Query().Get("hash")
		streamURL := r.URL.Query().Get("url")

		var probeInput string
		switch {
		case hash != "":
			probeInput = fmt.Sprintf("http://localhost:6969/api/play?hash=%s", hash)
		case streamURL != "":
			probeInput = streamURL
		default:
			http.Error(w, "missing hash or url", http.StatusBadRequest)
			return
		}

		audioTracks, err := player.ProbeAudioTracks(probeInput)
		if err != nil {
			log.Println("probe audio error:", err)
			audioTracks = []player.AudioTrackInfo{}
		}

		subtitleTracks, err := player.ProbeSubtitleTracks(probeInput)
		if err != nil {
			log.Println("probe subtitle error:", err)
			subtitleTracks = []player.SubtitleTrackInfo{}
		}

		duration, err := player.ProbeDuration(probeInput)
		if err != nil {
			log.Println("probe duration error:", err)
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"audio":     audioTracks,
			"subtitles": subtitleTracks,
			"duration":  duration,
		})
	}))

	http.HandleFunc("/api/subtitle/extract", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		hash := r.URL.Query().Get("hash")
		streamURL := r.URL.Query().Get("url")
		index, err := strconv.Atoi(r.URL.Query().Get("index"))
		if err != nil {
			http.Error(w, "invalid index", http.StatusBadRequest)
			return
		}
		var input string
		switch {
		case hash != "":
			input = fmt.Sprintf("http://localhost:6969/api/play?hash=%s", hash)
		case streamURL != "":
			input = streamURL
		default:
			http.Error(w, "missing hash or url", http.StatusBadRequest)
			return
		}
		player.ExtractSubtitle(input, index, w)
	}))

	http.HandleFunc("/api/play", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		infoHash := r.URL.Query().Get("hash")
		streamURL := r.URL.Query().Get("url")
		audioStr := r.URL.Query().Get("audio")

		if audioStr != "" {
			audioIndex, err := strconv.Atoi(audioStr)
			if err == nil {
				// For torrents, point ffmpeg at the local stream endpoint so it can seek via range requests.
				// For direct URLs, pass the URL straight through.
				var input string
				if streamURL != "" {
					input = streamURL
				} else if infoHash != "" {
					input = fmt.Sprintf("http://localhost:6969/api/play?hash=%s", infoHash)
				} else {
					http.Error(w, "missing hash or url", http.StatusBadRequest)
					return
				}
				player.StreamWithAudio(input, audioIndex, w, r)
				return
			}
		}

		if streamURL != "" {
			http.Redirect(w, r, streamURL, http.StatusTemporaryRedirect)
			return
		}

		if infoHash != "" {
			player.StreamTorrent(infoHash, w, r)
			return
		}

		http.Error(w, "missing hash or url", http.StatusBadRequest)
	}))

	http.HandleFunc("/api/trailer", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		tmdbID := r.URL.Query().Get("id")
		mediaType := r.URL.Query().Get("type")
		id := 0
		_, err := fmt.Sscanf(tmdbID, "%d", &id)
		if err != nil {
			http.Error(w, "invalid id", http.StatusBadRequest)
			return
		}
		trailer, err := tmdb.GetTrailer(id, mediaType, apiKey)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		err = json.NewEncoder(w).Encode(map[string]string{"url": trailer})
		if err != nil {
			log.Println(err)
		}
	}))

	http.HandleFunc("/api/clips", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		tmdbIDStr := r.URL.Query().Get("id")
		mediaType := r.URL.Query().Get("type")

		id, err := strconv.Atoi(tmdbIDStr)
		if err != nil {
			http.Error(w, "invalid id", http.StatusBadRequest)
			return
		}

		if mediaType == "" {
			http.Error(w, "missing media type", http.StatusBadRequest)
			return
		}

		clips, err := tmdb.GetClips(id, mediaType, apiKey)
		if err != nil {
			http.Error(w, "failed to fetch data", http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		err = json.NewEncoder(w).Encode(map[string][]string{"urls": clips})
		if err != nil {
			log.Println(err)
			return
		}
	}))

	http.HandleFunc("/api/images", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		tmdbID := r.URL.Query().Get("id")
		mediaType := r.URL.Query().Get("type")
		id := 0
		_, err := fmt.Sscanf(tmdbID, "%d", &id)
		if err != nil {
			http.Error(w, "invalid id", http.StatusBadRequest)
			return
		}
		images, err := tmdb.GetImages(id, mediaType, apiKey)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		err = json.NewEncoder(w).Encode(images)
		if err != nil {
			log.Println(err)
		}
	}))

	// POST /api/hls/start — starts an HLS session, returns the session ID
	http.HandleFunc("/api/hls/start", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Input    string                  `json:"input"`
			Tracks   []player.AudioTrackInfo `json:"tracks"`
			Duration float64                 `json:"duration"` // Added duration
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		sessionID, err := player.StartHLSSession(body.Input, body.Tracks, body.Duration)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		err = json.NewEncoder(w).Encode(map[string]string{"sessionID": sessionID})
		if err != nil {
			log.Println(err)
			return
		}
	}))

	// GET /api/hls/{sessionID}/{file} — serves master playlist, sub-playlists, and segments
	http.HandleFunc("/api/hls/", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		parts := strings.SplitN(strings.TrimPrefix(r.URL.Path, "/api/hls/"), "/", 2)
		if len(parts) != 2 {
			http.Error(w, "invalid path", http.StatusBadRequest)
			return
		}
		player.ServeHLSFile(parts[0], parts[1], w, r)
	}))

	http.HandleFunc("/api/details", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		tmdbID := r.URL.Query().Get("id")
		mediaType := r.URL.Query().Get("type")
		id := 0
		_, err := fmt.Sscanf(tmdbID, "%d", &id)
		if err != nil {
			http.Error(w, "invalid id", http.StatusBadRequest)
			return
		}
		details, err := tmdb.GetDetails(id, mediaType, apiKey)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		err = json.NewEncoder(w).Encode(details)
		if err != nil {
			log.Println(err)
		}
	}))

	http.HandleFunc("/api/similar", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		id, _ := strconv.Atoi(r.URL.Query().Get("id"))
		mediaType := r.URL.Query().Get("type")
		results, err := tmdb.GetSimilar(id, mediaType, apiKey)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		err = json.NewEncoder(w).Encode(results)
		if err != nil {
			log.Println(err)
			return
		}
	}))

	http.HandleFunc("/api/progress", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		hash := r.URL.Query().Get("hash")
		err := json.NewEncoder(w).Encode(player.GetProgress(hash))
		if err != nil {
			log.Println(err)
		}
	}))

	http.HandleFunc("/api/logos", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		tmdbID := r.URL.Query().Get("id")
		mediaType := r.URL.Query().Get("type")
		id := 0
		_, err := fmt.Sscanf(tmdbID, "%d", &id)
		if err != nil {
			http.Error(w, "invalid id", http.StatusBadRequest)
			return
		}
		logos, err := tmdb.GetLogos(id, mediaType, apiKey)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		err = json.NewEncoder(w).Encode(logos)
		if err != nil {
			log.Println(err)
		}
	}))

	http.HandleFunc("/api/imdb", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		tmdbID := r.URL.Query().Get("id")
		id := 0
		_, err := fmt.Sscanf(tmdbID, "%d", &id)
		if err != nil {
			log.Println(err)
			return
		}
		imdbID, err := tmdb.GetIMDBId(id, apiKey)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		err = json.NewEncoder(w).Encode(map[string]string{"imdb_id": imdbID})
		if err != nil {
			log.Println(err)
			return
		}
	}))

	// GET /api/tv/seasons?id=<tmdbID>
	// Returns the list of seasons for a TV show.
	http.HandleFunc("/api/tv/seasons", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		tmdbID := r.URL.Query().Get("id")
		id := 0
		if _, err := fmt.Sscanf(tmdbID, "%d", &id); err != nil {
			http.Error(w, "invalid id", http.StatusBadRequest)
			return
		}
		seasons, err := tmdb.GetSeasons(id, apiKey)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		if err := json.NewEncoder(w).Encode(seasons); err != nil {
			log.Println(err)
		}
	}))

	// GET /api/tv/episodes?id=<tmdbID>&season=<seasonNumber>
	// Returns the episodes for a given season of a TV show.
	http.HandleFunc("/api/tv/episodes", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		tmdbID := r.URL.Query().Get("id")
		seasonStr := r.URL.Query().Get("season")
		id := 0
		if _, err := fmt.Sscanf(tmdbID, "%d", &id); err != nil {
			http.Error(w, "invalid id", http.StatusBadRequest)
			return
		}
		season, err := strconv.Atoi(seasonStr)
		if err != nil || season < 1 {
			http.Error(w, "invalid season", http.StatusBadRequest)
			return
		}
		episodes, err := tmdb.GetEpisodes(id, season, apiKey)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		if err := json.NewEncoder(w).Encode(episodes); err != nil {
			log.Println(err)
		}
	}))

	http.HandleFunc("/api/subtitle-proxy", corsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		rawURL := r.URL.Query().Get("url")
		if rawURL == "" {
			http.Error(w, "missing url", http.StatusBadRequest)
			return
		}
		resp, err := http.Get(rawURL)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadGateway)
			return
		}
		defer func(Body io.ReadCloser) {
			err := Body.Close()
			if err != nil {
				log.Println(err)
			}
		}(resp.Body)

		body, err := io.ReadAll(resp.Body)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "text/vtt; charset=utf-8")
		w.Header().Set("Access-Control-Allow-Origin", "*")

		// If it's SRT, convert to WebVTT (browser only accepts VTT for <track>)
		content := string(body)
		if !strings.HasPrefix(strings.TrimSpace(content), "WEBVTT") {
			content = SrtToVTT(content)
		}
		_, err = fmt.Fprint(w, content)
		if err != nil {
			log.Println(err)
			return
		}
	}))

	log.Println("Server Running on: 6969")
	log.Fatal(http.ListenAndServe(":6969", nil))
}

func SrtToVTT(srt string) string {
	// SRT timestamps use commas; VTT uses dots. That's the only difference.
	vtt := strings.ReplaceAll(srt, ",", ".")
	return "WEBVTT\n\n" + vtt
}
