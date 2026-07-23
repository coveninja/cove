package clientsession

import (
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"github.com/coveninja/cove/internal/utils"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestClientSessionLifecycle(t *testing.T) {
	dataDir := t.TempDir()
	utils.SetDataDir(dataDir)
	mux := http.NewServeMux()
	SetupHandlers(mux)

	request := func(method, body string) *httptest.ResponseRecorder {
		t.Helper()
		recorder := httptest.NewRecorder()
		mux.ServeHTTP(recorder,
			httptest.NewRequest(method, "/api/client-session", strings.NewReader(body)))
		return recorder
	}

	assert.Equal(t, http.StatusNotFound, request(http.MethodGet, "").Code)
	assert.Equal(t, http.StatusBadRequest, request(http.MethodPost, "not json").Code)

	payload := `{"access_token":"secret","profile_id":"profile-1"}`
	assert.Equal(t, http.StatusNoContent, request(http.MethodPost, payload).Code)

	path, err := filePath()
	require.NoError(t, err)
	stored, err := os.ReadFile(path)
	require.NoError(t, err)
	assert.JSONEq(t, payload, string(stored))
	if info, err := os.Stat(path); assert.NoError(t, err) {
		assert.Equal(t, os.FileMode(0o600), info.Mode().Perm())
	}

	got := request(http.MethodGet, "")
	assert.Equal(t, http.StatusOK, got.Code)
	assert.Equal(t, "application/json", got.Header().Get("Content-Type"))
	assert.JSONEq(t, payload, got.Body.String())

	assert.Equal(t, http.StatusNoContent, request(http.MethodDelete, "").Code)
	assert.Equal(t, http.StatusNotFound, request(http.MethodGet, "").Code)
}

func TestClientSessionRejectsOversizedAndUnsupportedRequests(t *testing.T) {
	utils.SetDataDir(t.TempDir())
	mux := http.NewServeMux()
	SetupHandlers(mux)

	oversized := httptest.NewRecorder()
	mux.ServeHTTP(oversized, httptest.NewRequest(
		http.MethodPost, "/api/client-session",
		strings.NewReader(`"`+strings.Repeat("x", (1<<20)+1)+`"`),
	))
	assert.Equal(t, http.StatusBadRequest, oversized.Code)

	unsupported := httptest.NewRecorder()
	mux.ServeHTTP(unsupported,
		httptest.NewRequest(http.MethodPut, "/api/client-session", nil))
	assert.Equal(t, http.StatusMethodNotAllowed, unsupported.Code)
	assert.Equal(t, "GET, POST, DELETE", unsupported.Header().Get("Allow"))
}
