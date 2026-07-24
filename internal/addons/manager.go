package addons

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/coveninja/cove/internal/utils"
	"golang.org/x/sync/singleflight"
)

// fanOutDeadline bounds a whole GetAllStreams/GetAllSubtitles fan-out (A2), so
// one slow addon can't push the response past a hard cap — addons that
// already returned by then still count. A var (not a const) so tests can
// shrink it instead of running at real-world 15s.
var fanOutDeadline = 15 * time.Second

// Manager owns the configured addon registry and the HTTP client used to talk
// to addons. Fields are unexported, so tygo emits nothing for Manager — only
// the data types (AddonEntry, Stream, Subtitle, WatchOption, etc.) cross into
// the generated TS.
type Manager struct {
	mu              sync.RWMutex
	stremioAddons   []AddonEntry
	officialEnabled map[string]bool // persisted enabled-state overrides for official addons
	updatedAt       time.Time       // last local mutation time; used for LWW sync resolution
	client          *http.Client
	storePath       string
	imdbLookup      func(tmdbID int) string // returns IMDB ID for a TV show, or "" on failure

	// Per-addon stream response cache (A3). Keyed addon.URL+"|"+mediaType+"|"+
	// stremioID — cached per addon (not the merged GetAllStreams result) so
	// toggling an addon on/off never serves a stale merge, and /api/quality/batch
	// (which calls GetAllStreams too) shares entries with /api/streams.
	streamCacheMu sync.Mutex
	streamCache   map[string]streamCacheEntry
	streamSF      singleflight.Group
}

// streamCacheEntry is one addon's cached FetchStreams result, pre-expiry.
type streamCacheEntry struct {
	streams []Stream
	expires time.Time
}

// Cache TTLs (A3/E1). Non-empty results live longer than empty ones — an
// empty result is far more likely to change soon (an indexer catching up),
// so it gets a short negative-cache window instead of blocking rediscovery
// for the full positive TTL. Prefetch (E1) fills use longer TTLs since a
// slightly stale list is an acceptable trade for the list being warm at all.
const (
	streamCacheTTLHit           = 90 * time.Second
	streamCacheTTLEmpty         = 20 * time.Second
	streamCacheTTLPrefetch      = 15 * time.Minute
	streamCacheTTLPrefetchEmpty = 5 * time.Minute
)

// officialAddons lists the built-in addons that ship with Cove. Their definitions
// are reconstructed from code on each startup; only enabled-state is persisted.
var officialAddons = []AddonEntry{
	{
		ID:     "cove.justwatch",
		Kind:   KindProvider,
		Source: SourceOfficial,
		Manifest: Manifest{
			ID:          "cove.justwatch",
			Name:        "JustWatch",
			Description: "Streaming availability via TMDB/JustWatch",
		},
		Enabled: true,
	},
	{
		ID:     "cove.introdb",
		Kind:   KindTimestamps,
		Source: SourceOfficial,
		Manifest: Manifest{
			ID:          "cove.introdb",
			Name:        "IntroSkip",
			Description: "Intro, recap, credits, and preview timestamps. Sources: theintrodb.org (TMDB-based, all segment types) and introdb.app (IMDB-based, higher coverage for TV shows). Results are merged, with theintrodb.org taking priority.",
		},
		Enabled: true,
	},
}

type managerState struct {
	stremioAddons   []AddonEntry
	officialEnabled map[string]bool
	updatedAt       time.Time
}

func cloneStringBoolMap(src map[string]bool) map[string]bool {
	if src == nil {
		return nil
	}
	dst := make(map[string]bool, len(src))
	for key, value := range src {
		dst[key] = value
	}
	return dst
}

func cloneAddonEntries(src []AddonEntry) []AddonEntry {
	if src == nil {
		return nil
	}
	dst := make([]AddonEntry, len(src))
	for i, entry := range src {
		dst[i] = entry
		dst[i].DisabledCatalogs = cloneStringBoolMap(entry.DisabledCatalogs)
		dst[i].Manifest.Types = append([]string(nil), entry.Manifest.Types...)
		dst[i].Manifest.Resources = append([]ManifestResource(nil), entry.Manifest.Resources...)
		for j, resource := range entry.Manifest.Resources {
			dst[i].Manifest.Resources[j].Types = append([]string(nil), resource.Types...)
			dst[i].Manifest.Resources[j].IDPrefixes = append([]string(nil), resource.IDPrefixes...)
		}
		dst[i].Manifest.Catalogs = append([]ManifestCatalog(nil), entry.Manifest.Catalogs...)
		for j, catalog := range entry.Manifest.Catalogs {
			dst[i].Manifest.Catalogs[j].Extra = append([]ManifestCatalogExtra(nil), catalog.Extra...)
		}
	}
	return dst
}

