package trakt

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"strconv"
	"time"

	"github.com/coveninja/cove/internal/library"
	"github.com/coveninja/cove/internal/settings"
	"github.com/coveninja/cove/internal/tmdb"
)

const (
	syncStartupDelay = 60 * time.Second
	syncTickInterval = 6 * time.Hour
	syncDebounce     = 30 * time.Second
)

// SyncWorker mirrors prefetch.Worker — a startup delay, a steady ticker, and a
// debounced notify channel so a manual "sync now" from the frontend schedules
// at most one extra cycle rather than stacking goroutines.
type SyncWorker struct {
	client   *Client
	store    *Store
	lib      *library.Library
	settings *settings.Store
	tmdb     *tmdb.Client

	notify chan struct{}
}

func newSyncWorker(
	client *Client,
	store *Store,
	lib *library.Library,
	st *settings.Store,
	tmdbClient *tmdb.Client,
) *SyncWorker {
	return &SyncWorker{
		client:   client,
		store:    store,
		lib:      lib,
		settings: st,
		tmdb:     tmdbClient,
		notify:   make(chan struct{}, 1),
	}
}

// Notify schedules an immediate (debounced) sync cycle. Non-blocking.
func (sw *SyncWorker) Notify() {
	select {
	case sw.notify <- struct{}{}:
	default:
	}
}

// Run drives the sync loop: startupDelay, then syncTickInterval cadence, plus
// debounced re-runs on Notify(). Blocks until ctx is cancelled.
func (sw *SyncWorker) Run(ctx context.Context) {
	startup := time.NewTimer(syncStartupDelay)
	defer startup.Stop()

	ticker := time.NewTicker(syncTickInterval)
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
			sw.runCycle()
		case <-ticker.C:
			sw.runCycle()
		case <-sw.notify:
			debounce.Reset(syncDebounce)
		case <-debounce.C:
			sw.runCycle()
		}
	}
}

// runCycle performs one full sync: pull history + watchlist from Trakt, push
// local completed progress + watch_later entries. Additive only — never deletes.
func (sw *SyncWorker) runCycle() {
	st := sw.settings.Get()
	if !st.TraktSyncEnabled {
		return
	}
	if !sw.store.IsConnected() {
		return
	}
	if err := sw.client.EnsureValidToken(); err != nil {
		log.Println("trakt: sync ensure token:", err)
		return
	}

	cycleStart := time.Now()
	tst := sw.store.Get()
	cursor := tst.LastSyncAt // zero on first sync

	// GET /sync/last_activities — used to skip the pull when Trakt has
	// nothing newer than our cursor, while still running the push leg.
	var lastActs traktLastActivities
	if err := sw.client.get("/sync/last_activities", &lastActs); err != nil {
		log.Println("trakt: sync last_activities:", err)
		return
	}

	needsPull := cursor.IsZero() ||
		lastActs.Movies.WatchedAt.After(cursor) ||
		lastActs.Episodes.WatchedAt.After(cursor) ||
		lastActs.Movies.WatchlistedAt.After(cursor) ||
		lastActs.Shows.WatchlistedAt.After(cursor)

	cycleSucceeded := true
	if needsPull {
		if err := sw.pullHistory(cursor); err != nil {
			log.Println("trakt: sync pull history:", err)
			cycleSucceeded = false
		}
		if err := sw.pullWatchlist(); err != nil {
			log.Println("trakt: sync pull watchlist:", err)
			cycleSucceeded = false
		}
	}

	// Push local deltas regardless of whether we pulled — the push cursor is
	// still the same delta window, so this is always additive and idempotent.
	if err := sw.pushHistory(cursor); err != nil {
		log.Println("trakt: sync push history:", err)
		cycleSucceeded = false
	}
	if err := sw.pushWatchlist(cursor); err != nil {
		log.Println("trakt: sync push watchlist:", err)
		cycleSucceeded = false
	}

	// Advancing after a partial failure would permanently skip the failed
	// remote activity or local delta on the next cycle. Keep the old cursor so
	// every leg is retried; all Trakt sync endpoints are additive/idempotent.
	if !cycleSucceeded {
		log.Println("trakt: sync cycle incomplete; cursor retained for retry")
		return
	}

	// Advance the cursor only after every required leg succeeded.
	tst.LastSyncAt = cycleStart
	if err := sw.store.Save(tst); err != nil {
		log.Println("trakt: sync save cursor:", err)
	}
	log.Println("trakt: sync cycle complete")
}

