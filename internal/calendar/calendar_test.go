package calendar

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"

	"github.com/coveninja/cove/internal/library"
	"github.com/coveninja/cove/internal/tmdb"
)

type fakeTMDB struct {
	details     map[string]*tmdb.Details
	detailErr   map[string]error
	episodes    map[[2]int][]tmdb.TVEpisode
	episodesErr map[[2]int]error
}

func (f *fakeTMDB) GetDetails(id int, mediaType string) (*tmdb.Details, error) {
	key := mediaType + ":" + strconv.Itoa(id)
	if err := f.detailErr[key]; err != nil {
		return nil, err
	}
	return f.details[key], nil
}

func (f *fakeTMDB) GetEpisodesCached(id, season int) ([]tmdb.TVEpisode, error) {
	key := [2]int{id, season}
	if err := f.episodesErr[key]; err != nil {
		return nil, err
	}
	return f.episodes[key], nil
}

func intValue(v int) *int { return &v }

func testDay(t *testing.T) (time.Time, time.Time) {
	t.Helper()
	loc, err := time.LoadLocation("Europe/Istanbul")
	if err != nil {
		t.Fatal(err)
	}
	today := time.Date(2026, time.July, 22, 0, 0, 0, 0, loc)
	return today, today.AddDate(0, 0, 90)
}

func TestAiredAhead(t *testing.T) {
	tests := []struct {
		name                   string
		airedS, airedE, wS, wE int
		want                   bool
	}{
		{"later episode", 1, 4, 1, 3, true},
		{"same episode", 1, 3, 1, 3, false},
		{"earlier episode", 1, 2, 1, 3, false},
		{"later season", 2, 1, 1, 99, true},
		{"earlier season", 1, 99, 2, 1, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := airedAhead(tt.airedS, tt.airedE, tt.wS, tt.wE); got != tt.want {
				t.Fatalf("airedAhead() = %v, want %v", got, tt.want)
			}
		})
	}
}

func TestNextEpisode(t *testing.T) {
	seasons := []tmdb.TVSeason{
		{SeasonNumber: 1, EpisodeCount: 2},
		{SeasonNumber: 2, EpisodeCount: 3},
	}
	tests := []struct {
		name               string
		watchedS, watchedE int
		wantSeason, wantEp int
	}{
		{"never started", 0, 0, 1, 1},
		{"within season", 1, 1, 1, 2},
		{"crosses season", 1, 2, 2, 1},
		{"end of known show", 2, 3, 0, 0},
		{"unknown season", 4, 1, 0, 0},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			s, e := nextEpisode(seasons, tt.watchedS, tt.watchedE)
			if s != tt.wantSeason || e != tt.wantEp {
				t.Fatalf("nextEpisode() = S%dE%d, want S%dE%d", s, e, tt.wantSeason, tt.wantEp)
			}
		})
	}

	if s, e := nextEpisode(nil, 0, 0); s != 0 || e != 0 {
		t.Fatalf("nextEpisode(nil) = S%dE%d, want unknown", s, e)
	}
}

func TestComputeWaitingAcrossSeasons(t *testing.T) {
	seasons := []tmdb.TVSeason{
		{SeasonNumber: 1, EpisodeCount: 4},
		{SeasonNumber: 2, EpisodeCount: 3},
		{SeasonNumber: 3, EpisodeCount: 2},
	}
	tests := []struct {
		name                               string
		watchedS, watchedE, airedS, airedE int
		want                               int
	}{
		{"same season", 1, 1, 1, 4, 3},
		{"adjacent seasons", 1, 3, 2, 2, 3},
		{"multiple seasons", 1, 2, 3, 1, 6},
		{"never started", 0, 0, 1, 3, 3},
		{"defensive minimum", 3, 2, 3, 2, 1},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := computeWaiting(seasons, tt.watchedS, tt.watchedE, tt.airedS, tt.airedE)
			if got != tt.want {
				t.Fatalf("computeWaiting() = %d, want %d", got, tt.want)
			}
		})
	}
}

