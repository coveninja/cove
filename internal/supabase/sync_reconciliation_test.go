//go:build supabase

package supabase

import (
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/coveninja/cove/internal/library"
	"github.com/coveninja/cove/internal/settings"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func reconciliationLibrary(t *testing.T) *library.Library {
	t.Helper()
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	lib, err := library.New("profile-1")
	require.NoError(t, err)
	now := time.Date(2026, time.July, 23, 12, 0, 0, 0, time.UTC)
	season, episode := 2, 7
	lib.MergeFrom(
		[]*library.LibraryEntry{{
			ID: "local-entry", TmdbID: 42, MediaType: "tv", Title: "Series",
			Status: library.StatusWatching, AddedAt: now, UpdatedAt: now,
		}},
		[]*library.WatchProgress{{
			ID: "local-progress", LibraryEntryID: "local-entry",
			TmdbID: 42, MediaType: "tv", Season: &season, Episode: &episode,
			PositionSeconds: 90, DurationSeconds: 120, WatchedAt: now,
		}},
		nil,
		nil,
	)
	return lib
}

func restTable(r *http.Request) string {
	return strings.TrimPrefix(r.URL.Path, "/rest/v1/")
}

func decodeRequestRows(t *testing.T, r *http.Request) []map[string]any {
	t.Helper()
	var rows []map[string]any
	require.NoError(t, json.NewDecoder(r.Body).Decode(&rows))
	return rows
}

func TestFetchRemoteAndOwnedIDs(t *testing.T) {
	var queries []url.Values
	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		queries = append(queries, r.URL.Query())
		switch restTable(r) {
		case "watch_progress":
			return response(http.StatusOK, `[
				{"id":"progress-1","tmdb_id":42,"media_type":"tv","season":2,"episode":7},
				{"id":"progress-2","tmdb_id":99,"media_type":"movie","season":null,"episode":null}
			]`), nil
		case "library_entries":
			return response(http.StatusOK, `[{"id":"entry-1"},{"id":"entry-2"}]`), nil
		default:
			return response(http.StatusNotFound, `{}`), nil
		}
	})

	cfg := &Config{URL: "https://project.invalid", AnonKey: "anon"}
	remote, err := cfg.fetchRemoteRowIDs("jwt", "watch_progress", "profile/one", true)
	require.NoError(t, err)
	require.Len(t, remote, 2)
	assert.Equal(t, "progress-1", remote[0].ID)
	assert.Equal(t, 42, remote[0].TmdbID)
	require.NotNil(t, remote[0].Season)
	require.NotNil(t, remote[0].Episode)
	assert.Equal(t, 2, *remote[0].Season)
	assert.Equal(t, 7, *remote[0].Episode)
	assert.Nil(t, remote[1].Season)
	assert.Nil(t, remote[1].Episode)

	owned, err := cfg.fetchOwnedIDs("jwt", "library_entries", "profile/one")
	require.NoError(t, err)
	assert.Equal(t, map[string]bool{"entry-1": true, "entry-2": true}, owned)

	require.Len(t, queries, 2)
	assert.Equal(t, "eq.profile/one", queries[0].Get("profile_id"))
	assert.Equal(t, "id,tmdb_id,media_type,season,episode", queries[0].Get("select"))
	assert.Equal(t, "id", queries[1].Get("select"))
}

func TestIDDiscoveryRejectsMalformedRows(t *testing.T) {
	tests := []struct {
		name string
		body string
		call func(*Config) error
		want string
	}{
		{
			name: "remote row missing ID",
			body: `[{"tmdb_id":42,"media_type":"movie"}]`,
			call: func(cfg *Config) error {
				_, err := cfg.fetchRemoteRowIDs("jwt", "library_entries", "profile-1", false)
				return err
			},
			want: "invalid remote library_entries row ID",
		},
		{
			name: "remote row missing natural key",
			body: `[{"id":"entry-1","tmdb_id":0,"media_type":""}]`,
			call: func(cfg *Config) error {
				_, err := cfg.fetchRemoteRowIDs("jwt", "library_entries", "profile-1", false)
				return err
			},
			want: "invalid remote library_entries row ID",
		},
		{
			name: "owned row wrong ID type",
			body: `[{"id":42}]`,
			call: func(cfg *Config) error {
				_, err := cfg.fetchOwnedIDs("jwt", "library_entries", "profile-1")
				return err
			},
			want: "decode owned library_entries row ID",
		},
		{
			name: "owned row missing ID",
			body: `[{}]`,
			call: func(cfg *Config) error {
				_, err := cfg.fetchOwnedIDs("jwt", "library_entries", "profile-1")
				return err
			},
			want: "invalid owned library_entries row ID",
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
				return response(http.StatusOK, tt.body), nil
			})
			err := tt.call(&Config{URL: "https://project.invalid", AnonKey: "anon"})
			require.Error(t, err)
			assert.Contains(t, err.Error(), tt.want)
		})
	}
}

