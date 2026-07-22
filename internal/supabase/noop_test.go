//go:build !supabase

package supabase

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestNoopRoutesMatchTaggedAPI(t *testing.T) {
	mux := http.NewServeMux()
	(&Server{}).SetupHandlers(mux)

	for _, path := range []string{
		"/api/auth/register",
		"/api/auth/register/confirm",
		"/api/auth/login",
		"/api/auth/otp",
		"/api/auth/verify-otp",
		"/api/auth/logout",
		"/api/auth/me",
		"/api/auth/sync",
	} {
		t.Run(path, func(t *testing.T) {
			recorder := httptest.NewRecorder()
			mux.ServeHTTP(recorder, httptest.NewRequest(http.MethodPost, path, nil))
			assert.Equal(t, http.StatusServiceUnavailable, recorder.Code)
		})
	}
}
