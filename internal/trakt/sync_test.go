package trakt

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sort"
	"testing"
	"time"

	"github.com/coveninja/cove/internal/library"
	"github.com/coveninja/cove/internal/settings"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func syncLibraryAndSettings(t *testing.T) (*library.Library, *settings.Store) {
	t.Helper()
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	lib, err := library.New("trakt-sync")
	require.NoError(t, err)
	st, err := settings.New("trakt-sync")
	require.NoError(t, err)
	configured := st.Get()
	configured.TraktSyncEnabled = true
	require.NoError(t, st.Set(configured))
	return lib, st
}

func TestSyncCyclePullsPushesAndAdvancesCursor(t *testing.T) {
	lib, st := syncLibraryAndSettings(t)
	now := time.Now().UTC().Truncate(time.Second)
	cursor := now.Add(-time.Hour)
	localNew := now.Add(-10 * time.Minute)
	localOld := now.Add(-2 * time.Hour)
	season, episode := 1, 3
	lib.MergeFrom(
		[]*library.LibraryEntry{
			{ID: "new-watchlist", TmdbID: 301, MediaType: "movie", Status: library.StatusWatchLater, AddedAt: localNew, UpdatedAt: localNew},
			{ID: "old-watchlist", TmdbID: 302, MediaType: "tv", Status: library.StatusWatchLater, AddedAt: localOld, UpdatedAt: localOld},
		},
		[]*library.WatchProgress{
			{ID: "new-progress", LibraryEntryID: "new", TmdbID: 401, MediaType: "movie", Completed: true, WatchedAt: localNew},
			{ID: "old-progress", LibraryEntryID: "old", TmdbID: 402, MediaType: "tv", Season: &season, Episode: &episode, Completed: true, WatchedAt: localOld},
		}, nil, nil,
	)

	var pushedHistory, pushedWatchlist map[string]json.RawMessage
	historyPages := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/sync/last_activities":
			fmt.Fprintf(w, `{"movies":{"watched_at":%q,"watchlisted_at":%q},"shows":{"watchlisted_at":%q},"episodes":{"watched_at":%q}}`, now.Format(time.RFC3339), now.Format(time.RFC3339), now.Format(time.RFC3339), now.Format(time.RFC3339))
		case r.Method == http.MethodGet && r.URL.Path == "/sync/history":
			historyPages++
			assert.Equal(t, cursor.Format(time.RFC3339), r.URL.Query().Get("start_at"))
			w.Header().Set("X-Pagination-Page-Count", "2")
			if r.URL.Query().Get("page") == "1" {
				fmt.Fprintf(w, `[{"watched_at":%q,"type":"movie","movie":{"title":"Remote Movie","ids":{"tmdb":201}}},{"type":"movie","movie":null}]`, now.Add(-30*time.Minute).Format(time.RFC3339))
			} else {
				fmt.Fprintf(w, `[{"watched_at":%q,"type":"episode","show":{"title":"Remote Show","ids":{"tmdb":202}},"episode":{"season":2,"number":4}}]`, now.Add(-20*time.Minute).Format(time.RFC3339))
			}
		case r.Method == http.MethodGet && r.URL.Path == "/sync/watchlist":
			fmt.Fprintf(w, `[{"listed_at":%q,"type":"show","show":{"title":"Remote Watchlist","ids":{"tmdb":203}}},{"type":"unknown"}]`, now.Add(-15*time.Minute).Format(time.RFC3339))
		case r.Method == http.MethodPost && r.URL.Path == "/sync/history":
			require.NoError(t, json.NewDecoder(r.Body).Decode(&pushedHistory))
			w.WriteHeader(http.StatusCreated)
		case r.Method == http.MethodPost && r.URL.Path == "/sync/watchlist":
			require.NoError(t, json.NewDecoder(r.Body).Decode(&pushedWatchlist))
			w.WriteHeader(http.StatusCreated)
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	store := testStore(t)
	require.NoError(t, store.Save(tokenState{
		AccessToken: "token", ExpiresAt: now.Add(24 * time.Hour), LastSyncAt: cursor,
	}))
	client := testClient(srv.URL, store)
	worker := newSyncWorker(client, store, lib, st, nil)
	before := time.Now()
	worker.runCycle()

	assert.Equal(t, 2, historyPages)
	assert.True(t, store.Get().LastSyncAt.After(before) || store.Get().LastSyncAt.Equal(before))
	entries := lib.AllEntries()
	entryIDs := make([]int, 0, len(entries))
	for _, entry := range entries {
		entryIDs = append(entryIDs, entry.TmdbID)
	}
	sort.Ints(entryIDs)
	assert.Contains(t, entryIDs, 201)
	assert.Contains(t, entryIDs, 202)
	assert.Contains(t, entryIDs, 203)

	var historyMovies []struct {
		IDs map[string]int `json:"ids"`
	}
	require.NoError(t, json.Unmarshal(pushedHistory["movies"], &historyMovies))
	var pushedMovieIDs []int
	for _, movie := range historyMovies {
		pushedMovieIDs = append(pushedMovieIDs, movie.IDs["tmdb"])
	}
	assert.Contains(t, pushedMovieIDs, 401)

	var historyShows []struct {
		IDs map[string]int `json:"ids"`
	}
	require.NoError(t, json.Unmarshal(pushedHistory["shows"], &historyShows))
	var pushedShowIDs []int
	for _, show := range historyShows {
		pushedShowIDs = append(pushedShowIDs, show.IDs["tmdb"])
	}
	assert.NotContains(t, pushedShowIDs, 402)

	var watchlistMovies []struct {
		IDs map[string]int `json:"ids"`
	}
	require.NoError(t, json.Unmarshal(pushedWatchlist["movies"], &watchlistMovies))
	require.Len(t, watchlistMovies, 1)
	assert.Equal(t, 301, watchlistMovies[0].IDs["tmdb"])

	var watchlistShows []struct {
		IDs map[string]int `json:"ids"`
	}
	require.NoError(t, json.Unmarshal(pushedWatchlist["shows"], &watchlistShows))
	require.Len(t, watchlistShows, 1)
	assert.Equal(t, 203, watchlistShows[0].IDs["tmdb"])
	assert.NotEqual(t, 302, watchlistShows[0].IDs["tmdb"])
}

func TestSyncCycleRetainsCursorWhenAnyLegFails(t *testing.T) {
	lib, st := syncLibraryAndSettings(t)
	cursor := time.Now().UTC().Add(-time.Hour).Truncate(time.Second)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/sync/last_activities":
			fmt.Fprintf(w, `{"movies":{"watched_at":%q}}`, time.Now().UTC().Format(time.RFC3339))
		case "/sync/history":
			http.Error(w, "temporary failure", http.StatusBadGateway)
		case "/sync/watchlist":
			fmt.Fprint(w, `[]`)
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	store := testStore(t)
	require.NoError(t, store.Save(tokenState{
		AccessToken: "token", ExpiresAt: time.Now().Add(24 * time.Hour), LastSyncAt: cursor,
	}))
	worker := newSyncWorker(testClient(srv.URL, store), store, lib, st, nil)
	worker.runCycle()

	assert.Equal(t, cursor, store.Get().LastSyncAt)
}

func TestSyncNotifyCoalesces(t *testing.T) {
	worker := &SyncWorker{notify: make(chan struct{}, 1)}
	worker.Notify()
	worker.Notify()
	assert.Len(t, worker.notify, 1)
}
