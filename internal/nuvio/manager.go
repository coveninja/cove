package nuvio

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/coveninja/cove/internal/addons"
	"github.com/coveninja/cove/internal/utils"
)

// maxConcurrentScrapers bounds how many goja runtimes run at once for a
// single GetStreams call, capping memory/CPU when a user has many repos and
// scrapers enabled. Each scraper invocation is network-bound (mostly waiting
// on fetch(), not burning CPU), so this can be considerably higher than a
// CPU-bound worker pool — with a real repo of ~29 scrapers, a low value here
// serializes the batch into enough sequential waves to approach the overall
// deadline even though most individual scrapers finish in a few seconds.
const maxConcurrentScrapers = 12

// overallDeadline bounds the whole GetStreams call so one hung scraper can't
// push the /api/streams response past a hard cap; other scrapers' results
// that already completed are still returned.
const overallDeadline = 25 * time.Second

// Manager owns the configured Nuvio repo registry. Fields are unexported, so
// tygo emits nothing for Manager — only the data types (Repo, Scraper, etc.)
// cross into the generated TS.
type Manager struct {
	mu        sync.RWMutex
	repos     []Repo
	updatedAt time.Time // last local mutation time; used for LWW sync resolution
	client    *http.Client
	storePath string

	// Results cache (E1). Running goja + third-party scrapers is expensive
	// (up to overallDeadline per call), so repeat opens of the same title —
	// live requests or the background prefetch worker — are served from here
	// instead of re-running the whole batch. Keyed mediaType|tmdbID|S|E, same
	// shape as addons.Manager's per-addon stream cache.
	streamCacheMu   sync.Mutex
	streamCache     map[string]nuvioStreamCacheEntry
	cacheGeneration uint64
}

// nuvioStreamCacheEntry is one cached GetStreams result, pre-expiry.
type nuvioStreamCacheEntry struct {
	streams []addons.Stream
	expires time.Time
}

// streamCacheTTL is flat (no separate empty-result TTL, unlike addons.Manager's
// A3 cache) — a title genuinely having zero Nuvio results is just as valid a
// fact to cache as a populated list, and scraper runs are far more expensive
// than an addon HTTP call, so there's less value in re-probing sooner.
const streamCacheTTL = 15 * time.Minute

// nuvioCacheKey builds a cache key from what actually identifies the
// content — title/year/imdbID are enrichment passed to scrapers, not part of
// identity. "-" stands in for a nil season/episode (movies).
func nuvioCacheKey(mediaType string, tmdbID int, season, episode *int) string {
	s, e := "-", "-"
	if season != nil {
		s = strconv.Itoa(*season)
	}
	if episode != nil {
		e = strconv.Itoa(*episode)
	}
	return fmt.Sprintf("%s|%d|%s|%s", mediaType, tmdbID, s, e)
}

func (m *Manager) streamCacheGet(key string) ([]addons.Stream, bool) {
	m.streamCacheMu.Lock()
	defer m.streamCacheMu.Unlock()
	entry, ok := m.streamCache[key]
	if !ok || time.Now().After(entry.expires) {
		return nil, false
	}
	return cloneStreams(entry.streams), true
}

func cloneStreams(streams []addons.Stream) []addons.Stream {
	cloned := append([]addons.Stream(nil), streams...)
	for i := range cloned {
		if streams[i].Headers == nil {
			continue
		}
		cloned[i].Headers = make(map[string]string, len(streams[i].Headers))
		for key, value := range streams[i].Headers {
			cloned[i].Headers[key] = value
		}
	}
	return cloned
}

// streamCacheSet stores a result and sweeps expired entries while it's
// already holding the lock — same pattern as addons.Manager.streamCacheSet
// and Player.rememberStream, avoiding a separate background goroutine.
func (m *Manager) streamCacheSet(key string, streams []addons.Stream) {
	m.streamCacheMu.Lock()
	defer m.streamCacheMu.Unlock()
	m.streamCacheSetL(key, streams)
}

func (m *Manager) streamCacheSetL(key string, streams []addons.Stream) {
	now := time.Now()
	for k, v := range m.streamCache {
		if now.After(v.expires) {
			delete(m.streamCache, k)
		}
	}
	m.streamCache[key] = nuvioStreamCacheEntry{streams: cloneStreams(streams), expires: now.Add(streamCacheTTL)}
}

