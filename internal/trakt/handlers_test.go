package trakt

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/coveninja/cove/internal/library"
	"github.com/coveninja/cove/internal/settings"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// ─── Helpers ──────────────────────────────────────────────────────────────────

// newFakeTraktServer creates a minimal Trakt API stub that handles all paths
// exercised by the handler tests.
func newFakeTraktServer(t *testing.T) *httptest.Server {
	t.Helper()
	mux := http.NewServeMux()

	mux.HandleFunc("/oauth/device/code", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(DeviceCodeResponse{ //nolint:errcheck
			DeviceCode:      "dev-code-001",
			UserCode:        "USER-1234",
			VerificationURL: "https://trakt.tv/activate",
			ExpiresIn:       600,
			Interval:        5,
		})
	})
	mux.HandleFunc("/oauth/device/token", func(w http.ResponseWriter, r *http.Request) {
		// Remain "pending" so the background polling goroutine does not advance state.
		w.WriteHeader(http.StatusBadRequest)
	})
	mux.HandleFunc("/oauth/revoke", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	})
	mux.HandleFunc("/users/me", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{"username": "handler-testuser"}) //nolint:errcheck
	})
	// Catch-all: scrobble endpoints, etc.
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/scrobble/") {
			w.WriteHeader(http.StatusCreated)
			return
		}
		http.NotFound(w, r)
	})

	return httptest.NewServer(mux)
}

// newHandlerServerAt creates a Server for handler tests with the client pointed
// at baseURL. Library and settings are backed by an isolated temp directory.
func newHandlerServerAt(t *testing.T, baseURL string) *Server {
	t.Helper()
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())

	lib, err := library.New("handler-test")
	require.NoError(t, err)
	st, err := settings.New("handler-test")
	require.NoError(t, err)

	store := testStore(t)
	client := testClient(baseURL, store)
	sw := newSyncWorker(client, store, lib, st, nil)

	return &Server{
		cfg:        Config{ClientID: "fake-id", ClientSecret: "fake-secret"},
		client:     client,
		store:      store,
		lib:        lib,
		settings:   st,
		syncWorker: sw,
	}
}

// newHandlerServer is newHandlerServerAt using fakeTrakt.URL.
func newHandlerServer(t *testing.T, fakeTrakt *httptest.Server) *Server {
	t.Helper()
	return newHandlerServerAt(t, fakeTrakt.URL)
}

// doHandler calls h with a new recorder and returns the recorder.
func doHandler(h http.HandlerFunc, method, target string, body []byte) *httptest.ResponseRecorder {
	var req *http.Request
	if body != nil {
		req = httptest.NewRequest(method, target, bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
	} else {
		req = httptest.NewRequest(method, target, nil)
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, req)
	return w
}

// jsonBody encodes v as a JSON byte slice. Panics on error (test helper only).
func jsonBody(v any) []byte {
	b, err := json.Marshal(v)
	if err != nil {
		panic(err)
	}
	return b
}

// ─── handleDeviceCode ────────────────────────────────────────────────────────

func TestHandleDeviceCode_MethodNotAllowed(t *testing.T) {
	fake := newFakeTraktServer(t)
	defer fake.Close()
	s := newHandlerServer(t, fake)

	w := doHandler(s.handleDeviceCode, http.MethodGet, "/api/trakt/device-code", nil)
	assert.Equal(t, http.StatusMethodNotAllowed, w.Code)
}

func TestHandleDeviceCode_UpstreamError(t *testing.T) {
	bad := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "upstream down", http.StatusInternalServerError)
	}))
	defer bad.Close()

	s := newHandlerServerAt(t, bad.URL)
	w := doHandler(s.handleDeviceCode, http.MethodPost, "/api/trakt/device-code", nil)
	assert.Equal(t, http.StatusBadGateway, w.Code)
}

