//go:build supabase

package supabase

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func resetJWKSCache(t *testing.T) {
	t.Helper()
	jwksMu.Lock()
	old := jwksCache
	jwksCache = make(map[string]jwksCacheEntry)
	jwksMu.Unlock()
	t.Cleanup(func() {
		jwksMu.Lock()
		jwksCache = old
		jwksMu.Unlock()
	})
}

func TestConfigFromEnv(t *testing.T) {
	t.Setenv("SUPABASE_URL", "")
	t.Setenv("SUPABASE_PUBLISHABLE_KEY", "")
	assert.Nil(t, ConfigFromEnv("", ""))

	cfg := ConfigFromEnv("https://default.invalid/", "default-key")
	require.NotNil(t, cfg)
	assert.Equal(t, "https://default.invalid", cfg.URL)
	assert.Equal(t, "default-key", cfg.AnonKey)

	t.Setenv("SUPABASE_URL", "https://env.invalid///")
	t.Setenv("SUPABASE_PUBLISHABLE_KEY", "env-key")
	cfg = ConfigFromEnv("https://ignored.invalid", "ignored-key")
	require.NotNil(t, cfg)
	assert.Equal(t, "https://env.invalid", cfg.URL)
	assert.Equal(t, "env-key", cfg.AnonKey)
}

func TestBearerFromRequest(t *testing.T) {
	request := func(header string) *http.Request {
		req := httptestRequest(t, http.MethodGet, "http://cove.invalid", "")
		req.Header.Set("Authorization", header)
		return req
	}

	assert.Equal(t, "access-token", BearerFromRequest(request("Bearer access-token")))
	assert.Empty(t, BearerFromRequest(request("bearer access-token")))
	assert.Empty(t, BearerFromRequest(request("Basic access-token")))
	assert.Empty(t, BearerFromRequest(request("Bearer ")))
}

func TestAuthHelpersUseExpectedEndpointsAndPayloads(t *testing.T) {
	type authCall struct {
		path  string
		query url.Values
		body  map[string]any
	}
	var calls []authCall
	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		require.Equal(t, "anon", r.Header.Get("apikey"))
		require.Equal(t, "application/json", r.Header.Get("Content-Type"))
		var body map[string]any
		require.NoError(t, json.NewDecoder(r.Body).Decode(&body))
		calls = append(calls, authCall{path: r.URL.Path, query: r.URL.Query(), body: body})

		switch r.URL.Path {
		case "/auth/v1/signup":
			return response(http.StatusOK, `{"id":"signup-user"}`), nil
		case "/auth/v1/token":
			return response(http.StatusOK, `{
				"access_token":"sign-in-access",
				"refresh_token":"sign-in-refresh",
				"user":{"id":"sign-in-user"}
			}`), nil
		case "/auth/v1/otp":
			return response(http.StatusOK, `{"msg":"ok"}`), nil
		case "/auth/v1/verify":
			if body["type"] == "email" {
				return response(http.StatusOK, `{
					"access_token":"otp-access",
					"refresh_token":"otp-refresh",
					"user":{"id":"otp-user"}
				}`), nil
			}
			return response(http.StatusOK, `{}`), nil
		default:
			return response(http.StatusNotFound, `{"msg":"unknown endpoint"}`), nil
		}
	})

	cfg := &Config{URL: "https://project.invalid", AnonKey: "anon"}
	userID, accessToken, err := cfg.SignUp("new@example.com", "password")
	require.NoError(t, err)
	assert.Equal(t, "signup-user", userID)
	assert.Empty(t, accessToken)

	require.NoError(t, cfg.VerifySignup("new@example.com", "123456"))

	userID, accessToken, refreshToken, err := cfg.SignIn("user@example.com", "password")
	require.NoError(t, err)
	assert.Equal(t, "sign-in-user", userID)
	assert.Equal(t, "sign-in-access", accessToken)
	assert.Equal(t, "sign-in-refresh", refreshToken)

	require.NoError(t, cfg.SendOTP("otp@example.com"))
	userID, accessToken, refreshToken, err = cfg.VerifyOTP("otp@example.com", "654321")
	require.NoError(t, err)
	assert.Equal(t, "otp-user", userID)
	assert.Equal(t, "otp-access", accessToken)
	assert.Equal(t, "otp-refresh", refreshToken)

	require.Len(t, calls, 5)
	assert.Equal(t, "/auth/v1/signup", calls[0].path)
	assert.Equal(t, "new@example.com", calls[0].body["email"])
	assert.Equal(t, "signup", calls[1].body["type"])
	assert.Equal(t, "password", calls[2].query.Get("grant_type"))
	assert.Equal(t, true, calls[3].body["create_user"])
	assert.Equal(t, true, calls[3].body["should_create_user"])
	assert.Equal(t, "email", calls[4].body["type"])
}

