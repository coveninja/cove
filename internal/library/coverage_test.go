package library

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/coveninja/cove/internal/utils"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func libraryRequest(t *testing.T, l *Library, method, target, body string) *httptest.ResponseRecorder {
	t.Helper()
	mux := http.NewServeMux()
	l.SetupHandlers(mux)
	var requestBody *bytes.Reader
	if body == "" {
		requestBody = bytes.NewReader(nil)
	} else {
		requestBody = bytes.NewReader([]byte(body))
	}
	req := httptest.NewRequest(method, target, requestBody)
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	return rr
}

func writeLibraryFixture(t *testing.T, configRoot, profileID, contents string) string {
	t.Helper()
	dir := filepath.Join(configRoot, "cove")
	require.NoError(t, os.MkdirAll(dir, 0o755))
	path := filepath.Join(dir, "library-"+profileID+".json")
	require.NoError(t, os.WriteFile(path, []byte(contents), 0o600))
	return path
}

func TestMergeFromOwnsIncomingRecordsAndSnapshots(t *testing.T) {
	l := newLib(t)
	now := time.Now().UTC()
	profileID := "profile"
	rating := 4.5
	lastWatched := now.Add(time.Minute)
	season := 1
	episode := 2

	entry := &LibraryEntry{
		ID: "entry", ProfileID: &profileID, TmdbID: 10, MediaType: "tv",
		Title: "Original", Rating: &rating, LastWatchedAt: &lastWatched,
		LastWatchedSeason: &season, LastWatchedEpisode: &episode,
		AddedAt: now, UpdatedAt: now,
	}
	progress := &WatchProgress{
		ID: "progress", ProfileID: &profileID, LibraryEntryID: "entry",
		TmdbID: 10, MediaType: "tv", Season: &season, Episode: &episode,
		PositionSeconds: 30, WatchedAt: now,
	}
	dismissal := &Dismissal{TmdbID: 20, MediaType: "movie", DismissedAt: now}
	removal := &Removal{TmdbID: 30, MediaType: "movie", RemovedAt: now}

	l.MergeFrom(
		[]*LibraryEntry{nil, entry},
		[]*WatchProgress{nil, progress},
		[]*Dismissal{nil, dismissal},
		[]*Removal{nil, removal},
	)

	entry.Title = "Mutated"
	*entry.Rating = 1
	*entry.ProfileID = "other"
	*entry.LastWatchedSeason = 9
	progress.PositionSeconds = 999
	*progress.Episode = 8
	dismissal.TmdbID = 21
	removal.TmdbID = 31

	entries := l.AllEntries()
	require.Len(t, entries, 1)
	assert.Equal(t, "Original", entries[0].Title)
	require.NotNil(t, entries[0].Rating)
	assert.Equal(t, 4.5, *entries[0].Rating)
	require.NotNil(t, entries[0].ProfileID)
	assert.Equal(t, "profile", *entries[0].ProfileID)
	require.NotNil(t, entries[0].LastWatchedSeason)
	assert.Equal(t, 1, *entries[0].LastWatchedSeason)

	progressRows := l.AllProgress()
	require.Len(t, progressRows, 1)
	assert.Equal(t, float64(30), progressRows[0].PositionSeconds)
	require.NotNil(t, progressRows[0].Episode)
	assert.Equal(t, 2, *progressRows[0].Episode)
	assert.Equal(t, 20, l.AllDismissals()[0].TmdbID)
	assert.Equal(t, 30, l.AllRemovals()[0].TmdbID)

	// Mutating a returned snapshot, including its nested pointer fields, must
	// never alter the live store.
	entries[0].Title = "Snapshot mutation"
	*entries[0].Rating = 2
	*entries[0].LastWatchedSeason = 7
	progressRows[0].PositionSeconds = 500
	*progressRows[0].Episode = 6

	freshEntry := l.AllEntries()[0]
	assert.Equal(t, "Original", freshEntry.Title)
	assert.Equal(t, 4.5, *freshEntry.Rating)
	assert.Equal(t, 1, *freshEntry.LastWatchedSeason)
	freshProgress := l.AllProgress()[0]
	assert.Equal(t, float64(30), freshProgress.PositionSeconds)
	assert.Equal(t, 2, *freshProgress.Episode)
}

