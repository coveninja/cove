// Package addons manages Stremio-compatible provider/subtitle addons and a
// couple of bespoke "official" integrations (JustWatch availability, IntroDB
// timestamps) that aren't Stremio addons at all despite sharing the same
// AddonEntry shape. Fan-out across multiple enabled addons of the same kind
// runs one goroutine per addon under an overall deadline, with per-addon
// failures swallowed — one broken or slow addon should never break or stall
// the ones that work.
package addons

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"

	"github.com/coveninja/cove/internal/utils"
)

type AddonKind string
type AddonSource string

const (
	KindProvider   AddonKind = "provider"
	KindSubtitle   AddonKind = "subtitle"
	KindTimestamps AddonKind = "timestamps"

	SourceOfficial AddonSource = "official"
	SourceStremio  AddonSource = "stremio"

	// maxAddonResponseBody caps how many bytes we'll consume from any
	// third-party addon response. 20 MiB is already generous for JSON
	// payloads; anything larger is almost certainly an error or a rogue addon.
	maxAddonResponseBody = 20 << 20
)

type ManifestResource struct {
	Name       string   `json:"name"`
	Types      []string `json:"types"`
	IDPrefixes []string `json:"idPrefixes"`
}

// ManifestCatalogExtra describes one parameterisation dimension for a catalog
// (e.g. genre, skip). IsRequired true means the catalog can't be browsed
// without supplying that parameter — i.e. it's search-only.
type ManifestCatalogExtra struct {
	Name       string `json:"name"`
	IsRequired bool   `json:"isRequired"`
}

// ManifestCatalog is one catalog declared in a Stremio addon manifest. The
// custom UnmarshalJSON normalises both the modern extra:[{name,isRequired}]
// form and the legacy extraRequired/extraSupported:[]string form into the
// unified Extra field.
type ManifestCatalog struct {
	Type  string                 `json:"type"`
	ID    string                 `json:"id"`
	Name  string                 `json:"name"`
	Extra []ManifestCatalogExtra `json:"extra,omitempty"`
}

// CatalogKey is the stable composite identity for a catalog across all
// callers (cache keys, API params, persistence). The same catalog id can
// appear under both "movie" and "series", so the type prefix is required.
func (c ManifestCatalog) CatalogKey() string {
	return c.Type + "/" + c.ID
}

// IsHomeEligible returns false if the catalog has any required extra
// parameter other than "skip" — such catalogs can't be browsed without
// a supplied parameter value and therefore can't appear as home page rows.
func (c ManifestCatalog) IsHomeEligible() bool {
	for _, e := range c.Extra {
		if e.IsRequired && e.Name != "skip" {
			return false
		}
	}
	return true
}