func (m *Manager) snapshotL() managerState {
	return managerState{
		stremioAddons:   cloneAddonEntries(m.stremioAddons),
		officialEnabled: cloneStringBoolMap(m.officialEnabled),
		updatedAt:       m.updatedAt,
	}
}

func (m *Manager) restoreL(state managerState) {
	m.stremioAddons = state.stremioAddons
	m.officialEnabled = state.officialEnabled
	m.updatedAt = state.updatedAt
}

// New returns a Manager loaded from the profile-scoped store (or empty on first run).
// imdbLookup resolves a TMDB TV show ID to an IMDB ID (or "" on failure); it is
// used by subtitle addons that only accept IMDB IDs.
func New(profileID string, imdbLookup func(tmdbID int) string) *Manager {
	// SafeTransport dials only public addresses — addon URLs are user-supplied
	// (pasted manifest URLs), so without this a malicious addon could point at
	// loopback/LAN addresses and use Cove as an SSRF proxy into the user's own
	// network. Timeout stays as a backstop on top of A2's per-fan-out deadline.
	transport := utils.SafeTransport()
	transport.MaxIdleConns = 100
	transport.MaxIdleConnsPerHost = 8
	m := &Manager{
		client:          &http.Client{Transport: transport, Timeout: 30 * time.Second},
		officialEnabled: make(map[string]bool),
		imdbLookup:      imdbLookup,
		streamCache:     make(map[string]streamCacheEntry),
	}

	path, err := utils.ConfigPath(fmt.Sprintf("addons-%s.json", profileID))
	if err != nil {
		log.Println("addons: could not determine config path:", err)
		return m
	}
	m.storePath = path

	store, err := loadStore(path)
	if err != nil {
		log.Println("addons: could not load store:", err)
		return m
	}
	m.stremioAddons = store.StremioAddons
	if store.OfficialEnabled != nil {
		m.officialEnabled = store.OfficialEnabled
	}
	m.updatedAt = store.UpdatedAt
	return m
}

// SetProfile reloads addon configuration from the given profile's data file.
func (m *Manager) SetProfile(profileID string) error {
	path, err := utils.ConfigPath(fmt.Sprintf("addons-%s.json", profileID))
	if err != nil {
		return err
	}
	store, err := loadStore(path)
	if err != nil {
		return err
	}
	m.mu.Lock()
	m.storePath = path
	m.stremioAddons = store.StremioAddons
	if store.OfficialEnabled != nil {
		m.officialEnabled = store.OfficialEnabled
	} else {
		m.officialEnabled = make(map[string]bool)
	}
	m.updatedAt = store.UpdatedAt
	m.mu.Unlock()
	return nil
}

// GetEntries returns all addons (official + stremio) with current enabled state.
func (m *Manager) GetEntries() []AddonEntry {
	m.mu.RLock()
	defer m.mu.RUnlock()

	entries := cloneAddonEntries(officialAddons)
	for i := range entries {
		if enabled, ok := m.officialEnabled[entries[i].ID]; ok {
			entries[i].Enabled = enabled
		}
	}
	entries = append(entries, cloneAddonEntries(m.stremioAddons)...)
	return entries
}

