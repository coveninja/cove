package trakt

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coveninja/cove/internal/utils"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func fastTraktClient(client *Client) *Client {
	client.writeInterval = 0
	client.sleep = func(time.Duration) {}
	return client
}

func fastTraktServer(server *Server) *Server {
	fastTraktClient(server.client)
	server.deviceFlowTimeUnit = time.Millisecond
	return server
}

func TestStoreProfileSwitchIsAtomicAndCorruptFileCanRecover(t *testing.T) {
	configRoot := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", configRoot)
	dir := filepath.Join(configRoot, "cove")
	require.NoError(t, os.MkdirAll(dir, 0o755))

	corruptPath := filepath.Join(dir, "trakt-corrupt.json")
	require.NoError(t, os.WriteFile(corruptPath, []byte("{"), 0o600))
	store, err := newStore("corrupt")
	require.Error(t, err)
	assert.Equal(t, corruptPath, store.path)
	require.NoError(t, store.Save(tokenState{
		AccessToken: "recovered", RefreshToken: "refresh",
		ExpiresAt: time.Now().Add(time.Hour),
	}))
	reloaded, err := newStore("corrupt")
	require.NoError(t, err)
	assert.Equal(t, "recovered", reloaded.Get().AccessToken)

	require.NoError(t, store.SetProfile("profile-a"))
	require.NoError(t, store.Save(tokenState{
		AccessToken: "profile-a-token", ExpiresAt: time.Now().Add(time.Hour),
	}))
	oldPath := store.path

	badTarget := filepath.Join(dir, "trakt-profile-b.json")
	require.NoError(t, os.WriteFile(badTarget, []byte("{"), 0o600))
	require.Error(t, store.SetProfile("profile-b"))
	assert.Equal(t, oldPath, store.path)
	assert.Equal(t, "profile-a-token", store.Get().AccessToken,
		"a failed profile load must preserve the active profile token")

	require.NoError(t, store.SetProfile("missing-profile"))
	assert.Empty(t, store.Get().AccessToken)
	assert.Equal(t, filepath.Join(dir, "trakt-missing-profile.json"), store.path)
}

func TestNewRetainsRecoverableStoreAfterLoadError(t *testing.T) {
	configRoot := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", configRoot)
	dir := filepath.Join(configRoot, "cove")
	require.NoError(t, os.MkdirAll(dir, 0o755))
	path := filepath.Join(dir, "trakt-new-corrupt.json")
	require.NoError(t, os.WriteFile(path, []byte("{"), 0o600))

	server := New(Config{ClientID: "id", ClientSecret: "secret"}, "new-corrupt", nil, nil, nil)
	assert.Equal(t, path, server.store.path)
	require.NoError(t, server.store.Save(tokenState{
		AccessToken: "replacement", ExpiresAt: time.Now().Add(time.Hour),
	}))
	assert.Equal(t, "replacement", server.store.Get().AccessToken)
}

func TestServerSetProfileFailureCancelsFlowAndPreservesToken(t *testing.T) {
	fake := newFakeTraktServer(t)
	defer fake.Close()
	server := newHandlerServer(t, fake)
	require.NoError(t, server.store.Save(tokenState{
		AccessToken: "active-token", ExpiresAt: time.Now().Add(time.Hour),
	}))
	oldPath := server.store.path
	badTarget, err := utils.ConfigPath("trakt-bad-profile.json")
	require.NoError(t, err)
	require.NoError(t, os.WriteFile(badTarget, []byte("{"), 0o600))

	cancelled := false
	server.flowMu.Lock()
	server.flowState = "pending"
	server.flowCancel = func() { cancelled = true }
	server.flowMu.Unlock()

	require.Error(t, server.SetProfile("bad-profile"))
	assert.True(t, cancelled)
	server.flowMu.Lock()
	flowState := server.flowState
	server.flowMu.Unlock()
	assert.Equal(t, "idle", flowState)
	assert.Equal(t, oldPath, server.store.path)
	assert.Equal(t, "active-token", server.store.Get().AccessToken)
}

