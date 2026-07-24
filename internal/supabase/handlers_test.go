//go:build supabase

package supabase

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coveninja/cove/internal/activity"
	"github.com/coveninja/cove/internal/addons"
	"github.com/coveninja/cove/internal/library"
	"github.com/coveninja/cove/internal/nuvio"
	"github.com/coveninja/cove/internal/profiles"
	"github.com/coveninja/cove/internal/settings"
	"github.com/golang-jwt/jwt/v5"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func newHandlerTestServer(t *testing.T, cfg *Config) *Server {
	t.Helper()
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

	return NewServer(
		cfg,
		profileStore,
		lib,
		settingsStore,
		addons.New(profileID, nil),
		nuvio.New(profileID),
		activityStore,
	)
}

func authRequest(
	t *testing.T,
	server *Server,
	method, path, body string,
	headers map[string]string,
) *httptest.ResponseRecorder {
	t.Helper()
	mux := http.NewServeMux()
	server.SetupHandlers(mux)
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	for key, value := range headers {
		req.Header.Set(key, value)
	}
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	return rec
}

func testAccessToken(t *testing.T, cfg *Config, userID string) string {
	t.Helper()
	resetJWKSCache(t)
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	require.NoError(t, err)

	jwksMu.Lock()
	jwksCache[cfg.URL] = jwksCacheEntry{
		keys:    map[string]*ecdsa.PublicKey{"test-key": &key.PublicKey},
		fetched: time.Now(),
	}
	jwksMu.Unlock()

	claims := jwt.MapClaims{
		"sub": userID,
		"iss": cfg.URL + "/auth/v1",
		"aud": "authenticated",
		"exp": time.Now().Add(time.Hour).Unix(),
	}
	token := jwt.NewWithClaims(jwt.SigningMethodES256, claims)
	token.Header["kid"] = "test-key"
	signed, err := token.SignedString(key)
	require.NoError(t, err)
	return signed
}

func waitForPush(t *testing.T, server *Server) {
	t.Helper()
	require.Eventually(t, func() bool {
		return !server.pushQueued.Load()
	}, time.Second, 5*time.Millisecond)
	// pushQueued is reset immediately after the goroutine acquires pushMu.
	// Taking the same lock here therefore waits for all HTTP calls to finish.
	server.pushMu.Lock()
	server.pushMu.Unlock()
}

func TestAuthRoutesEnforceMethodsAndConfiguration(t *testing.T) {
	configured := newHandlerTestServer(t, &Config{
		URL:     "https://project.invalid",
		AnonKey: "anon",
	})
	methodChecks := []struct {
		path   string
		method string
	}{
		{path: "/api/auth/register", method: http.MethodGet},
		{path: "/api/auth/register/confirm", method: http.MethodGet},
		{path: "/api/auth/login", method: http.MethodGet},
		{path: "/api/auth/otp", method: http.MethodGet},
		{path: "/api/auth/verify-otp", method: http.MethodGet},
		{path: "/api/auth/logout", method: http.MethodGet},
		{path: "/api/auth/me", method: http.MethodPost},
		{path: "/api/auth/sync", method: http.MethodGet},
	}
	for _, tt := range methodChecks {
		t.Run("method "+tt.path, func(t *testing.T) {
			rec := authRequest(t, configured, tt.method, tt.path, "", nil)
			assert.Equal(t, http.StatusMethodNotAllowed, rec.Code)
		})
	}

	notConfigured := newHandlerTestServer(t, nil)
	for _, path := range []string{
		"/api/auth/register",
		"/api/auth/register/confirm",
		"/api/auth/login",
		"/api/auth/otp",
		"/api/auth/verify-otp",
		"/api/auth/sync",
	} {
		t.Run("not configured "+path, func(t *testing.T) {
			rec := authRequest(t, notConfigured, http.MethodPost, path, `{}`, nil)
			assert.Equal(t, http.StatusServiceUnavailable, rec.Code)
			assert.Contains(t, rec.Body.String(), "Supabase not configured")
		})
	}

	rec := authRequest(
		t,
		configured,
		http.MethodOptions,
		"/api/auth/login",
		"",
		map[string]string{"Origin": "http://example.com"},
	)
	assert.Equal(t, http.StatusNoContent, rec.Code)
}

