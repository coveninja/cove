package trakt

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"
)

// testStore creates a Store backed by a temp file so tests stay isolated.
func testStore(t *testing.T) *Store {
	t.Helper()
	dir := t.TempDir()
	s := &Store{path: filepath.Join(dir, "trakt-test.json")}
	return s
}

// testClient creates a Client pointed at the given test server URL.
func testClient(baseURL string, store *Store) *Client {
	c := newClient("test-client-id", "test-client-secret", store)
	c.baseURL = baseURL
	// Disable write rate-limiting in tests: set lastWrite to the zero value
	// (time.Since(zero) is huge, so the sleep condition is never true).
	c.lastWrite = time.Time{}
	return c
}

// ── Device flow ───────────────────────────────────────────────────────────────

// TestDeviceFlowPendingThenAuthorized exercises the full device-code flow:
// the first poll returns 400 (pending), the second returns 200 (authorized).
func TestDeviceFlowPendingThenAuthorized(t *testing.T) {
	pollCount := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/oauth/device/code":
			json.NewEncoder(w).Encode(map[string]any{
				"device_code":      "dcode123",
				"user_code":        "ABCD-1234",
				"verification_url": "https://trakt.tv/activate",
				"expires_in":       600,
				"interval":         5,
			})

		case "/oauth/device/token":
			pollCount++
			if pollCount == 1 {
				// Still pending.
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			// Authorized on second poll.
			json.NewEncoder(w).Encode(map[string]any{
				"access_token":  "access-tok",
				"refresh_token": "refresh-tok",
				"expires_in":    7776000,
			})

		case "/users/me":
			json.NewEncoder(w).Encode(map[string]string{"username": "testuser"})

		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()

	store := testStore(t)
	c := testClient(srv.URL, store)

	code, err := c.StartDeviceFlow()
	if err != nil {
		t.Fatalf("StartDeviceFlow: %v", err)
	}
	if code.DeviceCode != "dcode123" {
		t.Errorf("device_code = %q, want dcode123", code.DeviceCode)
	}
	if code.UserCode != "ABCD-1234" {
		t.Errorf("user_code = %q, want ABCD-1234", code.UserCode)
	}

	// First poll: pending.
	res, err := c.PollDeviceFlow(code.DeviceCode)
	if err != nil {
		t.Fatalf("PollDeviceFlow (1): %v", err)
	}
	if res.Status != PollPending {
		t.Errorf("status = %q, want %q", res.Status, PollPending)
	}

	// Second poll: authorized.
	res, err = c.PollDeviceFlow(code.DeviceCode)
	if err != nil {
		t.Fatalf("PollDeviceFlow (2): %v", err)
	}
	if res.Status != PollAuthorized {
		t.Errorf("status = %q, want %q", res.Status, PollAuthorized)
	}
	if res.Username != "testuser" {
		t.Errorf("username = %q, want testuser", res.Username)
	}

	// Verify token was persisted.
	st := store.Get()
	if st.AccessToken != "access-tok" {
		t.Errorf("access_token = %q, want access-tok", st.AccessToken)
	}
	if st.Username != "testuser" {
		t.Errorf("username = %q, want testuser", st.Username)
	}
	if st.ExpiresAt.IsZero() {
		t.Error("expires_at not set")
	}

	// Verify file was written.
	if _, err := os.Stat(store.path); err != nil {
		t.Errorf("token file not written: %v", err)
	}
}

// TestDeviceFlowExpired verifies that 410 from device/token maps to PollExpired.
func TestDeviceFlowExpired(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/oauth/device/code":
			json.NewEncoder(w).Encode(map[string]any{"device_code": "x", "user_code": "X", "verification_url": "u", "expires_in": 60, "interval": 5})
		case "/oauth/device/token":
			w.WriteHeader(http.StatusGone)
		}
	}))
	defer srv.Close()

	c := testClient(srv.URL, testStore(t))
	if _, err := c.StartDeviceFlow(); err != nil {
		t.Fatalf("StartDeviceFlow: %v", err)
	}
	res, err := c.PollDeviceFlow("x")
	if err != nil {
		t.Fatalf("PollDeviceFlow: %v", err)
	}
	if res.Status != PollExpired {
		t.Errorf("status = %q, want %q", res.Status, PollExpired)
	}
}

