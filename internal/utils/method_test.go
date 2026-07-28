package utils

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestMethodGuard_PassesMatchingMethod(t *testing.T) {
	called := false
	h := MethodGuard(http.MethodGet, func(w http.ResponseWriter, r *http.Request) {
		called = true
		w.WriteHeader(http.StatusOK)
	})
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	rr := httptest.NewRecorder()
	h(rr, req)

	assert.True(t, called, "inner handler must run for matching method")
	assert.Equal(t, http.StatusOK, rr.Code)
}

func TestMethodGuard_Rejects405WithAllowHeader(t *testing.T) {
	tests := []struct {
		name    string
		allowed string
		method  string
	}{
		{"GET guard rejects POST", http.MethodGet, http.MethodPost},
		{"POST guard rejects GET", http.MethodPost, http.MethodGet},
		{"PUT guard rejects DELETE", http.MethodPut, http.MethodDelete},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			called := false
			h := MethodGuard(tc.allowed, func(w http.ResponseWriter, r *http.Request) {
				called = true
			})
			req := httptest.NewRequest(tc.method, "/", nil)
			rr := httptest.NewRecorder()
			h(rr, req)

			assert.False(t, called, "inner handler must not run for mismatched method")
			assert.Equal(t, http.StatusMethodNotAllowed, rr.Code)
			assert.Equal(t, tc.allowed, rr.Header().Get("Allow"))
		})
	}
}

// TestMethodGuard_InnerOfCors verifies the canonical composition:
// CorsMiddleware(MethodGuard(method, h)). The CORS layer must intercept OPTIONS
// before MethodGuard sees it, returning 204 — not a 405 — so browser
// preflights succeed.
func TestMethodGuard_InnerOfCors_OptionsGetsPreflight(t *testing.T) {
	h := CorsMiddleware(MethodGuard(http.MethodGet, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	req := httptest.NewRequest(http.MethodOptions, "/api/test", nil)
	req.Header.Set("Origin", allowedOrigin)
	rr := httptest.NewRecorder()
	h(rr, req)

	// CorsMiddleware must have answered the preflight; MethodGuard must not
	// have 405'd it.
	assert.Equal(t, http.StatusNoContent, rr.Code)
}

// TestMethodGuard_InnerOfCors_GetPassesThrough confirms a real GET flows all
// the way to the inner handler when both CorsMiddleware and MethodGuard are in
// the chain.
func TestMethodGuard_InnerOfCors_GetPassesThrough(t *testing.T) {
	called := false
	h := CorsMiddleware(MethodGuard(http.MethodGet, func(w http.ResponseWriter, r *http.Request) {
		called = true
		w.WriteHeader(http.StatusTeapot)
	}))

	req := httptest.NewRequest(http.MethodGet, "/api/test", nil)
	req.Header.Set("Origin", allowedOrigin)
	rr := httptest.NewRecorder()
	h(rr, req)

	assert.True(t, called)
	assert.Equal(t, http.StatusTeapot, rr.Code)
}

// TestMethodGuard_InnerOfCors_PostIsRejected confirms that a POST to a
// GET-only handler gets 405 (not 403) because CorsMiddleware allows POSTs
// from known origins but MethodGuard rejects them before the inner handler.
func TestMethodGuard_InnerOfCors_PostIsRejected(t *testing.T) {
	called := false
	h := CorsMiddleware(MethodGuard(http.MethodGet, func(w http.ResponseWriter, r *http.Request) {
		called = true
	}))

	req := httptest.NewRequest(http.MethodPost, "/api/test", nil)
	req.Header.Set("Origin", allowedOrigin)
	rr := httptest.NewRecorder()
	h(rr, req)

	assert.False(t, called)
	assert.Equal(t, http.StatusMethodNotAllowed, rr.Code)
	assert.Equal(t, http.MethodGet, rr.Header().Get("Allow"))
}