// AddStremioAddon fetches the manifest at url, classifies it as provider or
// subtitle addon, persists it, and returns the new entry. If an addon at the
// same normalized URL already exists it is updated in place; a same-ID/
// different-URL install (e.g. the same addon configured with two different
// debrid API keys) is appended as a separate entry. The URL is normalized so
// users can paste either the base URL or the full manifest URL.
func (m *Manager) AddStremioAddon(ctx context.Context, url string) (AddonEntry, error) {
	url = normalizeAddonURL(url)
	manifest, err := m.FetchManifest(ctx, url)
	if err != nil {
		return AddonEntry{}, err
	}

	entry := AddonEntry{
		ID:       manifest.ID,
		URL:      url,
		Manifest: manifest,
		Kind:     detectKind(manifest),
		Source:   SourceStremio,
		Enabled:  true,
	}

	m.mu.Lock()
	defer m.mu.Unlock()
	previous := m.snapshotL()

	for i, a := range m.stremioAddons {
		if a.URL == entry.URL {
			m.stremioAddons[i] = entry
			if err := m.saveL(); err != nil {
				m.restoreL(previous)
				return entry, err
			}
			return entry, nil
		}
	}
	m.stremioAddons = append(m.stremioAddons, entry)
	if err := m.saveL(); err != nil {
		m.restoreL(previous)
		return entry, err
	}
	return entry, nil
}

// RefreshAddon re-fetches the manifest for an existing stremio addon and updates
// its Manifest and Kind while preserving the entry's Enabled state and
// DisabledCatalogs. Stale DisabledCatalogs keys (catalogs no longer present in
// the refreshed manifest) are pruned. When addonURL is non-empty, matching is by
// URL only; otherwise matching falls back to id. Returns an error for official
// addons or if the addon is not found.
func (m *Manager) RefreshAddon(ctx context.Context, id, addonURL string) (AddonEntry, error) {
	for _, a := range officialAddons {
		if a.ID == id {
			return AddonEntry{}, fmt.Errorf("cannot refresh built-in addon %q", id)
		}
	}

	// Read the existing entry's URL before fetching (no write lock needed yet).
	m.mu.RLock()
	var fetchURL string
	found := false
	for _, a := range m.stremioAddons {
		if (addonURL != "" && a.URL == addonURL) || (addonURL == "" && id != "" && a.ID == id) {
			fetchURL = a.URL
			found = true
			break
		}
	}
	m.mu.RUnlock()

	if !found {
		return AddonEntry{}, fmt.Errorf("addon not found")
	}
	if addonURL != "" {
		fetchURL = normalizeAddonURL(addonURL)
	}

	manifest, err := m.FetchManifest(ctx, fetchURL)
	if err != nil {
		return AddonEntry{}, err
	}

	m.mu.Lock()
	defer m.mu.Unlock()
	previous := m.snapshotL()

	// Re-find the entry under write lock — it may have changed while fetching.
	for i, a := range m.stremioAddons {
		if (addonURL != "" && a.URL == addonURL) || (addonURL == "" && id != "" && a.ID == id) {
			m.stremioAddons[i].Manifest = manifest
			m.stremioAddons[i].Kind = detectKind(manifest)

			// Prune DisabledCatalogs keys for catalogs no longer in the manifest.
			if len(m.stremioAddons[i].DisabledCatalogs) > 0 {
				valid := make(map[string]bool, len(manifest.Catalogs))
				for _, cat := range manifest.Catalogs {
					valid[cat.CatalogKey()] = true
				}
				for key := range m.stremioAddons[i].DisabledCatalogs {
					if !valid[key] {
						delete(m.stremioAddons[i].DisabledCatalogs, key)
					}
				}
			}

			entry := m.stremioAddons[i]
			if err := m.saveL(); err != nil {
				m.restoreL(previous)
				return AddonEntry{}, err
			}
			return entry, nil
		}
	}
	return AddonEntry{}, fmt.Errorf("addon not found")
}