// ── 429 retry ─────────────────────────────────────────────────────────────────

// Test429Retry verifies that doRequest retries once after a 429 response and
// succeeds on the second attempt.
func Test429Retry(t *testing.T) {
	attempts := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		attempts++
		if attempts == 1 {
			w.Header().Set("Retry-After", "0") // 0 = retry immediately in test
			w.WriteHeader(http.StatusTooManyRequests)
			return
		}
		json.NewEncoder(w).Encode(map[string]string{"ok": "true"})
	}))
	defer srv.Close()

	store := testStore(t)
	c := testClient(srv.URL, store)

	var out map[string]string
	err := c.get("/anything", &out)
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if attempts != 2 {
		t.Errorf("server hit %d times, want 2", attempts)
	}
	if out["ok"] != "true" {
		t.Errorf("response body = %v, want {ok:true}", out)
	}
}

// ── Token refresh ─────────────────────────────────────────────────────────────

// TestTokenRefresh verifies that EnsureValidToken calls POST /oauth/token when
// the access token expires within 1 hour, and saves the new token.
func TestTokenRefresh(t *testing.T) {
	refreshCalled := false
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/oauth/token" {
			http.NotFound(w, r)
			return
		}
		var body map[string]string
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, "bad body", http.StatusBadRequest)
			return
		}
		if body["grant_type"] != "refresh_token" {
			http.Error(w, "wrong grant_type", http.StatusBadRequest)
			return
		}
		refreshCalled = true
		json.NewEncoder(w).Encode(map[string]any{
			"access_token":  "new-access-tok",
			"refresh_token": "new-refresh-tok",
			"expires_in":    7776000,
		})
	}))
	defer srv.Close()

	store := testStore(t)
	// Seed an expired token (expires in 30 minutes, below the 1-hour threshold).
	_ = store.Save(tokenState{
		AccessToken:  "old-access-tok",
		RefreshToken: "old-refresh-tok",
		ExpiresAt:    time.Now().Add(30 * time.Minute),
	})

	c := testClient(srv.URL, store)

	if err := c.EnsureValidToken(); err != nil {
		t.Fatalf("EnsureValidToken: %v", err)
	}
	if !refreshCalled {
		t.Error("expected POST /oauth/token but it was not called")
	}
	st := store.Get()
	if st.AccessToken != "new-access-tok" {
		t.Errorf("access_token = %q, want new-access-tok", st.AccessToken)
	}
}

// TestTokenRefreshSkippedWhenFresh verifies that EnsureValidToken is a no-op
// when the token still has more than 1 hour left.
func TestTokenRefreshSkippedWhenFresh(t *testing.T) {
	calls := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
	}))
	defer srv.Close()

	store := testStore(t)
	_ = store.Save(tokenState{
		AccessToken: "fresh-token",
		ExpiresAt:   time.Now().Add(2 * time.Hour),
	})

	c := testClient(srv.URL, store)
	if err := c.EnsureValidToken(); err != nil {
		t.Fatalf("EnsureValidToken: %v", err)
	}
	if calls != 0 {
		t.Errorf("server called %d times, want 0 (token still fresh)", calls)
	}
}

// ── Scrobble payloads ─────────────────────────────────────────────────────────