func TestHandleDeviceCode_HappyPath(t *testing.T) {
	fake := newFakeTraktServer(t)
	defer fake.Close()
	s := newHandlerServer(t, fake)
	// Cancel the background polling goroutine when the test exits.
	t.Cleanup(func() { s.cancelFlow("idle") })

	w := doHandler(s.handleDeviceCode, http.MethodPost, "/api/trakt/device-code", nil)

	require.Equal(t, http.StatusOK, w.Code)
	assert.Contains(t, w.Header().Get("Content-Type"), "application/json")

	var resp DeviceCodeResponse
	require.NoError(t, json.NewDecoder(w.Body).Decode(&resp))
	assert.Equal(t, "dev-code-001", resp.DeviceCode)
	assert.Equal(t, "USER-1234", resp.UserCode)
	assert.NotEmpty(t, resp.VerificationURL)
	assert.Greater(t, resp.ExpiresIn, 0)

	// flowState should have been set to "pending".
	s.flowMu.Lock()
	fs := s.flowState
	s.flowMu.Unlock()
	assert.Equal(t, "pending", fs)
}

// ─── handlePoll ──────────────────────────────────────────────────────────────

func TestHandlePoll_MethodNotAllowed(t *testing.T) {
	fake := newFakeTraktServer(t)
	defer fake.Close()
	s := newHandlerServer(t, fake)

	w := doHandler(s.handlePoll, http.MethodGet, "/api/trakt/poll", nil)
	assert.Equal(t, http.StatusMethodNotAllowed, w.Code)
}