// RemoveAddon removes a user-added (stremio) addon by ID or URL. When addonURL
// is non-empty, matching is by URL only — this is required when duplicate
// manifest IDs exist (same addon, different config URLs) so the correct entry
// is removed rather than the first ID match. When addonURL is empty, matching
// falls back to ID. Returns an error for official addons or if nothing matches.
func (m *Manager) RemoveAddon(id, addonURL string) error {
	for _, a := range officialAddons {
		if a.ID == id {
			return fmt.Errorf("cannot remove built-in addon %q", id)
		}
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	previous := m.snapshotL()
	for i, a := range m.stremioAddons {
		if (addonURL != "" && a.URL == addonURL) || (addonURL == "" && id != "" && a.ID == id) {
			m.stremioAddons = append(m.stremioAddons[:i], m.stremioAddons[i+1:]...)
			if err := m.saveL(); err != nil {
				m.restoreL(previous)
				return err
			}
			return nil
		}
	}
	return fmt.Errorf("addon not found")
}

// SetEnabled toggles an addon on or off. Official addons are always matched by
// id. For stremio addons: when addonURL is non-empty, matching is by URL only
// (required when duplicate manifest IDs exist); when addonURL is empty,
// matching falls back to ID.
func (m *Manager) SetEnabled(id, addonURL string, enabled bool) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	previous := m.snapshotL()

	for _, a := range officialAddons {
		if a.ID == id {
			m.officialEnabled[id] = enabled
			if err := m.saveL(); err != nil {
				m.restoreL(previous)
				return err
			}
			return nil
		}
	}
	for i, a := range m.stremioAddons {
		if (addonURL != "" && a.URL == addonURL) || (addonURL == "" && id != "" && a.ID == id) {
			m.stremioAddons[i].Enabled = enabled
			if err := m.saveL(); err != nil {
				m.restoreL(previous)
				return err
			}
			return nil
		}
	}
	return fmt.Errorf("addon not found")
}

// GetAllStreams fans out to all enabled stremio provider addons concurrently
// (one goroutine per addon) and flattens the results in registration order —
// deterministic order keeps the frontend's ranking stable across identical
// requests. Per-addon errors are swallowed, same philosophy as the old
// sequential loop: one broken addon must never break the ones that work.
// Results are cached per-addon with live-request TTLs (see fetchStreamsCached);
// GetAllStreamsPrefetch is the prefetch-TTL equivalent (E1).
func (m *Manager) GetAllStreams(ctx context.Context, mediaType string, stremioID string) ([]Stream, error) {
	return m.getAllStreams(ctx, mediaType, stremioID, streamCacheTTLHit, streamCacheTTLEmpty)
}

// GetAllStreamsPrefetch behaves like GetAllStreams but fills the per-addon
// cache with longer prefetch TTLs (E1) — used by the background prefetch
// worker to warm caches well ahead of the normal 90s/20s live TTL lapsing.
func (m *Manager) GetAllStreamsPrefetch(ctx context.Context, mediaType string, stremioID string) ([]Stream, error) {
	return m.getAllStreams(ctx, mediaType, stremioID, streamCacheTTLPrefetch, streamCacheTTLPrefetchEmpty)
}

func (m *Manager) getAllStreams(ctx context.Context, mediaType string, stremioID string, hitTTL, emptyTTL time.Duration) ([]Stream, error) {
	m.mu.RLock()
	addonsSnapshot := make([]AddonEntry, len(m.stremioAddons))
	copy(addonsSnapshot, m.stremioAddons)
	m.mu.RUnlock()

	// Overall deadline for the whole fan-out — a single slow addon can't push
	// the response past this cap; addons that already answered still count.
	fanCtx, cancel := context.WithTimeout(ctx, fanOutDeadline)
	defer cancel()

	results := make([][]Stream, len(addonsSnapshot))
	var wg sync.WaitGroup
	for i, addon := range addonsSnapshot {
		if !addon.Enabled || addon.Kind != KindProvider {
			continue
		}
		// Skip addons that explicitly declare their resources without including
		// "stream" — catalog-only addons would always 404 on stream endpoints.
		hasStream := false
		for _, r := range addon.Manifest.Resources {
			if r.Name == "stream" {
				hasStream = true
				break
			}
		}
		if !hasStream {
			continue
		}
		wg.Add(1)
		go func(i int, addon AddonEntry) {
			defer wg.Done()
			streams := m.fetchStreamsCached(fanCtx, addon, mediaType, stremioID, hitTTL, emptyTTL)
			tagged := make([]Stream, len(streams))
			for j, s := range streams {
				s.AddonName = addon.Manifest.Name
				classifyStream(&s, addon.Manifest.Name, addon.URL)
				tagged[j] = s
			}
			results[i] = tagged
		}(i, addon)
	}
	wg.Wait()

	var allStreams []Stream
	for _, streams := range results {
		allStreams = append(allStreams, streams...)
	}
	return allStreams, nil
}