func TestNewNormalizesNullMaps(t *testing.T) {
	configRoot := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", configRoot)
	writeLibraryFixture(t, configRoot, "null-maps",
		`{"entries":null,"progress":null,"dismissed":null,"removed":null}`)

	l, err := New("null-maps")
	require.NoError(t, err)
	require.NotNil(t, l.db.Entries)
	require.NotNil(t, l.db.Progress)
	require.NotNil(t, l.db.Dismissed)
	require.NotNil(t, l.db.Removed)

	require.Equal(t, http.StatusOK, libraryRequest(t, l, http.MethodPost, "/api/library",
		`{"tmdb_id":1,"media_type":"movie","title":"Recovered"}`).Code)
	require.Equal(t, http.StatusOK, libraryRequest(t, l, http.MethodPost, "/api/library/progress",
		`{"tmdb_id":1,"media_type":"movie","position_seconds":5,"duration_seconds":10}`).Code)
	require.Equal(t, http.StatusNoContent, libraryRequest(t, l, http.MethodPost, "/api/library/dismiss",
		`{"tmdb_id":2,"media_type":"tv"}`).Code)
	require.Equal(t, http.StatusNoContent, libraryRequest(t, l, http.MethodDelete,
		"/api/library/1/movie", "").Code)
}

func TestNewDropsNullRecordsFromOtherwiseValidMaps(t *testing.T) {
	configRoot := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", configRoot)
	writeLibraryFixture(t, configRoot, "null-records", `{
		"entries":{"bad":null},
		"progress":{"bad":null},
		"dismissed":{"bad":null},
		"removed":{"bad":null}
	}`)

	l, err := New("null-records")
	require.NoError(t, err)
	assert.Empty(t, l.AllEntries())
	assert.Empty(t, l.AllProgress())
	assert.Empty(t, l.AllDismissals())
	assert.Empty(t, l.AllRemovals())
	require.Equal(t, http.StatusOK, libraryRequest(t, l, http.MethodPost, "/api/library",
		`{"tmdb_id":3,"media_type":"movie","title":"Usable"}`).Code)
}

func TestNewMalformedFileReturnsEmptyUsableFallback(t *testing.T) {
	configRoot := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", configRoot)
	writeLibraryFixture(t, configRoot, "partial",
		`{"entries":{"1:movie":{"id":"partial","tmdb_id":1,"media_type":"movie"}},"progress":`)

	l, err := New("partial")
	require.Error(t, err)
	require.NotNil(t, l)
	assert.Empty(t, l.AllEntries(), "partially decoded records must not leak into the fallback")
	require.NotNil(t, l.db.Entries)
	require.NotNil(t, l.db.Progress)
}

func TestSetProfileRejectsCorruptTargetAndNormalizesNullMaps(t *testing.T) {
	l := newLib(t)
	require.Equal(t, http.StatusOK, libraryRequest(t, l, http.MethodPost, "/api/library",
		`{"tmdb_id":7,"media_type":"movie","title":"Original profile"}`).Code)

	dir := filepath.Dir(l.path)
	require.NoError(t, os.WriteFile(filepath.Join(dir, "library-corrupt.json"), []byte("{"), 0o600))
	require.Error(t, l.SetProfile("corrupt"))
	require.Len(t, l.AllEntries(), 1)
	assert.Equal(t, "Original profile", l.AllEntries()[0].Title)

	require.NoError(t, os.WriteFile(filepath.Join(dir, "library-null-target.json"),
		[]byte(`{"entries":null,"progress":null,"dismissed":null,"removed":null}`), 0o600))
	gen := l.Generation()
	tasteGen := l.TasteGeneration()
	require.NoError(t, l.SetProfile("null-target"))
	assert.Empty(t, l.AllEntries())
	require.NotNil(t, l.db.Entries)
	require.NotNil(t, l.db.Progress)
	require.NotNil(t, l.db.Dismissed)
	require.NotNil(t, l.db.Removed)
	assert.Greater(t, l.Generation(), gen)
	assert.Greater(t, l.TasteGeneration(), tasteGen)
}

