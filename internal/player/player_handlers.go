package player

// player_handlers.go contains the HTTP handler methods registered by
// SetupHandlers. Each method corresponds to one route; SetupHandlers is a
// thin routing table in player.go.
//
// Handlers share the Player receiver so they can call all player methods and
// access all package-level helpers (positiveInt, parseSeasonEpisode, etc.).
// The composition order for every route is:
//
//	utils.CorsMiddleware(utils.MethodGuard(method, p.handleXxx))
//
// CorsMiddleware must be outermost so OPTIONS preflights are answered 204
// before MethodGuard can 405 them.

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strconv"
	"sync"
	"time"

	"github.com/coveninja/cove/internal/addons"
	"github.com/coveninja/cove/internal/utils"
)

func (p *Player) handleSubtitles(w http.ResponseWriter, r *http.Request) {
	tmdbID := r.URL.Query().Get("id")
	mediaType := r.URL.Query().Get("type")
	if mediaType == "" {
		mediaType = "movie"
	}
	if mediaType != "movie" && mediaType != "tv" {
		http.Error(w, "type must be movie or tv", http.StatusBadRequest)
		return
	}
	id, validID := positiveInt(tmdbID)
	if !validID {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}

	var imdbID string
	var err error
	if mediaType == "tv" {
		imdbID, err = p.tmdbClient.GetTVIMDBId(id)
	} else {
		imdbID, err = p.tmdbClient.GetIMDBId(id)
	}
	if err != nil || imdbID == "" {
		http.Error(w, "could not get IMDB id", http.StatusInternalServerError)
		return
	}

	stremioID := imdbID
	if mediaType == "tv" {
		season := r.URL.Query().Get("season")
		episode := r.URL.Query().Get("episode")
		if season != "" || episode != "" {
			if _, ok := positiveInt(season); !ok {
				http.Error(w, "invalid season", http.StatusBadRequest)
				return
			}
			if _, ok := positiveInt(episode); !ok {
				http.Error(w, "invalid episode", http.StatusBadRequest)
				return
			}
			stremioID = fmt.Sprintf("%s:%s:%s", imdbID, season, episode)
		}
	}

	allSubs := p.addonMgr.GetAllSubtitles(r.Context(), mediaType, stremioID)
	if allSubs == nil {
		allSubs = []addons.Subtitle{}
	}
	utils.WriteJSON(w, allSubs)
}

// handleStreams serves GET /api/streams?id=<tmdbID>&type=movie|tv[&season=N&episode=N].
func (p *Player) handleStreams(w http.ResponseWriter, r *http.Request) {
	tmdbID := r.URL.Query().Get("id")
	mediaType := r.URL.Query().Get("type")
	if mediaType == "" {
		mediaType = "movie"
	}
	if mediaType != "movie" && mediaType != "tv" {
		http.Error(w, "type must be movie or tv", http.StatusBadRequest)
		return
	}

	id, validID := positiveInt(tmdbID)
	if !validID {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}
	var err error

	var imdbID string
	if mediaType == "tv" {
		imdbID, err = p.tmdbClient.GetTVIMDBId(id)
	} else {
		imdbID, err = p.tmdbClient.GetIMDBId(id)
	}
	if err != nil || imdbID == "" {
		http.Error(w, "could not get IMDB id", http.StatusInternalServerError)
		return
	}

	// For TV, append season:episode to build the Stremio stream ID
	stremioID := imdbID
	var seasonNum, episodeNum *int
	if mediaType == "tv" {
		season := r.URL.Query().Get("season")
		episode := r.URL.Query().Get("episode")
		if season == "" || episode == "" {
			http.Error(w, "season and episode are required for tv streams", http.StatusBadRequest)
			return
		}
		sv, seasonOK := positiveInt(season)
		ev, episodeOK := positiveInt(episode)
		if !seasonOK || !episodeOK {
			http.Error(w, "season and episode must be positive integers", http.StatusBadRequest)
			return
		}
		stremioID = fmt.Sprintf("%s:%d:%d", imdbID, sv, ev)
		seasonNum, episodeNum = &sv, &ev
	}

	// Stremio addons and Nuvio scrapers are independent legs with no shared
	// state — run them concurrently so the response latency is max(leg),
	// not sum(leg). (A4)
	ctx := r.Context()
	var addonStreams []addons.Stream
	var addonErr error
	var nuvioStreams []addons.Stream

	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		addonStreams, addonErr = p.addonMgr.GetAllStreams(ctx, mediaType, stremioID)
	}()

	// Skip the extra TMDB lookup entirely for the common case (no Nuvio
	// scrapers enabled) — it exists purely to feed Nuvio's metadata.
	if p.nuvioMgr != nil && p.nuvioMgr.HasEnabledScrapers() {
		wg.Add(1)
		go func() {
			defer wg.Done()
			var title string
			var year int
			if media, mErr := p.tmdbClient.GetMediaByID(id, mediaType); mErr == nil && media != nil {
				title = utils.FirstNonEmpty(media.Title, media.Name)
				year = utils.ParseMediaYear(utils.FirstNonEmpty(media.Released, media.FirstAir))
			}
			nuvioStreams = p.nuvioMgr.GetStreams(ctx, mediaType, id, imdbID, title, year, seasonNum, episodeNum)
		}()
	}
	wg.Wait()

	if addonErr != nil {
		http.Error(w, addonErr.Error(), http.StatusInternalServerError)
		return
	}

	// Response stays a single JSON array, nuvio appended after addons
	// (frontend contract unchanged).
	streams := addonStreams
	if streams == nil {
		streams = []addons.Stream{}
	}
	streams = append(streams, nuvioStreams...)

	// Register every direct-URL stream we're about to offer — /api/play
	// only accepts URLs from this registry (see rememberStream).
	for _, s := range streams {
		p.rememberStream(s.URL, s.Headers)
	}

	utils.WriteJSON(w, streams)
}