func TestHandlePoll_MalformedBody(t *testing.T) {
	fake := newFakeTraktServer(t)
	defer fake.Close()
	s := newHandlerServer(t, fake)

	w := doHandler(s.handlePoll, http.MethodPost, "/api/trakt/poll", []byte("{not json"))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestHandlePoll_MissingDeviceCode(t *testing.T) {
	fake := newFakeTraktServer(t)
	defer fake.Close()
	s := newHandlerServer(t, fake)

	// Decode succeeds but device_code is blank.
	w := doHandler(s.handlePoll, http.MethodPost, "/api/trakt/poll",
		jsonBody(map[string]string{"device_code": ""}))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestHandlePoll_HappyPathPending(t *testing.T) {
	fake := newFakeTraktServer(t)
	defer fake.Close()
	s := newHandlerServer(t, fake)

	// fake Trakt returns 400 (pending) for /oauth/device/token.
	w := doHandler(s.handlePoll, http.MethodPost, "/api/trakt/poll",
		jsonBody(map[string]string{"device_code": "dev-code-001"}))

	require.Equal(t, http.StatusOK, w.Code)
	var resp map[string]any
	require.NoError(t, json.NewDecoder(w.Body).Decode(&resp))
	assert.Equal(t, string(PollPending), resp["status"])
}

// ─── handleStatus ─────────────────────────────────────────────────────────────

func TestHandleStatus_MethodNotAllowed(t *testing.T) {
	fake := newFakeTraktServer(t)
	defer fake.Close()
	s := newHandlerServer(t, fake)

	w := doHandler(s.handleStatus, http.MethodPost, "/api/trakt/status", nil)
	assert.Equal(t, http.StatusMethodNotAllowed, w.Code)
}

func TestHandleStatus_NotConnected(t *testing.T) {
	fake := newFakeTraktServer(t)
	defer fake.Close()
	s := newHandlerServer(t, fake)

	w := doHandler(s.handleStatus, http.MethodGet, "/api/trakt/status", nil)

	require.Equal(t, http.StatusOK, w.Code)
	var resp map[string]any
	require.NoError(t, json.NewDecoder(w.Body).Decode(&resp))
	assert.Equal(t, false, resp["connected"])
	assert.Equal(t, "idle", resp["flow_state"]) // empty string → normalised to "idle"
}

func TestHandleStatus_Connected(t *testing.T) {
	fake := newFakeTraktServer(t)
	defer fake.Close()
	s := newHandlerServer(t, fake)
	require.NoError(t, s.store.Save(tokenState{
		AccessToken: "live-access-tok",
		Username:    "alice",
		ExpiresAt:   time.Now().Add(24 * time.Hour),
	}))
	s.flowMu.Lock()
	s.flowState = "authorized"
	s.flowMu.Unlock()

	w := doHandler(s.handleStatus, http.MethodGet, "/api/trakt/status", nil)

	require.Equal(t, http.StatusOK, w.Code)
	var resp map[string]any
	require.NoError(t, json.NewDecoder(w.Body).Decode(&resp))
	assert.Equal(t, true, resp["connected"])
	assert.Equal(t, "alice", resp["username"])
	assert.Equal(t, "authorized", resp["flow_state"])
}

// ─── handleUnlink ─────────────────────────────────────────────────────────────

func TestHandleUnlink_MethodNotAllowed(t *testing.T) {
	fake := newFakeTraktServer(t)
	defer fake.Close()
	s := newHandlerServer(t, fake)

	w := doHandler(s.handleUnlink, http.MethodGet, "/api/trakt/unlink", nil)
	assert.Equal(t, http.StatusMethodNotAllowed, w.Code)
}

func TestHandleUnlink_ClearsTokenAndReturns204(t *testing.T) {
	fake := newFakeTraktServer(t)
	defer fake.Close()
	s := newHandlerServer(t, fake)
	require.NoError(t, s.store.Save(tokenState{
		AccessToken: "tok-to-revoke",
		ExpiresAt:   time.Now().Add(time.Hour),
	}))
	assert.True(t, s.store.IsConnected())
	tokenFilePath := s.store.path

	w := doHandler(s.handleUnlink, http.MethodPost, "/api/trakt/unlink", nil)

	assert.Equal(t, http.StatusNoContent, w.Code)
	assert.False(t, s.store.IsConnected())
	_, err := os.Stat(tokenFilePath)
	assert.True(t, os.IsNotExist(err), "token file should be deleted after unlink")
}

// ─── handleScrobble ───────────────────────────────────────────────────────────

func TestHandleScrobble_MethodNotAllowed(t *testing.T) {
	fake := newFakeTraktServer(t)
	defer fake.Close()
	s := newHandlerServer(t, fake)

	w := doHandler(s.handleScrobble, http.MethodGet, "/api/trakt/scrobble", nil)
	assert.Equal(t, http.StatusMethodNotAllowed, w.Code)
}

func TestHandleScrobble_NoopWhenNotConnected(t *testing.T) {
	fake := newFakeTraktServer(t)
	defer fake.Close()
	s := newHandlerServer(t, fake)
	// No token in store → not connected; handler must return 204 without parsing body.

	w := doHandler(s.handleScrobble, http.MethodPost, "/api/trakt/scrobble", nil)
	assert.Equal(t, http.StatusNoContent, w.Code)
}

func TestHandleScrobble_NoopWhenDisabled(t *testing.T) {
	fake := newFakeTraktServer(t)
	defer fake.Close()
	s := newHandlerServer(t, fake)
	require.NoError(t, s.store.Save(tokenState{
		AccessToken: "tok",
		ExpiresAt:   time.Now().Add(24 * time.Hour),
	}))
	cfg := s.settings.Get()
	cfg.TraktScrobbleEnabled = false
	require.NoError(t, s.settings.Set(cfg))

	w := doHandler(s.handleScrobble, http.MethodPost, "/api/trakt/scrobble", nil)
	assert.Equal(t, http.StatusNoContent, w.Code)
}

func TestHandleScrobble_MalformedBody(t *testing.T) {
	fake := newFakeTraktServer(t)
	defer fake.Close()
	s := newHandlerServer(t, fake)
	require.NoError(t, s.store.Save(tokenState{
		AccessToken: "tok",
		ExpiresAt:   time.Now().Add(24 * time.Hour),
	}))

	w := doHandler(s.handleScrobble, http.MethodPost, "/api/trakt/scrobble", []byte("{bad json"))
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestHandleScrobble_InvalidAction(t *testing.T) {
	fake := newFakeTraktServer(t)
	defer fake.Close()
	s := newHandlerServer(t, fake)
	require.NoError(t, s.store.Save(tokenState{
		AccessToken: "tok",
		ExpiresAt:   time.Now().Add(24 * time.Hour),
	}))

	body := jsonBody(ScrobbleRequest{Action: "rewind", TmdbID: 1, MediaType: "movie", Progress: 50})
	w := doHandler(s.handleScrobble, http.MethodPost, "/api/trakt/scrobble", body)
	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestHandleScrobble_HappyPath(t *testing.T) {
	fake := newFakeTraktServer(t)
	defer fake.Close()
	s := newHandlerServer(t, fake)
	// Connected + TraktScrobbleEnabled defaults to true.
	require.NoError(t, s.store.Save(tokenState{
		AccessToken: "tok",
		ExpiresAt:   time.Now().Add(24 * time.Hour),
	}))

	for _, action := range []string{"start", "pause", "stop"} {
		t.Run(action, func(t *testing.T) {
			body := jsonBody(ScrobbleRequest{Action: action, TmdbID: 12345, MediaType: "movie", Progress: 55})
			w := doHandler(s.handleScrobble, http.MethodPost, "/api/trakt/scrobble", body)
			assert.Equal(t, http.StatusAccepted, w.Code)
		})
	}
}

// ─── handleSync ──────────────────────────────────────────────────────────────

func TestHandleSync_MethodNotAllowed(t *testing.T) {
	fake := newFakeTraktServer(t)
	defer fake.Close()
	s := newHandlerServer(t, fake)

	w := doHandler(s.handleSync, http.MethodGet, "/api/trakt/sync", nil)
	assert.Equal(t, http.StatusMethodNotAllowed, w.Code)
}

func TestHandleSync_HappyPath(t *testing.T) {
	fake := newFakeTraktServer(t)
	defer fake.Close()
	s := newHandlerServer(t, fake)

	w := doHandler(s.handleSync, http.MethodPost, "/api/trakt/sync", nil)
	assert.Equal(t, http.StatusAccepted, w.Code)
	// Notify should have buffered exactly one signal.
	assert.Equal(t, 1, len(s.syncWorker.notify))
}

// ─── SetupHandlers (unconfigured) ─────────────────────────────────────────────

func TestSetupHandlers_UnconfiguredReturns503(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	lib, err := library.New("uncfg-test")
	require.NoError(t, err)
	st, err := settings.New("uncfg-test")
	require.NoError(t, err)

	s := New(Config{}, "uncfg", lib, st, nil)
	mux := http.NewServeMux()
	s.SetupHandlers(mux)

	paths := []string{
		"/api/trakt/device-code",
		"/api/trakt/poll",
		"/api/trakt/status",
		"/api/trakt/unlink",
		"/api/trakt/scrobble",
		"/api/trakt/sync",
	}
	for _, path := range paths {
		t.Run(path, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodGet, path, nil)
			w := httptest.NewRecorder()
			mux.ServeHTTP(w, req)
			assert.Equal(t, http.StatusServiceUnavailable, w.Code)
		})
	}
}

// ─── ConfigFromEnv ────────────────────────────────────────────────────────────

func TestConfigFromEnv_EnvOverridesLDFlags(t *testing.T) {
	t.Setenv("TRAKT_CLIENT_ID", "env-client-id")
	t.Setenv("TRAKT_CLIENT_SECRET", "env-client-secret")

	cfg := ConfigFromEnv("ldflag-id", "ldflag-secret")
	assert.Equal(t, "env-client-id", cfg.ClientID)
	assert.Equal(t, "env-client-secret", cfg.ClientSecret)
}

func TestConfigFromEnv_FallsBackToLDFlagsWhenEnvEmpty(t *testing.T) {
	t.Setenv("TRAKT_CLIENT_ID", "")
	t.Setenv("TRAKT_CLIENT_SECRET", "")

	cfg := ConfigFromEnv("ldflag-id", "ldflag-secret")
	assert.Equal(t, "ldflag-id", cfg.ClientID)
	assert.Equal(t, "ldflag-secret", cfg.ClientSecret)
}

// ─── Internal helpers: cancelFlow, setFlowState ───────────────────────────────

func TestCancelFlow_CancelsContextAndSetsState(t *testing.T) {
	s := &Server{}
	// Install a cancel function that records when it is called.
	cancelled := false
	s.flowMu.Lock()
	s.flowCancel = func() { cancelled = true }
	s.flowState = "pending"
	s.flowMu.Unlock()

	s.cancelFlow("idle")

	assert.True(t, cancelled, "cancelFlow must call the stored cancel func")
	s.flowMu.Lock()
	fs := s.flowState
	fc := s.flowCancel
	s.flowMu.Unlock()
	assert.Equal(t, "idle", fs)
	assert.Nil(t, fc, "cancelFlow must nil out flowCancel after calling it")
}

func TestSetFlowState_UpdatesStateUnderLock(t *testing.T) {
	s := &Server{}
	s.setFlowState("authorized")

	s.flowMu.Lock()
	got := s.flowState
	s.flowMu.Unlock()
	assert.Equal(t, "authorized", got)
}

// ─── runDeviceFlow ────────────────────────────────────────────────────────────

func TestRunDeviceFlow_ContextCancelExitsImmediately(t *testing.T) {
	// The goroutine must exit on <-ctx.Done() before the ticker fires (≥2 s).
	store := testStore(t)
	// Use an unreachable address; no network call should occur before cancel.
	client := testClient("http://127.0.0.1:1", store)
	s := &Server{client: client}

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		defer close(done)
		s.runDeviceFlow(ctx, "unused-code", 0 /* intervalSec */, 600 /* expiresInSec */)
	}()
	cancel()

	select {
	case <-done:
		// Exited cleanly.
	case <-time.After(3 * time.Second):
		t.Fatal("runDeviceFlow did not return after context cancel")
	}
}

func TestRunDeviceFlow_ExpiresOnDeadline(t *testing.T) {
	store := testStore(t)
	// Large interval so the ticker does not fire before expiry.
	fake := newFakeTraktServer(t)
	defer fake.Close()
	client := testClient(fake.URL, store)
	s := &Server{client: client}

	ctx := context.Background()
	done := make(chan struct{})
	go func() {
		defer close(done)
		// expiresInSec=0 fires the deadline timer immediately; interval=600 keeps ticker silent.
		s.runDeviceFlow(ctx, "unused-code", 600 /* intervalSec */, 0 /* expiresInSec */)
	}()

	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("runDeviceFlow did not return after expiry deadline")
	}

	s.flowMu.Lock()
	fs := s.flowState
	s.flowMu.Unlock()
	assert.Equal(t, "expired", fs)
}

