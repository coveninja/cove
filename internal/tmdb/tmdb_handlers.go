package tmdb

// tmdb_handlers.go contains the HTTP handler methods registered by
// SetupHandlers. Each method corresponds to one route; SetupHandlers is now a
// thin routing table in tmdb.go.
//
// Handlers share the Client receiver so they can call all client methods and
// access addonMgr (stored on Client after the SetupHandlers call). The
// composition order for every route is:
//
//	utils.CorsMiddleware(utils.MethodGuard(http.MethodGet, c.handleXxx))
//
// CorsMiddleware must be outermost so OPTIONS preflights are answered 204
// before MethodGuard can 405 them.

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strconv"
	"strings"
	"sync"

	"github.com/coveninja/cove/internal/addons"
	"github.com/coveninja/cove/internal/utils"
)

func (c *Client) handleKeywords(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query().Get("q")
	if query == "" {
		http.Error(w, "missing query", http.StatusBadRequest)
		return
	}
	keywords, err := c.SuggestKeywords(query)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	utils.WriteJSON(w, keywords)
}

func (c *Client) handleSearch(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query().Get("q")
	if query == "" {
		http.Error(w, "missing query", http.StatusBadRequest)
		return
	}

	regular, err := c.Search(query)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	byKeyword, _ := c.SearchByKeywords(query)

	seen := make(map[string]bool)
	merged := make([]Media, 0, len(regular)+len(byKeyword))
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

	utils.WriteJSON(w, merged)
}

// GET /api/search/multi?q=<query> — sectioned results (titles split into
// movies/tv, plus people and providers).
func (c *Client) handleSearchMulti(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query().Get("q")
	if query == "" {
		http.Error(w, "missing query", http.StatusBadRequest)
		return
	}

	results, err := c.MultiSearch(query)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	utils.WriteJSON(w, results)
}

// GET /api/person?id=<personID> — bio + filmography for the person overlay.
func (c *Client) handlePerson(w http.ResponseWriter, r *http.Request) {
	id, ok := parseTMDBID(r.URL.Query().Get("id"))
	if !ok {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}
	person, err := c.GetPerson(id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	utils.WriteJSON(w, person)
}

// GET /api/provider?id=<providerID>&limit=<n> — popular titles on a provider
// (US region). Blends movies and TV.
func (c *Client) handleProvider(w http.ResponseWriter, r *http.Request) {
	id, ok := parseTMDBID(r.URL.Query().Get("id"))
	if !ok {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}
	limit := 40
	if l, err := strconv.Atoi(r.URL.Query().Get("limit")); err == nil && l > 0 {
		limit = l
	}
	titles, err := c.ProviderTitles(id, limit)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	utils.WriteJSON(w, titles)
}

func (c *Client) handleImages(w http.ResponseWriter, r *http.Request) {
	tmdbIDStr := r.URL.Query().Get("id")
	mediaType := r.URL.Query().Get("type")

	if tmdbIDStr == "" || mediaType == "" {
		http.Error(w, "missing required parameters", http.StatusBadRequest)
		return
	}

	if !utils.ValidMediaType(mediaType) {
		http.Error(w, "invalid media type", http.StatusBadRequest)
		return
	}

	id, ok := parseTMDBID(tmdbIDStr)
	if !ok {
		http.Error(w, "invalid id format", http.StatusBadRequest)
		return
	}

	images, err := c.GetImages(id, mediaType)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	utils.WriteJSON(w, images)
}

func (c *Client) handleVideos(w http.ResponseWriter, r *http.Request) {
	tmdbIDStr := r.URL.Query().Get("id")
	mediaType := r.URL.Query().Get("type")

	if tmdbIDStr == "" || mediaType == "" {
		http.Error(w, "missing required parameters", http.StatusBadRequest)
		return
	}

	if !utils.ValidMediaType(mediaType) {
		http.Error(w, "invalid media type", http.StatusBadRequest)
		return
	}

	id, ok := parseTMDBID(tmdbIDStr)
	if !ok {
		http.Error(w, "invalid id format", http.StatusBadRequest)
		return
	}

	videos, err := c.GetVideos(id, mediaType)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	utils.WriteJSON(w, videos)
}

// GET /api/media?id=<tmdbID>&type=<movie|tv>
// Returns a single, fully-populated Media object by ID — for callers that
// only have a bare tmdb_id (e.g. from a LibraryEntry) and need the real
// TMDB object rather than reconstructing a partial one client-side.
func (c *Client) handleMedia(w http.ResponseWriter, r *http.Request) {
	tmdbIDStr := r.URL.Query().Get("id")
	mediaType := r.URL.Query().Get("type")

	if tmdbIDStr == "" || mediaType == "" {
		http.Error(w, "missing required parameters", http.StatusBadRequest)
		return
	}
	if !utils.ValidMediaType(mediaType) {
		http.Error(w, "invalid media type", http.StatusBadRequest)
		return
	}

	id, ok := parseTMDBID(tmdbIDStr)
	if !ok {
		http.Error(w, "invalid id format", http.StatusBadRequest)
		return
	}

	media, err := c.GetMediaByID(id, mediaType)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}

	utils.WriteJSON(w, media)
}

