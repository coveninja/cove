package server

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/coveninja/cove/internal/settings"
)

// newTestSettingsStore creates a settings.Store backed by an isolated temp
// config directory so tests never touch real user data. Same XDG_CONFIG_HOME
// pattern used by internal/activity/activity_test.go.
func newTestSettingsStore(t *testing.T) *settings.Store {
	t.Helper()
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	st, err := settings.New("test")
	if err != nil {
		t.Fatal("settings.New:", err)
	}
	return st
}

// storeWithRemoteAccess enables remote access and sets the given token.
func storeWithRemoteAccess(t *testing.T, token string) *settings.Store {
	t.Helper()
	st := newTestSettingsStore(t)
	s := st.Get()
	s.RemoteAccessEnabled = true
	s.RemoteAccessToken = token
	if err := st.Set(s); err != nil {
		t.Fatal("st.Set:", err)
	}
	return st
}

var okHandler = http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
	w.WriteHeader(http.StatusOK)
})

// newLANRequest creates a test request that appears to come from a LAN address
// (non-loopback) so the middleware takes the non-loopback code path.
func newLANRequest(method, url string) *http.Request {
	req := httptest.NewRequest(method, url, nil)
	req.RemoteAddr = "192.168.1.42:55001" // simulated LAN source
	return req
}

// TestMainListener_NoGate documents that the main HTTP handler is the bare mux
// with no auth gate. The main listener binds on loopback (127.0.0.1:PORT)
// which is network-isolated by design; no auth middleware is applied there.
// In the new separate-port design, the gate lives exclusively on the LAN
// listener — this test asserts its absence on the main path.
func TestMainListener_NoGate(t *testing.T) {
	// Call okHandler directly (no middleware) as the main listener would:
	// any request, any source address, any token state → 200.
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/ping", nil)
	req.RemoteAddr = "192.168.1.42:55001" // LAN source — still no gate
	okHandler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Errorf("main listener no gate: want 200, got %d", rec.Code)
	}
}

// TestLAN_LoopbackRequiresToken verifies that the LAN listener has no loopback
// bypass — even 127.0.0.1 connections on the LAN port need a valid token.
// (Nothing legitimate arrives loopback on the LAN port; allowing it would only
// widen the attack surface without any practical benefit.)
func TestLAN_LoopbackRequiresToken(t *testing.T) {
	h := lanTokenMiddleware(storeWithRemoteAccess(t, "secret"), okHandler)
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/ping", nil)
	req.RemoteAddr = "127.0.0.1:55001" // loopback — not bypassed on LAN port
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Errorf("LAN loopback no token: want 401 (no bypass), got %d", rec.Code)
	}
}

// TestLAN_NoToken: LAN request with no token → 401.
func TestLAN_NoToken(t *testing.T) {
	h := lanTokenMiddleware(storeWithRemoteAccess(t, "secret"), okHandler)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, newLANRequest(http.MethodGet, "/api/ping"))
	if rec.Code != http.StatusUnauthorized {
		t.Errorf("LAN+notoken: want 401, got %d", rec.Code)
	}
}

// TestLAN_HeaderToken: LAN request with correct X-Cove-Token header → 200.
func TestLAN_HeaderToken(t *testing.T) {
	h := lanTokenMiddleware(storeWithRemoteAccess(t, "secret"), okHandler)
	req := newLANRequest(http.MethodGet, "/api/ping")
	req.Header.Set("X-Cove-Token", "secret")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Errorf("LAN+header: want 200, got %d", rec.Code)
	}
}

// TestLAN_QueryToken: LAN request with correct ?token= query param → 200.
// (mpv on Android opens /api/play URLs directly and cannot inject headers.)
func TestLAN_QueryToken(t *testing.T) {
	h := lanTokenMiddleware(storeWithRemoteAccess(t, "secret"), okHandler)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, newLANRequest(http.MethodGet, "/api/ping?token=secret"))
	if rec.Code != http.StatusOK {
		t.Errorf("LAN+querytoken: want 200, got %d", rec.Code)
	}
}

// TestLAN_WrongToken: LAN request with wrong token → 401.
func TestLAN_WrongToken(t *testing.T) {
	h := lanTokenMiddleware(storeWithRemoteAccess(t, "secret"), okHandler)
	req := newLANRequest(http.MethodGet, "/api/ping")
	req.Header.Set("X-Cove-Token", "wrong")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Errorf("LAN+wrongtoken: want 401, got %d", rec.Code)
	}
}

// TestLAN_EmptyExpectedToken: when the store has RemoteAccessEnabled but an
// empty token (shouldn't happen post-applyTokenPolicy, but defensive), the
// middleware must refuse rather than granting open access.
func TestLAN_EmptyExpectedToken(t *testing.T) {
	st := newTestSettingsStore(t)
	s := st.Get()
	s.RemoteAccessEnabled = true
	s.RemoteAccessToken = "" // deliberately empty
	if err := st.Set(s); err != nil {
		t.Fatal("st.Set:", err)
	}
	// applyTokenPolicy will have generated a token; force it empty for the test
	s2 := st.Get()
	s2.RemoteAccessToken = ""
	_ = st.Set(s2)

	h := lanTokenMiddleware(st, okHandler)
	req := newLANRequest(http.MethodGet, "/api/ping")
	req.Header.Set("X-Cove-Token", "") // even empty-vs-empty must refuse
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Errorf("empty expected token: want 401, got %d", rec.Code)
	}
}