// ── Trakt API response types ──────────────────────────────────────────────────

type traktLastActivities struct {
	Movies struct {
		WatchedAt     time.Time `json:"watched_at"`
		WatchlistedAt time.Time `json:"watchlisted_at"`
	} `json:"movies"`
	Shows struct {
		WatchlistedAt time.Time `json:"watchlisted_at"`
	} `json:"shows"`
	Episodes struct {
		WatchedAt time.Time `json:"watched_at"`
	} `json:"episodes"`
}

type traktHistoryItem struct {
	WatchedAt time.Time `json:"watched_at"`
	Type      string    `json:"type"` // "movie" | "episode"
	Movie     *struct {
		Title string `json:"title"`
		IDs   struct {
			Tmdb int `json:"tmdb"`
		} `json:"ids"`
	} `json:"movie"`
	Show *struct {
		Title string `json:"title"`
		IDs   struct {
			Tmdb int `json:"tmdb"`
		} `json:"ids"`
	} `json:"show"`
	Episode *struct {
		Season int `json:"season"`
		Number int `json:"number"`
		IDs    struct {
			Tmdb int `json:"tmdb"`
		} `json:"ids"`
	} `json:"episode"`
}

type traktWatchlistItem struct {
	ListedAt time.Time `json:"listed_at"`
	Type     string    `json:"type"` // "movie" | "show"
	Movie    *struct {
		Title string `json:"title"`
		IDs   struct {
			Tmdb int `json:"tmdb"`
		} `json:"ids"`
	} `json:"movie"`
	Show *struct {
		Title string `json:"title"`
		IDs   struct {
			Tmdb int `json:"tmdb"`
		} `json:"ids"`
	} `json:"show"`
}

// ── Pull: Trakt → Cove ────────────────────────────────────────────────────────

// pullHistory fetches paginated watched history since cursor and marks items
// completed in the Cove library.
func (sw *SyncWorker) pullHistory(cursor time.Time) error {
	startAt := ""
	if !cursor.IsZero() {
		startAt = cursor.UTC().Format(time.RFC3339)
	}

	page := 1
	for {
		path := fmt.Sprintf("/sync/history?limit=100&page=%d", page)
		if startAt != "" {
			path += "&start_at=" + startAt
		}

		var items []traktHistoryItem
		hdrs, err := sw.client.getWithHeaders(path, &items)
		if err != nil {
			return fmt.Errorf("page %d: %w", page, err)
		}

		for _, item := range items {
			switch item.Type {
			case "movie":
				if item.Movie == nil || item.Movie.IDs.Tmdb == 0 {
					continue
				}
				sw.lib.MarkExternallyWatched(
					item.Movie.IDs.Tmdb, "movie",
					nil, nil,
					item.Movie.Title, "",
					item.WatchedAt,
				)
			case "episode":
				if item.Show == nil || item.Episode == nil || item.Show.IDs.Tmdb == 0 {
					continue
				}
				s, e := item.Episode.Season, item.Episode.Number
				sw.lib.MarkExternallyWatched(
					item.Show.IDs.Tmdb, "tv",
					&s, &e,
					item.Show.Title, "",
					item.WatchedAt,
				)
			}
		}

		totalPages := 1
		if pc := hdrs.Get("X-Pagination-Page-Count"); pc != "" {
			if n, err := strconv.Atoi(pc); err == nil {
				totalPages = n
			}
		}
		if page >= totalPages {
			break
		}
		page++
	}
	return nil
}

// pullWatchlist fetches all Trakt watchlist items and adds missing ones to
// the Cove library as watch_later (additive, never overwrites existing status).
func (sw *SyncWorker) pullWatchlist() error {
	var items []traktWatchlistItem
	if err := sw.client.get("/sync/watchlist", &items); err != nil {
		return err
	}

	for _, item := range items {
		var tmdbID int
		var mediaType, title string
		var listedAt time.Time

		switch item.Type {
		case "movie":
			if item.Movie == nil || item.Movie.IDs.Tmdb == 0 {
				continue
			}
			tmdbID = item.Movie.IDs.Tmdb
			mediaType = "movie"
			title = item.Movie.Title
			listedAt = item.ListedAt
		case "show":
			if item.Show == nil || item.Show.IDs.Tmdb == 0 {
				continue
			}
			tmdbID = item.Show.IDs.Tmdb
			mediaType = "tv"
			title = item.Show.Title
			listedAt = item.ListedAt
		default:
			continue
		}

		// Best-effort: try to fill poster via cached TMDB lookup.
		posterPath := ""
		if sw.tmdb != nil {
			if media, err := sw.tmdb.GetMediaByID(tmdbID, mediaType); err == nil && media != nil {
				posterPath = media.PosterURL
			}
		}

		sw.lib.AddWatchLater(tmdbID, mediaType, title, posterPath, listedAt)
	}
	return nil
}