func (c *Client) handleDetails(w http.ResponseWriter, r *http.Request) {
	mediaType := r.URL.Query().Get("type")
	id, ok := parseTMDBID(r.URL.Query().Get("id"))
	if !ok {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}
	if !utils.ValidMediaType(mediaType) {
		http.Error(w, "invalid media type", http.StatusBadRequest)
		return
	}
	details, err := c.GetDetails(id, mediaType)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	utils.WriteJSON(w, details)
}

func (c *Client) handleSimilar(w http.ResponseWriter, r *http.Request) {
	id, ok := parseTMDBID(r.URL.Query().Get("id"))
	if !ok {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}
	mediaType := r.URL.Query().Get("type")
	if !utils.ValidMediaType(mediaType) {
		http.Error(w, "invalid media type", http.StatusBadRequest)
		return
	}
	results, err := c.GetSimilar(id, mediaType)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	utils.WriteJSON(w, results)
}

func (c *Client) handleLogos(w http.ResponseWriter, r *http.Request) {
	mediaType := r.URL.Query().Get("type")
	id, ok := parseTMDBID(r.URL.Query().Get("id"))
	if !ok {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}
	if !utils.ValidMediaType(mediaType) {
		http.Error(w, "invalid media type", http.StatusBadRequest)
		return
	}
	logos, err := c.GetLogos(id, mediaType)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	utils.WriteJSON(w, logos)
}

