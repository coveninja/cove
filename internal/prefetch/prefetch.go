// Package prefetch runs a low-priority background worker that warms the
// backend's per-title stream-list caches (addons.Manager's A3 cache,
// nuvio.Manager's E1 cache) for media the user is likely to watch next —
// continue-watching titles and the next episode of shows in progress — so
// that by the time they actually press play, /api/streams answers from cache
// in milliseconds and quickPlay's client-side ranking starts playback near-
// instantly.
//
// Deliberate non-goals (see the Phase E plan): this package never duplicates
// stream *ranking* — that stays entirely client-side in
// web/src/lib/streamSelection.ts — and it never calls rememberStream or
// touches playback state; /api/play registration still happens only on the
// real /api/streams request a user's play action triggers.
package prefetch

import (
	"context"
	"fmt"
	"log"
	"sort"
	"strconv"
	"time"

	"github.com/coveninja/cove/internal/addons"
	"github.com/coveninja/cove/internal/library"
	"github.com/coveninja/cove/internal/nuvio"
	"github.com/coveninja/cove/internal/settings"
	"github.com/coveninja/cove/internal/tmdb"
)

const (
	// maxCandidates caps how many titles get warmed per cycle.
	maxCandidates = 10
	// perCandidateTimeout bounds one candidate's addon+nuvio fan-out.
	perCandidateTimeout = 30 * time.Second
	// candidateSpacing is the politeness delay between candidates — the
	// fan-out inside each is already parallel (A2), this just keeps the
	// worker from bursting every candidate's addon calls simultaneously.
	candidateSpacing = 2 * time.Second
	// startupDelay defers the first cycle so prefetch doesn't compete with
	// the home page's own burst of requests right after the backend boots.
	startupDelay = 30 * time.Second
	// tickerInterval re-warms well before the 15-minute prefetch TTL (E1)
	// lapses.
	tickerInterval = 10 * time.Minute
	// debounceDelay batches rapid-fire progress writes (a tick every ~10s
	// during playback) into a single re-run after they settle.
	debounceDelay = 15 * time.Second
	// nearCompleteFrac is the position/duration fraction at which an
	// in-progress title is treated as "basically done" — worth prefetching
	// the *next* episode for, rather than the one already being watched.
	nearCompleteFrac = 0.9
)

// Worker owns the background prefetch loop. Construct with New and start it
// with `go w.Run(ctx)`.
type Worker struct {
	lib      *library.Library
	tmdb     tmdbReader
	addonMgr addonPrefetcher
	nuvioMgr nuvioPrefetcher
	settings *settings.Store

	notify chan struct{}
}

type tmdbReader interface {
	GetEpisodes(tmdbID, seasonNumber int) ([]tmdb.TVEpisode, error)
	GetTVIMDBId(tmdbID int) (string, error)
	GetIMDBId(tmdbID int) (string, error)
	GetMediaByID(tmdbID int, mediaType string) (*tmdb.Media, error)
}

type addonPrefetcher interface {
	GetAllStreamsPrefetch(ctx context.Context, mediaType, stremioID string) ([]addons.Stream, error)
}

type nuvioPrefetcher interface {
	HasEnabledScrapers() bool
	GetStreams(ctx context.Context, mediaType string, tmdbID int, imdbID, title string, year int, season, episode *int) []addons.Stream
}

// New wires up a Worker from the same dependencies main.go already
// constructs at startup.
func New(lib *library.Library, tmdbClient *tmdb.Client, addonMgr *addons.Manager, nuvioMgr *nuvio.Manager, st *settings.Store) *Worker {
	return &Worker{
		lib:      lib,
		tmdb:     tmdbClient,
		addonMgr: addonMgr,
		nuvioMgr: nuvioMgr,
		settings: st,
		notify:   make(chan struct{}, 1),
	}
}

// Notify signals that a library progress write may have changed the
// candidate set (see library.Library.SetOnNearComplete) — debounced so a
// burst of progress ticks during playback triggers at most one re-run.
// Non-blocking: if a notification is already pending, this is a no-op.
func (w *Worker) Notify() {
	select {
	case w.notify <- struct{}{}:
	default:
	}
}