func TestAuthRoutesRejectMalformedInput(t *testing.T) {
	server := newHandlerTestServer(t, &Config{
		URL:     "https://project.invalid",
		AnonKey: "anon",
	})
	tests := []struct {
		path string
		body string
		want string
	}{
		{path: "/api/auth/register", body: `{`, want: "email and password required"},
		{path: "/api/auth/register/confirm", body: `{"email":"a@example.com","token":"123"}`, want: "email, token, and password required"},
		{path: "/api/auth/login", body: `{"password":"secret"}`, want: "email and password required"},
		{path: "/api/auth/otp", body: `{}`, want: "email required"},
		{path: "/api/auth/verify-otp", body: `{"email":"a@example.com"}`, want: "email and token required"},
	}
	for _, tt := range tests {
		t.Run(tt.path, func(t *testing.T) {
			rec := authRequest(t, server, http.MethodPost, tt.path, tt.body, nil)
			assert.Equal(t, http.StatusBadRequest, rec.Code)
			assert.Contains(t, rec.Body.String(), tt.want)
		})
	}
}

func TestRegisterConfirmationAndOTPRoutes(t *testing.T) {
	var mu sync.Mutex
	var paths []string
	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		mu.Lock()
		paths = append(paths, r.URL.Path)
		mu.Unlock()
		switch r.URL.Path {
		case "/auth/v1/signup":
			return response(http.StatusOK, `{"id":"pending-user"}`), nil
		case "/auth/v1/otp":
			return response(http.StatusOK, `{"msg":"ok"}`), nil
		default:
			return response(http.StatusNotFound, `{"msg":"not found"}`), nil
		}
	})
	server := newHandlerTestServer(t, &Config{
		URL:     "https://project.invalid",
		AnonKey: "anon",
	})

	rec := authRequest(
		t,
		server,
		http.MethodPost,
		"/api/auth/register",
		`{"email":"new@example.com","password":"secret","profile_name":"Movie Night"}`,
		nil,
	)
	require.Equal(t, http.StatusOK, rec.Code)
	assert.Equal(t, "application/json", rec.Header().Get("Content-Type"))
	var registerResult map[string]any
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &registerResult))
	assert.Equal(t, true, registerResult["confirmation_required"])

	rec = authRequest(
		t,
		server,
		http.MethodPost,
		"/api/auth/otp",
		`{"email":"user@example.com"}`,
		nil,
	)
	require.Equal(t, http.StatusOK, rec.Code)
	assert.JSONEq(t, `{"status":"ok"}`, rec.Body.String())

	mu.Lock()
	assert.Equal(t, []string{"/auth/v1/signup", "/auth/v1/otp"}, paths)
	mu.Unlock()
}

func TestRegisterWithImmediateSessionLinksProfileAndPushes(t *testing.T) {
	var mu sync.Mutex
	var profilePosts int
	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		if r.URL.Path == "/auth/v1/signup" {
			return response(http.StatusOK, `{
				"access_token":"access-token",
				"user":{"id":"user-1"}
			}`), nil
		}
		if r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/profiles") {
			mu.Lock()
			profilePosts++
			mu.Unlock()
		}
		return response(http.StatusOK, `[]`), nil
	})
	server := newHandlerTestServer(t, &Config{
		URL:     "https://project.invalid",
		AnonKey: "anon",
	})

	rec := authRequest(
		t,
		server,
		http.MethodPost,
		"/api/auth/register",
		`{"email":"new@example.com","password":"secret","profile_name":"Movie Night"}`,
		nil,
	)
	require.Equal(t, http.StatusOK, rec.Code, rec.Body.String())
	var result map[string]any
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &result))
	assert.Equal(t, "access-token", result["access_token"])
	assert.Equal(t, "", result["refresh_token"])
	require.NotNil(t, server.profileStore.ActiveProfile().SupabaseUID)
	assert.Equal(t, "user-1", *server.profileStore.ActiveProfile().SupabaseUID)

	waitForPush(t, server)
	mu.Lock()
	assert.GreaterOrEqual(t, profilePosts, 2)
	mu.Unlock()
}

