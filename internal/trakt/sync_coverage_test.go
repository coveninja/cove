package trakt

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coveninja/cove/internal/library"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestSyncCycleGuardsAndNoPullCursorBehavior(t *testing.T) {
	t.Run("disabled", func(t *testing.T) {
		lib, settingsStore := syncLibraryAndSettings(t)
		current := settingsStore.Get()
		current.TraktSyncEnabled = false
		require.NoError(t, settingsStore.Set(current))

		var calls atomic.Int32
		server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
			calls.Add(1)
		}))
		defer server.Close()
		store := testStore(t)
		require.NoError(t, store.Save(tokenState{
			AccessToken: "token", ExpiresAt: time.Now().Add(time.Hour),
		}))
		newSyncWorker(testClient(server.URL, store), store, lib, settingsStore, nil).runCycle()
		assert.Zero(t, calls.Load())
	})

	t.Run("disconnected", func(t *testing.T) {
		lib, settingsStore := syncLibraryAndSettings(t)
		var calls atomic.Int32
		server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
			calls.Add(1)
		}))
		defer server.Close()
		store := testStore(t)
		newSyncWorker(testClient(server.URL, store), store, lib, settingsStore, nil).runCycle()
		assert.Zero(t, calls.Load())
	})

	t.Run("token refresh failure", func(t *testing.T) {
		lib, settingsStore := syncLibraryAndSettings(t)
		var calls atomic.Int32
		server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
			calls.Add(1)
		}))
		defer server.Close()
		store := testStore(t)
		require.NoError(t, store.Save(tokenState{
			AccessToken: "token", ExpiresAt: time.Now().Add(time.Minute),
		}))
		newSyncWorker(testClient(server.URL, store), store, lib, settingsStore, nil).runCycle()
		assert.Zero(t, calls.Load())
	})

	t.Run("last activities failure retains cursor", func(t *testing.T) {
		lib, settingsStore := syncLibraryAndSettings(t)
		cursor := time.Now().UTC().Add(-time.Hour).Truncate(time.Second)
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			http.Error(w, "down", http.StatusBadGateway)
		}))
		defer server.Close()
		store := testStore(t)
		require.NoError(t, store.Save(tokenState{
			AccessToken: "token", ExpiresAt: time.Now().Add(2 * time.Hour), LastSyncAt: cursor,
		}))
		newSyncWorker(testClient(server.URL, store), store, lib, settingsStore, nil).runCycle()
		assert.Equal(t, cursor, store.Get().LastSyncAt)
	})

	t.Run("no remote or local changes still advances cursor", func(t *testing.T) {
		lib, settingsStore := syncLibraryAndSettings(t)
		cursor := time.Now().UTC().Add(-time.Hour).Truncate(time.Second)
		var calls atomic.Int32
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			calls.Add(1)
			assert.Equal(t, "/sync/last_activities", r.URL.Path)
			_, _ = fmt.Fprint(w, `{}`)
		}))
		defer server.Close()
		store := testStore(t)
		require.NoError(t, store.Save(tokenState{
			AccessToken: "token", ExpiresAt: time.Now().Add(2 * time.Hour), LastSyncAt: cursor,
		}))
		before := time.Now()
		newSyncWorker(testClient(server.URL, store), store, lib, settingsStore, nil).runCycle()
		assert.Equal(t, int32(1), calls.Load())
		assert.False(t, store.Get().LastSyncAt.Before(before))
	})

	t.Run("cursor save failure keeps old in-memory cursor", func(t *testing.T) {
		lib, settingsStore := syncLibraryAndSettings(t)
		cursor := time.Now().UTC().Add(-time.Hour).Truncate(time.Second)
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			_, _ = fmt.Fprint(w, `{}`)
		}))
		defer server.Close()
		blocked := t.TempDir()
		require.NoError(t, os.WriteFile(filepath.Join(blocked, "child"), []byte("x"), 0o600))
		store := &Store{
			path: blocked,
			state: tokenState{
				AccessToken: "token", ExpiresAt: time.Now().Add(2 * time.Hour), LastSyncAt: cursor,
			},
		}
		newSyncWorker(testClient(server.URL, store), store, lib, settingsStore, nil).runCycle()
		assert.Equal(t, cursor, store.Get().LastSyncAt)
	})
}