func TestMergeFromTombstoneOrdering(t *testing.T) {
	base := time.Now().UTC()
	entryAt := func(at time.Time) *LibraryEntry {
		return &LibraryEntry{
			ID: "entry", TmdbID: 42, MediaType: "movie", Title: "Title",
			Status: StatusWatchLater, AddedAt: base, UpdatedAt: at,
		}
	}
	removalAt := func(at time.Time) *Removal {
		return &Removal{TmdbID: 42, MediaType: "movie", RemovedAt: at}
	}

	t.Run("newest local tombstone rejects older entry and removal", func(t *testing.T) {
		l := newLib(t)
		l.MergeFrom(nil, nil, nil, []*Removal{removalAt(base.Add(3 * time.Hour))})
		gen := l.Generation()
		l.MergeFrom(
			[]*LibraryEntry{entryAt(base.Add(time.Hour))},
			nil, nil,
			[]*Removal{removalAt(base.Add(2 * time.Hour))},
		)
		assert.Empty(t, l.AllEntries())
		require.Len(t, l.AllRemovals(), 1)
		assert.Equal(t, base.Add(3*time.Hour), l.AllRemovals()[0].RemovedAt)
		assert.Equal(t, gen, l.Generation(), "older remote state must be a no-op")
	})

	t.Run("entry newer than both tombstones is a re-add", func(t *testing.T) {
		l := newLib(t)
		l.MergeFrom(nil, nil, nil, []*Removal{removalAt(base.Add(time.Hour))})
		l.MergeFrom(
			[]*LibraryEntry{entryAt(base.Add(3 * time.Hour))},
			nil, nil,
			[]*Removal{removalAt(base.Add(2 * time.Hour))},
		)
		require.Len(t, l.AllEntries(), 1)
		assert.Empty(t, l.AllRemovals())
	})

	t.Run("removal wins an exact timestamp tie", func(t *testing.T) {
		l := newLib(t)
		l.MergeFrom([]*LibraryEntry{entryAt(base.Add(time.Hour))}, nil, nil, nil)
		l.MergeFrom(nil, nil, nil, []*Removal{removalAt(base.Add(time.Hour))})
		assert.Empty(t, l.AllEntries())
		require.Len(t, l.AllRemovals(), 1)
	})

	t.Run("older removal cannot erase a newer local re-add", func(t *testing.T) {
		l := newLib(t)
		l.MergeFrom([]*LibraryEntry{entryAt(base.Add(2 * time.Hour))}, nil, nil, nil)
		gen := l.Generation()
		l.MergeFrom(nil, nil, nil, []*Removal{removalAt(base.Add(time.Hour))})
		require.Len(t, l.AllEntries(), 1)
		assert.Empty(t, l.AllRemovals())
		assert.Equal(t, gen, l.Generation())
	})
}