func TestLoginFailsClosedWhenInitialPullFails(t *testing.T) {
	server := newHandlerTestServer(t, &Config{
		URL:     "https://project.invalid",
		AnonKey: "anon",
	})
	profileID := server.profileStore.ActiveProfileID()

	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		switch {
		case r.URL.Path == "/auth/v1/token":
			return response(http.StatusOK, `{
				"access_token":"access-token",
				"refresh_token":"refresh-token",
				"user":{"id":"user-1"}
			}`), nil
		case strings.HasSuffix(r.URL.Path, "/profiles"):
			return response(http.StatusOK, `[{
				"id":"`+profileID+`",
				"user_id":"user-1",
				"name":"Primary",
				"is_primary":true
			}]`), nil
		case strings.HasSuffix(r.URL.Path, "/library_entries"):
			return response(http.StatusServiceUnavailable, `{"message":"unavailable"}`), nil
		default:
			return response(http.StatusOK, `[]`), nil
		}
	})

	rec := authRequest(
		t,
		server,
		http.MethodPost,
		"/api/auth/login",
		`{"email":"user@example.com","password":"secret"}`,
		nil,
	)
	assert.Equal(t, http.StatusBadGateway, rec.Code)
	assert.Contains(t, rec.Body.String(), "signed in but initial sync failed")
	assert.Contains(t, rec.Body.String(), "pull library_entries")
}

func TestMeAndLogoutReflectProfileLink(t *testing.T) {
	server := newHandlerTestServer(t, &Config{
		URL:     "https://project.invalid",
		AnonKey: "anon",
	})
	profileID := server.profileStore.ActiveProfileID()
	require.NoError(t, server.profileStore.LinkSupabase(profileID, "user-1"))

	rec := authRequest(t, server, http.MethodGet, "/api/auth/me", "", nil)
	require.Equal(t, http.StatusOK, rec.Code)
	var me map[string]any
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &me))
	assert.Equal(t, true, me["linked"])

	rec = authRequest(t, server, http.MethodPost, "/api/auth/logout", "", nil)
	require.Equal(t, http.StatusOK, rec.Code)
	assert.JSONEq(t, `{"status":"ok"}`, rec.Body.String())
	assert.Nil(t, server.profileStore.ActiveProfile().SupabaseUID)

	rec = authRequest(t, server, http.MethodGet, "/api/auth/me", "", nil)
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &me))
	assert.Equal(t, false, me["linked"])
}

func TestSyncRequiresAValidBearerToken(t *testing.T) {
	server := newHandlerTestServer(t, &Config{
		URL:     "https://project.invalid",
		AnonKey: "anon",
	})

	rec := authRequest(t, server, http.MethodPost, "/api/auth/sync", "", nil)
	assert.Equal(t, http.StatusUnauthorized, rec.Code)
	assert.Contains(t, rec.Body.String(), "authorization required")

	rec = authRequest(
		t,
		server,
		http.MethodPost,
		"/api/auth/sync",
		"",
		map[string]string{"Authorization": "Bearer not-a-jwt"},
	)
	assert.Equal(t, http.StatusUnauthorized, rec.Code)
	assert.Contains(t, rec.Body.String(), "invalid token")
}

func TestSyncPullsThenQueuesSuccessfulPush(t *testing.T) {
	cfg := &Config{URL: "https://project.invalid", AnonKey: "anon"}
	server := newHandlerTestServer(t, cfg)
	profileID := server.profileStore.ActiveProfileID()
	token := testAccessToken(t, cfg, "user-1")

	var mu sync.Mutex
	var methods []string
	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		mu.Lock()
		methods = append(methods, r.Method+" "+strings.TrimPrefix(r.URL.Path, "/rest/v1/"))
		mu.Unlock()
		if r.Method == http.MethodGet && strings.HasSuffix(r.URL.Path, "/profiles") {
			return response(http.StatusOK, `[{
				"id":"`+profileID+`",
				"user_id":"user-1",
				"name":"Primary",
				"is_primary":true
			}]`), nil
		}
		return response(http.StatusOK, `[]`), nil
	})

	rec := authRequest(
		t,
		server,
		http.MethodPost,
		"/api/auth/sync",
		"",
		map[string]string{"Authorization": "Bearer " + token},
	)
	require.Equal(t, http.StatusOK, rec.Code, rec.Body.String())
	var result map[string]any
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &result))
	assert.Equal(t, "ok", result["status"])
	assert.Equal(t, "", result["push_error"])
	assert.NotNil(t, result["library_generation"])
	require.NotNil(t, server.profileStore.ActiveProfile().SupabaseUID)
	assert.Equal(t, "user-1", *server.profileStore.ActiveProfile().SupabaseUID)

	waitForPush(t, server)

	mu.Lock()
	defer mu.Unlock()
	assert.Contains(t, methods, "GET profiles")
	assert.Contains(t, methods, "GET library_entries")
	assert.Contains(t, methods, "POST profile_settings")
	assert.Contains(t, methods, "POST profiles")
}

