package prefetch

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/coveninja/cove/internal/addons"
	"github.com/coveninja/cove/internal/library"
	"github.com/coveninja/cove/internal/settings"
	"github.com/coveninja/cove/internal/tmdb"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func newTestLib(t *testing.T) *library.Library {
	t.Helper()
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	l, err := library.New("test")
	require.NoError(t, err)
	return l
}

func intp(v int) *int { return &v }

type fakeTMDB struct {
	episodes   map[[2]int][]tmdb.TVEpisode
	episodeErr map[[2]int]error
	movieIMDB  string
	tvIMDB     string
	idErr      error
	media      *tmdb.Media
}

func (f *fakeTMDB) GetEpisodes(id, season int) ([]tmdb.TVEpisode, error) {
	key := [2]int{id, season}
	return f.episodes[key], f.episodeErr[key]
}

func (f *fakeTMDB) GetTVIMDBId(int) (string, error)               { return f.tvIMDB, f.idErr }
func (f *fakeTMDB) GetIMDBId(int) (string, error)                 { return f.movieIMDB, f.idErr }
func (f *fakeTMDB) GetMediaByID(int, string) (*tmdb.Media, error) { return f.media, nil }

type fakeAddonPrefetcher struct {
	calls []struct{ mediaType, stremioID string }
	err   error
}

func (f *fakeAddonPrefetcher) GetAllStreamsPrefetch(_ context.Context, mediaType, stremioID string) ([]addons.Stream, error) {
	f.calls = append(f.calls, struct{ mediaType, stremioID string }{mediaType, stremioID})
	return nil, f.err
}

type nuvioCall struct {
	mediaType       string
	tmdbID          int
	imdbID          string
	title           string
	year            int
	season, episode *int
}

type fakeNuvioPrefetcher struct {
	enabled bool
	calls   []nuvioCall
}

func (f *fakeNuvioPrefetcher) HasEnabledScrapers() bool { return f.enabled }
func (f *fakeNuvioPrefetcher) GetStreams(_ context.Context, mediaType string, tmdbID int, imdbID, title string, year int, season, episode *int) []addons.Stream {
	f.calls = append(f.calls, nuvioCall{mediaType, tmdbID, imdbID, title, year, season, episode})
	return nil
}

func TestFraction(t *testing.T) {
	assert.Equal(t, 0.5, fraction(&library.WatchProgress{PositionSeconds: 50, DurationSeconds: 100}))
	assert.Equal(t, 0.0, fraction(&library.WatchProgress{PositionSeconds: 50, DurationSeconds: 0}))
}

func TestBuildCandidates_SkipsNonWatchingStatuses(t *testing.T) {
	lib := newTestLib(t)
	now := time.Now()
	lib.MergeFrom([]*library.LibraryEntry{
		{ID: "a", TmdbID: 1, MediaType: "movie", Status: library.StatusWatchLater, AddedAt: now, UpdatedAt: now},
		{ID: "b", TmdbID: 2, MediaType: "movie", Status: library.StatusFinished, AddedAt: now, UpdatedAt: now},
		{ID: "c", TmdbID: 3, MediaType: "movie", Status: library.StatusDropped, AddedAt: now, UpdatedAt: now},
	}, nil, nil, nil)

	w := New(lib, nil, nil, nil, nil)
	candidates := w.buildCandidates()
	assert.Empty(t, candidates)
}

func TestBuildCandidates_MidProgressMovie(t *testing.T) {
	lib := newTestLib(t)
	now := time.Now()
	lib.MergeFrom([]*library.LibraryEntry{
		{ID: "a", TmdbID: 42, MediaType: "movie", Status: library.StatusWatching, AddedAt: now, UpdatedAt: now},
	}, []*library.WatchProgress{
		{ID: "p1", TmdbID: 42, MediaType: "movie", PositionSeconds: 600, DurationSeconds: 6000, WatchedAt: now},
	}, nil, nil)

	w := New(lib, nil, nil, nil, nil)
	candidates := w.buildCandidates()
	require.Len(t, candidates, 1)
	assert.Equal(t, 42, candidates[0].tmdbID)
	assert.Equal(t, "movie", candidates[0].mediaType)
	assert.Nil(t, candidates[0].season)
}

