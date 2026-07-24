package activity

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/coveninja/cove/internal/library"
	"github.com/coveninja/cove/internal/utils"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type libraryStub struct {
	entries []*library.LibraryEntry
}

func (s libraryStub) AllEntries() []*library.LibraryEntry {
	return s.entries
}

func activityDay(hour int, seconds int64, titles map[string]int64) *DayEntry {
	day := &DayEntry{ByTitle: titles}
	day.ByHour[hour] = seconds
	return day
}

func TestComputeStatsAggregatesAndEnrichesTitles(t *testing.T) {
	store := newTestStore(t)
	now := time.Now()
	today := now
	yesterday := now.AddDate(0, 0, -1)
	lastYear := time.Date(now.Year()-1, time.March, 15, 12, 0, 0, 0, time.Local)
	store.db.Days = map[string]*DayEntry{
		today.Format("2006-01-02"): activityDay(10, 120, map[string]int64{
			"1:movie": 120,
		}),
		yesterday.Format("2006-01-02"): activityDay(11, 60, map[string]int64{
			"2:tv": 60,
		}),
		lastYear.Format("2006-01-02"): activityDay(12, 300, map[string]int64{
			"1:movie": 300,
		}),
		"not-a-date": activityDay(1, 999, map[string]int64{
			"999:movie": 999,
		}),
		"1900-01-01": nil,
	}
	lib := libraryStub{entries: []*library.LibraryEntry{
		{
			TmdbID:     1,
			MediaType:  "movie",
			Title:      "First Movie",
			PosterPath: "https://image.tmdb.org/t/p/w500/poster.jpg",
		},
		{
			TmdbID:     2,
			MediaType:  "tv",
			Title:      "Second Show",
			PosterPath: "/local-poster.jpg",
		},
	}}
	store.SetLibrary(lib)

	store.mu.RLock()
	stats := store.computeStats(store.lib)
	store.mu.RUnlock()

	assert.Equal(t, int64(480), stats.TotalSeconds)
	assert.Equal(t, 2, stats.TotalTitles)
	assert.Equal(t, 2, stats.CurrentStreak)
	assert.Equal(t, 2, stats.LongestStreak)
	assert.Equal(t, int64(160), stats.AvgSecondsPerActiveDay)
	assert.Equal(t, 2, stats.TitlesThisYear)
	assert.Equal(t, int64(180), stats.ThisYearSeconds)
	assert.Equal(t, int64(300), stats.LastYearSeconds)
	assert.Equal(t, int64(180), stats.ByYear[fmt.Sprintf("%d", now.Year())])
	assert.Equal(t, int64(300), stats.ByYear[fmt.Sprintf("%d", now.Year()-1)])
	assert.Equal(t, int64(120), stats.ByHourOfDay[10])
	assert.Equal(t, int64(60), stats.ByHourOfDay[11])
	assert.Equal(t, int64(300), stats.ByHourOfDay[12])
	expectedThisMonth := int64(120)
	if yesterday.Month() == today.Month() {
		expectedThisMonth += 60
	}
	assert.Equal(t, expectedThisMonth, stats.ByMonthThisYear[int(today.Month())-1])
	assert.Equal(t, int64(300), stats.ByMonthLastYear[int(lastYear.Month())-1])
	require.Len(t, stats.TitlesWatchedThisYear, 2)
	assert.Equal(t, TitleSeconds{
		TmdbID:     1,
		MediaType:  "movie",
		Seconds:    120,
		Title:      "First Movie",
		PosterPath: "http://127.0.0.1:6969/api/img/w500/poster.jpg",
	}, stats.TitlesWatchedThisYear[0])
	assert.Equal(t, "/local-poster.jpg", stats.TitlesWatchedThisYear[1].PosterPath)
}

func TestComputeStatsCapsAndOrdersCurrentYearTitles(t *testing.T) {
	store := newTestStore(t)
	today := time.Now().Format("2006-01-02")
	titles := make(map[string]int64)
	for i := 1; i <= 25; i++ {
		titles[fmt.Sprintf("%d:movie", i)] = int64(i)
	}
	store.db.Days = map[string]*DayEntry{
		today: activityDay(8, 325, titles),
	}

	store.mu.RLock()
	stats := store.computeStats(nil)
	store.mu.RUnlock()

	assert.Equal(t, 25, stats.TotalTitles)
	assert.Equal(t, 25, stats.TitlesThisYear)
	require.Len(t, stats.TitlesWatchedThisYear, 20)
	assert.Equal(t, 25, stats.TitlesWatchedThisYear[0].TmdbID)
	assert.Equal(t, int64(25), stats.TitlesWatchedThisYear[0].Seconds)
	assert.Equal(t, 6, stats.TitlesWatchedThisYear[19].TmdbID)
}