func (m *Manager) cacheGenerationSnapshot() uint64 {
	m.streamCacheMu.Lock()
	defer m.streamCacheMu.Unlock()
	return m.cacheGeneration
}

func (m *Manager) streamCacheSetIfCurrent(key string, streams []addons.Stream, generation uint64) {
	m.streamCacheMu.Lock()
	defer m.streamCacheMu.Unlock()
	if generation != m.cacheGeneration {
		return
	}
	m.streamCacheSetL(key, streams)
}

func (m *Manager) invalidateStreamCache() {
	m.streamCacheMu.Lock()
	m.streamCache = make(map[string]nuvioStreamCacheEntry)
	m.cacheGeneration++
	m.streamCacheMu.Unlock()
}

// New returns a Manager loaded from the profile-scoped store (or empty on
// first run — no repos means the feature is entirely inert).
func New(profileID string) *Manager {
	m := &Manager{
		client:      &http.Client{Timeout: 30 * time.Second},
		streamCache: make(map[string]nuvioStreamCacheEntry),
	}

	path, err := utils.ConfigPath(fmt.Sprintf("nuvio-%s.json", profileID))
	if err != nil {
		log.Println("nuvio: could not determine config path:", err)
		return m
	}
	m.storePath = path

	store, err := loadStore(path)
	if err != nil {
		log.Println("nuvio: could not load store:", err)
		return m
	}
	m.repos = store.Repos
	m.updatedAt = store.UpdatedAt
	return m
}

// SetProfile reloads repo configuration from the given profile's data file.
func (m *Manager) SetProfile(profileID string) error {
	path, err := utils.ConfigPath(fmt.Sprintf("nuvio-%s.json", profileID))
	if err != nil {
		return err
	}
	store, err := loadStore(path)
	if err != nil {
		return err
	}
	m.mu.Lock()
	m.storePath = path
	m.repos = store.Repos
	m.updatedAt = store.UpdatedAt
	m.mu.Unlock()
	m.invalidateStreamCache()
	return nil
}

// GetRepos returns all configured repos.
func (m *Manager) GetRepos() []Repo {
	m.mu.RLock()
	defer m.mu.RUnlock()
	repos := make([]Repo, len(m.repos))
	copy(repos, m.repos)
	for i := range repos {
		repos[i].Scrapers = append([]Scraper(nil), repos[i].Scrapers...)
		for j := range repos[i].Scrapers {
			repos[i].Scrapers[j].SupportedTypes = append([]string(nil), repos[i].Scrapers[j].SupportedTypes...)
			repos[i].Scrapers[j].ContentLanguage = append([]string(nil), repos[i].Scrapers[j].ContentLanguage...)
		}
	}
	return repos
}

// HasEnabledScrapers reports whether any repo has at least one enabled,
// ready-to-run scraper. Callers use this to skip Nuvio-specific setup (like
// an extra TMDB lookup for title/year) on the /api/streams hot path for the
// common case of a user who has never touched this feature.
func (m *Manager) HasEnabledScrapers() bool {
	m.mu.RLock()
	defer m.mu.RUnlock()
	for _, r := range m.repos {
		if !r.Enabled {
			continue
		}
		for _, s := range r.Scrapers {
			if s.Enabled && s.Code != "" {
				return true
			}
		}
	}
	return false
}

// saveL persists the current state and stamps updatedAt to now. Must be
// called with m.mu write-locked.
func (m *Manager) saveL() error {
	if m.storePath == "" {
		return nil
	}
	m.updatedAt = time.Now().UTC()
	return saveStore(m.storePath, nuvioStore{Repos: m.repos, UpdatedAt: m.updatedAt})
}

// SnapshotJSON marshals the whole nuvio store and returns it along with the
// last-mutation timestamp for cross-device sync. Safe for concurrent use.
func (m *Manager) SnapshotJSON() ([]byte, time.Time) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	data, _ := json.Marshal(nuvioStore{Repos: m.repos, UpdatedAt: m.updatedAt})
	return data, m.updatedAt
}