func TestBuildCandidates_MidProgressTVEpisode(t *testing.T) {
	lib := newTestLib(t)
	now := time.Now()
	lib.MergeFrom([]*library.LibraryEntry{
		{ID: "a", TmdbID: 99, MediaType: "tv", Status: library.StatusWatching, AddedAt: now, UpdatedAt: now},
	}, []*library.WatchProgress{
		{ID: "p1", TmdbID: 99, MediaType: "tv", Season: intp(2), Episode: intp(5), PositionSeconds: 100, DurationSeconds: 1200, WatchedAt: now},
	}, nil, nil)

	w := New(lib, nil, nil, nil, nil)
	candidates := w.buildCandidates()
	require.Len(t, candidates, 1)
	require.NotNil(t, candidates[0].season)
	require.NotNil(t, candidates[0].episode)
	assert.Equal(t, 2, *candidates[0].season)
	assert.Equal(t, 5, *candidates[0].episode)
}

func TestBuildCandidates_NearCompleteMovieSkipped(t *testing.T) {
	// A movie at ≥90% has nothing further to prefetch (no "next episode"
	// concept), so it should simply be dropped, not error or hang.
	lib := newTestLib(t)
	now := time.Now()
	lib.MergeFrom([]*library.LibraryEntry{
		{ID: "a", TmdbID: 7, MediaType: "movie", Status: library.StatusWatching, AddedAt: now, UpdatedAt: now},
	}, []*library.WatchProgress{
		{ID: "p1", TmdbID: 7, MediaType: "movie", PositionSeconds: 95, DurationSeconds: 100, WatchedAt: now},
	}, nil, nil)

	w := New(lib, nil, nil, nil, nil)
	candidates := w.buildCandidates()
	assert.Empty(t, candidates)
}

func TestBuildCandidates_WatchingNoProgressRow(t *testing.T) {
	lib := newTestLib(t)
	now := time.Now()
	lib.MergeFrom([]*library.LibraryEntry{
		{ID: "a", TmdbID: 5, MediaType: "tv", Status: library.StatusWatching, AddedAt: now, UpdatedAt: now},
	}, nil, nil, nil)

	w := New(lib, nil, nil, nil, nil)
	candidates := w.buildCandidates()
	require.Len(t, candidates, 1)
	require.NotNil(t, candidates[0].season)
	require.NotNil(t, candidates[0].episode)
	assert.Equal(t, 1, *candidates[0].season)
	assert.Equal(t, 1, *candidates[0].episode)
}

func TestBuildCandidates_WatchingNoProgressRowUsesLastWatched(t *testing.T) {
	lib := newTestLib(t)
	now := time.Now()
	s, e := 3, 4
	lib.MergeFrom([]*library.LibraryEntry{
		{
			ID: "a", TmdbID: 5, MediaType: "tv", Status: library.StatusWatching,
			LastWatchedSeason: &s, LastWatchedEpisode: &e,
			AddedAt: now, UpdatedAt: now,
		},
	}, nil, nil, nil)

	w := &Worker{lib: lib, tmdb: &fakeTMDB{episodes: map[[2]int][]tmdb.TVEpisode{
		{5, 3}: {{EpisodeNumber: 5}},
	}}}
	candidates := w.buildCandidates()
	require.Len(t, candidates, 1)
	assert.Equal(t, 3, *candidates[0].season)
	assert.Equal(t, 5, *candidates[0].episode)
}

func TestNextEpisodeWithinSeasonRolloverAndFinale(t *testing.T) {
	w := &Worker{tmdb: &fakeTMDB{episodes: map[[2]int][]tmdb.TVEpisode{
		{10, 1}: {{EpisodeNumber: 1}, {EpisodeNumber: 3}},
		{10, 2}: {{EpisodeNumber: 1}},
	}}}

	next := w.nextEpisode(10, 1, 2)
	require.NotNil(t, next)
	assert.Equal(t, episodeRef{season: 1, episode: 3}, *next)
	next = w.nextEpisode(10, 1, 3)
	require.NotNil(t, next)
	assert.Equal(t, episodeRef{season: 2, episode: 1}, *next)
	assert.Nil(t, w.nextEpisode(10, 2, 1))
}

func TestNextEpisodeHandlesMetadataFailures(t *testing.T) {
	w := &Worker{tmdb: &fakeTMDB{
		episodes:   map[[2]int][]tmdb.TVEpisode{{11, 1}: {{EpisodeNumber: 2}}},
		episodeErr: map[[2]int]error{{11, 2}: errors.New("unavailable")},
	}}
	assert.Nil(t, w.nextEpisode(99, 1, 1))
	assert.Nil(t, w.nextEpisode(11, 1, 2))
}