// handleStreamsProbe serves POST /api/streams/probe — HEAD-checks a batch of
// direct-URL streams and reports which are reachable. Only URLs previously
// issued by /api/streams (in the rememberStream registry) are probed —
// unknown URLs return alive:false without making any outbound request, so
// this can't be used as a proxy.
//
// Request:  {"streams":[{"url":"..."}], "timeoutMs":700}
// Response: {"results":[{"url":"...","alive":true,"contentLength":123}]}
func (p *Player) handleStreamsProbe(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Streams []struct {
			URL string `json:"url"`
		} `json:"streams"`
		TimeoutMs int `json:"timeoutMs"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&req); err != nil {
		http.Error(w, "invalid body: "+err.Error(), http.StatusBadRequest)
		return
	}

	if len(req.Streams) == 0 || len(req.Streams) > 10 {
		http.Error(w, "streams must contain 1–10 entries", http.StatusBadRequest)
		return
	}

	timeoutMs := req.TimeoutMs
	if timeoutMs == 0 {
		timeoutMs = 700
	}
	if timeoutMs < 100 {
		timeoutMs = 100
	}
	if timeoutMs > 800 {
		timeoutMs = 800
	}

	type probeResult struct {
		URL           string `json:"url"`
		Alive         bool   `json:"alive"`
		ContentLength int64  `json:"contentLength,omitempty"`
	}

	results := make([]probeResult, len(req.Streams))
	for i, s := range req.Streams {
		results[i].URL = s.URL
	}

	ctx, cancel := context.WithTimeout(r.Context(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()

	var wg sync.WaitGroup
	for i, s := range req.Streams {
		headers, known := p.lookupStream(s.URL)
		if !known {
			continue // results[i].Alive stays false — not our URL
		}
		wg.Add(1)
		go func(idx int, streamURL string, hdrs map[string]string) {
			defer wg.Done()
			alive, cl := p.probeURL(ctx, streamURL, hdrs)
			results[idx].Alive = alive
			if cl > 0 {
				results[idx].ContentLength = cl
			}
		}(i, s.URL, headers)
	}
	wg.Wait()

	utils.WriteJSON(w, struct {
		Results []probeResult `json:"results"`
	}{Results: results})
}

func (p *Player) handlePlay(w http.ResponseWriter, r *http.Request) {
	infoHash := r.URL.Query().Get("hash")
	streamURL := r.URL.Query().Get("url")

	// Direct http(s) sources: redirect mpv straight to them, unless the
	// origin needs extra headers (e.g. Referer) that a redirect can't
	// carry — in that case proxy the request instead. Only URLs this
	// backend itself returned from /api/streams are accepted; anything
	// else would make this an open redirect / request proxy.
	if streamURL != "" {
		if u, err := url.Parse(streamURL); err != nil ||
			(u.Scheme != "http" && u.Scheme != "https") {
			http.Error(w, "invalid stream url", http.StatusBadRequest)
			return
		}
		headers, known := p.lookupStream(streamURL)
		if !known {
			// Loud on purpose: mpv sees this 403 but the UI only shows a
			// generic spinner-then-fail, and the registry losing an entry
			// (backend restart, 30-min TTL) is otherwise undiagnosable.
			log.Printf("play: rejected stream url not in registry (restarted backend or entry expired?): %.120s", streamURL)
			http.Error(w, "unknown stream url (list streams first)", http.StatusForbidden)
			return
		}
		if len(headers) > 0 {
			p.proxyStream(streamURL, headers, w, r)
			return
		}
		http.Redirect(w, r, streamURL, http.StatusTemporaryRedirect)
		return
	}

	// Torrent sources: stream the selected file (largest, or the matching
	// episode of a season pack — see selectFile/D1) as seekable http. mpv
	// handles every codec/container natively, so no transcoding involved.
	if infoHash != "" {
		season, episode := parseSeasonEpisode(r)
		fileIdx := parseFileIdx(r)
		p.StreamTorrent(infoHash, season, episode, fileIdx, w, r)
		return
	}

	http.Error(w, "missing hash or url", http.StatusBadRequest)
}

// handlePrefetchDownload serves POST /api/prefetch-download?hash=&season=&episode=
// — background-downloads the next episode's torrent (F7) ahead of the user
// getting there. Fires PrefetchTorrent in a goroutine and returns immediately:
// metadata fetch alone can take up to 45s (getTorrentFile's GotInfo timeout),
// and this is a fire-and-forget warm, not something the caller waits on. Same
// policy as /api/play?hash= — a hash play isn't gated on rememberStream, so
// this isn't either — but the hash format IS validated here (unlike /api/play,
// which just lets AddMagnet reject garbage) since a malformed value would
// otherwise burn a full metadata-fetch attempt in the background goroutine
// before failing.
//
// A single-flight guard (prefetchInFlight) ensures at most one goroutine is
// running at a time — PrefetchTorrent's bookkeeping is a single slot, so
// concurrent metadata fetches are never useful and only waste resources.
// Callers that arrive while a fetch is already in flight get 202 with
// {"started": false} and should not retry immediately.
func (p *Player) handlePrefetchDownload(w http.ResponseWriter, r *http.Request) {
	infoHash := r.URL.Query().Get("hash")
	if err := validateInfoHash(infoHash); err != nil {
		http.Error(w, "invalid or missing ?hash= (expected a 40-char hex infohash)", http.StatusBadRequest)
		return
	}
	season, episode := parseSeasonEpisode(r)
	fileIdx := parseFileIdx(r)

	if !p.prefetchInFlight.CompareAndSwap(false, true) {
		// Another PrefetchTorrent is already running; a second concurrent
		// fetch would race on the same single-slot bookkeeping and waste a
		// full metadata-fetch attempt for nothing.
		utils.WriteJSONStatus(w, http.StatusAccepted, map[string]bool{"started": false})
		return
	}
	go func() {
		defer p.prefetchInFlight.Store(false)
		if err := p.PrefetchTorrent(infoHash, season, episode, fileIdx); err != nil {
			log.Println("prefetch-download:", err)
		}
	}()

	utils.WriteJSONStatus(w, http.StatusAccepted, map[string]bool{"started": true})
}

// handleProgress serves GET /api/progress — legacy polling endpoint kept for
// compatibility; prefer /api/progress/stream (SSE).
func (p *Player) handleProgress(w http.ResponseWriter, r *http.Request) {
	hash := r.URL.Query().Get("hash")
	season, episode := parseSeasonEpisode(r)
	fileIdx := parseFileIdx(r)
	utils.WriteJSON(w, p.GetProgress(hash, season, episode, fileIdx))
}

// handleProgressStream serves GET /api/progress/stream — a Server-Sent Events
// stream that pushes torrent download progress every 2 s for the lifetime of
// the connection. The SSE wire format requires an http.Flusher; if the
// ResponseWriter doesn't support it the handler returns 500 early.
func (p *Player) handleProgressStream(w http.ResponseWriter, r *http.Request) {
	hash := r.URL.Query().Get("hash")
	season, episode := parseSeasonEpisode(r)
	fileIdx := parseFileIdx(r)
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")

	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming not supported", http.StatusInternalServerError)
		return
	}

	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-r.Context().Done():
			return
		case <-ticker.C:
			data, _ := json.Marshal(p.GetProgress(hash, season, episode, fileIdx))
			fmt.Fprintf(w, "data: %s\n\n", data)
			flusher.Flush()
		}
	}
}

// handleSpeedtest serves GET /api/speedtest — streams a fixed-size payload so
// the client can measure raw download throughput for the "Match My Internet
// Speed" stream-selection mode. Not a rigorous benchmark (single connection,
// no compression, local network only) but good enough as a rough guide.
func (p *Player) handleSpeedtest(w http.ResponseWriter, r *http.Request) {
	const payloadSize = 25 * 1024 * 1024 // 25 MiB
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.Itoa(payloadSize))
	w.Header().Set("Cache-Control", "no-store")

	buf := make([]byte, 1<<20) // 1 MiB chunks
	flusher, _ := w.(http.Flusher)
	for written := 0; written < payloadSize; {
		n := len(buf)
		if remaining := payloadSize - written; remaining < n {
			n = remaining
		}
		if _, err := w.Write(buf[:n]); err != nil {
			return // client aborted — nothing to clean up
		}
		written += n
		if flusher != nil {
			flusher.Flush()
		}
	}
}

func (p *Player) handleSubtitleProxy(w http.ResponseWriter, r *http.Request) {
	rawURL := r.URL.Query().Get("url")
	if rawURL == "" {
		http.Error(w, "missing url", http.StatusBadRequest)
		return
	}
	// Subtitle URLs come from third-party addons; only plain http(s) to
	// public hosts is allowed (SafeHTTPClient refuses local/private IPs).
	if _, err := utils.ValidatePublicURL(rawURL); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, rawURL, nil)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	resp, err := utils.SafeHTTPClient.Do(req)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	writeSubtitleResponse(w, resp)
}