// ─── Server.SetProfile ────────────────────────────────────────────────────────

func TestSetProfile_CancelsFlowAndSwitchesStore(t *testing.T) {
	fake := newFakeTraktServer(t)
	defer fake.Close()
	s := newHandlerServer(t, fake)

	s.flowMu.Lock()
	s.flowState = "pending"
	s.flowCancel = func() {} // no-op placeholder
	s.flowMu.Unlock()

	require.NoError(t, s.SetProfile("new-profile"))

	s.flowMu.Lock()
	fs := s.flowState
	s.flowMu.Unlock()
	assert.Equal(t, "idle", fs)
}

// ─── Store.newStore ───────────────────────────────────────────────────────────

func TestNewStore_MissingFileIsOK(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	s, err := newStore("brand-new-profile")
	require.NoError(t, err)
	assert.Empty(t, s.Get().AccessToken)
	assert.NotEmpty(t, s.path)
}

func TestNewStore_LoadsExistingToken(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())

	// Seed a token file by creating and saving via a first store instance.
	s1, err := newStore("existing-profile")
	require.NoError(t, err)
	require.NoError(t, s1.Save(tokenState{
		AccessToken: "pre-existing-tok",
		ExpiresAt:   time.Now().Add(time.Hour),
	}))

	// A second newStore for the same profile must load the saved token.
	s2, err := newStore("existing-profile")
	require.NoError(t, err)
	assert.Equal(t, "pre-existing-tok", s2.Get().AccessToken)
}