func TestPrefetchOneWarmsAddonAndEnabledNuvio(t *testing.T) {
	fakeMetadata := &fakeTMDB{
		tvIMDB: "tt123",
		media:  &tmdb.Media{Name: "Example Show", FirstAir: "2024-05-06"},
	}
	addon := &fakeAddonPrefetcher{}
	nuvio := &fakeNuvioPrefetcher{enabled: true}
	w := &Worker{tmdb: fakeMetadata, addonMgr: addon, nuvioMgr: nuvio}
	c := candidate{tmdbID: 42, mediaType: "tv", season: intp(2), episode: intp(7)}
	w.prefetchOne(context.Background(), c)

	require.Len(t, addon.calls, 1)
	assert.Equal(t, "tv", addon.calls[0].mediaType)
	assert.Equal(t, "tt123:2:7", addon.calls[0].stremioID)
	require.Len(t, nuvio.calls, 1)
	assert.Equal(t, "Example Show", nuvio.calls[0].title)
	assert.Equal(t, 2024, nuvio.calls[0].year)
	assert.Equal(t, 2, *nuvio.calls[0].season)
	assert.Equal(t, 7, *nuvio.calls[0].episode)
}

func TestPrefetchOneStopsWhenIMDBResolutionFails(t *testing.T) {
	addon := &fakeAddonPrefetcher{}
	w := &Worker{tmdb: &fakeTMDB{idErr: errors.New("tmdb down")}, addonMgr: addon}
	w.prefetchOne(context.Background(), candidate{tmdbID: 42, mediaType: "movie"})
	assert.Empty(t, addon.calls)

	w.tmdb = &fakeTMDB{movieIMDB: ""}
	w.prefetchOne(context.Background(), candidate{tmdbID: 42, mediaType: "movie"})
	assert.Empty(t, addon.calls)
}

func TestRunCycleHonorsPrefetchSetting(t *testing.T) {
	lib := newTestLib(t)
	now := time.Now()
	lib.MergeFrom([]*library.LibraryEntry{{
		ID: "movie", TmdbID: 50, MediaType: "movie", Status: library.StatusWatching,
		AddedAt: now, UpdatedAt: now,
	}}, nil, nil, nil)
	st, err := settings.New("prefetch-cycle")
	require.NoError(t, err)
	configured := st.Get()
	configured.PrefetchStreams = false
	require.NoError(t, st.Set(configured))

	addon := &fakeAddonPrefetcher{}
	w := &Worker{
		lib: lib, settings: st, addonMgr: addon,
		tmdb: &fakeTMDB{movieIMDB: "tt050"}, notify: make(chan struct{}, 1),
	}
	w.runCycle(context.Background())
	assert.Empty(t, addon.calls)

	configured = st.Get()
	configured.PrefetchStreams = true
	require.NoError(t, st.Set(configured))
	w.runCycle(context.Background())
	require.Len(t, addon.calls, 1)
	assert.Equal(t, "tt050", addon.calls[0].stremioID)
}

func TestNotifyCoalescesAndHelpers(t *testing.T) {
	w := &Worker{notify: make(chan struct{}, 1)}
	w.Notify()
	w.Notify()
	assert.Len(t, w.notify, 1)
	assert.Equal(t, "second", firstNonEmpty("", "second", "third"))
	assert.Equal(t, "", firstNonEmpty("", ""))
	assert.Equal(t, 2025, parseYear("2025-08-09"))
	assert.Equal(t, 0, parseYear("bad"))
	assert.Equal(t, 0, parseYear("xx25-01-01"))
}

func TestBuildCandidates_CapAndMostRecentFirst(t *testing.T) {
	lib := newTestLib(t)
	base := time.Now()

	var entries []*library.LibraryEntry
	for i := 0; i < 15; i++ {
		// Each entry's UpdatedAt increases with i, so higher i == more recent.
		ts := base.Add(time.Duration(i) * time.Minute)
		entries = append(entries, &library.LibraryEntry{
			ID: string(rune('a' + i)), TmdbID: i, MediaType: "movie",
			Status: library.StatusWatching, AddedAt: ts, UpdatedAt: ts,
		})
	}
	lib.MergeFrom(entries, nil, nil, nil)

	w := New(lib, nil, nil, nil, nil)
	candidates := w.buildCandidates()
	require.Len(t, candidates, maxCandidates)
	// Most recently touched (tmdbID 14) should be first.
	assert.Equal(t, 14, candidates[0].tmdbID)
}
