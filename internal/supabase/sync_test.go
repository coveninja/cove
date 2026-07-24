//go:build supabase

package supabase

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coveninja/cove/internal/activity"
	"github.com/coveninja/cove/internal/addons"
	"github.com/coveninja/cove/internal/library"
	"github.com/coveninja/cove/internal/nuvio"
	"github.com/coveninja/cove/internal/profiles"
	"github.com/coveninja/cove/internal/settings"
	"github.com/coveninja/cove/internal/utils"
	"github.com/golang-jwt/jwt/v5"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func withHTTPClient(t *testing.T, fn roundTripFunc) {
	t.Helper()
	old := httpClient
	httpClient = &http.Client{Transport: fn}
	t.Cleanup(func() { httpClient = old })
}

func response(status int, body string) *http.Response {
	return &http.Response{
		StatusCode: status,
		Body:       io.NopCloser(strings.NewReader(body)),
		Header:     make(http.Header),
	}
}

func TestPullAllReturnsOptionalTableErrors(t *testing.T) {
	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		if strings.HasSuffix(r.URL.Path, "/profile_settings") {
			return response(http.StatusServiceUnavailable, `{"message":"unavailable"}`), nil
		}
		return response(http.StatusOK, `[]`), nil
	})

	cfg := &Config{URL: "https://project.invalid", AnonKey: "anon"}
	_, err := cfg.PullAll("jwt", "profile")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "pull profile_settings")
}

func TestPullAllReturnsMalformedOptionalPayload(t *testing.T) {
	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		if strings.HasSuffix(r.URL.Path, "/profile_addons") {
			return response(http.StatusOK, `[{"data":"not-an-array","updated_at":"2026-01-01T00:00:00Z"}]`), nil
		}
		return response(http.StatusOK, `[]`), nil
	})

	cfg := &Config{URL: "https://project.invalid", AnonKey: "anon"}
	_, err := cfg.PullAll("jwt", "profile")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "decode profile_addons data")
}

func TestDeleteProfileDataDeletesAllChildrenBeforeParent(t *testing.T) {
	var mu sync.Mutex
	var tables []string
	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		mu.Lock()
		tables = append(tables, strings.TrimPrefix(r.URL.Path, "/rest/v1/"))
		mu.Unlock()
		return response(http.StatusOK, `[]`), nil
	})

	cfg := &Config{URL: "https://project.invalid", AnonKey: "anon"}
	require.NoError(t, cfg.DeleteProfileData("jwt", "profile"))
	assert.Equal(t, []string{
		"library_entries", "watch_progress", "dismissals", "library_removals",
		"profile_settings", "profile_addons", "profile_nuvio", "profile_activity",
		"profiles",
	}, tables)
}

func TestMergeRemoteStopsBeforePushWhenPullFails(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	profileStore, err := profiles.New(nil)
	require.NoError(t, err)
	profileID := profileStore.ActiveProfileID()
	lib, err := library.New(profileID)
	require.NoError(t, err)
	settingsStore, err := settings.New(profileID)
	require.NoError(t, err)
	activityStore, err := activity.New(profileID)
	require.NoError(t, err)

	postCount := 0
	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		if r.Method == http.MethodPost {
			postCount++
		}
		if strings.HasSuffix(r.URL.Path, "/profiles") {
			return response(http.StatusOK, `[{"id":"`+profileID+`","user_id":"user-1","name":"Primary","is_primary":true}]`), nil
		}
		if strings.HasSuffix(r.URL.Path, "/library_entries") {
			return response(http.StatusServiceUnavailable, `{"message":"unavailable"}`), nil
		}
		return response(http.StatusOK, `[]`), nil
	})

	server := NewServer(
		&Config{URL: "https://project.invalid", AnonKey: "anon"},
		profileStore, lib, settingsStore, addons.New(profileID, nil),
		nuvio.New(profileID), activityStore,
	)
	err = server.mergeRemote("user-1", "jwt")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "pull library_entries")
	assert.Zero(t, postCount, "a failed pull must never be followed by a push")
	assert.Nil(t, profileStore.ActiveProfile().SupabaseUID, "failed initial pull must not leave the profile linked")
}