func (c *Client) handleIMDB(w http.ResponseWriter, r *http.Request) {
	id, ok := parseTMDBID(r.URL.Query().Get("id"))
	if !ok {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}
	imdbID, err := c.GetIMDBId(id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	utils.WriteJSON(w, map[string]string{"imdb_id": imdbID})
}

// GET /api/tv/seasons?id=<tmdbID>
// Returns the list of seasons for a TV show.
func (c *Client) handleTVSeasons(w http.ResponseWriter, r *http.Request) {
	id, ok := parseTMDBID(r.URL.Query().Get("id"))
	if !ok {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}
	seasons, err := c.GetSeasons(id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	utils.WriteJSON(w, seasons)
}

// GET /api/tv/episodes?id=<tmdbID>&season=<seasonNumber>
// Returns the episodes for a given season of a TV show.
func (c *Client) handleTVEpisodes(w http.ResponseWriter, r *http.Request) {
	seasonStr := r.URL.Query().Get("season")
	id, ok := parseTMDBID(r.URL.Query().Get("id"))
	if !ok {
		http.Error(w, "invalid id", http.StatusBadRequest)
		return
	}
	season, err := strconv.Atoi(seasonStr)
	if err != nil || season < 1 {
		http.Error(w, "invalid season", http.StatusBadRequest)
		return
	}
	episodes, err := c.GetEpisodes(id, season)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	utils.WriteJSON(w, episodes)
}

// handleQualityBatch serves GET /api/quality/batch?ids=<typed-id,...>
//
// Nuvio scrapers are intentionally excluded: unlike internal/player's
// single-title /api/streams, this fans out across every title in a discovery
// grid, and running goja + third-party network scrapers per grid tile would be
// far too slow/heavy for a quality badge. Don't "fix" this into consistency
// with /api/streams later.
//
// WriteJSON is not used here: the response is NDJSON
// (application/x-ndjson), not a single JSON document, and flushing must
// happen after each line for progressive delivery to the client.
func (c *Client) handleQualityBatch(w http.ResponseWriter, r *http.Request) {
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

	ctx := r.Context()
	var mu sync.Mutex
	var wg sync.WaitGroup
	enc := json.NewEncoder(w)

	// write emits one NDJSON line. Guarded on ctx so a client that already
	// disconnected doesn't get written to (and so wg.Wait() below isn't
	// the only thing standing between a dead connection and this handler
	// winding down).
	write := func(id, quality string) {
		if ctx.Err() != nil {
			return
		}
		mu.Lock()
		defer mu.Unlock()
		if err := enc.Encode(entry{ID: id, Quality: quality}); err != nil {
			log.Println(err)
			return
		}
		if canFlush {
			flusher.Flush()
		}
	}

	for _, s := range idStrs {
		typedID, mediaType, tmdbID, ok := parseQualityID(s)
		if !ok {
			continue
		}

		// Cached hit — emit immediately, no worker/IMDB-lookup/addon
		// fan-out needed at all.
		if q, hit := c.qualityCacheGet(typedID); hit {
			if q != "" {
				write(typedID, q)
			}
			continue
		}

		wg.Add(1)
		go func(typedID, mediaType string, tmdbID int) {
			defer wg.Done()
			select {
			case sem <- struct{}{}:
				defer func() { <-sem }()
			case <-ctx.Done():
				return
			}
			if ctx.Err() != nil {
				return
			}

			imdbID, err := c.GetIMDBIdForMediaType(tmdbID, mediaType)
			if err != nil || imdbID == "" {
				return
			}

			stremioID := imdbID
			if mediaType == "tv" {
				// The badge is per-title, not per-episode: probe S1E1 as a
				// representative sample of what's available for the show
				// rather than fanning out across every episode.
				stremioID = imdbID + ":1:1"
			}

			streams, err := c.addonMgr.GetAllStreams(ctx, mediaType, stremioID)
			if err != nil {
				return
			}
			q := addons.GetMaxQuality(streams)
			ttl := qualityCacheTTLHit
			if q == "" {
				ttl = qualityCacheTTLEmpty
			}
			c.qualityCacheSet(typedID, q, ttl)
			if q == "" {
				return
			}
			write(typedID, q)
		}(typedID, mediaType, tmdbID)
	}

	wg.Wait()
}

// handleCatalog serves GET /api/catalog?addonId=&catalogType=&catalogId=&skip=<n>&limit=<n>
// Returns {medias:[], nextSkip:n} — one page of a Stremio addon catalog,
// resolved through TMDB. Unresolvable metas are silently dropped; page
// results are cached 5 minutes per (addonURL, type, id, skip, limit) key.
func (c *Client) handleCatalog(w http.ResponseWriter, r *http.Request) {
	addonID := r.URL.Query().Get("addonId")
	catalogType := r.URL.Query().Get("catalogType")
	catalogID := r.URL.Query().Get("catalogId")
	if addonID == "" || catalogType == "" || catalogID == "" {
		http.Error(w, "missing addonId, catalogType, or catalogId", http.StatusBadRequest)
		return
	}

	skip := 0
	if s := r.URL.Query().Get("skip"); s != "" {
		if n, err := strconv.Atoi(s); err == nil && n >= 0 {
			skip = n
		}
	}

	const defaultLimit = 20
	const maxLimit = 100
	limit := defaultLimit
	if l := r.URL.Query().Get("limit"); l != "" {
		if n, err := strconv.Atoi(l); err == nil && n > 0 {
			limit = n
		}
		// n <= 0 stays at default
	}
	if limit > maxLimit {
		limit = maxLimit
	}

	// When the caller supplies ?addonUrl=, validate it against the
	// configured registry (SSRF guard) and use it directly. This is
	// required when two addons share a manifest ID but have different
	// config URLs — FindAddonURL would always return the first match.
	var addonURL string
	if rawURL := r.URL.Query().Get("addonUrl"); rawURL != "" {
		if !c.addonMgr.HasAddonURL(rawURL) {
			http.Error(w, "addon not found", http.StatusNotFound)
			return
		}
		addonURL = rawURL
	} else {
		var ok bool
		addonURL, ok = c.addonMgr.FindAddonURL(addonID)
		if !ok {
			http.Error(w, "addon not found", http.StatusNotFound)
			return
		}
	}

	cacheKey := c.Locale() + "|" + addonURL + "|" + catalogType + "|" + catalogID + "|" + strconv.Itoa(skip) + "|" + strconv.Itoa(limit)

	type page struct {
		Medias   []Media `json:"medias"`
		NextSkip int     `json:"nextSkip"`
	}

	if medias, nextSkip, ok := c.catalogCacheGet(cacheKey); ok {
		utils.WriteJSON(w, page{Medias: medias, NextSkip: nextSkip})
		return
	}

	rawMetas, err := c.addonMgr.FetchCatalog(r.Context(), addonURL, catalogType, catalogID, skip)
	if err != nil {
		http.Error(w, "addon fetch failed: "+err.Error(), http.StatusBadGateway)
		return
	}

	// nextSkip advances by raw item count (before resolution) so
	// unresolvable metas don't cause the pagination cursor to undercount.
	consumed := len(rawMetas)
	if consumed > limit {
		consumed = limit
		rawMetas = rawMetas[:limit]
	}
	nextSkip := skip + consumed

	// Resolve metas concurrently, order-preserving, bounded semaphore so we
	// don't open hundreds of TMDB connections for a large limit.
	const resolveSem = 6
	results := make([]*Media, len(rawMetas))
	sem := make(chan struct{}, resolveSem)
	var wg sync.WaitGroup
	ctx := r.Context()

	for i, meta := range rawMetas {
		wg.Add(1)
		go func(i int, meta addons.StremioMeta) {
			defer wg.Done()
			select {
			case sem <- struct{}{}:
				defer func() { <-sem }()
			case <-ctx.Done():
				return
			}
			results[i] = c.ResolveMeta(ctx, meta)
		}(i, meta)
	}
	wg.Wait()

	// Compact nils — unresolvable metas are silently dropped.
	medias := make([]Media, 0, len(results))
	for _, m := range results {
		if m != nil {
			medias = append(medias, *m)
		}
	}

	// Don't cache when the client disconnected mid-resolution — the early
	// ctx-done returns above would bake a partially-resolved page in for
	// the full TTL.
	if ctx.Err() == nil {
		c.catalogCacheSet(cacheKey, medias, nextSkip)
	}

	utils.WriteJSON(w, page{Medias: medias, NextSkip: nextSkip})
}