func TestTasteSignalsCombineLatestInteractionsAndDoNotAliasRatings(t *testing.T) {
	l := newLib(t)
	oldAddr := utils.LocalAddr()
	utils.SetLocalAddr("127.0.0.1:7777")
	t.Cleanup(func() { utils.SetLocalAddr(oldAddr) })

	base := time.Now().UTC()
	rating := 4.5
	lastWatched := base.Add(2 * time.Hour)
	season := 1
	episode1, episode2 := 1, 2
	l.MergeFrom([]*LibraryEntry{
		{
			ID: "one", TmdbID: 1, MediaType: "movie", Title: "One",
			PosterPath: "https://image.tmdb.org/t/p/w500/one.jpg",
			Status:     StatusFinished, Rating: &rating, LastWatchedAt: &lastWatched,
			AddedAt: base, UpdatedAt: base.Add(time.Hour),
		},
		{
			ID: "two", TmdbID: 2, MediaType: "tv", Title: "Two",
			Status: StatusWatching, AddedAt: base, UpdatedAt: base.Add(time.Hour),
		},
	}, []*WatchProgress{
		{ID: "one-progress", TmdbID: 1, MediaType: "movie", Completed: true, WatchedAt: base},
		{ID: "two-progress", TmdbID: 2, MediaType: "tv", Completed: true, WatchedAt: base.Add(3 * time.Hour)},
		{ID: "three-a", TmdbID: 3, MediaType: "tv", Season: &season, Episode: &episode1, Completed: true, WatchedAt: base.Add(2 * time.Hour)},
		{ID: "three-b", TmdbID: 3, MediaType: "tv", Season: &season, Episode: &episode2, Completed: true, WatchedAt: base.Add(4 * time.Hour)},
		{ID: "incomplete", TmdbID: 4, MediaType: "movie", Completed: false, WatchedAt: base.Add(5 * time.Hour)},
	}, []*Dismissal{
		{TmdbID: 1, MediaType: "movie", DismissedAt: base.Add(5 * time.Hour)},
		{TmdbID: 5, MediaType: "movie", DismissedAt: base.Add(6 * time.Hour)},
	}, nil)

	byID := make(map[int]TasteSignal)
	for _, signal := range l.TasteSignals() {
		byID[signal.TmdbID] = signal
	}
	require.Len(t, byID, 4)

	one := byID[1]
	assert.True(t, one.Completed)
	assert.True(t, one.Dismissed)
	assert.Equal(t, base.Add(5*time.Hour), one.LastInteractionAt)
	assert.Equal(t, "http://127.0.0.1:7777/api/img/w500/one.jpg", one.PosterPath)
	require.NotNil(t, one.UserRating)
	assert.Equal(t, 4.5, *one.UserRating)
	assert.Equal(t, base.Add(3*time.Hour), byID[2].LastInteractionAt)
	assert.Equal(t, base.Add(4*time.Hour), byID[3].LastInteractionAt)
	assert.True(t, byID[5].Dismissed)
	assert.Equal(t, base.Add(6*time.Hour), byID[5].LastInteractionAt)

	*one.UserRating = 1
	var storedOne *LibraryEntry
	for _, entry := range l.AllEntries() {
		if entry.TmdbID == 1 {
			storedOne = entry
			break
		}
	}
	require.NotNil(t, storedOne)
	assert.Equal(t, 4.5, *storedOne.Rating,
		"TasteSignals must not expose the live stored rating pointer")
}

func TestReaddPathsClearTombstonesAndRelinkRetainedProgress(t *testing.T) {
	t.Run("progress endpoint", func(t *testing.T) {
		l := newLib(t)
		body := `{"tmdb_id":60,"media_type":"movie","position_seconds":10,"duration_seconds":100}`
		require.Equal(t, http.StatusOK,
			libraryRequest(t, l, http.MethodPost, "/api/library/progress", body).Code)
		oldEntryID := l.AllEntries()[0].ID
		require.Equal(t, oldEntryID, l.AllProgress()[0].LibraryEntryID)
		require.Equal(t, http.StatusNoContent,
			libraryRequest(t, l, http.MethodDelete, "/api/library/60/movie", "").Code)
		require.Len(t, l.AllRemovals(), 1)

		tasteGen := l.TasteGeneration()
		require.Equal(t, http.StatusOK,
			libraryRequest(t, l, http.MethodPost, "/api/library/progress", body).Code)
		require.Len(t, l.AllEntries(), 1)
		newEntryID := l.AllEntries()[0].ID
		assert.NotEqual(t, oldEntryID, newEntryID)
		assert.Empty(t, l.AllRemovals())
		require.Len(t, l.AllProgress(), 1)
		assert.Equal(t, newEntryID, l.AllProgress()[0].LibraryEntryID)
		assert.Greater(t, l.TasteGeneration(), tasteGen,
			"auto-creating the replacement entry must invalidate taste data")
	})

	t.Run("external completion", func(t *testing.T) {
		l := newLib(t)
		watchedAt := time.Now().UTC()
		season, episode := 1, 2
		l.MarkExternallyWatched(61, "tv", &season, &episode, "External", "", watchedAt)
		oldEntryID := l.AllEntries()[0].ID
		require.Equal(t, http.StatusNoContent,
			libraryRequest(t, l, http.MethodDelete, "/api/library/61/tv", "").Code)
		require.Len(t, l.AllRemovals(), 1)

		gen := l.Generation()
		tasteGen := l.TasteGeneration()
		// The retained completion has the same timestamp, so only the entry
		// recreation and progress relink constitute new state.
		l.MarkExternallyWatched(61, "tv", &season, &episode, "External", "", watchedAt)
		require.Len(t, l.AllEntries(), 1)
		newEntryID := l.AllEntries()[0].ID
		assert.NotEqual(t, oldEntryID, newEntryID)
		assert.Empty(t, l.AllRemovals())
		assert.Equal(t, newEntryID, l.AllProgress()[0].LibraryEntryID)
		assert.Greater(t, l.Generation(), gen)
		assert.Greater(t, l.TasteGeneration(), tasteGen)

		season, episode = 8, 9
		assert.Equal(t, 1, *l.AllProgress()[0].Season,
			"external caller pointers must not alias stored progress")
		assert.Equal(t, 2, *l.AllProgress()[0].Episode)
	})

	t.Run("external watchlist", func(t *testing.T) {
		l := newLib(t)
		watchedAt := time.Now().UTC()
		l.MarkExternallyWatched(62, "movie", nil, nil, "Watchlist", "", watchedAt)
		oldEntryID := l.AllEntries()[0].ID
		require.Equal(t, http.StatusNoContent,
			libraryRequest(t, l, http.MethodDelete, "/api/library/62/movie", "").Code)

		tasteGen := l.TasteGeneration()
		l.AddWatchLater(62, "movie", "Watchlist", "", watchedAt)
		require.Len(t, l.AllEntries(), 1)
		newEntryID := l.AllEntries()[0].ID
		assert.NotEqual(t, oldEntryID, newEntryID)
		assert.Empty(t, l.AllRemovals())
		assert.Equal(t, newEntryID, l.AllProgress()[0].LibraryEntryID)
		assert.Greater(t, l.TasteGeneration(), tasteGen)

		unchangedTasteGen := l.TasteGeneration()
		l.AddWatchLater(62, "movie", "Ignored duplicate", "", watchedAt)
		assert.Equal(t, unchangedTasteGen, l.TasteGeneration())
	})
}