func TestMergeRemoteFailsClosedWhenLocalPersistenceFails(t *testing.T) {
	server := newHandlerTestServer(t, &Config{URL: "https://project.invalid", AnonKey: "anon"})
	profileID := server.profileStore.ActiveProfileID()
	now := time.Now().UTC().Format(time.RFC3339)

	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		if restTable(r) == "profiles" {
			return response(http.StatusOK, `[{
				"id":"`+profileID+`","user_id":"user-1","name":"Primary","is_primary":true
			}]`), nil
		}
		if restTable(r) == "library_entries" {
			return response(http.StatusOK, `[{
				"id":"remote-entry","tmdb_id":42,"media_type":"movie",
				"title":"Remote","status":"watch_later",
				"added_at":"`+now+`","updated_at":"`+now+`"
			}]`), nil
		}
		return response(http.StatusOK, `[]`), nil
	})

	probe, err := utils.ConfigPath("probe")
	require.NoError(t, err)
	appDir := filepath.Dir(probe)
	require.NoError(t, os.RemoveAll(appDir))
	require.NoError(t, os.WriteFile(appDir, []byte("not a directory"), 0o600))

	err = server.mergeRemote("user-1", "jwt")
	require.ErrorContains(t, err, "apply remote data")
	require.ErrorContains(t, err, "library persist")
	assert.Nil(t, server.profileStore.ActiveProfile().SupabaseUID)
	assert.Empty(t, server.lib.AllEntries(), "failed persistence must roll back the in-memory merge")
}

func TestMergeRemoteReturnsEveryProfileStorePersistenceFailure(t *testing.T) {
	now := time.Now().UTC().Add(time.Hour).Format(time.RFC3339)
	tests := []struct {
		name    string
		table   string
		row     string
		wantErr string
	}{
		{
			name:    "settings",
			table:   "profile_settings",
			row:     `[{"data":{"hideSpoilers":true,"updatedAt":"` + now + `"}}]`,
			wantErr: "settings",
		},
		{
			name:  "addons",
			table: "profile_addons",
			row: `[{"data":[{"id":"addon-1","url":"https://addon.invalid/manifest.json",
				"manifest":{"id":"addon-1","name":"Addon","version":"1.0.0"},
				"kind":"provider","source":"stremio","enabled":true}],
				"updated_at":"` + now + `"}]`,
			wantErr: "addons",
		},
		{
			name:    "nuvio",
			table:   "profile_nuvio",
			row:     `[{"data":{"repos":[]},"updated_at":"` + now + `"}]`,
			wantErr: "merge nuvio",
		},
		{
			name:    "activity",
			table:   "profile_activity",
			row:     `[{"data":{"days":{},"last_pos":{"movie:42":90},"backfilled":true}}]`,
			wantErr: "merge activity",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			server := newHandlerTestServer(t, &Config{
				URL:     "https://project.invalid",
				AnonKey: "anon",
			})
			profileID := server.profileStore.ActiveProfileID()
			withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
				if restTable(r) == "profiles" {
					return response(http.StatusOK, `[{
						"id":"`+profileID+`","user_id":"user-1",
						"name":"Primary","is_primary":true
					}]`), nil
				}
				if restTable(r) == tt.table {
					return response(http.StatusOK, tt.row), nil
				}
				return response(http.StatusOK, `[]`), nil
			})

			probe, err := utils.ConfigPath("probe")
			require.NoError(t, err)
			appDir := filepath.Dir(probe)
			require.NoError(t, os.RemoveAll(appDir))
			require.NoError(t, os.WriteFile(appDir, []byte("not a directory"), 0o600))

			err = server.mergeRemote("user-1", "jwt")
			require.ErrorContains(t, err, "apply remote data")
			require.ErrorContains(t, err, tt.wantErr)
			assert.Nil(t, server.profileStore.ActiveProfile().SupabaseUID)
		})
	}
}

func TestPushAsyncRejectsStaleProfilesAndRecordsDatasetErrors(t *testing.T) {
	server := newHandlerTestServer(t, &Config{
		URL:     "https://project.invalid",
		AnonKey: "anon",
	})
	server.pushAsync("jwt", "not-active", "test push")
	server.pushErrMu.Lock()
	assert.Contains(t, server.lastPushErr, "profile changed before push started")
	server.pushErrMu.Unlock()

	profileID := server.profileStore.ActiveProfileID()
	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		return response(http.StatusServiceUnavailable, `{"message":"unavailable"}`), nil
	})
	server.pushAsync("jwt", profileID, "test push")
	waitForPush(t, server)
	server.pushErrMu.Lock()
	assert.Contains(t, server.lastPushErr, "settings")
	assert.Contains(t, server.lastPushErr, "addons")
	server.pushErrMu.Unlock()
}