// ── Push: Cove → Trakt ────────────────────────────────────────────────────────

type traktHistoryMovieItem struct {
	WatchedAt string         `json:"watched_at"`
	IDs       map[string]int `json:"ids"`
}

type traktHistoryEpisodeItem struct {
	Number    int    `json:"number"`
	WatchedAt string `json:"watched_at"`
}

type traktHistorySeasonItem struct {
	Number   int                       `json:"number"`
	Episodes []traktHistoryEpisodeItem `json:"episodes"`
}

type traktHistoryShowItem struct {
	IDs     map[string]int           `json:"ids"`
	Seasons []traktHistorySeasonItem `json:"seasons"`
}

// pushHistory uploads Cove's locally completed progress that is newer than
// cursor to Trakt's history. Uses the batch POST /sync/history endpoint.
func (sw *SyncWorker) pushHistory(cursor time.Time) error {
	progress := sw.lib.AllProgress()

	var movies []traktHistoryMovieItem
	// shows[tmdbID][season] → list of episodes
	showMap := map[int]map[int][]traktHistoryEpisodeItem{}

	for _, p := range progress {
		if !p.Completed {
			continue
		}
		if !cursor.IsZero() && !p.WatchedAt.After(cursor) {
			continue
		}

		switch p.MediaType {
		case "movie":
			movies = append(movies, traktHistoryMovieItem{
				WatchedAt: p.WatchedAt.UTC().Format(time.RFC3339),
				IDs:       map[string]int{"tmdb": p.TmdbID},
			})
		case "tv":
			if p.Season == nil || p.Episode == nil {
				continue
			}
			if showMap[p.TmdbID] == nil {
				showMap[p.TmdbID] = map[int][]traktHistoryEpisodeItem{}
			}
			showMap[p.TmdbID][*p.Season] = append(showMap[p.TmdbID][*p.Season],
				traktHistoryEpisodeItem{
					Number:    *p.Episode,
					WatchedAt: p.WatchedAt.UTC().Format(time.RFC3339),
				},
			)
		}
	}

	if len(movies) == 0 && len(showMap) == 0 {
		return nil
	}

	var shows []traktHistoryShowItem
	for tmdbID, seasonMap := range showMap {
		item := traktHistoryShowItem{
			IDs: map[string]int{"tmdb": tmdbID},
		}
		for seasonNum, episodes := range seasonMap {
			item.Seasons = append(item.Seasons, traktHistorySeasonItem{
				Number:   seasonNum,
				Episodes: episodes,
			})
		}
		shows = append(shows, item)
	}

	payload := map[string]any{
		"movies": movies,
		"shows":  shows,
	}
	resp, err := sw.client.doWrite(http.MethodPost, "/sync/history", payload)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("trakt: push history: HTTP %d", resp.StatusCode)
	}
	return nil
}

// pushWatchlist uploads Cove's watch_later entries newer than cursor to the
// Trakt watchlist.
func (sw *SyncWorker) pushWatchlist(cursor time.Time) error {
	entries := sw.lib.AllEntries()

	var movies []map[string]any
	var shows []map[string]any

	for _, e := range entries {
		if e.Status != library.StatusWatchLater {
			continue
		}
		if !cursor.IsZero() && !e.AddedAt.After(cursor) {
			continue
		}
		ids := map[string]any{"ids": map[string]int{"tmdb": e.TmdbID}}
		switch e.MediaType {
		case "movie":
			movies = append(movies, ids)
		case "tv":
			shows = append(shows, ids)
		}
	}

	if len(movies) == 0 && len(shows) == 0 {
		return nil
	}

	payload := map[string]any{
		"movies": movies,
		"shows":  shows,
	}
	resp, err := sw.client.doWrite(http.MethodPost, "/sync/watchlist", payload)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("trakt: push watchlist: HTTP %d", resp.StatusCode)
	}
	return nil
}