func TestStoreClearFailurePreservesStateAndUnsetPathFails(t *testing.T) {
	blocked := t.TempDir()
	require.NoError(t, os.WriteFile(filepath.Join(blocked, "child"), []byte("x"), 0o600))
	store := &Store{
		path:  blocked,
		state: tokenState{AccessToken: "keep-me"},
	}

	require.Error(t, store.Clear())
	assert.Equal(t, "keep-me", store.Get().AccessToken)

	empty := &Store{}
	require.Error(t, empty.Save(tokenState{AccessToken: "token"}))
	require.Error(t, empty.Clear())

	valid := testStore(t)
	require.NoError(t, valid.Save(tokenState{AccessToken: "old"}))
	_, revision := valid.Snapshot()
	require.NoError(t, valid.Clear())
	require.Error(t, valid.SaveIfRevision(tokenState{AccessToken: "stale"}, revision))
	assert.False(t, valid.IsConnected())
}

func TestDeviceAndTokenResponseValidation(t *testing.T) {
	t.Run("device code failures", func(t *testing.T) {
		for name, response := range map[string]struct {
			status int
			body   string
		}{
			"status":     {http.StatusBadGateway, `{}`},
			"malformed":  {http.StatusOK, `{`},
			"incomplete": {http.StatusOK, `{"device_code":"code"}`},
		} {
			t.Run(name, func(t *testing.T) {
				server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
					w.WriteHeader(response.status)
					_, _ = w.Write([]byte(response.body))
				}))
				defer server.Close()
				_, err := testClient(server.URL, testStore(t)).StartDeviceFlow()
				require.Error(t, err)
			})
		}
		client := testClient("://bad-url", testStore(t))
		_, err := client.StartDeviceFlow()
		require.Error(t, err)
	})

	t.Run("poll terminal and malformed responses", func(t *testing.T) {
		for name, response := range map[string]struct {
			status  int
			body    string
			want    PollStatus
			wantErr bool
		}{
			"not found":  {status: http.StatusNotFound, want: PollInvalid},
			"conflict":   {status: http.StatusConflict, want: PollInvalid},
			"denied":     {status: http.StatusTeapot, want: PollDenied},
			"unexpected": {status: http.StatusBadGateway, body: "down", wantErr: true},
			"malformed":  {status: http.StatusOK, body: `{`, wantErr: true},
			"incomplete": {status: http.StatusOK, body: `{}`, wantErr: true},
		} {
			t.Run(name, func(t *testing.T) {
				server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
					w.WriteHeader(response.status)
					_, _ = w.Write([]byte(response.body))
				}))
				defer server.Close()
				result, err := testClient(server.URL, testStore(t)).PollDeviceFlow("code")
				if response.wantErr {
					require.Error(t, err)
					return
				}
				require.NoError(t, err)
				assert.Equal(t, response.want, result.Status)
			})
		}
	})

	t.Run("token save failure", func(t *testing.T) {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path == "/users/me" {
				_, _ = w.Write([]byte(`{"username":"user"}`))
				return
			}
			_, _ = w.Write([]byte(`{"access_token":"access","refresh_token":"refresh","expires_in":3600}`))
		}))
		defer server.Close()
		store := &Store{path: t.TempDir()}
		_, err := testClient(server.URL, store).PollDeviceFlow("code")
		require.Error(t, err)
		assert.False(t, store.IsConnected())
	})
}

func TestFetchUsernameFailureModes(t *testing.T) {
	client := testClient("://bad-url", testStore(t))
	assert.Empty(t, client.fetchUsername("token"))

	for name, response := range map[string]string{
		"status":    "",
		"malformed": "{",
	} {
		t.Run(name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				if response == "" {
					w.WriteHeader(http.StatusBadGateway)
					return
				}
				_, _ = w.Write([]byte(response))
			}))
			defer server.Close()
			assert.Empty(t, testClient(server.URL, testStore(t)).fetchUsername("token"))
		})
	}

	server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	url := server.URL
	server.Close()
	assert.Empty(t, testClient(url, testStore(t)).fetchUsername("token"))
}