func TestPushAsyncSkipsUnchangedLibraryGeneration(t *testing.T) {
	server := newHandlerTestServer(t, &Config{
		URL:     "https://project.invalid",
		AnonKey: "anon",
	})
	profileID := server.profileStore.ActiveProfileID()
	server.lastPushedGen = server.lib.Generation()
	server.lastPushedGenOK = true
	server.lastPushedProfile = profileID

	var tables []string
	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		tables = append(tables, restTable(r))
		return response(http.StatusOK, `[]`), nil
	})
	server.pushAsync("jwt", profileID, "test push")
	waitForPush(t, server)
	assert.NotContains(t, tables, "library_entries")
	assert.NotContains(t, tables, "watch_progress")
}

func TestAsyncPushCannotContinueAcrossProfileSwitch(t *testing.T) {
	server := newHandlerTestServer(t, &Config{URL: "https://project.invalid", AnonKey: "anon"})
	primaryID := server.profileStore.ActiveProfileID()
	kid, err := server.profileStore.Create("Kid")
	require.NoError(t, err)
	now := time.Now().UTC()
	require.NoError(t, server.lib.MergeFrom([]*library.LibraryEntry{{
		ID: "entry-1", TmdbID: 42, MediaType: "movie", Title: "Primary",
		Status: library.StatusWatchLater, AddedAt: now, UpdatedAt: now,
	}}, nil, nil, nil))

	started := make(chan struct{})
	release := make(chan struct{})
	var mu sync.Mutex
	var postedTables []string
	var switchCompleted atomic.Bool
	var postsAfterSwitch atomic.Int32
	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		if r.Method == http.MethodPost {
			if switchCompleted.Load() {
				postsAfterSwitch.Add(1)
			}
			table := restTable(r)
			mu.Lock()
			postedTables = append(postedTables, table)
			mu.Unlock()
			if table == "library_entries" {
				close(started)
				<-release
			}
		}
		return response(http.StatusOK, `[]`), nil
	})

	server.pushAsync("jwt", primaryID, "test push")
	<-started
	switched := make(chan error, 1)
	go func() {
		err := server.profileStore.SetActive(kid.ID)
		switchCompleted.Store(true)
		switched <- err
	}()
	select {
	case err := <-switched:
		t.Fatalf("profile switched before the active-profile push released its snapshot: %v", err)
	case <-time.After(50 * time.Millisecond):
	}
	close(release)
	require.NoError(t, <-switched)

	require.Eventually(t, func() bool {
		server.pushErrMu.Lock()
		defer server.pushErrMu.Unlock()
		return !server.lastPushAt.IsZero()
	}, time.Second, 5*time.Millisecond)
	mu.Lock()
	defer mu.Unlock()
	assert.NotEmpty(t, postedTables)
	assert.Equal(t, int32(0), postsAfterSwitch.Load(), "the old push must issue no request after profile activation completes")
}

func TestValidateJWTRequiresProjectIssuerAndAuthenticatedAudience(t *testing.T) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	require.NoError(t, err)
	cfg := &Config{URL: "https://project.invalid", AnonKey: "anon"}

	jwksMu.Lock()
	oldCache := jwksCache
	jwksCache = map[string]jwksCacheEntry{
		cfg.URL: {keys: map[string]*ecdsa.PublicKey{"key-1": &key.PublicKey}, fetched: time.Now()},
	}
	jwksMu.Unlock()
	t.Cleanup(func() {
		jwksMu.Lock()
		jwksCache = oldCache
		jwksMu.Unlock()
	})

	sign := func(issuer, audience string) string {
		t.Helper()
		claims := jwt.MapClaims{
			"sub": "user-1", "iss": issuer, "aud": audience,
			"exp": time.Now().Add(time.Hour).Unix(),
		}
		token := jwt.NewWithClaims(jwt.SigningMethodES256, claims)
		token.Header["kid"] = "key-1"
		signed, err := token.SignedString(key)
		require.NoError(t, err)
		return signed
	}

	userID, err := cfg.ValidateJWT(sign(cfg.URL+"/auth/v1", "authenticated"))
	require.NoError(t, err)
	assert.Equal(t, "user-1", userID)

	_, err = cfg.ValidateJWT(sign("https://other.invalid/auth/v1", "authenticated"))
	assert.Error(t, err)
	_, err = cfg.ValidateJWT(sign(cfg.URL+"/auth/v1", "other"))
	assert.Error(t, err)
}

