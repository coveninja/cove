package prefetch

import (
	"testing"
	"time"

	"github.com/coveninja/cove/internal/library"
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

	w := New(lib, nil, nil, nil, nil)
	candidates := w.buildCandidates()
	require.Len(t, candidates, 1)
	assert.Equal(t, 3, *candidates[0].season)
	assert.Equal(t, 4, *candidates[0].episode)
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