// fetchStreamsCached returns one addon's streams for (mediaType, stremioID),
// serving from the per-addon cache when fresh, else running (or joining) a
// singleflight fetch. cacheKey := addon.URL+"|"+mediaType+"|"+stremioID —
// stremioID already encodes "tt123:S:E" for TV episodes, so this naturally
// caches per-episode. The raw (unannotated) FetchStreams result is what gets
// cached; callers annotate AddonName on their own copy after retrieval.
func (m *Manager) fetchStreamsCached(ctx context.Context, addon AddonEntry, mediaType, stremioID string, hitTTL, emptyTTL time.Duration) []Stream {
	key := addon.URL + "|" + mediaType + "|" + stremioID

	if streams, ok := m.streamCacheGet(key); ok {
		return streams
	}

	resultCh := m.streamSF.DoChan(key, func() (interface{}, error) {
		// Deliberately context.Background(), not the caller's ctx: this fetch
		// is shared by every current and future caller coalescing on `key`
		// (singleflight), so one caller's disconnect must not abort a fetch
		// other callers (or the cache itself) still need. It gets its own
		// deadline instead.
		fetchCtx, fetchCancel := context.WithTimeout(context.Background(), fanOutDeadline)
		defer fetchCancel()
		streams, err := m.FetchStreams(fetchCtx, addon.URL, mediaType, stremioID)
		if err != nil {
			return nil, err
		}
		ttl := hitTTL
		if len(streams) == 0 {
			ttl = emptyTTL
		}
		m.streamCacheSet(key, streams, ttl)
		return streams, nil
	})

	select {
	case res := <-resultCh:
		if res.Err != nil || res.Val == nil {
			return nil
		}
		streams := res.Val.([]Stream)
		return cloneStreams(streams)
	case <-ctx.Done():
		// This caller's fan-out deadline passed before the addon answered —
		// the fetch above keeps running for the cache and any other waiter on
		// the same key; this particular response just gives up on it.
		return nil
	}
}

func cloneStreams(src []Stream) []Stream {
	if src == nil {
		return nil
	}
	dst := make([]Stream, len(src))
	for i, stream := range src {
		dst[i] = stream
		dst[i].Subtitles = append([]Subtitle(nil), stream.Subtitles...)
		if stream.Headers != nil {
			dst[i].Headers = make(map[string]string, len(stream.Headers))
			for key, value := range stream.Headers {
				dst[i].Headers[key] = value
			}
		}
		if stream.BehaviorHints != nil {
			hints := *stream.BehaviorHints
			dst[i].BehaviorHints = &hints
		}
		if stream.FileIdx != nil {
			index := *stream.FileIdx
			dst[i].FileIdx = &index
		}
	}
	return dst
}

// streamCacheGet returns a copy of a fresh cache entry, or (nil, false) on a
// miss or expiry. Nested slices, maps, and pointers are copied too so callers
// can never mutate cache state shared with other addons/requests.
func (m *Manager) streamCacheGet(key string) ([]Stream, bool) {
	m.streamCacheMu.Lock()
	defer m.streamCacheMu.Unlock()
	entry, ok := m.streamCache[key]
	if !ok || time.Now().After(entry.expires) {
		return nil, false
	}
	return cloneStreams(entry.streams), true
}

// streamCacheSet stores an addon's raw stream result and sweeps expired
// entries while it's already holding the lock — same pattern as
// Player.rememberStream (player.go), avoiding a separate background goroutine.
func (m *Manager) streamCacheSet(key string, streams []Stream, ttl time.Duration) {
	m.streamCacheMu.Lock()
	defer m.streamCacheMu.Unlock()
	now := time.Now()
	for k, v := range m.streamCache {
		if now.After(v.expires) {
			delete(m.streamCache, k)
		}
	}
	m.streamCache[key] = streamCacheEntry{streams: cloneStreams(streams), expires: now.Add(ttl)}
}