func TestPushEntriesRepairsRLSAndUniqueConflicts(t *testing.T) {
	tests := []struct {
		name               string
		status             int
		errorBody          string
		remoteEntries      string
		remoteProgress     string
		wantEntryID        string
		wantProgressID     string
		wantEntryIDChanged bool
	}{
		{
			name: "RLS regenerates unowned IDs", status: http.StatusForbidden,
			errorBody:          `{"code":"42501"}`,
			remoteEntries:      `[]`,
			remoteProgress:     `[]`,
			wantEntryIDChanged: true,
		},
		{
			name: "unique violation adopts remote IDs", status: http.StatusConflict,
			errorBody:      `{"code":"23505"}`,
			remoteEntries:  `[{"id":"remote-entry","tmdb_id":42,"media_type":"tv"}]`,
			remoteProgress: `[{"id":"remote-progress","tmdb_id":42,"media_type":"tv","season":2,"episode":7}]`,
			wantEntryID:    "remote-entry",
			wantProgressID: "remote-progress",
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			lib := reconciliationLibrary(t)
			var posts [][]map[string]any
			withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
				table := restTable(r)
				switch {
				case r.Method == http.MethodPost && table == "library_entries":
					posts = append(posts, decodeRequestRows(t, r))
					if len(posts) == 1 {
						return response(tt.status, tt.errorBody), nil
					}
					return response(http.StatusOK, `[]`), nil
				case r.Method == http.MethodGet && table == "library_entries":
					return response(http.StatusOK, tt.remoteEntries), nil
				case r.Method == http.MethodGet && table == "watch_progress":
					return response(http.StatusOK, tt.remoteProgress), nil
				default:
					return response(http.StatusNotFound, `{}`), nil
				}
			})

			cfg := &Config{URL: "https://project.invalid", AnonKey: "anon"}
			require.NoError(t, cfg.pushEntries("jwt", "profile-1", lib))
			require.Len(t, posts, 2)
			require.Len(t, posts[0], 1)
			require.Len(t, posts[1], 1)
			assert.Equal(t, "local-entry", posts[0][0]["id"])

			entry := lib.AllEntries()[0]
			progress := lib.AllProgress()[0]
			assert.Equal(t, entry.ID, posts[1][0]["id"])
			assert.Equal(t, entry.ID, progress.LibraryEntryID)
			if tt.wantEntryIDChanged {
				assert.NotEqual(t, "local-entry", entry.ID)
				assert.NotEqual(t, "local-progress", progress.ID)
			} else {
				assert.Equal(t, tt.wantEntryID, entry.ID)
				assert.Equal(t, tt.wantProgressID, progress.ID)
			}
		})
	}
}