// ─── Store.SetProfile ─────────────────────────────────────────────────────────

func TestStoreSetProfile_SwitchesToNewProfile(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())

	sA, err := newStore("profile-a")
	require.NoError(t, err)
	require.NoError(t, sA.Save(tokenState{AccessToken: "tok-a", ExpiresAt: time.Now().Add(time.Hour)}))

	sB, err := newStore("profile-b")
	require.NoError(t, err)
	require.NoError(t, sB.Save(tokenState{AccessToken: "tok-b", ExpiresAt: time.Now().Add(time.Hour)}))

	// Switch sA over to profile-b.
	require.NoError(t, sA.SetProfile("profile-b"))
	assert.Equal(t, "tok-b", sA.Get().AccessToken)
}

// ─── Store.Clear ─────────────────────────────────────────────────────────────

func TestStoreClear_DeletesFileAndResetsState(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())

	s, err := newStore("clear-profile")
	require.NoError(t, err)
	require.NoError(t, s.Save(tokenState{AccessToken: "clear-me", ExpiresAt: time.Now().Add(time.Hour)}))
	assert.True(t, s.IsConnected())
	path := s.path

	require.NoError(t, s.Clear())
	assert.False(t, s.IsConnected())
	_, statErr := os.Stat(path)
	assert.True(t, os.IsNotExist(statErr), "token file should be removed after Clear")

	// Second Clear on a missing file must be a no-op (not an error).
	require.NoError(t, s.Clear())
}

