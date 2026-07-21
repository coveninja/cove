//go:build supabase

package supabase

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"io"
	"net/http"
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