func TestPullWatchlistParsesMovieShowAndSkipsInvalidRows(t *testing.T) {
	lib, settingsStore := syncLibraryAndSettings(t)
	now := time.Now().UTC().Truncate(time.Second)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		fmt.Fprintf(w, `[
			{"listed_at":%q,"type":"movie","movie":{"title":"Movie","ids":{"tmdb":101}}},
			{"listed_at":%q,"type":"show","show":{"title":"Show","ids":{"tmdb":102}}},
			{"type":"movie","movie":null},
			{"type":"movie","movie":{"ids":{"tmdb":0}}},
			{"type":"show","show":null},
			{"type":"show","show":{"ids":{"tmdb":0}}},
			{"type":"episode"}
		]`, now.Format(time.RFC3339), now.Add(time.Minute).Format(time.RFC3339))
	}))
	defer server.Close()

	store := testStore(t)
	worker := newSyncWorker(testClient(server.URL, store), store, lib, settingsStore, nil)
	require.NoError(t, worker.pullWatchlist())

	byID := make(map[int]*library.LibraryEntry)
	for _, entry := range lib.AllEntries() {
		byID[entry.TmdbID] = entry
	}
	require.Len(t, byID, 2)
	assert.Equal(t, "Movie", byID[101].Title)
	assert.Equal(t, "movie", byID[101].MediaType)
	assert.Equal(t, now, byID[101].AddedAt)
	assert.Equal(t, "Show", byID[102].Title)
	assert.Equal(t, "tv", byID[102].MediaType)
	assert.Equal(t, now.Add(time.Minute), byID[102].AddedAt)

	failing := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "down", http.StatusBadGateway)
	}))
	defer failing.Close()
	worker.client = testClient(failing.URL, store)
	require.Error(t, worker.pullWatchlist())
}

func TestPushHistoryFiltersRowsAndReportsFailures(t *testing.T) {
	lib, settingsStore := syncLibraryAndSettings(t)
	now := time.Now().UTC().Truncate(time.Second)
	cursor := now.Add(-time.Hour)
	season, episode := 1, 2
	lib.MergeFrom(nil, []*library.WatchProgress{
		{ID: "incomplete", TmdbID: 1, MediaType: "movie", Completed: false, WatchedAt: now},
		{ID: "old", TmdbID: 2, MediaType: "movie", Completed: true, WatchedAt: now.Add(-2 * time.Hour)},
		{ID: "tv-missing-coordinates", TmdbID: 3, MediaType: "tv", Completed: true, WatchedAt: now},
		{ID: "unknown", TmdbID: 4, MediaType: "album", Completed: true, WatchedAt: now},
	}, nil, nil)

	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		calls.Add(1)
		w.WriteHeader(http.StatusCreated)
	}))
	defer server.Close()
	store := testStore(t)
	worker := newSyncWorker(fastTraktClient(testClient(server.URL, store)), store, lib, settingsStore, nil)
	require.NoError(t, worker.pushHistory(cursor))
	assert.Zero(t, calls.Load(), "filtered history rows should not issue an empty write")

	lib.MergeFrom(nil, []*library.WatchProgress{
		{ID: "movie", TmdbID: 5, MediaType: "movie", Completed: true, WatchedAt: now},
		{ID: "episode", TmdbID: 6, MediaType: "tv", Season: &season, Episode: &episode, Completed: true, WatchedAt: now},
	}, nil, nil)
	failing := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "failed", http.StatusBadGateway)
	}))
	defer failing.Close()
	worker.client = fastTraktClient(testClient(failing.URL, store))
	require.Error(t, worker.pushHistory(cursor))

	closed := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	closedURL := closed.URL
	closed.Close()
	worker.client = fastTraktClient(testClient(closedURL, store))
	require.Error(t, worker.pushHistory(cursor))
}

func TestPushWatchlistFiltersRowsAndReportsFailures(t *testing.T) {
	lib, settingsStore := syncLibraryAndSettings(t)
	now := time.Now().UTC().Truncate(time.Second)
	cursor := now.Add(-time.Hour)
	lib.MergeFrom([]*library.LibraryEntry{
		{ID: "watching", TmdbID: 10, MediaType: "movie", Status: library.StatusWatching, AddedAt: now, UpdatedAt: now},
		{ID: "old", TmdbID: 11, MediaType: "movie", Status: library.StatusWatchLater, AddedAt: now.Add(-2 * time.Hour), UpdatedAt: now},
		{ID: "unknown", TmdbID: 12, MediaType: "album", Status: library.StatusWatchLater, AddedAt: now, UpdatedAt: now},
	}, nil, nil, nil)

	var calls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		calls.Add(1)
		w.WriteHeader(http.StatusCreated)
	}))
	defer server.Close()
	store := testStore(t)
	worker := newSyncWorker(fastTraktClient(testClient(server.URL, store)), store, lib, settingsStore, nil)
	require.NoError(t, worker.pushWatchlist(cursor))
	assert.Zero(t, calls.Load(), "filtered watchlist rows should not issue an empty write")

	lib.MergeFrom([]*library.LibraryEntry{
		{ID: "movie", TmdbID: 13, MediaType: "movie", Status: library.StatusWatchLater, AddedAt: now, UpdatedAt: now},
		{ID: "show", TmdbID: 14, MediaType: "tv", Status: library.StatusWatchLater, AddedAt: now, UpdatedAt: now},
	}, nil, nil, nil)
	failing := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "failed", http.StatusBadGateway)
	}))
	defer failing.Close()
	worker.client = fastTraktClient(testClient(failing.URL, store))
	require.Error(t, worker.pushWatchlist(cursor))

	closed := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	closedURL := closed.URL
	closed.Close()
	worker.client = fastTraktClient(testClient(closedURL, store))
	require.Error(t, worker.pushWatchlist(cursor))
}