// ─── RevokeToken (auth.go) ────────────────────────────────────────────────────

func TestRevokeToken_NoopWhenNotConnected(t *testing.T) {
	calls := 0
	fake := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		w.WriteHeader(http.StatusOK)
	}))
	defer fake.Close()

	store := testStore(t)
	c := testClient(fake.URL, store)
	c.RevokeToken() // no token in store → must short-circuit before any HTTP call

	assert.Equal(t, 0, calls)
}

func TestRevokeToken_CallsRevokeEndpoint(t *testing.T) {
	revoked := false
	fake := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/oauth/revoke" && r.Method == http.MethodPost {
			revoked = true
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer fake.Close()

	store := testStore(t)
	require.NoError(t, store.Save(tokenState{
		AccessToken: "live-tok",
		ExpiresAt:   time.Now().Add(time.Hour),
	}))
	c := testClient(fake.URL, store)
	c.RevokeToken()

	assert.True(t, revoked, "RevokeToken must POST to /oauth/revoke")
}

// ─── Server.RunSync ───────────────────────────────────────────────────────────

func TestRunSync_ExitsOnContextCancel(t *testing.T) {
	fake := newFakeTraktServer(t)
	defer fake.Close()
	s := newHandlerServer(t, fake)

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		defer close(done)
		s.RunSync(ctx)
	}()
	cancel()

	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("RunSync did not return after context cancel")
	}
}

// ─── SyncWorker.Run (sync.go) ─────────────────────────────────────────────────

func TestSyncWorkerRun_ExitsOnContextCancel(t *testing.T) {
	worker := &SyncWorker{notify: make(chan struct{}, 1)}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		defer close(done)
		worker.Run(ctx)
	}()
	cancel()

	select {
	case <-done:
		// Run returned cleanly.
	case <-time.After(5 * time.Second):
		t.Fatal("SyncWorker.Run did not return after context cancel")
	}
}