func TestExternalCompletionNoOpDoesNotInvalidateGenerations(t *testing.T) {
	l := newLib(t)
	watchedAt := time.Now().UTC()
	l.MarkExternallyWatched(70, "movie", nil, nil, "Completed", "", watchedAt)
	gen := l.Generation()
	tasteGen := l.TasteGeneration()

	l.MarkExternallyWatched(70, "movie", nil, nil, "Ignored", "", watchedAt)

	assert.Equal(t, gen, l.Generation())
	assert.Equal(t, tasteGen, l.TasteGeneration())
}

func TestCollectionAndProgressRequestBoundaries(t *testing.T) {
	l := newLib(t)

	for name, tc := range map[string]struct {
		method string
		target string
		body   string
	}{
		"malformed collection": {http.MethodPost, "/api/library", "{"},
		"missing id":           {http.MethodPost, "/api/library", `{"media_type":"movie"}`},
		"invalid media":        {http.MethodPost, "/api/library", `{"tmdb_id":1,"media_type":"album"}`},
		"invalid status":       {http.MethodPost, "/api/library", `{"tmdb_id":1,"media_type":"movie","status":"paused"}`},
		"bad progress id":      {http.MethodGet, "/api/library/progress?tmdb_id=nope&media_type=movie", ""},
		"missing media":        {http.MethodGet, "/api/library/progress?tmdb_id=1", ""},
		"bad season":           {http.MethodGet, "/api/library/progress?tmdb_id=1&media_type=tv&season=nope", ""},
		"bad episode":          {http.MethodGet, "/api/library/progress?tmdb_id=1&media_type=tv&episode=nope", ""},
		"malformed progress":   {http.MethodPost, "/api/library/progress", "{"},
		"negative position":    {http.MethodPost, "/api/library/progress", `{"tmdb_id":1,"media_type":"movie","position_seconds":-1}`},
		"negative duration":    {http.MethodPost, "/api/library/progress", `{"tmdb_id":1,"media_type":"movie","duration_seconds":-1}`},
	} {
		t.Run(name, func(t *testing.T) {
			assert.Equal(t, http.StatusBadRequest,
				libraryRequest(t, l, tc.method, tc.target, tc.body).Code)
		})
	}

	first := libraryRequest(t, l, http.MethodPost, "/api/library",
		`{"tmdb_id":10,"media_type":"movie","title":"Default status"}`)
	require.Equal(t, http.StatusOK, first.Code)
	var entry LibraryEntry
	require.NoError(t, json.NewDecoder(first.Body).Decode(&entry))
	assert.Equal(t, StatusWatchLater, entry.Status)

	second := libraryRequest(t, l, http.MethodPost, "/api/library",
		`{"tmdb_id":11,"media_type":"tv","title":"Watching","status":"watching","last_aired_season":3,"last_aired_episode":8}`)
	require.Equal(t, http.StatusOK, second.Code)
	filtered := libraryRequest(t, l, http.MethodGet, "/api/library?status=watching", "")
	var entries []*LibraryEntry
	require.NoError(t, json.NewDecoder(filtered.Body).Decode(&entries))
	require.Len(t, entries, 1)
	assert.Equal(t, 11, entries[0].TmdbID)
	assert.Equal(t, 3, *entries[0].LastAiredSeason)
	assert.Equal(t, 8, *entries[0].LastAiredEpisode)
	assert.Equal(t, http.StatusMethodNotAllowed,
		libraryRequest(t, l, http.MethodPut, "/api/library", `{}`).Code)

	notFound := libraryRequest(t, l, http.MethodGet,
		"/api/library/progress?tmdb_id=99&media_type=movie", "")
	require.Equal(t, http.StatusOK, notFound.Code)
	assert.JSONEq(t, "null", notFound.Body.String())

	progressBody := `{
		"tmdb_id":20,"media_type":"tv","title":"Episode",
		"season":2,"episode":4,"position_seconds":90,"duration_seconds":100,
		"last_air_date":"2026-07-01","last_aired_season":3,"last_aired_episode":7
	}`
	require.Equal(t, http.StatusOK,
		libraryRequest(t, l, http.MethodPost, "/api/library/progress", progressBody).Code)
	require.Equal(t, http.StatusOK,
		libraryRequest(t, l, http.MethodPost, "/api/library/progress",
			`{"tmdb_id":20,"media_type":"tv","season":2,"episode":4,"position_seconds":95,"duration_seconds":100}`).Code)

	found := libraryRequest(t, l, http.MethodGet,
		"/api/library/progress?tmdb_id=20&media_type=tv&season=2&episode=4", "")
	require.Equal(t, http.StatusOK, found.Code)
	var progress WatchProgress
	require.NoError(t, json.NewDecoder(found.Body).Decode(&progress))
	assert.Equal(t, float64(95), progress.PositionSeconds)
	assert.Equal(t, 2, *progress.Season)
	assert.Equal(t, 4, *progress.Episode)
	assert.Equal(t, http.StatusMethodNotAllowed,
		libraryRequest(t, l, http.MethodPut, "/api/library/progress", `{}`).Code)
}