func TestProcessMovieUsesCalendarDateInLocalTimezone(t *testing.T) {
	today, cutoff := testDay(t)
	fake := &fakeTMDB{
		details: map[string]*tmdb.Details{
			"movie:1": {ReleaseDate: "2026-07-22"},
			"movie:2": {ReleaseDate: "2026-07-23"},
			"movie:3": {ReleaseDate: "2026-10-21"},
			"movie:4": {ReleaseDate: "not-a-date"},
		},
		detailErr: map[string]error{"movie:5": errors.New("upstream unavailable")},
	}
	s := &Server{tmdb: fake}

	items, err := s.processMovie(&library.LibraryEntry{TmdbID: 1, Title: "Today"}, today, cutoff, nil)
	if err != nil || len(items) != 1 || items[0].Kind != "available" {
		t.Fatalf("today's local release = %#v, %v; want one available item", items, err)
	}

	items, err = s.processMovie(&library.LibraryEntry{TmdbID: 2, Title: "Tomorrow"}, today, cutoff, nil)
	if err != nil || len(items) != 1 || items[0].Kind != "movie" {
		t.Fatalf("tomorrow's release = %#v, %v; want one future movie", items, err)
	}

	items, err = s.processMovie(&library.LibraryEntry{TmdbID: 1}, today, cutoff, map[int]bool{1: true})
	if err != nil || len(items) != 0 {
		t.Fatalf("completed release = %#v, %v; want skipped", items, err)
	}

	for _, id := range []int{3, 4} {
		items, err = s.processMovie(&library.LibraryEntry{TmdbID: id}, today, cutoff, nil)
		if err != nil || len(items) != 0 {
			t.Fatalf("movie %d = %#v, %v; want skipped", id, items, err)
		}
	}

	if _, err = s.processMovie(&library.LibraryEntry{TmdbID: 5}, today, cutoff, nil); err == nil {
		t.Fatal("upstream error was swallowed")
	}
}

func TestCollectFutureEpisodesFiltersAndMapsMetadata(t *testing.T) {
	today, cutoff := testDay(t)
	fake := &fakeTMDB{episodes: map[[2]int][]tmdb.TVEpisode{
		{7, 2}: {
			{EpisodeNumber: 1, Name: "Past", AirDate: "2026-07-21"},
			{EpisodeNumber: 2, Name: "Today", AirDate: "2026-07-22"},
			{EpisodeNumber: 3, Name: "Tomorrow", AirDate: "2026-07-23", StillPath: "/still.jpg"},
			{EpisodeNumber: 4, Name: "Cutoff", AirDate: "2026-10-20"},
			{EpisodeNumber: 5, Name: "Too late", AirDate: "2026-10-21"},
			{EpisodeNumber: 6, Name: "Malformed", AirDate: "soon"},
			{EpisodeNumber: 0, Name: "Invalid", AirDate: "2026-07-23"},
		},
	}}
	s := &Server{tmdb: fake}
	items, err := s.collectFutureEps(7, 2, today, cutoff, "Show", "/poster.jpg")
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 2 {
		t.Fatalf("got %d items (%#v), want tomorrow and cutoff", len(items), items)
	}
	if items[0].EpisodeName != "Tomorrow" || *items[0].SeasonNumber != 2 || *items[0].EpisodeNumber != 3 || items[0].StillPath != "/still.jpg" {
		t.Fatalf("unexpected mapped episode: %#v", items[0])
	}
}

func TestProcessTVBuildsBacklogAndTwoUpcomingSeasons(t *testing.T) {
	today, cutoff := testDay(t)
	details := &tmdb.Details{
		Seasons: []tmdb.TVSeason{
			{SeasonNumber: 0, EpisodeCount: 5},
			{SeasonNumber: 1, EpisodeCount: 2},
			{SeasonNumber: 2, EpisodeCount: 3},
			{SeasonNumber: 3, EpisodeCount: 1},
		},
	}
	details.LastEpisodeToAir = &struct {
		SeasonNumber  int    `json:"season_number"`
		EpisodeNumber int    `json:"episode_number"`
		AirDate       string `json:"air_date"`
	}{SeasonNumber: 2, EpisodeNumber: 2, AirDate: "2026-07-15"}
	details.NextEpisodeToAir = &struct {
		Name          string `json:"name"`
		SeasonNumber  int    `json:"season_number"`
		EpisodeNumber int    `json:"episode_number"`
		AirDate       string `json:"air_date"`
		StillPath     string `json:"still_path"`
	}{SeasonNumber: 2, EpisodeNumber: 3, AirDate: "2026-07-23"}
	fake := &fakeTMDB{
		details: map[string]*tmdb.Details{"tv:10": details},
		episodes: map[[2]int][]tmdb.TVEpisode{
			{10, 2}: {
				{EpisodeNumber: 1, Name: "Backlog", AirDate: "2026-07-15", StillPath: "/backlog.jpg"},
				{EpisodeNumber: 3, Name: "Soon", AirDate: "2026-07-23"},
			},
			{10, 3}: {{EpisodeNumber: 1, Name: "Next season", AirDate: "2026-08-01"}},
		},
	}
	s := &Server{tmdb: fake}
	entry := &library.LibraryEntry{
		TmdbID: 10, MediaType: "tv", Title: "Series", PosterPath: "https://image.tmdb.org/t/p/w500/poster.jpg",
		Status: library.StatusWatching, LastWatchedSeason: intValue(1), LastWatchedEpisode: intValue(2),
	}

	items, err := s.processTV(entry, today, cutoff)
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 3 {
		t.Fatalf("got %d items (%#v), want backlog plus two upcoming", len(items), items)
	}
	backlog := items[0]
	if backlog.Kind != "available" || *backlog.SeasonNumber != 2 || *backlog.EpisodeNumber != 1 || backlog.WaitingCount != 2 || backlog.EpisodeName != "Backlog" {
		t.Fatalf("unexpected backlog: %#v", backlog)
	}
	if backlog.PosterPath != "http://127.0.0.1:6969/api/img/w500/poster.jpg" {
		t.Fatalf("poster was not rewritten: %q", backlog.PosterPath)
	}
	if items[1].Kind != "episode" || items[2].Kind != "episode" || *items[2].SeasonNumber != 3 {
		t.Fatalf("unexpected upcoming items: %#v", items[1:])
	}
}

