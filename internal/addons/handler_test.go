package addons

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	neturl "net/url"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestSetupHandlersAddonLifecycleAndValidation(t *testing.T) {
	manifestServer := httptest.NewServer(manifestHandler("handler.addon", "Handler Addon"))
	defer manifestServer.Close()

	manager := newTestManager(nil)
	mux := http.NewServeMux()
	manager.SetupHandlers(mux)

	get := httptest.NewRecorder()
	mux.ServeHTTP(get, httptest.NewRequest(http.MethodGet, "/api/addons", nil))
	require.Equal(t, http.StatusOK, get.Code)
	assert.Contains(t, get.Header().Get("Content-Type"), "application/json")
	var initial []AddonEntry
	require.NoError(t, json.NewDecoder(get.Body).Decode(&initial))
	require.Len(t, initial, len(officialAddons))

	for _, body := range []string{"{", `{}`, `{"url":""}`, strings.Repeat("x", 65<<10)} {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodPost, "/api/addons", strings.NewReader(body))
		mux.ServeHTTP(rec, req)
		assert.Equal(t, http.StatusBadRequest, rec.Code)
	}

	add := httptest.NewRecorder()
	addBody := fmt.Sprintf(`{"url":%q}`, "  "+manifestServer.URL+"/manifest.json  ")
	mux.ServeHTTP(add, httptest.NewRequest(http.MethodPost, "/api/addons", strings.NewReader(addBody)))
	require.Equal(t, http.StatusOK, add.Code, add.Body.String())
	var added AddonEntry
	require.NoError(t, json.NewDecoder(add.Body).Decode(&added))
	assert.Equal(t, "handler.addon", added.ID)
	assert.Equal(t, manifestServer.URL, added.URL)

	for _, request := range []struct {
		path string
		body string
		code int
	}{
		{"/api/addons", `{"enabled":false}`, http.StatusBadRequest},
		{"/api/addons?id=handler.addon", `{`, http.StatusBadRequest},
		{"/api/addons?id=handler.addon", `{}`, http.StatusBadRequest},
		{"/api/addons?id=missing", `{"enabled":false}`, http.StatusNotFound},
	} {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodPatch, request.path, strings.NewReader(request.body))
		mux.ServeHTTP(rec, req)
		assert.Equal(t, request.code, rec.Code)
	}

	toggle := httptest.NewRecorder()
	togglePath := "/api/addons?url=" + neturl.QueryEscape(manifestServer.URL)
	mux.ServeHTTP(toggle, httptest.NewRequest(http.MethodPatch, togglePath, strings.NewReader(`{"enabled":false}`)))
	require.Equal(t, http.StatusNoContent, toggle.Code)
	assert.False(t, entryByID(manager.GetEntries(), "handler.addon").Enabled)

	missingDelete := httptest.NewRecorder()
	mux.ServeHTTP(missingDelete, httptest.NewRequest(http.MethodDelete, "/api/addons", nil))
	assert.Equal(t, http.StatusBadRequest, missingDelete.Code)

	officialDelete := httptest.NewRecorder()
	mux.ServeHTTP(officialDelete, httptest.NewRequest(http.MethodDelete, "/api/addons?id=cove.justwatch", nil))
	assert.Equal(t, http.StatusBadRequest, officialDelete.Code)

	notFoundDelete := httptest.NewRecorder()
	mux.ServeHTTP(notFoundDelete, httptest.NewRequest(http.MethodDelete, "/api/addons?id=missing", nil))
	assert.Equal(t, http.StatusBadRequest, notFoundDelete.Code)

	remove := httptest.NewRecorder()
	mux.ServeHTTP(remove, httptest.NewRequest(http.MethodDelete, togglePath, nil))
	require.Equal(t, http.StatusNoContent, remove.Code)
	assert.False(t, hasEntry(manager.GetEntries(), "handler.addon"))

	wrongMethod := httptest.NewRecorder()
	mux.ServeHTTP(wrongMethod, httptest.NewRequest(http.MethodPut, "/api/addons", nil))
	assert.Equal(t, http.StatusMethodNotAllowed, wrongMethod.Code)
}

func TestSetupHandlersAddonPostReportsManifestFailure(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "down", http.StatusBadGateway)
	}))
	defer srv.Close()

	manager := newTestManager(nil)
	mux := http.NewServeMux()
	manager.SetupHandlers(mux)

	rec := httptest.NewRecorder()
	body := fmt.Sprintf(`{"url":%q}`, srv.URL)
	mux.ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/api/addons", strings.NewReader(body)))
	assert.Equal(t, http.StatusBadRequest, rec.Code)
	assert.False(t, hasEntry(manager.GetEntries(), "handler.addon"))
}