func (c *ManifestCatalog) UnmarshalJSON(data []byte) error {
	// Raw form captures both the modern extra array and the legacy
	// extraRequired/extraSupported string slices in one pass.
	var raw struct {
		Type           string                 `json:"type"`
		ID             string                 `json:"id"`
		Name           string                 `json:"name"`
		Extra          []ManifestCatalogExtra `json:"extra"`
		ExtraRequired  []string               `json:"extraRequired"`
		ExtraSupported []string               `json:"extraSupported"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return err
	}
	c.Type = raw.Type
	c.ID = raw.ID
	c.Name = raw.Name

	if len(raw.Extra) > 0 {
		// Modern form — use as-is.
		c.Extra = raw.Extra
	} else if len(raw.ExtraRequired) > 0 || len(raw.ExtraSupported) > 0 {
		// Legacy form — merge required first, then optional, deduped.
		seen := make(map[string]bool)
		for _, name := range raw.ExtraRequired {
			if !seen[name] {
				seen[name] = true
				c.Extra = append(c.Extra, ManifestCatalogExtra{Name: name, IsRequired: true})
			}
		}
		for _, name := range raw.ExtraSupported {
			if !seen[name] {
				seen[name] = true
				c.Extra = append(c.Extra, ManifestCatalogExtra{Name: name, IsRequired: false})
			}
		}
	}
	return nil
}

type ManifestBehaviorHints struct {
	Configurable          bool `json:"configurable,omitempty"`
	ConfigurationRequired bool `json:"configurationRequired,omitempty"`
}

type Manifest struct {
	ID            string                 `json:"id"`
	Name          string                 `json:"name"`
	Description   string                 `json:"description"`
	Version       string                 `json:"version"`
	Resources     []ManifestResource     `json:"resources"`
	Types         []string               `json:"types"`
	Catalogs      []ManifestCatalog      `json:"catalogs,omitempty"`
	BehaviorHints *ManifestBehaviorHints `json:"behaviorHints,omitempty"`
}

type AddonEntry struct {
	ID       string      `json:"id"`
	URL      string      `json:"url,omitempty"`
	Manifest Manifest    `json:"manifest"`
	Kind     AddonKind   `json:"kind"`
	Source   AddonSource `json:"source"`
	Enabled  bool        `json:"enabled"`
	// DisabledCatalogs tracks per-catalog opt-outs. An absent key means the
	// catalog is enabled (default-on, only explicit opt-outs are stored,
	// mirroring how OfficialEnabled works for official addons). Persists via
	// the existing addonStore JSON encode in store.go.
	DisabledCatalogs map[string]bool `json:"disabledCatalogs,omitempty"`
}

// StremioMeta is one item from a Stremio catalog response.
type StremioMeta struct {
	ID          string `json:"id"`
	Type        string `json:"type"`
	Name        string `json:"name"`
	Poster      string `json:"poster"`
	Description string `json:"description"`
	ReleaseInfo string `json:"releaseInfo"`
}

// CatalogRef is the DTO for a single enabled catalog sent to the frontend.
type CatalogRef struct {
	AddonID     string `json:"addonId"`
	AddonName   string `json:"addonName"`
	AddonURL    string `json:"addonUrl"`
	CatalogType string `json:"catalogType"`
	CatalogID   string `json:"catalogId"`
	Name        string `json:"name"`
}

type Subtitle struct {
	ID   string `json:"id"`
	URL  string `json:"url"`
	Lang string `json:"lang"`
}

// StreamBehaviorHints is the subset of Stremio's behaviorHints object we
// retain. VideoSize, when present, is a structured byte size — more reliable
// than parsing the free-text title — and is promoted into Stream.SizeBytes
// by classifyStream when SizeBytes is unset. Filename is the addon-suggested
// file name (e.g. "Show.S01E02.mkv") — captured for future use; not yet
// wired into file selection.
type StreamBehaviorHints struct {
	NotWebReady bool   `json:"notWebReady,omitempty"`
	BingeGroup  string `json:"bingeGroup,omitempty"`
	VideoSize   int64  `json:"videoSize,omitempty"`
	Filename    string `json:"filename,omitempty"`
}

type Stream struct {
	Name      string     `json:"name"`
	Title     string     `json:"title"`
	URL       string     `json:"url"`
	InfoHash  string     `json:"infoHash"`
	AddonName string     `json:"addonName"`
	Subtitles []Subtitle `json:"subtitles,omitempty"`
	// SizeBytes is the stream's file size when the source reports it as a
	// structured number (currently only Nuvio scrapers). Zero means unknown —
	// callers fall back to parsing a size out of Title text (the ubiquitous
	// but unstructured "💾 1.4 GB" convention used by most Stremio addons).
	SizeBytes int64 `json:"sizeBytes,omitempty"`
	// Headers are extra HTTP headers (e.g. Referer/Origin) the origin CDN
	// requires. Only Nuvio-sourced streams set this today; when present,
	// /api/play proxies the request instead of redirecting, since a bare
	// redirect can't carry them to the origin.
	Headers map[string]string `json:"headers,omitempty"`
	// BehaviorHints is the raw hints object from the addon response. They were
	// previously dropped at decode because the field didn't exist; adding it
	// lets encoding/json pick them up. classifyStream promotes VideoSize into
	// SizeBytes when the latter is unset.
	BehaviorHints *StreamBehaviorHints `json:"behaviorHints,omitempty"`
	// FileIdx is the 0-based index into the torrent's raw file list (t.Files()
	// order, not the filtered video subset) identifying the exact file to play.
	// Populated by Stremio addons like Torrentio for season-pack torrents.
	// Pointer so that absent and 0 are distinguishable.
	FileIdx *int `json:"fileIdx,omitempty"`
	// Cached is true when confirmed debrid-cached (instant retrieval).
	// Classifier is conservative, prefers false-negatives.
	Cached bool `json:"cached,omitempty"`
	// Debrid is the detected debrid service ("RealDebrid", "AllDebrid",
	// "Premiumize", "TorBox", "Offcloud", "Debrid-Link", or generic "Debrid");
	// set for cached and uncached debrid streams; empty for plain
	// torrents/unknown direct streams.
	Debrid string `json:"debrid,omitempty"`
}

// WatchOption represents a streaming service availability entry from JustWatch.
type WatchOption struct {
	ProviderID   int    `json:"providerId"`
	ProviderName string `json:"providerName"`
	LogoPath     string `json:"logoPath"`
	Type         string `json:"type"` // "flatrate", "rent", or "buy"
	Link         string `json:"link"` // JustWatch/provider page to open in browser
}

func (r *ManifestResource) UnmarshalJSON(data []byte) error {
	// Try string first
	var name string
	if err := json.Unmarshal(data, &name); err == nil {
		r.Name = name
		return nil
	}
	// Fall back to object form
	type alias ManifestResource
	var obj alias
	if err := json.Unmarshal(data, &obj); err != nil {
		return err
	}
	*r = ManifestResource(obj)
	return nil
}

func (m *Manager) addonRequest(ctx context.Context, url string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, "GET", url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
	req.Header.Set("Accept", "application/json")
	return m.client.Do(req)
}

// normalizeAddonURL strips a trailing /manifest.json so users can paste either
// the base URL or the full manifest URL and get the same result.
func normalizeAddonURL(raw string) string {
	u := strings.TrimRight(strings.TrimSpace(raw), "/")
	u = strings.TrimSuffix(u, "/manifest.json")
	return strings.TrimRight(u, "/")
}

func (m *Manager) FetchManifest(ctx context.Context, addonURL string) (Manifest, error) {
	res, err := m.addonRequest(ctx, addonURL+"/manifest.json")
	if err != nil {
		return Manifest{}, err
	}
	defer res.Body.Close()

	if res.StatusCode != http.StatusOK {
		return Manifest{}, fmt.Errorf("addon returned HTTP %d", res.StatusCode)
	}

	var manifest Manifest
	if err := json.NewDecoder(io.LimitReader(res.Body, maxAddonResponseBody)).Decode(&manifest); err != nil {
		return Manifest{}, err
	}
	if manifest.ID == "" {
		return Manifest{}, fmt.Errorf("addon manifest has no id — check the URL")
	}
	return manifest, nil
}

// StremioType maps Cove's internal media type onto the Stremio protocol type
// used in addon request paths. Cove speaks TMDB's vocabulary ("movie"/"tv"),
// but in Stremio "tv" means *live TV channels* — episodic shows are "series".
// Requesting /stream/tv/tt123:1:1.json gets a well-formed HTTP 200 with an
// empty stream list from addons that only serve movie/series, so the mismatch
// looks like "no streams found" rather than an error. Movies are unaffected
// because "movie" means the same thing in both vocabularies.
//
// Anything that isn't "tv" passes through untouched, so catalog/meta types
// already read out of an addon manifest (which are Stremio vocabulary
// already) stay as they are.
func StremioType(mediaType string) string {
	if mediaType == "tv" {
		return "series"
	}
	return mediaType
}

func (m *Manager) FetchStreams(ctx context.Context, addonURL string, mediaType string, imdbID string) ([]Stream, error) {
	url := fmt.Sprintf("%s/stream/%s/%s.json", addonURL, StremioType(mediaType), imdbID)

	res, err := m.addonRequest(ctx, url)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()

	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("addon returned HTTP %d", res.StatusCode)
	}

	var data struct {
		Streams []Stream `json:"streams"`
	}
	if err := json.NewDecoder(io.LimitReader(res.Body, maxAddonResponseBody)).Decode(&data); err != nil {
		return nil, err
	}
	return data.Streams, nil
}

func (m *Manager) FetchSubtitles(ctx context.Context, addonURL string, mediaType string, id string) ([]Subtitle, error) {
	url := fmt.Sprintf("%s/subtitles/%s/%s.json", addonURL, StremioType(mediaType), id)
	res, err := m.addonRequest(ctx, url)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()

	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("addon returned HTTP %d", res.StatusCode)
	}

	var data struct {
		Subtitles []Subtitle `json:"subtitles"`
	}
	if err := json.NewDecoder(io.LimitReader(res.Body, maxAddonResponseBody)).Decode(&data); err != nil {
		return nil, err
	}
	return data.Subtitles, nil
}

// FetchCatalog retrieves one page of catalog items from a Stremio addon.
// skip == 0 uses the plain .json form (some addons 404 on the skip=0 extra
// param form); otherwise the /skip=N.json paginated form is used.
func (m *Manager) FetchCatalog(ctx context.Context, addonURL, catalogType, catalogID string, skip int) ([]StremioMeta, error) {
	var url string
	if skip == 0 {
		url = fmt.Sprintf("%s/catalog/%s/%s.json", addonURL, catalogType, catalogID)
	} else {
		url = fmt.Sprintf("%s/catalog/%s/%s/skip=%d.json", addonURL, catalogType, catalogID, skip)
	}

	res, err := m.addonRequest(ctx, url)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()

	if res.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("addon returned HTTP %d", res.StatusCode)
	}

	var data struct {
		Metas []StremioMeta `json:"metas"`
	}
	if err := json.NewDecoder(io.LimitReader(res.Body, maxAddonResponseBody)).Decode(&data); err != nil {
		return nil, err
	}
	return data.Metas, nil
}

func (m *Manager) SetupHandlers(mux *http.ServeMux) {
	// GET  /api/addons          — list all addons
	// POST /api/addons          — add stremio addon (body: {"url":"..."})
	// PATCH /api/addons?id=X   — toggle enabled (body: {"enabled":true})
	// DELETE /api/addons?id=X  — remove stremio addon
	// POST /api/addons/refresh?id=X — re-fetch manifest, preserve enabled/catalog state
	mux.HandleFunc("/api/addons", utils.CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodGet:
			utils.WriteJSON(w, m.GetEntries())

		case http.MethodPost:
			var body struct {
				URL string `json:"url"`
			}
			r.Body = http.MaxBytesReader(w, r.Body, utils.SmallBodyLimit)
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.URL == "" {
				http.Error(w, `body must be {"url":"..."}`, http.StatusBadRequest)
				return
			}
			entry, err := m.AddStremioAddon(r.Context(), body.URL)
			if err != nil {
				http.Error(w, "could not add addon: "+err.Error(), http.StatusBadRequest)
				return
			}
			utils.WriteJSON(w, entry)

		case http.MethodPatch:
			id := r.URL.Query().Get("id")
			addonURL := r.URL.Query().Get("url")
			if id == "" && addonURL == "" {
				http.Error(w, "missing ?id= or ?url=", http.StatusBadRequest)
				return
			}
			var body struct {
				Enabled *bool `json:"enabled"`
			}
			r.Body = http.MaxBytesReader(w, r.Body, utils.SmallBodyLimit)
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Enabled == nil {
				http.Error(w, "invalid body", http.StatusBadRequest)
				return
			}
			if err := m.SetEnabled(id, addonURL, *body.Enabled); err != nil {
				http.Error(w, err.Error(), http.StatusNotFound)
				return
			}
			w.WriteHeader(http.StatusNoContent)

		case http.MethodDelete:
			id := r.URL.Query().Get("id")
			addonURL := r.URL.Query().Get("url")
			if id == "" && addonURL == "" {
				http.Error(w, "missing ?id= or ?url=", http.StatusBadRequest)
				return
			}
			if err := m.RemoveAddon(id, addonURL); err != nil {
				http.Error(w, err.Error(), http.StatusBadRequest)
				return
			}
			w.WriteHeader(http.StatusNoContent)

		default:
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		}
	}))

	// GET /api/timestamps?id=<tmdbID>&season=1&episode=2
	mux.HandleFunc("/api/timestamps", utils.CorsMiddleware(utils.MethodGuard(http.MethodGet, func(w http.ResponseWriter, r *http.Request) {
		tmdbIDStr := r.URL.Query().Get("id")
		if tmdbIDStr == "" {
			http.Error(w, "missing ?id=", http.StatusBadRequest)
			return
		}
		tmdbID, err := strconv.Atoi(tmdbIDStr)
		if err != nil || tmdbID <= 0 {
			http.Error(w, "invalid ?id=", http.StatusBadRequest)
			return
		}

		var season, episode *int
		if s := r.URL.Query().Get("season"); s != "" {
			sv, err := strconv.Atoi(s)
			if err != nil || sv < 0 {
				http.Error(w, "invalid ?season=", http.StatusBadRequest)
				return
			}
			season = &sv
		}
		if e := r.URL.Query().Get("episode"); e != "" {
			ev, err := strconv.Atoi(e)
			if err != nil || ev < 0 {
				http.Error(w, "invalid ?episode=", http.StatusBadRequest)
				return
			}
			episode = &ev
		}

		data, err := m.GetTimestamps(tmdbID, season, episode)
		if err != nil {
			log.Println("timestamps:", err)
			data = &TimestampData{}
		}
		utils.WriteJSON(w, data)
	})))

	// GET /api/catalogs — list enabled, home-eligible catalogs across all stremio addons.
	mux.HandleFunc("/api/catalogs", utils.CorsMiddleware(utils.MethodGuard(http.MethodGet, func(w http.ResponseWriter, r *http.Request) {
		refs := m.GetEnabledCatalogs()
		if refs == nil {
			refs = []CatalogRef{}
		}
		utils.WriteJSON(w, refs)
	})))

	// PATCH /api/addons/catalog?id=<addonID>&catalog=<type/id>[&url=<addonURL>]
	// body: {"enabled":bool} — toggle a specific catalog on or off (204 on success).
	// When ?url= is supplied, matching is by URL only (required for addons with
	// duplicate manifest IDs); otherwise matching falls back to ?id=.
	// Either ?id= or ?url= must be present; ?catalog= is always required.
	mux.HandleFunc("/api/addons/catalog", utils.CorsMiddleware(utils.MethodGuard(http.MethodPatch, func(w http.ResponseWriter, r *http.Request) {
		addonID := r.URL.Query().Get("id")
		addonURL := r.URL.Query().Get("url")
		catalogKey := r.URL.Query().Get("catalog")
		if (addonID == "" && addonURL == "") || catalogKey == "" {
			http.Error(w, "missing ?id= or ?url=, and ?catalog=", http.StatusBadRequest)
			return
		}
		var body struct {
			Enabled *bool `json:"enabled"`
		}
		r.Body = http.MaxBytesReader(w, r.Body, utils.SmallBodyLimit)
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Enabled == nil {
			http.Error(w, "invalid body", http.StatusBadRequest)
			return
		}
		if err := m.SetCatalogEnabled(addonID, addonURL, catalogKey, *body.Enabled); err != nil {
			http.Error(w, err.Error(), http.StatusNotFound)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})))

	// POST /api/addons/refresh?id=X[&url=Y] — re-fetch manifest, preserve enabled/catalog state
	mux.HandleFunc("/api/addons/refresh", utils.CorsMiddleware(utils.MethodGuard(http.MethodPost, func(w http.ResponseWriter, r *http.Request) {
		id := r.URL.Query().Get("id")
		addonURL := r.URL.Query().Get("url")
		if id == "" && addonURL == "" {
			http.Error(w, "missing ?id= or ?url=", http.StatusBadRequest)
			return
		}
		entry, err := m.RefreshAddon(r.Context(), id, addonURL)
		if err != nil {
			if err.Error() == "addon not found" {
				http.Error(w, err.Error(), http.StatusNotFound)
				return
			}
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		utils.WriteJSON(w, entry)
	})))

	// GET /api/watch-options?id=<tmdbID>&type=movie|tv
	mux.HandleFunc("/api/watch-options", utils.CorsMiddleware(utils.MethodGuard(http.MethodGet, func(w http.ResponseWriter, r *http.Request) {
		tmdbID := r.URL.Query().Get("id")
		mediaType := r.URL.Query().Get("type")
		if tmdbID == "" || mediaType == "" {
			http.Error(w, "missing ?id= or ?type=", http.StatusBadRequest)
			return
		}
		id, err := strconv.Atoi(tmdbID)
		if err != nil || id <= 0 {
			http.Error(w, "invalid ?id=", http.StatusBadRequest)
			return
		}
		if mediaType != "movie" && mediaType != "tv" {
			http.Error(w, "invalid ?type=", http.StatusBadRequest)
			return
		}
		options, err := m.GetWatchOptions(mediaType, strconv.Itoa(id))
		if err != nil {
			log.Println("watch-options:", err)
			options = []WatchOption{}
		}
		utils.WriteJSON(w, options)
	})))
}