// Run drives the background loop: an initial cycle after startupDelay, then
// a steady tickerInterval cadence, plus debounced re-runs on Notify(). Blocks
// until ctx is cancelled. Every trigger funnels through this single select
// loop, so cycles never overlap — candidates are processed sequentially by
// design (see candidateSpacing).
func (w *Worker) Run(ctx context.Context) {
	startup := time.NewTimer(startupDelay)
	defer startup.Stop()

	ticker := time.NewTicker(tickerInterval)
	defer ticker.Stop()

	debounce := time.NewTimer(time.Hour)
	defer debounce.Stop()
	if !debounce.Stop() {
		<-debounce.C
	}

	for {
		select {
		case <-ctx.Done():
			return
		case <-startup.C:
			w.runCycle(ctx)
		case <-ticker.C:
			w.runCycle(ctx)
		case <-w.notify:
			debounce.Reset(debounceDelay)
		case <-debounce.C:
			w.runCycle(ctx)
		}
	}
}

func (w *Worker) runCycle(ctx context.Context) {
	if w.settings != nil && !w.settings.Get().PrefetchStreams {
		return
	}

	candidates := w.buildCandidates()
	if len(candidates) == 0 {
		return
	}
	log.Printf("prefetch: warming %d candidate(s)", len(candidates))

	// Hoist the spacing timer outside the loop so we create exactly one timer
	// per runCycle call rather than one per candidate. time.After leaks the
	// underlying timer until it fires; with up to maxCandidates iterations and
	// each prefetchOne taking up to perCandidateTimeout, those orphaned timers
	// pile up. We stop-and-drain once before entering the loop to ensure the
	// channel starts empty, then Reset right before the select each iteration
	// (safe because the only other arm — ctx.Done — returns, so timer.C is
	// always consumed or the loop exits).
	timer := time.NewTimer(candidateSpacing)
	defer timer.Stop()
	if !timer.Stop() {
		<-timer.C
	}

	for i, c := range candidates {
		if ctx.Err() != nil {
			return
		}
		w.prefetchOne(ctx, c)

		if i < len(candidates)-1 {
			timer.Reset(candidateSpacing)
			select {
			case <-timer.C:
			case <-ctx.Done():
				return
			}
		}
	}
}

// candidate is one title (or TV episode) to warm caches for.
type candidate struct {
	tmdbID    int
	mediaType string
	season    *int // nil for movies
	episode   *int // nil for movies
	touchedAt time.Time
}

// buildCandidates derives the prefetch set from the library store: every
// actively-watched title (status "watching"), most-recently-touched first,
// capped at maxCandidates. See the package doc / Phase E plan for the exact
// in-progress vs. next-episode rules.
func (w *Worker) buildCandidates() []candidate {
	entries := w.lib.AllEntries()
	progress := w.lib.AllProgress()

	type progKey struct {
		tmdbID    int
		mediaType string
	}
	latestProgress := make(map[progKey]*library.WatchProgress, len(progress))
	for _, p := range progress {
		k := progKey{p.TmdbID, p.MediaType}
		if cur, ok := latestProgress[k]; !ok || p.WatchedAt.After(cur.WatchedAt) {
			latestProgress[k] = p
		}
	}

	var out []candidate
	for _, e := range entries {
		// Only "watching" is worth prefetching — watch_later/finished/dropped
		// titles aren't an imminent next watch.
		if e.Status != library.StatusWatching {
			continue
		}

		touchedAt := e.UpdatedAt
		if e.LastWatchedAt != nil && e.LastWatchedAt.After(touchedAt) {
			touchedAt = *e.LastWatchedAt
		}

		p := latestProgress[progKey{e.TmdbID, e.MediaType}]

		switch {
		case p != nil && !p.Completed && fraction(p) < nearCompleteFrac:
			// Still mid-episode/movie — prefetch exactly what's in progress.
			out = append(out, candidate{
				tmdbID: e.TmdbID, mediaType: e.MediaType,
				season: p.Season, episode: p.Episode, touchedAt: touchedAt,
			})

		case p != nil && e.MediaType == "tv" && p.Season != nil && p.Episode != nil:
			// Completed, or ≥90% through — for TV, the next episode is the
			// likely next watch. (A movie at this point has nothing further
			// to prefetch, so it's simply skipped.)
			if next := w.nextEpisode(e.TmdbID, *p.Season, *p.Episode); next != nil {
				out = append(out, candidate{
					tmdbID: e.TmdbID, mediaType: "tv",
					season: &next.season, episode: &next.episode, touchedAt: touchedAt,
				})
			}

		case p == nil:
			// "Watching" but no progress row yet — next unwatched episode
			// (S1E1 if genuinely nothing watched).
			if e.MediaType == "tv" {
				s, ep := 1, 1
				if e.LastWatchedSeason != nil && e.LastWatchedEpisode != nil {
					next := w.nextEpisode(e.TmdbID, *e.LastWatchedSeason, *e.LastWatchedEpisode)
					if next == nil {
						continue
					}
					s, ep = next.season, next.episode
				}
				out = append(out, candidate{tmdbID: e.TmdbID, mediaType: "tv", season: &s, episode: &ep, touchedAt: touchedAt})
			} else {
				out = append(out, candidate{tmdbID: e.TmdbID, mediaType: "movie", touchedAt: touchedAt})
			}
		}
	}

	sort.Slice(out, func(i, j int) bool { return out[i].touchedAt.After(out[j].touchedAt) })
	if len(out) > maxCandidates {
		out = out[:maxCandidates]
	}
	return out
}