func TestTokenRefreshFailureModesAndConcurrentDeduplication(t *testing.T) {
	t.Run("not connected", func(t *testing.T) {
		require.Error(t, testClient("http://unused", testStore(t)).EnsureValidToken())
	})

	t.Run("missing refresh token", func(t *testing.T) {
		store := testStore(t)
		require.NoError(t, store.Save(tokenState{
			AccessToken: "access", ExpiresAt: time.Now().Add(time.Minute),
		}))
		require.Error(t, testClient("http://unused", store).EnsureValidToken())
	})

	for name, response := range map[string]struct {
		status int
		body   string
	}{
		"status":     {http.StatusBadGateway, `{}`},
		"malformed":  {http.StatusOK, `{`},
		"incomplete": {http.StatusOK, `{}`},
	} {
		t.Run(name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
				w.WriteHeader(response.status)
				_, _ = w.Write([]byte(response.body))
			}))
			defer server.Close()
			store := testStore(t)
			require.NoError(t, store.Save(tokenState{
				AccessToken: "old", RefreshToken: "refresh",
				ExpiresAt: time.Now().Add(time.Minute),
			}))
			require.Error(t, testClient(server.URL, store).EnsureValidToken())
			assert.Equal(t, "old", store.Get().AccessToken)
		})
	}

	t.Run("save failure", func(t *testing.T) {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			_, _ = w.Write([]byte(`{"access_token":"new","expires_in":3600}`))
		}))
		defer server.Close()
		store := &Store{
			path: t.TempDir(),
			state: tokenState{
				AccessToken: "old", RefreshToken: "refresh",
				ExpiresAt: time.Now().Add(time.Minute),
			},
		}
		require.Error(t, testClient(server.URL, store).EnsureValidToken())
		assert.Equal(t, "old", store.Get().AccessToken)
	})

	t.Run("concurrent callers refresh once", func(t *testing.T) {
		var calls atomic.Int32
		entered := make(chan struct{})
		release := make(chan struct{})
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			if calls.Add(1) == 1 {
				close(entered)
			}
			<-release
			_, _ = w.Write([]byte(`{"access_token":"new","refresh_token":"new-refresh","expires_in":7200}`))
		}))
		defer server.Close()
		store := testStore(t)
		require.NoError(t, store.Save(tokenState{
			AccessToken: "old", RefreshToken: "refresh",
			ExpiresAt: time.Now().Add(time.Minute),
		}))
		client := fastTraktClient(testClient(server.URL, store))

		errs := make(chan error, 2)
		go func() { errs <- client.EnsureValidToken() }()
		<-entered
		go func() { errs <- client.EnsureValidToken() }()
		close(release)
		require.NoError(t, <-errs)
		require.NoError(t, <-errs)
		assert.Equal(t, int32(1), calls.Load())
	})
}

func TestPollDeviceFlowCannotSaveAcrossProfileSwitch(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	store, err := newStore("profile-a")
	require.NoError(t, err)
	requestStarted := make(chan struct{})
	releaseResponse := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/users/me" {
			_, _ = w.Write([]byte(`{"username":"old-profile-user"}`))
			return
		}
		close(requestStarted)
		<-releaseResponse
		_, _ = w.Write([]byte(`{"access_token":"old-profile-token","refresh_token":"refresh","expires_in":3600}`))
	}))
	defer server.Close()
	client := fastTraktClient(testClient(server.URL, store))

	errs := make(chan error, 1)
	go func() {
		_, pollErr := client.PollDeviceFlow("code")
		errs <- pollErr
	}()
	<-requestStarted
	require.NoError(t, store.SetProfile("profile-b"))
	close(releaseResponse)

	require.Error(t, <-errs)
	assert.False(t, store.IsConnected())
	assert.Contains(t, store.path, "trakt-profile-b.json")
}