func TestCleanupDeletedProfileSkipsUnlinkedProfiles(t *testing.T) {
	server := newHandlerTestServer(t, nil)
	uid := "user-1"
	require.NoError(t, server.CleanupDeletedProfile("jwt", "profile-1", &uid))

	server.cfg = &Config{URL: "https://project.invalid", AnonKey: "anon"}
	require.NoError(t, server.CleanupDeletedProfile("", "profile-1", &uid))
	require.NoError(t, server.CleanupDeletedProfile("jwt", "profile-1", nil))
}

func TestReconcileProfileAppliesNewerRemoteName(t *testing.T) {
	server := newHandlerTestServer(t, &Config{
		URL:     "https://project.invalid",
		AnonKey: "anon",
	})
	profileID := server.profileStore.ActiveProfileID()
	updatedAt := time.Now().Add(time.Hour).UTC().Format(time.RFC3339Nano)
	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		return response(http.StatusOK, `[{
			"id":"`+profileID+`",
			"user_id":"user-1",
			"name":"Remote Name",
			"is_primary":true,
			"updated_at":"`+updatedAt+`"
		}]`), nil
	})

	reconciled, err := server.reconcileProfile("user-1", "jwt")
	require.NoError(t, err)
	assert.Equal(t, profileID, reconciled.ID)
	assert.Equal(t, "Remote Name", reconciled.Name)
	assert.Equal(t, "Remote Name", server.profileStore.ActiveProfile().Name)
}

func TestReconcileProfileCreatesFirstRemoteProfile(t *testing.T) {
	server := newHandlerTestServer(t, &Config{
		URL:     "https://project.invalid",
		AnonKey: "anon",
	})
	var mu sync.Mutex
	var methods []string
	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		mu.Lock()
		methods = append(methods, r.Method)
		mu.Unlock()
		return response(http.StatusOK, `[]`), nil
	})

	reconciled, err := server.reconcileProfile("user-1", "jwt")
	require.NoError(t, err)
	assert.Equal(t, server.profileStore.ActiveProfileID(), reconciled.ID)
	mu.Lock()
	assert.Equal(t, []string{http.MethodGet, http.MethodPost}, methods)
	mu.Unlock()
}

func TestReconcileProfileAdoptsRemotePrimary(t *testing.T) {
	server := newHandlerTestServer(t, &Config{
		URL:     "https://project.invalid",
		AnonKey: "anon",
	})
	oldID := server.profileStore.ActiveProfileID()
	updatedAt := time.Now().UTC().Format(time.RFC3339Nano)
	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		return response(http.StatusOK, `[{
			"id":"remote-secondary",
			"user_id":"user-1",
			"name":"Secondary",
			"is_primary":false,
			"updated_at":"`+updatedAt+`"
		},{
			"id":"remote-primary",
			"user_id":"user-1",
			"name":"Shared Primary",
			"is_primary":true,
			"updated_at":"`+updatedAt+`"
		}]`), nil
	})

	reconciled, err := server.reconcileProfile("user-1", "jwt")
	require.NoError(t, err)
	assert.Equal(t, "remote-primary", reconciled.ID)
	assert.Equal(t, "Shared Primary", reconciled.Name)
	_, exists := server.profileStore.Get(oldID)
	assert.False(t, exists)
}