func fraction(p *library.WatchProgress) float64 {
	if p.DurationSeconds <= 0 {
		return 0
	}
	return p.PositionSeconds / p.DurationSeconds
}

type episodeRef struct {
	season, episode int
}

// nextEpisode resolves the episode after (season, episode), rolling over to
// S+1E1 when the season is exhausted. Returns nil if there's no next episode
// at all (season finale of a show with no further seasons yet).
func (w *Worker) nextEpisode(tmdbID, season, episode int) *episodeRef {
	eps, err := w.tmdb.GetEpisodes(tmdbID, season)
	if err != nil || len(eps) == 0 {
		return nil
	}
	maxEp := 0
	for _, e := range eps {
		if e.EpisodeNumber > maxEp {
			maxEp = e.EpisodeNumber
		}
	}
	if episode < maxEp {
		return &episodeRef{season, episode + 1}
	}
	nextSeasonEps, err := w.tmdb.GetEpisodes(tmdbID, season+1)
	if err != nil || len(nextSeasonEps) == 0 {
		return nil // no next season (yet) — nothing to prefetch
	}
	return &episodeRef{season + 1, 1}
}

// prefetchOne warms one candidate's addon + (if enabled) Nuvio caches.
// IMDB-id resolution goes through A1's cached getters, which is itself a
// permanent warm the very next live /api/streams request benefits from too.
func (w *Worker) prefetchOne(ctx context.Context, c candidate) {
	cctx, cancel := context.WithTimeout(ctx, perCandidateTimeout)
	defer cancel()

	var imdbID string
	var err error
	if c.mediaType == "tv" {
		imdbID, err = w.tmdb.GetTVIMDBId(c.tmdbID)
	} else {
		imdbID, err = w.tmdb.GetIMDBId(c.tmdbID)
	}
	if err != nil || imdbID == "" {
		return
	}

	stremioID := imdbID
	if c.mediaType == "tv" && c.season != nil && c.episode != nil {
		stremioID = fmt.Sprintf("%s:%d:%d", imdbID, *c.season, *c.episode)
	}

	if _, err := w.addonMgr.GetAllStreamsPrefetch(cctx, c.mediaType, stremioID); err != nil {
		log.Println("prefetch: addon fan-out:", err)
	}

	// Only run scrapers if the user already opted into background JS by
	// enabling them — same gate /api/streams uses (player.go).
	if w.nuvioMgr != nil && w.nuvioMgr.HasEnabledScrapers() {
		var title string
		var year int
		if media, mErr := w.tmdb.GetMediaByID(c.tmdbID, c.mediaType); mErr == nil && media != nil {
			title = firstNonEmpty(media.Title, media.Name)
			year = parseYear(firstNonEmpty(media.Released, media.FirstAir))
		}
		w.nuvioMgr.GetStreams(cctx, c.mediaType, c.tmdbID, imdbID, title, year, c.season, c.episode)
	}

	log.Printf("prefetch: warmed %s", stremioID)
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}

// parseYear extracts the year from a TMDB-style "YYYY-MM-DD" date string.
// Mirrors internal/player's helper of the same name — small enough that a
// shared internal/tmdbutil package would be more ceremony than the
// duplication it'd save.
func parseYear(date string) int {
	if len(date) < 4 {
		return 0
	}
	year, err := strconv.Atoi(date[:4])
	if err != nil {
		return 0
	}
	return year
}