// MergeFromJSON applies a remote nuvio store blob using whole-store
// last-write-wins gated on UpdatedAt. When the remote wins, repos are replaced
// and the on-disk store is persisted with UpdatedAt = remoteUpdatedAt (NOT
// time.Now()) for LWW convergence. Runtime state (m.repos) is updated so
// scrapers reflecting the merged config are available immediately. Cached
// streams are invalidated so a remotely disabled repo/scraper stops
// contributing results immediately.
func (m *Manager) MergeFromJSON(data []byte, remoteUpdatedAt time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if !remoteUpdatedAt.After(m.updatedAt) {
		return nil
	}
	var s nuvioStore
	if err := json.Unmarshal(data, &s); err != nil {
		return err
	}
	m.repos = s.Repos
	m.updatedAt = remoteUpdatedAt
	m.invalidateStreamCache()
	if m.storePath == "" {
		return nil
	}
	return saveStore(m.storePath, nuvioStore{Repos: m.repos, UpdatedAt: remoteUpdatedAt})
}

// enabledScraper pairs a scraper with the repo it came from, snapshotted
// under a read lock so GetStreams can run without holding it.
type enabledScraper struct {
	repoID string
	Scraper
}

// GetStreams runs every enabled scraper across every enabled repo against the
// given title and returns whatever streams they produced, tagged with a
// distinctive AddonName so their origin is visible in the stream list.
// Per-scraper errors and timeouts are logged and skipped — one broken or slow
// scraper never blocks or fails the whole call, matching
// addons.Manager.GetAllStreams's swallow-per-addon-error philosophy.
//
// This is deliberately not folded into addons.Manager.GetAllStreams: that
// method is also called by internal/tmdb's batch quality-probe endpoint,
// fanned out across every title in a discovery grid, which must not incur
// goja startup + third-party network calls per grid tile. Only call this from
// a single-title context (the user explicitly requested streams to play).
func (m *Manager) GetStreams(ctx context.Context, mediaType string, tmdbID int, imdbID, title string, year int, season, episode *int) []addons.Stream {
	key := nuvioCacheKey(mediaType, tmdbID, season, episode)
	if streams, ok := m.streamCacheGet(key); ok {
		return streams
	}
	cacheGeneration := m.cacheGenerationSnapshot()

	m.mu.RLock()
	var scrapers []enabledScraper
	for _, r := range m.repos {
		if !r.Enabled {
			continue
		}
		for _, s := range r.Scrapers {
			if !s.Enabled || s.Code == "" {
				continue
			}
			scrapers = append(scrapers, enabledScraper{repoID: r.ID, Scraper: s})
		}
	}
	m.mu.RUnlock()

	if len(scrapers) == 0 {
		return nil
	}

	ctx, cancel := context.WithTimeout(ctx, overallDeadline)
	defer cancel()

	sem := make(chan struct{}, maxConcurrentScrapers)
	var wg sync.WaitGroup
	var mu sync.Mutex
	var allStreams []addons.Stream

	for _, s := range scrapers {
		s := s
		wg.Add(1)
		go func() {
			defer wg.Done()
			select {
			case sem <- struct{}{}:
				defer func() { <-sem }()
			case <-ctx.Done():
				return
			}

			scraped, err := runScraper(ctx, s.ID, s.Code, invocationTimeout, tmdbID, mediaType, title, year, imdbID, season, episode)
			if err != nil {
				log.Println("nuvio: scraper", s.ID, "failed:", err)
				return
			}

			mu.Lock()
			for _, sc := range scraped {
				allStreams = append(allStreams, addons.Stream{
					Name:      sc.Name,
					Title:     firstNonEmpty(sc.Title, sc.Quality, sc.Name),
					URL:       sc.URL,
					AddonName: "Nuvio: " + s.Name,
					Headers:   sc.Headers,
					SizeBytes: sc.Size,
				})
			}
			mu.Unlock()
		}()
	}

	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()
	// Only cache a result that actually completed — one cut short by the
	// overall deadline is missing whatever scrapers hadn't finished yet, and
	// caching that partial list for the full TTL would strand later callers
	// with fewer streams than a fresh run could have found.
	completed := false
	select {
	case <-done:
		completed = true
	case <-ctx.Done():
	}

	mu.Lock()
	result := allStreams
	mu.Unlock()

	if completed {
		// A profile/repo/scraper change may have happened while third-party
		// scraper code was running. Do not repopulate the freshly invalidated
		// cache with results from that obsolete configuration.
		m.streamCacheSetIfCurrent(key, result, cacheGeneration)
	}
	return result
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}