// TestScrobbleMoviePayload verifies the movie scrobble request body shape.
func TestScrobbleMoviePayload(t *testing.T) {
	var got map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := json.NewDecoder(r.Body).Decode(&got); err != nil {
			http.Error(w, "bad body", http.StatusBadRequest)
			return
		}
		w.WriteHeader(http.StatusCreated)
	}))
	defer srv.Close()

	store := testStore(t)
	_ = store.Save(tokenState{AccessToken: "tok", ExpiresAt: time.Now().Add(2 * time.Hour)})
	c := testClient(srv.URL, store)

	req := ScrobbleRequest{
		Action:    "stop",
		TmdbID:    12345,
		MediaType: "movie",
		Progress:  85.0,
	}
	if err := c.scrobble(req.Action, req); err != nil {
		t.Fatalf("scrobble: %v", err)
	}

	movie, ok := got["movie"].(map[string]any)
	if !ok {
		t.Fatalf("response missing movie key, got %v", got)
	}
	ids, ok := movie["ids"].(map[string]any)
	if !ok {
		t.Fatalf("movie.ids missing, got %v", movie)
	}
	// JSON numbers decode as float64.
	if tmdb, _ := ids["tmdb"].(float64); int(tmdb) != 12345 {
		t.Errorf("movie.ids.tmdb = %v, want 12345", ids["tmdb"])
	}
	if progress, _ := got["progress"].(float64); progress != 85.0 {
		t.Errorf("progress = %v, want 85.0", got["progress"])
	}
	if _, hasShow := got["show"]; hasShow {
		t.Error("movie scrobble must not include show key")
	}
}

// TestScrobbleEpisodePayload verifies the TV episode scrobble request body shape.
func TestScrobbleEpisodePayload(t *testing.T) {
	var got map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewDecoder(r.Body).Decode(&got)
		w.WriteHeader(http.StatusCreated)
	}))
	defer srv.Close()

	store := testStore(t)
	_ = store.Save(tokenState{AccessToken: "tok", ExpiresAt: time.Now().Add(2 * time.Hour)})
	c := testClient(srv.URL, store)

	s, e := 2, 5
	req := ScrobbleRequest{
		Action:    "start",
		TmdbID:    99999,
		MediaType: "tv",
		Season:    &s,
		Episode:   &e,
		Progress:  1.5,
	}
	if err := c.scrobble(req.Action, req); err != nil {
		t.Fatalf("scrobble: %v", err)
	}

	show, ok := got["show"].(map[string]any)
	if !ok {
		t.Fatalf("response missing show key, got %v", got)
	}
	ids, _ := show["ids"].(map[string]any)
	if tmdbID, _ := ids["tmdb"].(float64); int(tmdbID) != 99999 {
		t.Errorf("show.ids.tmdb = %v, want 99999", ids["tmdb"])
	}

	episode, ok := got["episode"].(map[string]any)
	if !ok {
		t.Fatalf("response missing episode key, got %v", got)
	}
	if season, _ := episode["season"].(float64); int(season) != 2 {
		t.Errorf("episode.season = %v, want 2", episode["season"])
	}
	if number, _ := episode["number"].(float64); int(number) != 5 {
		t.Errorf("episode.number = %v, want 5", episode["number"])
	}
	if progress, _ := got["progress"].(float64); progress != 1.5 {
		t.Errorf("progress = %v, want 1.5", got["progress"])
	}
	if _, hasMovie := got["movie"]; hasMovie {
		t.Error("episode scrobble must not include movie key")
	}
}

// ── Pagination header ─────────────────────────────────────────────────────────

// TestGetWithHeadersPagination verifies that getWithHeaders exposes the
// X-Pagination-Page-Count header correctly.
func TestGetWithHeadersPagination(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Pagination-Page-Count", "7")
		json.NewEncoder(w).Encode([]string{"a", "b"})
	}))
	defer srv.Close()

	c := testClient(srv.URL, testStore(t))
	var out []string
	hdrs, err := c.getWithHeaders("/test", &out)
	if err != nil {
		t.Fatalf("getWithHeaders: %v", err)
	}
	pageCount, _ := strconv.Atoi(hdrs.Get("X-Pagination-Page-Count"))
	if pageCount != 7 {
		t.Errorf("X-Pagination-Page-Count = %d, want 7", pageCount)
	}
	if len(out) != 2 {
		t.Errorf("body len = %d, want 2", len(out))
	}
}