func TestSignInFallsBackToJWTSubject(t *testing.T) {
	payload, err := json.Marshal(map[string]string{"sub": "jwt-user"})
	require.NoError(t, err)
	token := "e30." + base64.RawURLEncoding.EncodeToString(payload) + ".signature"

	call := 0
	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		call++
		if call == 1 {
			return response(http.StatusOK, `{
				"access_token":"`+token+`",
				"refresh_token":"refresh"
			}`), nil
		}
		return response(http.StatusOK, `{"access_token":"not-a-jwt"}`), nil
	})

	cfg := &Config{URL: "https://project.invalid", AnonKey: "anon"}
	userID, accessToken, refreshToken, err := cfg.SignIn("user@example.com", "password")
	require.NoError(t, err)
	assert.Equal(t, "jwt-user", userID)
	assert.Equal(t, token, accessToken)
	assert.Equal(t, "refresh", refreshToken)

	_, _, _, err = cfg.SignIn("user@example.com", "password")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "could not determine user ID")

	assert.Empty(t, subFromJWT("not-a-jwt"))
	assert.Empty(t, subFromJWT("a.invalid!.c"))
}

func TestAuthPostSurfacesSupabaseErrorShapes(t *testing.T) {
	tests := []struct {
		name string
		body string
		want string
	}{
		{name: "description", body: `{"error_description":"invalid credentials"}`, want: "invalid credentials"},
		{name: "error", body: `{"error":"rate_limited"}`, want: "rate_limited"},
		{name: "message", body: `{"msg":"email blocked"}`, want: "email blocked"},
		{name: "raw body", body: `{}`, want: "{}"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
				return response(http.StatusBadRequest, tt.body), nil
			})
			cfg := &Config{URL: "https://project.invalid", AnonKey: "anon"}
			err := cfg.SendOTP("user@example.com")
			require.Error(t, err)
			assert.Contains(t, err.Error(), "supabase auth (400)")
			assert.Contains(t, err.Error(), tt.want)
		})
	}

	t.Run("malformed success response", func(t *testing.T) {
		withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
			return response(http.StatusOK, "not-json"), nil
		})
		cfg := &Config{URL: "https://project.invalid", AnonKey: "anon"}
		err := cfg.SendOTP("user@example.com")
		require.Error(t, err)
		assert.Contains(t, err.Error(), "unexpected response")
	})
}

func TestRestRequestHeadersAndFailures(t *testing.T) {
	cfg := &Config{URL: "https://project.invalid", AnonKey: "anon"}
	_, err := cfg.restReq("", http.MethodGet, "profiles", "", nil)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "no user token")

	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		assert.Equal(t, "/rest/v1/profiles", r.URL.Path)
		assert.Equal(t, "id", r.URL.Query().Get("select"))
		assert.Equal(t, "anon", r.Header.Get("apikey"))
		assert.Equal(t, "Bearer jwt", r.Header.Get("Authorization"))
		assert.Equal(t, "resolution=merge-duplicates,return=representation", r.Header.Get("Prefer"))
		data, readErr := io.ReadAll(r.Body)
		require.NoError(t, readErr)
		assert.JSONEq(t, `{"name":"Primary"}`, string(data))
		return response(http.StatusOK, `[{"id":"profile-1"}]`), nil
	})

	data, err := cfg.restReq("jwt", http.MethodPatch, "profiles", "select=id", map[string]string{"name": "Primary"})
	require.NoError(t, err)
	assert.JSONEq(t, `[{"id":"profile-1"}]`, string(data))

	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		return response(http.StatusForbidden, `{"code":"42501"}`), nil
	})
	_, err = cfg.Select("jwt", "profiles", "select=id")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "42501")

	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		return response(http.StatusOK, `not-json`), nil
	})
	_, err = cfg.Select("jwt", "profiles", "select=id")
	require.Error(t, err)
}

