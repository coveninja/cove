package nuvio

import (
	"context"
	"fmt"
	"log"
	"net/http"
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
	client    *http.Client
	storePath string
}

// New returns a Manager loaded from the profile-scoped store (or empty on
// first run — no repos means the feature is entirely inert).
func New(profileID string) *Manager {
	m := &Manager{
		client: &http.Client{Timeout: 30 * time.Second},
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
	m.mu.Unlock()
	return nil
}

// GetRepos returns all configured repos.
func (m *Manager) GetRepos() []Repo {
	m.mu.RLock()
	defer m.mu.RUnlock()
	repos := make([]Repo, len(m.repos))
	copy(repos, m.repos)
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

// saveL persists the current state. Must be called with m.mu held.
func (m *Manager) saveL() error {
	if m.storePath == "" {
		return nil
	}
	return saveStore(m.storePath, nuvioStore{Repos: m.repos})
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
func (m *Manager) GetStreams(mediaType string, tmdbID int, imdbID, title string, year int, season, episode *int) []addons.Stream {
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

	ctx, cancel := context.WithTimeout(context.Background(), overallDeadline)
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
	select {
	case <-done:
	case <-ctx.Done():
	}

	mu.Lock()
	defer mu.Unlock()
	return allStreams
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}