func TestBuildRowsAssignProfileAndPreserveFields(t *testing.T) {
	now := time.Date(2026, time.July, 22, 10, 11, 12, 0, time.UTC)
	season, episode := 2, 7
	entry := &library.LibraryEntry{
		ID: "entry-1", TmdbID: 42, MediaType: "tv", Title: "Series",
		Status: library.StatusWatching, AddedAt: now.Add(-time.Hour), UpdatedAt: now,
	}
	progress := &library.WatchProgress{
		ID: "progress-1", TmdbID: 42, MediaType: "tv", Season: &season,
		Episode: &episode, PositionSeconds: 90, DurationSeconds: 120,
		Completed: true, WatchedAt: now,
	}

	entryRows := buildEntryRows([]*library.LibraryEntry{entry}, "profile-1")
	progressRows := buildProgressRows([]*library.WatchProgress{progress}, "profile-1")
	require.Len(t, entryRows, 1)
	require.Len(t, progressRows, 1)
	assert.Equal(t, "profile-1", *entry.ProfileID)
	assert.Equal(t, "profile-1", *progress.ProfileID)
	assert.Equal(t, "entry-1", entryRows[0]["id"])
	assert.Equal(t, 42, entryRows[0]["tmdb_id"])
	assert.Equal(t, true, progressRows[0]["completed"])
	assert.Equal(t, &season, progressRows[0]["season"])
}

func TestPushLibraryWritesAllCollectionsAndDeletesRemovedEntries(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	lib, err := library.New("profile-1")
	require.NoError(t, err)
	now := time.Date(2026, time.July, 22, 10, 11, 12, 0, time.UTC)
	lib.MergeFrom(
		[]*library.LibraryEntry{{
			ID: "entry-1", TmdbID: 1, MediaType: "movie", Title: "Movie",
			Status: library.StatusWatching, AddedAt: now, UpdatedAt: now,
		}},
		[]*library.WatchProgress{{
			ID: "progress-1", LibraryEntryID: "entry-1", TmdbID: 1,
			MediaType: "movie", PositionSeconds: 30, DurationSeconds: 100,
			WatchedAt: now,
		}},
		[]*library.Dismissal{{TmdbID: 2, MediaType: "tv", DismissedAt: now}},
		[]*library.Removal{{TmdbID: 3, MediaType: "movie", RemovedAt: now}},
	)

	type call struct {
		method string
		table  string
		query  url.Values
		body   []map[string]any
	}
	var calls []call
	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		table := strings.TrimPrefix(r.URL.Path, "/rest/v1/")
		var body []map[string]any
		if r.Body != nil {
			require.NoError(t, json.NewDecoder(r.Body).Decode(&body))
		}
		assert.Equal(t, "Bearer jwt", r.Header.Get("Authorization"))
		assert.Equal(t, "anon", r.Header.Get("apikey"))
		calls = append(calls, call{method: r.Method, table: table, query: r.URL.Query(), body: body})
		return response(http.StatusOK, `[]`), nil
	})

	cfg := &Config{URL: "https://project.invalid", AnonKey: "anon"}
	require.NoError(t, cfg.PushLibrary("jwt", "profile-1", lib))
	require.Len(t, calls, 5)
	assert.Equal(t, []string{
		"library_entries", "watch_progress", "dismissals", "library_removals", "library_entries",
	}, []string{calls[0].table, calls[1].table, calls[2].table, calls[3].table, calls[4].table})
	for _, index := range []int{0, 1, 2, 3} {
		assert.Equal(t, http.MethodPost, calls[index].method)
		require.Len(t, calls[index].body, 1)
		assert.Equal(t, "profile-1", calls[index].body[0]["profile_id"])
	}
	assert.Equal(t, http.MethodDelete, calls[4].method)
	assert.Equal(t, "eq.profile-1", calls[4].query.Get("profile_id"))
	assert.Equal(t, "eq.3", calls[4].query.Get("tmdb_id"))
	assert.Equal(t, "eq.movie", calls[4].query.Get("media_type"))
}

func TestDeleteProfileDataStopsBeforeParentWhenChildDeleteFails(t *testing.T) {
	var tables []string
	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		table := strings.TrimPrefix(r.URL.Path, "/rest/v1/")
		tables = append(tables, table)
		if table == "watch_progress" {
			return response(http.StatusServiceUnavailable, `{"message":"unavailable"}`), nil
		}
		return response(http.StatusOK, `[]`), nil
	})

	cfg := &Config{URL: "https://project.invalid", AnonKey: "anon"}
	err := cfg.DeleteProfileData("jwt", "profile-1")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "delete watch_progress")
	assert.Equal(t, []string{"library_entries", "watch_progress"}, tables)
}