func TestPushProgressRepairsRLSAndUniqueConflicts(t *testing.T) {
	tests := []struct {
		name           string
		status         int
		errorBody      string
		remoteEntries  string
		remoteProgress string
		wantProgressID string
	}{
		{
			name: "RLS regenerates unowned progress ID", status: http.StatusForbidden,
			errorBody:      `{"code":"42501"}`,
			remoteEntries:  `[{"id":"local-entry"}]`,
			remoteProgress: `[]`,
		},
		{
			name: "unique violation adopts progress ID", status: http.StatusConflict,
			errorBody:      `{"code":"23505"}`,
			remoteEntries:  `[{"id":"local-entry","tmdb_id":42,"media_type":"tv"}]`,
			remoteProgress: `[{"id":"remote-progress","tmdb_id":42,"media_type":"tv","season":2,"episode":7}]`,
			wantProgressID: "remote-progress",
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			lib := reconciliationLibrary(t)
			var posts [][]map[string]any
			withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
				table := restTable(r)
				switch {
				case r.Method == http.MethodPost && table == "watch_progress":
					posts = append(posts, decodeRequestRows(t, r))
					if len(posts) == 1 {
						return response(tt.status, tt.errorBody), nil
					}
					return response(http.StatusOK, `[]`), nil
				case r.Method == http.MethodGet && table == "library_entries":
					return response(http.StatusOK, tt.remoteEntries), nil
				case r.Method == http.MethodGet && table == "watch_progress":
					return response(http.StatusOK, tt.remoteProgress), nil
				default:
					return response(http.StatusNotFound, `{}`), nil
				}
			})

			cfg := &Config{URL: "https://project.invalid", AnonKey: "anon"}
			require.NoError(t, cfg.pushProgress("jwt", "profile-1", lib))
			require.Len(t, posts, 2)
			progress := lib.AllProgress()[0]
			assert.Equal(t, progress.ID, posts[1][0]["id"])
			if tt.wantProgressID == "" {
				assert.NotEqual(t, "local-progress", progress.ID)
			} else {
				assert.Equal(t, tt.wantProgressID, progress.ID)
			}
			assert.Equal(t, "local-entry", lib.AllEntries()[0].ID)
			assert.Equal(t, "local-entry", progress.LibraryEntryID)
		})
	}
}

func TestPushConflictRecoveryReturnsLookupAndRetryErrors(t *testing.T) {
	t.Run("ownership lookup", func(t *testing.T) {
		lib := reconciliationLibrary(t)
		postCount := 0
		withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
			if r.Method == http.MethodPost {
				postCount++
				return response(http.StatusForbidden, `{"code":"42501"}`), nil
			}
			return response(http.StatusServiceUnavailable, `{"message":"offline"}`), nil
		})
		err := (&Config{URL: "https://project.invalid", AnonKey: "anon"}).
			pushEntries("jwt", "profile-1", lib)
		require.Error(t, err)
		assert.Contains(t, err.Error(), "fetch owned entry IDs")
		assert.Contains(t, err.Error(), "42501")
		assert.Equal(t, 1, postCount)
	})

	t.Run("retry", func(t *testing.T) {
		lib := reconciliationLibrary(t)
		postCount := 0
		withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
			table := restTable(r)
			if r.Method == http.MethodPost && table == "watch_progress" {
				postCount++
				if postCount == 1 {
					return response(http.StatusForbidden, `{"code":"42501"}`), nil
				}
				return response(http.StatusServiceUnavailable, `{"message":"retry offline"}`), nil
			}
			if r.Method == http.MethodGet && table == "library_entries" {
				return response(http.StatusOK, `[{"id":"local-entry"}]`), nil
			}
			if r.Method == http.MethodGet && table == "watch_progress" {
				return response(http.StatusOK, `[]`), nil
			}
			return response(http.StatusNotFound, `{}`), nil
		})
		err := (&Config{URL: "https://project.invalid", AnonKey: "anon"}).
			pushProgress("jwt", "profile-1", lib)
		require.Error(t, err)
		assert.Contains(t, err.Error(), "retry after ID regen")
		assert.Contains(t, err.Error(), "retry offline")
		assert.Equal(t, 2, postCount)
	})
}

func TestPushLibraryCollectsIndependentTableFailures(t *testing.T) {
	lib := reconciliationLibrary(t)
	now := time.Date(2026, time.July, 23, 12, 0, 0, 0, time.UTC)
	lib.MergeFrom(
		nil,
		nil,
		[]*library.Dismissal{{TmdbID: 7, MediaType: "movie", DismissedAt: now}},
		[]*library.Removal{{TmdbID: 8, MediaType: "movie", RemovedAt: now}},
	)

	var tables []string
	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		tables = append(tables, restTable(r))
		return response(http.StatusServiceUnavailable, `{"message":"offline"}`), nil
	})

	err := (&Config{URL: "https://project.invalid", AnonKey: "anon"}).
		PushLibrary("jwt", "profile-1", lib)
	require.Error(t, err)
	assert.ErrorContains(t, err, "push library entries")
	assert.ErrorContains(t, err, "push watch progress")
	assert.ErrorContains(t, err, "push dismissals")
	assert.ErrorContains(t, err, "push removals")
	assert.Equal(t, []string{
		"library_entries", "watch_progress", "dismissals", "library_removals",
	}, tables)
}