func TestSetupHandlersTimestampsValidationAndHappyPath(t *testing.T) {
	manager := newTestManager(nil)
	manager.officialEnabled["cove.introdb"] = false
	mux := http.NewServeMux()
	manager.SetupHandlers(mux)

	for _, path := range []string{
		"/api/timestamps",
		"/api/timestamps?id=bad",
		"/api/timestamps?id=1junk",
		"/api/timestamps?id=0",
		"/api/timestamps?id=1&season=bad&episode=1",
		"/api/timestamps?id=1&season=-1&episode=1",
		"/api/timestamps?id=1&season=1&episode=bad",
		"/api/timestamps?id=1&season=1&episode=-1",
	} {
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
		assert.Equal(t, http.StatusBadRequest, rec.Code, "path: %s", path)
	}

	wrongMethod := httptest.NewRecorder()
	mux.ServeHTTP(wrongMethod, httptest.NewRequest(http.MethodPost, "/api/timestamps?id=1", nil))
	assert.Equal(t, http.StatusMethodNotAllowed, wrongMethod.Code)

	for _, path := range []string{
		"/api/timestamps?id=1",
		"/api/timestamps?id=1&season=2&episode=3",
		"/api/timestamps?id=1&season=0&episode=0",
		"/api/timestamps?id=1&season=1",
		"/api/timestamps?id=1&episode=1",
	} {
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
		require.Equal(t, http.StatusOK, rec.Code)
		var timestamps TimestampData
		require.NoError(t, json.NewDecoder(rec.Body).Decode(&timestamps))
		assert.Empty(t, timestamps.Intro)
	}
}

func TestSetupHandlersCatalogsAndCatalogToggle(t *testing.T) {
	manager := newTestManager([]AddonEntry{catalogAddon(
		"catalog.addon",
		"https://catalog.example",
		[]ManifestCatalog{{Type: "movie", ID: "top", Name: "Top"}},
	)})
	mux := http.NewServeMux()
	manager.SetupHandlers(mux)

	list := httptest.NewRecorder()
	mux.ServeHTTP(list, httptest.NewRequest(http.MethodGet, "/api/catalogs", nil))
	require.Equal(t, http.StatusOK, list.Code)
	var refs []CatalogRef
	require.NoError(t, json.NewDecoder(list.Body).Decode(&refs))
	require.Len(t, refs, 1)

	wrongListMethod := httptest.NewRecorder()
	mux.ServeHTTP(wrongListMethod, httptest.NewRequest(http.MethodPost, "/api/catalogs", nil))
	assert.Equal(t, http.StatusMethodNotAllowed, wrongListMethod.Code)

	for _, request := range []struct {
		method string
		path   string
		body   string
		code   int
	}{
		{http.MethodGet, "/api/addons/catalog?id=catalog.addon&catalog=movie/top", "", http.StatusMethodNotAllowed},
		{http.MethodPatch, "/api/addons/catalog", `{"enabled":false}`, http.StatusBadRequest},
		{http.MethodPatch, "/api/addons/catalog?id=catalog.addon", `{"enabled":false}`, http.StatusBadRequest},
		{http.MethodPatch, "/api/addons/catalog?id=catalog.addon&catalog=movie/top", `{`, http.StatusBadRequest},
		{http.MethodPatch, "/api/addons/catalog?id=catalog.addon&catalog=movie/top", `{}`, http.StatusBadRequest},
		{http.MethodPatch, "/api/addons/catalog?id=missing&catalog=movie/top", `{"enabled":false}`, http.StatusNotFound},
	} {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(request.method, request.path, strings.NewReader(request.body))
		mux.ServeHTTP(rec, req)
		assert.Equal(t, request.code, rec.Code)
	}

	disable := httptest.NewRecorder()
	path := "/api/addons/catalog?url=" + neturl.QueryEscape("https://catalog.example") + "&catalog=movie/top"
	mux.ServeHTTP(disable, httptest.NewRequest(http.MethodPatch, path, strings.NewReader(`{"enabled":false}`)))
	require.Equal(t, http.StatusNoContent, disable.Code)
	assert.Empty(t, manager.GetEnabledCatalogs())

	enable := httptest.NewRecorder()
	mux.ServeHTTP(enable, httptest.NewRequest(http.MethodPatch, path, strings.NewReader(`{"enabled":true}`)))
	require.Equal(t, http.StatusNoContent, enable.Code)
	require.Len(t, manager.GetEnabledCatalogs(), 1)

	emptyManager := newTestManager(nil)
	emptyMux := http.NewServeMux()
	emptyManager.SetupHandlers(emptyMux)
	emptyResult := httptest.NewRecorder()
	emptyMux.ServeHTTP(emptyResult, httptest.NewRequest(http.MethodGet, "/api/catalogs", nil))
	assert.Equal(t, "[]\n", emptyResult.Body.String())
}

func TestSetupHandlersWatchOptionsValidationAndDisabledResult(t *testing.T) {
	manager := newTestManager(nil)
	manager.officialEnabled["cove.justwatch"] = false
	mux := http.NewServeMux()
	manager.SetupHandlers(mux)

	for _, path := range []string{
		"/api/watch-options",
		"/api/watch-options?id=bad&type=movie",
		"/api/watch-options?id=0&type=movie",
		"/api/watch-options?id=1&type=person",
	} {
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
		assert.Equal(t, http.StatusBadRequest, rec.Code, "path: %s", path)
	}

	wrongMethod := httptest.NewRecorder()
	mux.ServeHTTP(wrongMethod, httptest.NewRequest(http.MethodPost, "/api/watch-options?id=1&type=movie", nil))
	assert.Equal(t, http.StatusMethodNotAllowed, wrongMethod.Code)

	success := httptest.NewRecorder()
	mux.ServeHTTP(success, httptest.NewRequest(http.MethodGet, "/api/watch-options?id=001&type=movie", nil))
	require.Equal(t, http.StatusOK, success.Code)
	assert.Equal(t, "[]\n", success.Body.String())
}
