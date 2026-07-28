// Package calendar owns the GET /api/library/calendar endpoint, which returns
// a merged timeline of:
//   - "available" items: episodes or movies the user has in their library that
//     have aired/released but not yet been watched.
//   - "episode" items: upcoming episodes airing within the next 90 days for
//     shows the user is watching or has in watch-later.
//   - "movie" items: upcoming theatrical/digital releases within the next 90
//     days for movies in the user's library.
package calendar

import (
	"log"
	"net/http"
	"sort"
	"sync"
	"time"

	"github.com/coveninja/cove/internal/library"
	"github.com/coveninja/cove/internal/tmdb"
	"github.com/coveninja/cove/internal/utils"
)

// CalendarItem is one scheduled event in the user's release calendar.
type CalendarItem struct {
	Date          string `json:"date"` // YYYY-MM-DD; for kind="available" it's the item's air/release date
	Kind          string `json:"kind"` // "episode" | "movie" | "available"
	TmdbID        int    `json:"tmdb_id"`
	MediaType     string `json:"media_type"` // "tv" | "movie"
	Title         string `json:"title"`
	PosterPath    string `json:"poster_path"`
	SeasonNumber  *int   `json:"season_number"`  // nil for movies
	EpisodeNumber *int   `json:"episode_number"` // nil for movies
	EpisodeName   string `json:"episode_name"`
	StillPath     string `json:"still_path"`
	WaitingCount  int    `json:"waiting_count"` // aired-but-unwatched count for kind="available" TV
}

type episodePosition struct {
	season  int
	episode int
}

type completedEpisodeSet map[episodePosition]struct{}

// Server owns the /api/library/calendar handler.
type Server struct {
	lib  *library.Library
	tmdb tmdbReader
}

// tmdbReader is the narrow metadata surface the calendar needs. Keeping this
// boundary small makes the scheduling rules independently testable without
// changing the concrete client used by the running server.
type tmdbReader interface {
	GetDetails(tmdbID int, mediaType string) (*tmdb.Details, error)
	GetEpisodesCached(tmdbID, seasonNumber int) ([]tmdb.TVEpisode, error)
}

// New creates a calendar Server.
func New(lib *library.Library, tmdbClient *tmdb.Client) *Server {
	return &Server{lib: lib, tmdb: tmdbClient}
}

// SetupHandlers registers /api/library/calendar on mux.
func (s *Server) SetupHandlers(mux *http.ServeMux) {
	mux.HandleFunc("/api/library/calendar", utils.CorsMiddleware(utils.MethodGuard(http.MethodGet, s.handleCalendar)))
}

func (s *Server) handleCalendar(w http.ResponseWriter, r *http.Request) {

	// today = midnight local; cutoffFuture = today + 90 days.
	now := time.Now()
	today := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, now.Location())
	cutoffFuture := today.AddDate(0, 0, 90)

	entries := s.lib.AllEntries()

	// Pre-index completed progress so the calendar uses the same source of
	// truth as the detail views. LibraryEntry.LastWatchedSeason/Episode only
	// describe the most recently touched episode and can be stale or
	// non-contiguous after manual watch actions and sync.
	completedMovies := make(map[int]bool)
	completedTVEpisodes := make(map[int]completedEpisodeSet)
	for _, p := range s.lib.AllProgress() {
		if !p.Completed {
			continue
		}
		if p.MediaType == "movie" {
			completedMovies[p.TmdbID] = true
			continue
		}
		if p.MediaType != "tv" || p.Season == nil || p.Episode == nil ||
			*p.Season <= 0 || *p.Episode <= 0 {
			continue
		}
		completed := completedTVEpisodes[p.TmdbID]
		if completed == nil {
			completed = make(completedEpisodeSet)
			completedTVEpisodes[p.TmdbID] = completed
		}
		completed[episodePosition{season: *p.Season, episode: *p.Episode}] = struct{}{}
	}

	// Filter entries: watching or watch_later only.
	var relevant []*library.LibraryEntry
	for _, e := range entries {
		if e.Status == library.StatusWatching || e.Status == library.StatusWatchLater {
			relevant = append(relevant, e)
		}
	}

	// Fan out per entry with bounded concurrency (semaphore of 6).
	results := make([][]CalendarItem, len(relevant))
	sem := make(chan struct{}, 6)
	var wg sync.WaitGroup
	for i, entry := range relevant {
		wg.Add(1)
		go func(idx int, e *library.LibraryEntry) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()

			var items []CalendarItem
			var err error
			if e.MediaType == "movie" {
				items, err = s.processMovie(e, today, cutoffFuture, completedMovies)
			} else {
				items, err = s.processTV(e, today, cutoffFuture, completedTVEpisodes[e.TmdbID])
			}
			if err != nil {
				log.Printf("calendar: entry %d %s: %v", e.TmdbID, e.MediaType, err)
				return
			}
			results[idx] = items
		}(i, entry)
	}
	wg.Wait()

	// Flatten results, ensuring a non-null slice.
	all := make([]CalendarItem, 0)
	for _, items := range results {
		all = append(all, items...)
	}

	// Sort: "available" before others; among available, Date descending; among
	// future, Date ascending; ties broken by Title ascending.
	sort.SliceStable(all, func(i, j int) bool {
		ki, kj := all[i].Kind, all[j].Kind
		if ki == "available" && kj != "available" {
			return true
		}
		if ki != "available" && kj == "available" {
			return false
		}
		// Same availability bucket.
		if all[i].Date != all[j].Date {
			if ki == "available" {
				// available: descending date (most recently aired first)
				return all[i].Date > all[j].Date
			}
			// future: ascending date (soonest first)
			return all[i].Date < all[j].Date
		}
		return all[i].Title < all[j].Title
	})

	utils.WriteJSON(w, all)
}