func TestClientAndScrobbleErrorBoundaries(t *testing.T) {
	client := testClient("://bad-url", testStore(t))
	_, err := client.doRequest(http.MethodPost, "/path", func() {})
	require.Error(t, err)
	_, err = client.doRequest(http.MethodGet, "/path", nil)
	require.Error(t, err)

	var scrobbleCalls atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/status":
			http.Error(w, "no", http.StatusBadGateway)
		case "/malformed":
			_, _ = w.Write([]byte("{"))
		case "/scrobble/stop":
			if scrobbleCalls.Add(1) == 1 {
				http.NotFound(w, r)
			} else {
				http.Error(w, "failed", http.StatusBadGateway)
			}
		}
	}))
	defer server.Close()
	client = testClient(server.URL, testStore(t))
	var out map[string]any
	require.Error(t, client.get("/status", &out))
	require.Error(t, client.get("/malformed", &out))
	_, err = client.getWithHeaders("/status", &out)
	require.Error(t, err)
	_, err = client.getWithHeaders("/malformed", &out)
	require.Error(t, err)
	require.NoError(t, client.scrobble("stop", ScrobbleRequest{TmdbID: 1, MediaType: "movie"}))
	require.Error(t, client.scrobble("stop", ScrobbleRequest{TmdbID: 2, MediaType: "movie"}))

	closed := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	closedURL := closed.URL
	closed.Close()
	client = testClient(closedURL, testStore(t))
	require.Error(t, client.get("/transport", &out))
	_, err = client.getWithHeaders("/transport", &out)
	require.Error(t, err)
	require.Error(t, client.scrobble("stop", ScrobbleRequest{TmdbID: 1, MediaType: "movie"}))
}

func TestSetupHandlersCredentialGuardAndConfiguredRoutes(t *testing.T) {
	for name, cfg := range map[string]Config{
		"missing id":     {ClientSecret: "secret"},
		"missing secret": {ClientID: "id"},
	} {
		t.Run(name, func(t *testing.T) {
			server := &Server{cfg: cfg}
			mux := http.NewServeMux()
			server.SetupHandlers(mux)
			rr := httptest.NewRecorder()
			mux.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "/api/trakt/status", nil))
			assert.Equal(t, http.StatusServiceUnavailable, rr.Code)
		})
	}

	fake := newFakeTraktServer(t)
	defer fake.Close()
	server := newHandlerServer(t, fake)
	mux := http.NewServeMux()
	server.SetupHandlers(mux)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "/api/trakt/status", nil))
	assert.Equal(t, http.StatusOK, rr.Code)
}

func TestHandleUnlinkRevokesCapturedTokenAndPreservesStateOnClearFailure(t *testing.T) {
	revoked := make(chan string, 2)
	fake := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]string
		_ = json.NewDecoder(r.Body).Decode(&body)
		revoked <- body["token"]
		w.WriteHeader(http.StatusOK)
	}))
	defer fake.Close()

	server := fastTraktServer(newHandlerServerAt(t, fake.URL))
	require.NoError(t, server.store.Save(tokenState{
		AccessToken: "captured-token", ExpiresAt: time.Now().Add(time.Hour),
	}))
	rr := doHandler(server.handleUnlink, http.MethodPost, "/api/trakt/unlink", nil)
	require.Equal(t, http.StatusNoContent, rr.Code)
	select {
	case token := <-revoked:
		assert.Equal(t, "captured-token", token)
	case <-time.After(time.Second):
		t.Fatal("revoke request did not use the captured token")
	}
	assert.False(t, server.store.IsConnected())

	blocked := t.TempDir()
	require.NoError(t, os.WriteFile(filepath.Join(blocked, "child"), []byte("x"), 0o600))
	server.store.mu.Lock()
	server.store.path = blocked
	server.store.state = tokenState{AccessToken: "preserve-token"}
	server.store.mu.Unlock()
	rr = doHandler(server.handleUnlink, http.MethodPost, "/api/trakt/unlink", nil)
	assert.Equal(t, http.StatusInternalServerError, rr.Code)
	assert.True(t, server.store.IsConnected())
	select {
	case token := <-revoked:
		assert.Equal(t, "preserve-token", token)
	case <-time.After(time.Second):
		t.Fatal("failed unlink did not attempt revocation")
	}
}