// GetAllSubtitles fans out to all enabled stremio subtitle addons concurrently
// (one goroutine per addon), under the same overall fanOutDeadline as
// GetAllStreams. Per-addon errors are swallowed.
func (m *Manager) GetAllSubtitles(ctx context.Context, mediaType string, stremioID string) []Subtitle {
	m.mu.RLock()
	addonsSnapshot := make([]AddonEntry, len(m.stremioAddons))
	copy(addonsSnapshot, m.stremioAddons)
	m.mu.RUnlock()

	fanCtx, cancel := context.WithTimeout(ctx, fanOutDeadline)
	defer cancel()

	results := make([][]Subtitle, len(addonsSnapshot))
	var wg sync.WaitGroup
	for i, addon := range addonsSnapshot {
		if !addon.Enabled || addon.Kind != KindSubtitle {
			continue
		}
		wg.Add(1)
		go func(i int, addon AddonEntry) {
			defer wg.Done()
			subs, err := m.FetchSubtitles(fanCtx, addon.URL, mediaType, stremioID)
			if err != nil {
				return
			}
			results[i] = subs
		}(i, addon)
	}
	wg.Wait()

	var all []Subtitle
	for _, subs := range results {
		all = append(all, subs...)
	}
	return all
}

// GetTimestamps returns merged intro/recap/credits/preview timestamps.
// It queries theintrodb.org first (TMDB IDs, all segment types), then
// supplements any missing segments from introdb.app (IMDB IDs, TV-only).
func (m *Manager) GetTimestamps(tmdbID int, season, episode *int) (*TimestampData, error) {
	m.mu.RLock()
	enabled := m.isOfficialEnabledL("cove.introdb")
	lookup := m.imdbLookup
	m.mu.RUnlock()

	if !enabled {
		return &TimestampData{}, nil
	}

	base, err := fetchTimestamps(m.client, tmdbID, season, episode)
	if err != nil {
		base = &TimestampData{}
	}

	// Supplement with introdb.app for TV episodes when an IMDB lookup is wired up.
	if lookup != nil && season != nil && episode != nil {
		if imdbID := lookup(tmdbID); imdbID != "" {
			if fill, err2 := fetchIntroDBApp(m.client, imdbID, *season, *episode); err2 == nil {
				base = mergeTimestamps(base, fill)
			}
		}
	}
	return base, nil
}

// GetWatchOptions returns streaming availability from JustWatch (via TMDB) if
// the built-in JustWatch addon is enabled.
func (m *Manager) GetWatchOptions(mediaType string, tmdbID string) ([]WatchOption, error) {
	m.mu.RLock()
	enabled := m.isOfficialEnabledL("cove.justwatch")
	m.mu.RUnlock()

	if !enabled {
		return []WatchOption{}, nil
	}
	return fetchWatchOptions(mediaType, tmdbID)
}

// isOfficialEnabledL returns whether an official addon is enabled.
// Defaults to true (official addons are on by default). Must be called with m.mu held.
func (m *Manager) isOfficialEnabledL(id string) bool {
	if enabled, ok := m.officialEnabled[id]; ok {
		return enabled
	}
	return true
}

// GetEnabledCatalogs returns CatalogRefs for all home-eligible, non-disabled
// catalogs across all enabled stremio addons.
func (m *Manager) GetEnabledCatalogs() []CatalogRef {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var refs []CatalogRef
	for _, addon := range m.stremioAddons {
		if !addon.Enabled {
			continue
		}
		for _, cat := range addon.Manifest.Catalogs {
			if !cat.IsHomeEligible() {
				continue
			}
			if addon.DisabledCatalogs[cat.CatalogKey()] {
				continue
			}
			refs = append(refs, CatalogRef{
				AddonID:     addon.ID,
				AddonName:   addon.Manifest.Name,
				AddonURL:    addon.URL,
				CatalogType: cat.Type,
				CatalogID:   cat.ID,
				Name:        cat.Name,
			})
		}
	}
	return refs
}