func TestActivityHandlerReturnsStatsAndEnforcesMethod(t *testing.T) {
	store := newTestStore(t)
	now := time.Now()
	store.db.Days[now.Format("2006-01-02")] = activityDay(9, 42, map[string]int64{
		"7:movie": 42,
	})
	store.SetLibrary(libraryStub{entries: []*library.LibraryEntry{{
		TmdbID: 7, MediaType: "movie", Title: "The Answer",
	}}})

	mux := http.NewServeMux()
	store.SetupHandlers(mux)

	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/library/activity", nil))
	require.Equal(t, http.StatusOK, rec.Code)
	assert.Equal(t, "application/json", rec.Header().Get("Content-Type"))
	var stats Stats
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &stats))
	assert.Equal(t, int64(42), stats.TotalSeconds)
	require.Len(t, stats.TitlesWatchedThisYear, 1)
	assert.Equal(t, "The Answer", stats.TitlesWatchedThisYear[0].Title)

	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/api/library/activity", nil))
	assert.Equal(t, http.StatusMethodNotAllowed, rec.Code)

	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(http.MethodOptions, "/api/library/activity", nil))
	assert.Equal(t, http.StatusNoContent, rec.Code)
}

func TestFlushPendingAndFlushPersistLatestSnapshot(t *testing.T) {
	store := newTestStore(t)
	at := time.Now()
	store.OnProgressSave(progressEvent("1:movie", 10, at))
	store.flushPending()

	readDisk := func() diskStore {
		t.Helper()
		raw, err := os.ReadFile(store.path)
		require.NoError(t, err)
		var disk diskStore
		require.NoError(t, json.Unmarshal(raw, &disk))
		return disk
	}
	assert.Equal(t, int64(10), dayTotal(readDisk().Days[at.Format("2006-01-02")]))

	store.OnProgressSave(progressEvent("1:movie", 20, at))
	store.Flush()
	assert.Equal(t, int64(20), dayTotal(readDisk().Days[at.Format("2006-01-02")]))

	// A second flush is a harmless no-op once pending data was consumed.
	store.Flush()
}

func TestSetProfileFlushesOldStateAndLoadsNewState(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	first, err := New("first")
	require.NoError(t, err)
	second, err := New("second")
	require.NoError(t, err)
	at := time.Now()

	first.OnProgressSave(progressEvent("1:movie", 60, at))
	firstPath := first.path
	second.OnProgressSave(progressEvent("2:movie", 30, at))
	second.Flush()

	require.NoError(t, first.SetProfile("second"))
	raw, err := os.ReadFile(firstPath)
	require.NoError(t, err)
	var oldDisk diskStore
	require.NoError(t, json.Unmarshal(raw, &oldDisk))
	assert.Equal(t, int64(60), dayTotal(oldDisk.Days[at.Format("2006-01-02")]))

	snapshot, err := first.SnapshotJSON()
	require.NoError(t, err)
	var current diskStore
	require.NoError(t, json.Unmarshal(snapshot, &current))
	assert.Equal(t, float64(30), current.LastPos["2:movie"])
	assert.NotContains(t, current.LastPos, "1:movie")

	require.NoError(t, first.SetProfile("missing"))
	snapshot, err = first.SnapshotJSON()
	require.NoError(t, err)
	current = diskStore{}
	require.NoError(t, json.Unmarshal(snapshot, &current))
	assert.Empty(t, current.Days)
	assert.Empty(t, current.LastPos)
}