func TestItemDismissAndStatsRequestBoundaries(t *testing.T) {
	l := newLib(t)
	require.Equal(t, http.StatusOK, libraryRequest(t, l, http.MethodPost, "/api/library",
		`{"tmdb_id":30,"media_type":"movie","title":"Boundaries"}`).Code)

	cases := []struct {
		method string
		target string
		body   string
		status int
	}{
		{http.MethodGet, "/api/library/30/album", "", http.StatusBadRequest},
		{http.MethodPatch, "/api/library/30/movie/status", "{", http.StatusBadRequest},
		{http.MethodPatch, "/api/library/30/movie/status", `{"status":"paused"}`, http.StatusBadRequest},
		{http.MethodPatch, "/api/library/30/movie/rating", "{", http.StatusBadRequest},
		{http.MethodPatch, "/api/library/999/movie/rating", `{"rating":4}`, http.StatusNotFound},
		{http.MethodPost, "/api/library/30/movie", `{}`, http.StatusMethodNotAllowed},
		{http.MethodPost, "/api/library/dismiss", "{", http.StatusBadRequest},
		{http.MethodPost, "/api/library/dismiss", `{"tmdb_id":31,"media_type":"album"}`, http.StatusBadRequest},
		{http.MethodGet, "/api/library/dismiss", "", http.StatusMethodNotAllowed},
		{http.MethodPost, "/api/library/stats", `{}`, http.StatusMethodNotAllowed},
	}
	for _, tc := range cases {
		rr := libraryRequest(t, l, tc.method, tc.target, tc.body)
		assert.Equal(t, tc.status, rr.Code, "%s %s: %s", tc.method, tc.target, rr.Body.String())
	}
}