func TestPushSettingsRedactsDeviceLocalFields(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	store, err := settings.New("profile-1")
	require.NoError(t, err)
	local := store.Get()
	local.HideSpoilers = true
	local.RemoteAccessEnabled = true
	local.RemoteAccessToken = "device-secret"
	local.AllowLanStreamSources = true
	require.NoError(t, store.Set(local))

	var synced settings.Settings
	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		rows := decodeRequestRows(t, r)
		require.Len(t, rows, 1)
		raw, err := json.Marshal(rows[0]["data"])
		require.NoError(t, err)
		require.NoError(t, json.Unmarshal(raw, &synced))
		return response(http.StatusOK, `[]`), nil
	})

	cfg := &Config{URL: "https://project.invalid", AnonKey: "anon"}
	require.NoError(t, cfg.PushSettings("jwt", "profile-1", store))
	assert.True(t, synced.HideSpoilers)
	assert.False(t, synced.RemoteAccessEnabled)
	assert.Empty(t, synced.RemoteAccessToken)
	assert.False(t, synced.AllowLanStreamSources)

	unchanged := store.Get()
	assert.True(t, unchanged.RemoteAccessEnabled)
	assert.Equal(t, "device-secret", unchanged.RemoteAccessToken)
	assert.True(t, unchanged.AllowLanStreamSources)
}

func TestPullAllDecodesEveryDataset(t *testing.T) {
	updatedAt := "2026-07-23T12:00:00Z"
	var tables []string
	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		tables = append(tables, restTable(r))
		assert.Equal(t, "eq.profile/one", r.URL.Query().Get("profile_id"))
		switch restTable(r) {
		case "library_entries":
			return response(http.StatusOK, `[{
				"id":"entry-1","profile_id":"profile/one","tmdb_id":42,
				"media_type":"tv","title":"Series","status":"watching",
				"added_at":"`+updatedAt+`","updated_at":"`+updatedAt+`"
			}]`), nil
		case "watch_progress":
			return response(http.StatusOK, `[{
				"id":"progress-1","profile_id":"profile/one","library_entry_id":"entry-1",
				"tmdb_id":42,"media_type":"tv","season":2,"episode":7,
				"position_seconds":90,"duration_seconds":120,"watched_at":"`+updatedAt+`"
			}]`), nil
		case "dismissals":
			return response(http.StatusOK, `[{
				"tmdb_id":7,"media_type":"movie","dismissed_at":"`+updatedAt+`"
			}]`), nil
		case "library_removals":
			return response(http.StatusOK, `[{
				"tmdb_id":8,"media_type":"movie","removed_at":"`+updatedAt+`"
			}]`), nil
		case "profile_settings":
			assert.Equal(t, "updated_at.desc", r.URL.Query().Get("order"))
			assert.Equal(t, "1", r.URL.Query().Get("limit"))
			return response(http.StatusOK, `[{"data":{
				"hideSpoilers":true,"updatedAt":"`+updatedAt+`"
			}}]`), nil
		case "profile_addons":
			return response(http.StatusOK, `[{
				"data":[{"id":"addon-1","url":"https://addon.invalid/manifest.json",
					"manifest":{"id":"addon-1","name":"Addon","version":"1.0.0"},
					"kind":"provider","source":"stremio","enabled":true}],
				"updated_at":"`+updatedAt+`"
			}]`), nil
		case "profile_nuvio":
			return response(http.StatusOK, `[{
				"data":{"repos":[],"updatedAt":"`+updatedAt+`"},
				"updated_at":"`+updatedAt+`"
			}]`), nil
		case "profile_activity":
			return response(http.StatusOK, `[{"data":{
				"days":{},"last_pos":{"movie:42":90},"backfilled":true
			}}]`), nil
		default:
			return response(http.StatusNotFound, `{}`), nil
		}
	})

	cfg := &Config{URL: "https://project.invalid", AnonKey: "anon"}
	pulled, err := cfg.PullAll("jwt", "profile/one")
	require.NoError(t, err)
	require.Len(t, pulled.Entries, 1)
	require.Len(t, pulled.Progress, 1)
	require.Len(t, pulled.Dismissals, 1)
	require.Len(t, pulled.Removals, 1)
	assert.Equal(t, "entry-1", pulled.Entries[0].ID)
	assert.Equal(t, "progress-1", pulled.Progress[0].ID)
	assert.Equal(t, 7, pulled.Dismissals[0].TmdbID)
	assert.Equal(t, 8, pulled.Removals[0].TmdbID)
	require.NotNil(t, pulled.Settings)
	assert.True(t, pulled.Settings.HideSpoilers)
	assert.True(t, pulled.AddonsPresent)
	require.Len(t, pulled.Addons, 1)
	assert.Equal(t, "addon-1", pulled.Addons[0].ID)
	assert.True(t, pulled.NuvioPresent)
	assert.JSONEq(t, `{"repos":[],"updatedAt":"`+updatedAt+`"}`, string(pulled.NuvioData))
	assert.True(t, pulled.ActivityPresent)
	assert.JSONEq(t, `{"days":{},"last_pos":{"movie:42":90},"backfilled":true}`, string(pulled.ActivityData))
	assert.Equal(t, []string{
		"library_entries", "watch_progress", "dismissals", "library_removals",
		"profile_settings", "profile_addons", "profile_nuvio", "profile_activity",
	}, tables)
}