// localizedPresentation prefers the active-locale metadata returned by TMDB.
// Library values are retained as an offline/partial-response fallback.
func localizedPresentation(
	e *library.LibraryEntry,
	details *tmdb.Details,
) (title, poster string) {
	title = details.DisplayTitle()
	if title == "" {
		title = e.Title
	}
	poster = utils.RewriteTMDBImageURL(details.PosterPath)
	if poster == "" {
		poster = utils.RewriteTMDBImageURL(e.PosterPath)
	}
	return title, poster
}

// processMovie emits zero or one CalendarItem for a movie library entry.
func (s *Server) processMovie(
	e *library.LibraryEntry,
	today, cutoffFuture time.Time,
	completedMovies map[int]bool,
) ([]CalendarItem, error) {
	det, err := s.tmdb.GetDetails(e.TmdbID, "movie")
	if err != nil {
		return nil, err
	}
	if det.ReleaseDate == "" {
		return nil, nil
	}
	rel, err := time.ParseInLocation("2006-01-02", det.ReleaseDate, today.Location())
	if err != nil {
		return nil, nil // unparseable date — skip
	}
	if rel.After(cutoffFuture) {
		return nil, nil // too far out
	}
	title, poster := localizedPresentation(e, det)

	item := CalendarItem{
		Date:       det.ReleaseDate,
		TmdbID:     e.TmdbID,
		MediaType:  "movie",
		Title:      title,
		PosterPath: poster,
	}

	if !rel.After(today) {
		// Already released.
		if e.Status == library.StatusWatchLater {
			return nil, nil // the Watch Later shelf owns released titles
		}
		if completedMovies[e.TmdbID] {
			return nil, nil // already watched
		}
		item.Kind = "available"
	} else {
		item.Kind = "movie"
	}
	return []CalendarItem{item}, nil
}