func TestNewAndSetProfileRejectMalformedDataAndNormalizeMaps(t *testing.T) {
	t.Run("new normalizes omitted maps", func(t *testing.T) {
		t.Setenv("XDG_CONFIG_HOME", t.TempDir())
		path, err := utils.ConfigPath("activity-normalized.json")
		require.NoError(t, err)
		require.NoError(t, os.WriteFile(path, []byte(`{"days":null,"last_pos":null}`), 0o644))

		store, err := New("normalized")
		require.NoError(t, err)
		require.NotNil(t, store.db.Days)
		require.NotNil(t, store.db.LastPos)
	})

	t.Run("new returns usable store with parse error", func(t *testing.T) {
		t.Setenv("XDG_CONFIG_HOME", t.TempDir())
		path, err := utils.ConfigPath("activity-corrupt.json")
		require.NoError(t, err)
		require.NoError(t, os.WriteFile(path, []byte(`{`), 0o644))

		store, err := New("corrupt")
		require.Error(t, err)
		require.NotNil(t, store)
		require.NotNil(t, store.db.Days)
	})

	t.Run("profile switch preserves current state on parse error", func(t *testing.T) {
		store := newTestStore(t)
		at := time.Now()
		store.OnProgressSave(progressEvent("1:movie", 10, at))
		path, err := utils.ConfigPath("activity-bad-profile.json")
		require.NoError(t, err)
		require.NoError(t, os.WriteFile(path, []byte(`not-json`), 0o644))

		err = store.SetProfile("bad-profile")
		require.Error(t, err)
		store.mu.RLock()
		assert.Equal(t, float64(10), store.db.LastPos["1:movie"])
		store.mu.RUnlock()
		store.Flush()
	})
}

// TestCloneDiskStoreSkipsNilDays verifies that cloneDiskStore silently skips
// nil *DayEntry values (activity.go line 61) while deep-copying the rest. Such
// nil values can appear when the on-disk JSON contains an explicit null for a
// date key. The clone snapshots the pre-merge state MergeFromJSON restores on
// a write failure, so it must not carry nil entries into the rollback target.
func TestCloneDiskStoreSkipsNilDays(t *testing.T) {
	source := diskStore{
		Days: map[string]*DayEntry{
			"nil-day":  nil,
			"real-day": {ByTitle: map[string]int64{"1:movie": 42}},
		},
		LastPos:    map[string]float64{"1:movie": 12.5},
		Backfilled: true,
	}

	cloned := cloneDiskStore(source)

	_, nilDayPresent := cloned.Days["nil-day"]
	assert.False(t, nilDayPresent, "nil DayEntry must be skipped by cloneDiskStore")
	require.NotNil(t, cloned.Days["real-day"], "non-nil DayEntry must be preserved")
	assert.Equal(t, int64(42), cloned.Days["real-day"].ByTitle["1:movie"])
	assert.Equal(t, 12.5, cloned.LastPos["1:movie"])
	assert.True(t, cloned.Backfilled)

	// The clone must be deep: mutating it must not touch the source's maps.
	cloned.Days["real-day"].ByTitle["1:movie"] = 99
	assert.Equal(t, int64(42), source.Days["real-day"].ByTitle["1:movie"],
		"clone must not alias the source's ByTitle map")
}

// TestMergeFromJSONWriteFailureRollsBackState verifies that MergeFromJSON
// propagates a disk-write error and restores the pre-merge snapshot
// (activity.go lines 225-226).
func TestMergeFromJSONWriteFailureRollsBackState(t *testing.T) {
	store := newTestStore(t)
	at := time.Now()

	// Establish a known baseline in both memory and on disk.
	store.OnProgressSave(progressEvent("1:movie", 50, at))
	store.Flush()

	// Make the config directory read-only so AtomicWriteFile cannot create a
	// temp file, causing writeNow to return an error.
	dir := filepath.Dir(store.path)
	require.NoError(t, os.Chmod(dir, 0o555))
	t.Cleanup(func() { _ = os.Chmod(dir, 0o755) })

	// Attempt to merge data that would increment LastPos for a different key.
	remote := diskStore{
		Days:    make(map[string]*DayEntry),
		LastPos: map[string]float64{"2:movie": 100},
	}
	data, err := json.Marshal(remote)
	require.NoError(t, err)

	mergeErr := store.MergeFromJSON(data)
	require.Error(t, mergeErr, "MergeFromJSON must propagate write errors")

	// State must be rolled back: only the original LastPos entry should exist.
	store.mu.RLock()
	pos1 := store.db.LastPos["1:movie"]
	_, has2 := store.db.LastPos["2:movie"]
	store.mu.RUnlock()

	assert.Equal(t, float64(50), pos1, "rollback must restore the pre-merge LastPos")
	assert.False(t, has2, "rolled-back state must not contain the incoming key")
}