func TestPullAllReturnsEveryTableError(t *testing.T) {
	tables := []string{
		"library_entries", "watch_progress", "dismissals", "library_removals",
		"profile_settings", "profile_addons", "profile_nuvio", "profile_activity",
	}
	for _, failedTable := range tables {
		t.Run(failedTable, func(t *testing.T) {
			withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
				if restTable(r) == failedTable {
					return response(http.StatusServiceUnavailable, `{"message":"offline"}`), nil
				}
				return response(http.StatusOK, `[]`), nil
			})
			_, err := (&Config{URL: "https://project.invalid", AnonKey: "anon"}).
				PullAll("jwt", "profile-1")
			require.Error(t, err)
			assert.Contains(t, err.Error(), "pull "+failedTable)
		})
	}
}

func TestPullAllRejectsMalformedCoreRows(t *testing.T) {
	tests := []struct {
		table string
		body  string
		want  string
	}{
		{"library_entries", `[{"id":"","tmdb_id":0,"media_type":""}]`, "invalid library_entry"},
		{"watch_progress", `[{"id":"","tmdb_id":0,"media_type":""}]`, "invalid watch_progress"},
		{"dismissals", `[{"tmdb_id":0,"media_type":""}]`, "invalid dismissal"},
		{"library_removals", `[{"tmdb_id":0,"media_type":""}]`, "invalid library_removal"},
	}
	for _, tt := range tests {
		t.Run(tt.table, func(t *testing.T) {
			withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
				if restTable(r) == tt.table {
					return response(http.StatusOK, tt.body), nil
				}
				return response(http.StatusOK, `[]`), nil
			})
			_, err := (&Config{URL: "https://project.invalid", AnonKey: "anon"}).
				PullAll("jwt", "profile-1")
			require.Error(t, err)
			assert.Contains(t, err.Error(), tt.want)
		})
	}
}

func TestPullAllRejectsSemanticallyInvalidStoreData(t *testing.T) {
	tests := []struct {
		table string
		body  string
		want  string
	}{
		{
			table: "profile_nuvio",
			body:  `[{"data":{"repos":"not-an-array"},"updated_at":"2026-07-23T12:00:00Z"}]`,
			want:  "decode profile_nuvio data",
		},
		{
			table: "profile_activity",
			body:  `[{"data":{"days":[],"last_pos":{}}}]`,
			want:  "decode profile_activity data",
		},
	}
	for _, tt := range tests {
		t.Run(tt.table, func(t *testing.T) {
			withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
				if restTable(r) == tt.table {
					return response(http.StatusOK, tt.body), nil
				}
				return response(http.StatusOK, `[]`), nil
			})
			_, err := (&Config{URL: "https://project.invalid", AnonKey: "anon"}).
				PullAll("jwt", "profile-1")
			require.Error(t, err)
			assert.Contains(t, err.Error(), tt.want)
		})
	}
}