// processTV emits zero or more CalendarItems for a TV library entry: at most
// one "available" backlog item and any number of upcoming "episode" items.
func (s *Server) processTV(
	e *library.LibraryEntry,
	today, cutoffFuture time.Time,
	completed completedEpisodeSet,
) ([]CalendarItem, error) {
	det, err := s.tmdb.GetDetails(e.TmdbID, "tv")
	if err != nil {
		return nil, err
	}

	// Real seasons only (SeasonNumber > 0, EpisodeCount > 0).
	var realSeasons []tmdb.TVSeason
	for _, s := range det.Seasons {
		if s.SeasonNumber > 0 && s.EpisodeCount > 0 {
			realSeasons = append(realSeasons, s)
		}
	}
	sort.Slice(realSeasons, func(i, j int) bool {
		return realSeasons[i].SeasonNumber < realSeasons[j].SeasonNumber
	})

	title, poster := localizedPresentation(e, det)
	var items []CalendarItem

	// ── Backlog: aired-but-unwatched episodes (watching status only) ─────────
	if e.Status == library.StatusWatching && det.LastEpisodeToAir != nil {
		airedS := det.LastEpisodeToAir.SeasonNumber
		airedE := det.LastEpisodeToAir.EpisodeNumber

		nextS, nextE, waiting := airedEpisodeBacklog(realSeasons, airedS, airedE, completed)
		if nextS > 0 && nextE > 0 {
			item := CalendarItem{
				Kind:          "available",
				TmdbID:        e.TmdbID,
				MediaType:     "tv",
				Title:         title,
				PosterPath:    poster,
				SeasonNumber:  intPtr(nextS),
				EpisodeNumber: intPtr(nextE),
				WaitingCount:  waiting,
				Date:          e.LastAirDate, // fallback; enriched below
			}

			// Enrich with episode metadata from the season.
			eps, err := s.tmdb.GetEpisodesCached(e.TmdbID, nextS)
			if err == nil {
				for _, ep := range eps {
					if ep.EpisodeNumber == nextE {
						item.EpisodeName = ep.Name
						item.StillPath = ep.StillPath
						if ep.AirDate != "" {
							item.Date = ep.AirDate
						}
						break
					}
				}
			}

			items = append(items, item)
		}
	}

	// Watch Later is a lightweight release reminder, not a second episode
	// schedule. Emit only TMDB's single next episode and never an aired backlog.
	if e.Status == library.StatusWatchLater {
		next := det.NextEpisodeToAir
		if next == nil ||
			next.AirDate == "" ||
			next.SeasonNumber <= 0 ||
			next.EpisodeNumber <= 0 {
			return items, nil
		}
		airDate, err := time.ParseInLocation("2006-01-02", next.AirDate, today.Location())
		if err != nil || !airDate.After(today) || airDate.After(cutoffFuture) {
			return items, nil
		}
		items = append(items, CalendarItem{
			Date:          next.AirDate,
			Kind:          "episode",
			TmdbID:        e.TmdbID,
			MediaType:     "tv",
			Title:         title,
			PosterPath:    poster,
			SeasonNumber:  intPtr(next.SeasonNumber),
			EpisodeNumber: intPtr(next.EpisodeNumber),
			EpisodeName:   next.Name,
			StillPath:     next.StillPath,
		})
		return items, nil
	}

	// ── Upcoming episodes within 90 days (watching status only) ─────────────
	if det.NextEpisodeToAir != nil &&
		det.NextEpisodeToAir.AirDate != "" &&
		det.NextEpisodeToAir.SeasonNumber > 0 {

		nextSeason := det.NextEpisodeToAir.SeasonNumber
		upcoming, err := s.collectFutureEps(e.TmdbID, nextSeason, today, cutoffFuture, title, poster)
		if err != nil {
			log.Printf("calendar: collectFutureEps tv:%d s%d: %v", e.TmdbID, nextSeason, err)
		} else {
			// If any episode from this season fell within the window AND Details
			// shows a next season with episodes, fetch that season too (cap one extra).
			if len(upcoming) > 0 {
				nextNextSeason := nextSeason + 1
				for _, s2 := range det.Seasons {
					if s2.SeasonNumber == nextNextSeason && s2.EpisodeCount > 0 {
						extra, err2 := s.collectFutureEps(e.TmdbID, nextNextSeason, today, cutoffFuture, title, poster)
						if err2 == nil {
							upcoming = append(upcoming, extra...)
						}
						break
					}
				}
			}
			items = append(items, upcoming...)
		}
	}

	return items, nil
}

// collectFutureEps fetches a season's episodes and returns those with
// non-empty parseable AirDate strictly after today and within cutoffFuture.
func (s *Server) collectFutureEps(
	tmdbID, seasonNumber int,
	today, cutoffFuture time.Time,
	title, poster string,
) ([]CalendarItem, error) {
	eps, err := s.tmdb.GetEpisodesCached(tmdbID, seasonNumber)
	if err != nil {
		return nil, err
	}

	var items []CalendarItem
	for _, ep := range eps {
		if ep.EpisodeNumber <= 0 || ep.AirDate == "" {
			continue
		}
		airDate, err := time.ParseInLocation("2006-01-02", ep.AirDate, today.Location())
		if err != nil {
			continue
		}
		if !airDate.After(today) || airDate.After(cutoffFuture) {
			continue
		}
		items = append(items, CalendarItem{
			Date:          ep.AirDate,
			Kind:          "episode",
			TmdbID:        tmdbID,
			MediaType:     "tv",
			Title:         title,
			PosterPath:    poster,
			SeasonNumber:  intPtr(seasonNumber),
			EpisodeNumber: intPtr(ep.EpisodeNumber),
			EpisodeName:   ep.Name,
			StillPath:     ep.StillPath,
		})
	}
	return items, nil
}

// airedEpisodeBacklog finds the earliest aired episode without a completed
// progress record and counts all such episodes through the latest aired
// position. Completion records are authoritative because watch history need
// not be contiguous and the most recently touched episode may be incomplete.
func airedEpisodeBacklog(
	realSeasons []tmdb.TVSeason,
	airedS, airedE int,
	completed completedEpisodeSet,
) (nextS, nextE, waiting int) {
	for _, s := range realSeasons {
		if s.SeasonNumber > airedS {
			break
		}
		lastEpisode := s.EpisodeCount
		if s.SeasonNumber == airedS && airedE < lastEpisode {
			lastEpisode = airedE
		}
		if lastEpisode <= 0 {
			continue
		}

		for episode := 1; episode <= lastEpisode; episode++ {
			position := episodePosition{season: s.SeasonNumber, episode: episode}
			if _, ok := completed[position]; ok {
				continue
			}
			if waiting == 0 {
				nextS = s.SeasonNumber
				nextE = episode
			}
			waiting++
		}
	}
	return nextS, nextE, waiting
}

func intPtr(v int) *int { return &v }