func TestFetchJWKSCachesUsableES256Keys(t *testing.T) {
	resetJWKSCache(t)
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	require.NoError(t, err)
	x := base64.RawURLEncoding.EncodeToString(key.X.Bytes())
	y := base64.RawURLEncoding.EncodeToString(key.Y.Bytes())

	calls := 0
	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		calls++
		assert.Equal(t, "/auth/v1/.well-known/jwks.json", r.URL.Path)
		return response(http.StatusOK, `{"keys":[
			{"kid":"wrong-curve","kty":"EC","crv":"P-384","x":"`+x+`","y":"`+y+`"},
			{"kid":"key-1","kty":"EC","crv":"P-256","x":"`+x+`","y":"`+y+`"}
		]}`), nil
	})

	cfg := &Config{URL: "https://project.invalid", AnonKey: "anon"}
	keys, err := cfg.fetchJWKS(true)
	require.NoError(t, err)
	require.Contains(t, keys, "key-1")
	assert.Equal(t, key.X, keys["key-1"].X)
	assert.Equal(t, key.Y, keys["key-1"].Y)

	cached, err := cfg.fetchJWKS(false)
	require.NoError(t, err)
	assert.Same(t, keys["key-1"], cached["key-1"])
	assert.Equal(t, 1, calls)
}

func TestFetchJWKSErrors(t *testing.T) {
	tests := []struct {
		name string
		code int
		body string
		want string
	}{
		{name: "upstream status", code: http.StatusBadGateway, body: "unavailable", want: "fetch jwks (502)"},
		{name: "malformed JSON", code: http.StatusOK, body: "not-json", want: "decode jwks"},
		{name: "no usable keys", code: http.StatusOK, body: `{"keys":[]}`, want: "no usable ES256 keys"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			resetJWKSCache(t)
			withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
				return response(tt.code, tt.body), nil
			})
			cfg := &Config{URL: "https://project.invalid", AnonKey: "anon"}
			_, err := cfg.fetchJWKS(true)
			require.Error(t, err)
			assert.Contains(t, err.Error(), tt.want)
		})
	}
}

func TestValidateJWTRefreshesUnknownSigningKey(t *testing.T) {
	resetJWKSCache(t)
	oldKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	require.NoError(t, err)
	newKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	require.NoError(t, err)
	cfg := &Config{URL: "https://project.invalid", AnonKey: "anon"}

	jwksMu.Lock()
	jwksCache[cfg.URL] = jwksCacheEntry{
		keys:    map[string]*ecdsa.PublicKey{"old-key": &oldKey.PublicKey},
		fetched: time.Now(),
	}
	jwksMu.Unlock()

	x := base64.RawURLEncoding.EncodeToString(newKey.X.Bytes())
	y := base64.RawURLEncoding.EncodeToString(newKey.Y.Bytes())
	withHTTPClient(t, func(r *http.Request) (*http.Response, error) {
		return response(http.StatusOK, `{"keys":[{
			"kid":"new-key","kty":"EC","crv":"P-256","x":"`+x+`","y":"`+y+`"
		}]}`), nil
	})

	claims := jwt.MapClaims{
		"sub": "rotated-user",
		"iss": cfg.URL + "/auth/v1",
		"aud": "authenticated",
		"exp": time.Now().Add(time.Hour).Unix(),
	}
	token := jwt.NewWithClaims(jwt.SigningMethodES256, claims)
	token.Header["kid"] = "new-key"
	signed, err := token.SignedString(newKey)
	require.NoError(t, err)

	userID, err := cfg.ValidateJWT(signed)
	require.NoError(t, err)
	assert.Equal(t, "rotated-user", userID)
}

func httptestRequest(t *testing.T, method, target, body string) *http.Request {
	t.Helper()
	req, err := http.NewRequest(method, target, strings.NewReader(body))
	require.NoError(t, err)
	return req
}