// SetCatalogEnabled enables or disables a specific catalog for an addon.
// An absent DisabledCatalogs entry means enabled (default-on); disabling
// stores true, re-enabling removes the entry. When addonURL is non-empty,
// matching is by URL only (required when duplicate manifest IDs exist);
// when addonURL is empty, matching falls back to ID.
func (m *Manager) SetCatalogEnabled(addonID, addonURL, catalogKey string, enabled bool) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	previous := m.snapshotL()

	for i, a := range m.stremioAddons {
		if addonURL != "" {
			if a.URL != addonURL {
				continue
			}
		} else if a.ID != addonID {
			continue
		}
		if !enabled {
			if m.stremioAddons[i].DisabledCatalogs == nil {
				m.stremioAddons[i].DisabledCatalogs = make(map[string]bool)
			}
			m.stremioAddons[i].DisabledCatalogs[catalogKey] = true
		} else {
			delete(m.stremioAddons[i].DisabledCatalogs, catalogKey)
		}
		if err := m.saveL(); err != nil {
			m.restoreL(previous)
			return err
		}
		return nil
	}
	return fmt.Errorf("addon not found")
}

// FindAddonURL returns the base URL for a stremio addon by ID.
func (m *Manager) FindAddonURL(addonID string) (string, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	for _, a := range m.stremioAddons {
		if a.ID == addonID {
			return a.URL, true
		}
	}
	return "", false
}

// HasAddonURL reports whether a stremio addon at the given URL is currently
// configured. Used by /api/catalog to validate a caller-supplied addonUrl
// before using it directly, guarding against SSRF via caller-controlled URLs.
func (m *Manager) HasAddonURL(url string) bool {
	m.mu.RLock()
	defer m.mu.RUnlock()
	for _, a := range m.stremioAddons {
		if a.URL == url {
			return true
		}
	}
	return false
}

// saveL persists the current state and stamps updatedAt to now. Must be
// called with m.mu write-locked.
func (m *Manager) saveL() error {
	m.updatedAt = time.Now().UTC()
	if m.storePath == "" {
		return nil
	}
	return saveStore(m.storePath, addonStore{
		StremioAddons:   m.stremioAddons,
		OfficialEnabled: m.officialEnabled,
		UpdatedAt:       m.updatedAt,
	})
}

// UpdatedAt returns the timestamp of the last local mutation to this store.
// Used by sync to send a data-mutation timestamp to Supabase instead of
// wall-clock time, keeping last-write-wins convergent across devices.
func (m *Manager) UpdatedAt() time.Time {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.updatedAt
}

// MergeFrom applies a remote addon configuration using whole-store
// last-write-wins: a no-op when remoteUpdatedAt is not strictly after the
// local updatedAt. When the remote wins, stremio-source entries replace the
// local stremio list and official-source entries replace the officialEnabled
// map (and carry DisabledCatalogs where applicable). The store is persisted with
// UpdatedAt = remoteUpdatedAt (NOT time.Now()) so LWW converges across devices.
//
// Guard: an account that has never pushed addons will have no remote row; the
// caller must distinguish "no remote row" from "remote row with empty list" and
// pass entries = nil / call this method only when a remote row is present.
func (m *Manager) MergeFrom(entries []AddonEntry, remoteUpdatedAt time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if !remoteUpdatedAt.After(m.updatedAt) {
		return nil
	}
	previous := m.snapshotL()
	// Rebuild from the pulled entries.
	var stremio []AddonEntry
	officialEnabled := make(map[string]bool)
	for _, e := range entries {
		switch e.Source {
		case SourceStremio:
			stremio = append(stremio, e)
		case SourceOfficial:
			officialEnabled[e.ID] = e.Enabled
		}
	}
	m.stremioAddons = cloneAddonEntries(stremio)
	m.officialEnabled = officialEnabled
	m.updatedAt = remoteUpdatedAt
	// Save directly with remoteUpdatedAt so the on-disk timestamp reflects the
	// data mutation time, not the moment this merge ran.
	if m.storePath != "" {
		if err := saveStore(m.storePath, addonStore{
			StremioAddons:   m.stremioAddons,
			OfficialEnabled: m.officialEnabled,
			UpdatedAt:       remoteUpdatedAt,
		}); err != nil {
			m.restoreL(previous)
			return fmt.Errorf("addons merge persist: %w", err)
		}
	}
	return nil
}

// detectKind classifies an addon as a stream provider or subtitle provider based
// on its manifest resources.
func detectKind(manifest Manifest) AddonKind {
	for _, r := range manifest.Resources {
		if r.Name == "stream" {
			return KindProvider
		}
	}
	for _, r := range manifest.Resources {
		if r.Name == "subtitles" {
			return KindSubtitle
		}
	}
	return KindProvider // safe default
}