func TestCalendarHandlerRejectsOtherMethods(t *testing.T) {
	s := &Server{}
	rec := httptest.NewRecorder()
	s.handleCalendar(rec, httptest.NewRequest(http.MethodPost, "/api/library/calendar", nil))
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, want 405", rec.Code)
	}
}

func TestCalendarHandlerFiltersSortsAndSuppressesCompletedMovies(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	lib, err := library.New("calendar-handler")
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	today := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, now.Location())
	date := func(offset int) string { return today.AddDate(0, 0, offset).Format("2006-01-02") }

	entries := []*library.LibraryEntry{
		{ID: "recent", TmdbID: 101, MediaType: "movie", Title: "Recent", Status: library.StatusWatching, UpdatedAt: now},
		{ID: "older", TmdbID: 102, MediaType: "movie", Title: "Older", Status: library.StatusWatchLater, UpdatedAt: now},
		{ID: "soon", TmdbID: 103, MediaType: "movie", Title: "Soon", Status: library.StatusWatchLater, UpdatedAt: now},
		{ID: "later", TmdbID: 104, MediaType: "movie", Title: "Later", Status: library.StatusWatching, UpdatedAt: now},
		{ID: "complete", TmdbID: 105, MediaType: "movie", Title: "Complete", Status: library.StatusWatching, UpdatedAt: now},
		{ID: "dropped", TmdbID: 106, MediaType: "movie", Title: "Dropped", Status: library.StatusDropped, UpdatedAt: now},
	}
	progress := []*library.WatchProgress{{
		ID: "complete-progress", LibraryEntryID: "complete", TmdbID: 105,
		MediaType: "movie", Completed: true, WatchedAt: now,
	}}
	lib.MergeFrom(entries, progress, nil, nil)

	fake := &fakeTMDB{details: map[string]*tmdb.Details{
		"movie:101": {ReleaseDate: date(-1)},
		"movie:102": {ReleaseDate: date(-3)},
		"movie:103": {ReleaseDate: date(1)},
		"movie:104": {ReleaseDate: date(5)},
		"movie:105": {ReleaseDate: date(-2)},
	}}
	server := New(lib, nil)
	server.tmdb = fake
	mux := http.NewServeMux()
	server.SetupHandlers(mux)

	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/library/calendar", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", rec.Code, rec.Body.String())
	}
	if got := rec.Header().Get("Content-Type"); got != "application/json" {
		t.Fatalf("Content-Type = %q", got)
	}

	var items []CalendarItem
	if err := json.NewDecoder(rec.Body).Decode(&items); err != nil {
		t.Fatal(err)
	}
	if len(items) != 4 {
		t.Fatalf("got %d items (%#v), want four visible incomplete movies", len(items), items)
	}
	wantIDs := []int{101, 102, 103, 104}
	wantKinds := []string{"available", "available", "movie", "movie"}
	for i := range wantIDs {
		if items[i].TmdbID != wantIDs[i] || items[i].Kind != wantKinds[i] {
			t.Fatalf("item %d = %#v, want tmdb=%d kind=%s", i, items[i], wantIDs[i], wantKinds[i])
		}
	}
}