func TestHandleScrobbleValidatesMediaFields(t *testing.T) {
	fake := newFakeTraktServer(t)
	defer fake.Close()
	server := newHandlerServer(t, fake)
	current := server.settings.Get()
	current.TraktScrobbleEnabled = true
	require.NoError(t, server.settings.Set(current))
	require.NoError(t, server.store.Save(tokenState{
		AccessToken: "token", ExpiresAt: time.Now().Add(2 * time.Hour),
	}))

	for name, body := range map[string]map[string]any{
		"missing id":        {"action": "start", "media_type": "movie", "progress": 1},
		"invalid media":     {"action": "start", "tmdb_id": 1, "media_type": "album", "progress": 1},
		"negative progress": {"action": "pause", "tmdb_id": 1, "media_type": "movie", "progress": -1},
		"excess progress":   {"action": "stop", "tmdb_id": 1, "media_type": "movie", "progress": 101},
		"missing episode":   {"action": "start", "tmdb_id": 1, "media_type": "tv", "season": 1},
		"negative season":   {"action": "start", "tmdb_id": 1, "media_type": "tv", "season": -1, "episode": 1},
	} {
		t.Run(name, func(t *testing.T) {
			rr := doHandler(server.handleScrobble, http.MethodPost, "/api/trakt/scrobble", jsonBody(body))
			assert.Equal(t, http.StatusBadRequest, rr.Code)
		})
	}
}

func TestRunDeviceFlowTerminalStates(t *testing.T) {
	for name, status := range map[string]int{
		"authorized": http.StatusOK,
		"expired":    http.StatusGone,
		"denied":     http.StatusTeapot,
		"invalid":    http.StatusNotFound,
	} {
		t.Run(name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == "/users/me" {
					_, _ = w.Write([]byte(`{"username":"flow-user"}`))
					return
				}
				w.WriteHeader(status)
				if status == http.StatusOK {
					_, _ = w.Write([]byte(`{"access_token":"access","refresh_token":"refresh","expires_in":3600}`))
				}
			}))
			defer server.Close()
			s := fastTraktServer(&Server{
				client: testClient(server.URL, testStore(t)), flowCancel: func() {},
			})
			s.runDeviceFlow(context.Background(), "code", 0, 500)
			s.flowMu.Lock()
			state := s.flowState
			cancel := s.flowCancel
			s.flowMu.Unlock()
			switch status {
			case http.StatusOK:
				assert.Equal(t, "authorized", state)
				assert.Nil(t, cancel)
			case http.StatusGone:
				assert.Equal(t, "expired", state)
			default:
				assert.Equal(t, "denied", state)
			}
		})
	}

	t.Run("slow down then authorize", func(t *testing.T) {
		var calls atomic.Int32
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path == "/users/me" {
				_, _ = w.Write([]byte(`{"username":"flow-user"}`))
				return
			}
			if calls.Add(1) <= 2 {
				w.WriteHeader(http.StatusTooManyRequests)
				return
			}
			_, _ = w.Write([]byte(`{"access_token":"access","refresh_token":"refresh","expires_in":3600}`))
		}))
		defer server.Close()
		s := fastTraktServer(&Server{client: testClient(server.URL, testStore(t))})
		s.runDeviceFlow(context.Background(), "code", 0, 500)
		s.flowMu.Lock()
		state := s.flowState
		s.flowMu.Unlock()
		assert.Equal(t, "authorized", state)
		assert.Equal(t, int32(3), calls.Load())
	})
}
