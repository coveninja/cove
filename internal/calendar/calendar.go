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
	"encoding/json"
	"log"
	"net/http"
	"sort"
	"strings"
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

// Server owns the /api/library/calendar handler.
type Server struct {
	lib  *library.Library
	tmdb *tmdb.Client
}

// New creates a calendar Server.
func New(lib *library.Library, tmdbClient *tmdb.Client) *Server {
	return &Server{lib: lib, tmdb: tmdbClient}
}

// SetupHandlers registers /api/library/calendar on mux.
func (s *Server) SetupHandlers(mux *http.ServeMux) {
	mux.HandleFunc("/api/library/calendar", utils.CorsMiddleware(s.handleCalendar))
}

func (s *Server) handleCalendar(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	// today = midnight local; cutoffFuture = today + 90 days.
	now := time.Now()
	today := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, now.Location())
	cutoffFuture := today.AddDate(0, 0, 90)

	entries := s.lib.AllEntries()

	// Pre-index completed movies so processMovie can skip them.
	completedMovies := make(map[int]bool)
	for _, p := range s.lib.AllProgress() {
		if p.MediaType == "movie" && p.Completed {
			completedMovies[p.TmdbID] = true
		}
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
				items, err = s.processTV(e, today, cutoffFuture)
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

	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(all); err != nil {
		log.Println("calendar: json encode:", err)
	}
}

// rewritePosterURL rewrites legacy TMDB-hosted poster URLs to route through
// the local image-cache proxy — mirrors the unexported rewritePosterURL in
// internal/library/library.go so CalendarItem.PosterPath has the same shape as
// every other library response.
func rewritePosterURL(s string) string {
	if rest, ok := strings.CutPrefix(s, "https://image.tmdb.org/t/p/"); ok {
		return "http://" + utils.LocalAddr() + "/api/img/" + rest
	}
	return s
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
	rel, err := time.Parse("2006-01-02", det.ReleaseDate)
	if err != nil {
		return nil, nil // unparseable date — skip
	}
	if rel.After(cutoffFuture) {
		return nil, nil // too far out
	}

	item := CalendarItem{
		Date:       det.ReleaseDate,
		TmdbID:     e.TmdbID,
		MediaType:  "movie",
		Title:      e.Title,
		PosterPath: rewritePosterURL(e.PosterPath),
	}

	if !rel.After(today) {
		// Already released.
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

	poster := rewritePosterURL(e.PosterPath)
	var items []CalendarItem

	// ── Backlog: aired-but-unwatched episodes (watching status only) ─────────
	if e.Status == library.StatusWatching && det.LastEpisodeToAir != nil {
		airedS := det.LastEpisodeToAir.SeasonNumber
		airedE := det.LastEpisodeToAir.EpisodeNumber

		watchedS := 0
		watchedE := 0
		if e.LastWatchedSeason != nil {
			watchedS = *e.LastWatchedSeason
		}
		if e.LastWatchedEpisode != nil {
			watchedE = *e.LastWatchedEpisode
		}

		// aired position > watched position?
		if airedAhead(airedS, airedE, watchedS, watchedE) {
			nextS, nextE := nextEpisode(realSeasons, watchedS, watchedE)
			if nextS > 0 && nextE > 0 {
				waiting := computeWaiting(realSeasons, watchedS, watchedE, airedS, airedE)

				item := CalendarItem{
					Kind:          "available",
					TmdbID:        e.TmdbID,
					MediaType:     "tv",
					Title:         e.Title,
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
	}

	// ── Upcoming episodes within 90 days ────────────────────────────────────
	if det.NextEpisodeToAir != nil &&
		det.NextEpisodeToAir.AirDate != "" &&
		det.NextEpisodeToAir.SeasonNumber > 0 {

		nextSeason := det.NextEpisodeToAir.SeasonNumber
		upcoming, err := s.collectFutureEps(e.TmdbID, nextSeason, today, cutoffFuture, e.Title, poster)
		if err != nil {
			log.Printf("calendar: collectFutureEps tv:%d s%d: %v", e.TmdbID, nextSeason, err)
		} else {
			// If any episode from this season fell within the window AND Details
			// shows a next season with episodes, fetch that season too (cap one extra).
			if len(upcoming) > 0 {
				nextNextSeason := nextSeason + 1
				for _, s2 := range det.Seasons {
					if s2.SeasonNumber == nextNextSeason && s2.EpisodeCount > 0 {
						extra, err2 := s.collectFutureEps(e.TmdbID, nextNextSeason, today, cutoffFuture, e.Title, poster)
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
		airDate, err := time.Parse("2006-01-02", ep.AirDate)
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

// airedAhead reports whether the aired position (aS, aE) is strictly ahead of
// the watched position (wS, wE).
func airedAhead(aS, aE, wS, wE int) bool {
	if aS != wS {
		return aS > wS
	}
	return aE > wE
}

// nextEpisode returns the (season, episode) immediately after (watchedS, watchedE)
// using realSeasons episode counts. Returns (0,0) if unknown.
func nextEpisode(realSeasons []tmdb.TVSeason, watchedS, watchedE int) (int, int) {
	if len(realSeasons) == 0 {
		return 0, 0
	}
	if watchedS == 0 {
		// Never started: first real season, episode 1.
		return realSeasons[0].SeasonNumber, 1
	}
	for i, s := range realSeasons {
		if s.SeasonNumber == watchedS {
			if watchedE < s.EpisodeCount {
				return watchedS, watchedE + 1
			}
			// Last episode of this season — move to next real season.
			if i+1 < len(realSeasons) {
				return realSeasons[i+1].SeasonNumber, 1
			}
			return 0, 0 // no next season known
		}
	}
	return 0, 0
}

// computeWaiting counts episodes strictly after (watchedS, watchedE) up through
// and including (airedS, airedE) using season episode counts. Minimum 1.
func computeWaiting(realSeasons []tmdb.TVSeason, watchedS, watchedE, airedS, airedE int) int {
	count := 0
	// Build a quick lookup: season number → episode count.
	seasonEps := make(map[int]int, len(realSeasons))
	for _, s := range realSeasons {
		seasonEps[s.SeasonNumber] = s.EpisodeCount
	}

	// Walk real seasons in order, counting episodes after the watch position up
	// through the aired position.
	inRange := false
	for _, s := range realSeasons {
		sn := s.SeasonNumber
		total := s.EpisodeCount

		if sn < watchedS {
			continue
		}
		if sn == watchedS {
			inRange = true
			startEp := watchedE + 1
			if sn == airedS {
				// Watched and aired are in the same season.
				if airedE >= startEp {
					count += airedE - startEp + 1
				}
				break
			}
			count += total - watchedE
			continue
		}
		if !inRange {
			inRange = true
		}
		if sn == airedS {
			count += airedE
			break
		}
		if sn > airedS {
			break
		}
		count += total
	}

	if count < 1 {
		count = 1
	}
	return count
}

func intPtr(v int) *int { return &v }
