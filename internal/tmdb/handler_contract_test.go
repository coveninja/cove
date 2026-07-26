package tmdb

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	neturl "net/url"
	"os"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/coveninja/cove/internal/addons"
	"github.com/coveninja/cove/internal/utils"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestMetadataMethodsRejectJSONErrorResponses(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusTooManyRequests)
		fmt.Fprint(w, `{"success":false,"status_message":"rate limited"}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	tests := []struct {
		name string
		call func(*Client) error
	}{
		{"details", func(c *Client) error { _, err := c.GetDetails(1, "movie"); return err }},
		{"similar", func(c *Client) error { _, err := c.GetSimilar(1, "movie"); return err }},
		{"logos", func(c *Client) error { _, err := c.GetLogos(1, "movie"); return err }},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := tt.call(New("key"))
			require.Error(t, err)
			assert.Contains(t, err.Error(), "429")
		})
	}
}

func TestGenreListHappyPathAndInputValidation(t *testing.T) {
	var hits atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		assert.Equal(t, "/genre/movie/list", r.URL.Path)
		assert.Equal(t, "key", r.URL.Query().Get("api_key"))
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"genres":[{"id":28,"name":"Action"},{"id":12,"name":"Adventure"}]}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	genres, err := New("key").GenreList("movie")
	require.NoError(t, err)
	require.Len(t, genres, 2)
	assert.Equal(t, "Action", genres[0].Name)

	_, err = New("key").GenreList("person")
	require.Error(t, err)
	assert.Equal(t, int32(1), hits.Load(), "invalid media types must fail before an upstream request")
}

func TestSetupHandlersRejectNonGETMethods(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	var upstreamHits atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		upstreamHits.Add(1)
		http.Error(w, "unexpected upstream call", http.StatusInternalServerError)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	mux := http.NewServeMux()
	New("key").SetupHandlers(mux, newTestAddonMgr())

	paths := []string{
		"/api/keywords?q=x",
		"/api/search?q=x",
		"/api/search/multi?q=x",
		"/api/person?id=1",
		"/api/provider?id=1",
		"/api/images?id=1&type=movie",
		"/api/videos?id=1&type=movie",
		"/api/media?id=1&type=movie",
		"/api/details?id=1&type=movie",
		"/api/similar?id=1&type=movie",
		"/api/logos?id=1&type=movie",
		"/api/imdb?id=1",
		"/api/tv/seasons?id=1",
		"/api/tv/episodes?id=1&season=1",
		"/api/quality/batch?ids=movie:1",
		"/api/catalog?addonId=x&catalogType=movie&catalogId=top",
	}
	for _, path := range paths {
		t.Run(path, func(t *testing.T) {
			rec := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodPost, path, nil)
			mux.ServeHTTP(rec, req)
			assert.Equal(t, http.StatusMethodNotAllowed, rec.Code)
			assert.Equal(t, http.MethodGet, rec.Header().Get("Allow"))
		})
	}
	assert.Zero(t, upstreamHits.Load())
}

func TestSetupHandlersUpstreamFailuresReturn500(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusBadGateway)
		fmt.Fprint(w, `{"success":false}`)
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	mux := http.NewServeMux()
	New("key").SetupHandlers(mux, newTestAddonMgr())

	paths := []string{
		"/api/keywords?q=x",
		"/api/person?id=1",
		"/api/images?id=1&type=movie",
		"/api/videos?id=1&type=movie",
		"/api/media?id=1&type=movie",
		"/api/details?id=1&type=movie",
		"/api/similar?id=1&type=movie",
		"/api/logos?id=1&type=movie",
		"/api/imdb?id=1",
		"/api/tv/seasons?id=1",
		"/api/tv/episodes?id=1&season=1",
	}
	for _, path := range paths {
		t.Run(path, func(t *testing.T) {
			rec := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodGet, path, nil)
			mux.ServeHTTP(rec, req)
			assert.Equal(t, http.StatusInternalServerError, rec.Code)
		})
	}
}

func TestSetupHandlersQualityBatchCacheAndNegativeResults(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	client := New("key")
	client.qualityCacheSet("movie:1", "4K", qualityCacheTTLHit)
	client.qualityCacheSet("tv:2", "", qualityCacheTTLEmpty)

	mux := http.NewServeMux()
	client.SetupHandlers(mux, newTestAddonMgr())

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/quality/batch?ids=movie:1,tv:2,foo:3,bad", nil)
	mux.ServeHTTP(rec, req)
	require.Equal(t, http.StatusOK, rec.Code)
	assert.Equal(t, "application/x-ndjson", rec.Header().Get("Content-Type"))
	assert.Equal(t, "no", rec.Header().Get("X-Accel-Buffering"))

	var entries []struct {
		ID      string `json:"id"`
		Quality string `json:"quality"`
	}
	dec := json.NewDecoder(rec.Body)
	for {
		var entry struct {
			ID      string `json:"id"`
			Quality string `json:"quality"`
		}
		err := dec.Decode(&entry)
		if err == io.EOF {
			break
		}
		require.NoError(t, err)
		entries = append(entries, entry)
	}
	require.Len(t, entries, 1)
	assert.Equal(t, "movie:1", entries[0].ID)
	assert.Equal(t, "4K", entries[0].Quality)

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	canceled := httptest.NewRequest(http.MethodGet, "/api/quality/batch?ids=movie:1", nil).WithContext(ctx)
	canceledResult := httptest.NewRecorder()
	mux.ServeHTTP(canceledResult, canceled)
	assert.Empty(t, canceledResult.Body.String(), "a canceled request must not emit cached results")
}

func TestSetupHandlersQualityBatchFreshEmptyMatchesCachedEmpty(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/movie/3":
			fmt.Fprint(w, `{"imdb_id":"tt0000003"}`)
		case "/tv/4/external_ids":
			fmt.Fprint(w, `{"imdb_id":"tt0000004"}`)
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	client := New("key")
	mux := http.NewServeMux()
	client.SetupHandlers(mux, newTestAddonMgr())

	for i := 0; i < 2; i++ {
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/api/quality/batch?ids=movie:3,tv:4", nil)
		mux.ServeHTTP(rec, req)
		require.Equal(t, http.StatusOK, rec.Code)
		assert.Empty(t, rec.Body.String(), "empty quality must be omitted on both fresh and cached requests")
	}

	for _, id := range []string{"movie:3", "tv:4"} {
		quality, hit := client.qualityCacheGet(id)
		assert.True(t, hit)
		assert.Empty(t, quality)
	}
}

func TestSetupHandlersCatalogValidationCacheAndFetchFailure(t *testing.T) {
	manager, addonURL := configuredCatalogAddonManager(t)
	client := New("key")
	mux := http.NewServeMux()
	client.SetupHandlers(mux, manager)

	for _, path := range []string{
		"/api/catalog?addonId=missing&catalogType=movie&catalogId=top",
		"/api/catalog?addonId=catalog.test&catalogType=movie&catalogId=top&addonUrl=" + neturl.QueryEscape("https://not-configured.example"),
	} {
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
		assert.Equal(t, http.StatusNotFound, rec.Code)
	}

	client.catalogCacheSet(client.Locale()+"|"+addonURL+"|movie|top|0|100", []Media{{ID: 7, Title: "Cached"}}, 100)
	cached := httptest.NewRecorder()
	cachedPath := "/api/catalog?addonId=catalog.test&catalogType=movie&catalogId=top&limit=999"
	mux.ServeHTTP(cached, httptest.NewRequest(http.MethodGet, cachedPath, nil))
	require.Equal(t, http.StatusOK, cached.Code)
	var payload struct {
		Medias   []Media `json:"medias"`
		NextSkip int     `json:"nextSkip"`
	}
	require.NoError(t, json.NewDecoder(cached.Body).Decode(&payload))
	require.Len(t, payload.Medias, 1)
	assert.Equal(t, "Cached", payload.Medias[0].Title)
	assert.Equal(t, 100, payload.NextSkip)

	failed := httptest.NewRecorder()
	failedPath := "/api/catalog?addonId=catalog.test&catalogType=movie&catalogId=uncached&skip=bad&limit=0"
	mux.ServeHTTP(failed, httptest.NewRequest(http.MethodGet, failedPath, nil))
	assert.Equal(t, http.StatusBadGateway, failed.Code)
}

func configuredCatalogAddonManager(t *testing.T) (*addons.Manager, string) {
	t.Helper()
	configHome := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", configHome)
	t.Setenv("AppData", configHome)
	t.Setenv("HOME", configHome)

	const profileID = "catalog-handler"
	const addonURL = "http://127.0.0.1:1"
	raw, err := json.Marshal(map[string]any{
		"stremioAddons": []addons.AddonEntry{{
			ID:       "catalog.test",
			URL:      addonURL,
			Manifest: addons.Manifest{ID: "catalog.test", Name: "Catalog Test"},
			Kind:     addons.KindProvider,
			Source:   addons.SourceStremio,
			Enabled:  true,
		}},
	})
	require.NoError(t, err)

	path, err := utils.ConfigPath("addons-" + profileID + ".json")
	require.NoError(t, err)
	require.NoError(t, os.WriteFile(path, raw, 0o600))
	return addons.New(profileID, nil), addonURL
}

func TestSetupHandlersAdditionalHappyPaths(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.URL.Path == "/movie/7":
			fmt.Fprint(w, `{"id":7,"title":"Movie","overview":"Details","runtime":90,"imdb_id":"tt0000007"}`)
		case strings.HasPrefix(r.URL.Path, "/discover/"):
			fmt.Fprint(w, `{"results":[{"id":7,"title":"Movie","poster_path":"/movie.jpg","popularity":10}]}`)
		case strings.HasPrefix(r.URL.Path, "/search/movie"):
			fmt.Fprint(w, `{"results":[{"id":7,"title":"Movie","poster_path":"/movie.jpg","popularity":10}]}`)
		case strings.HasPrefix(r.URL.Path, "/search/tv"):
			fmt.Fprint(w, `{"results":[]}`)
		case r.URL.Path == "/search/keyword":
			fmt.Fprint(w, `{"results":[]}`)
		case r.URL.Path == "/search/person":
			fmt.Fprint(w, `{"results":[]}`)
		case strings.HasPrefix(r.URL.Path, "/watch/providers/"):
			fmt.Fprint(w, `{"results":[]}`)
		default:
			http.NotFound(w, r)
		}
	}))
	defer srv.Close()
	withTestBaseURL(t, srv)

	mux := http.NewServeMux()
	New("key").SetupHandlers(mux, newTestAddonMgr())

	for _, path := range []string{
		"/api/details?id=7&type=movie",
		"/api/imdb?id=7",
		"/api/provider?id=8&limit=1",
		"/api/search/multi?q=movie",
	} {
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
		assert.Equal(t, http.StatusOK, rec.Code, "path: %s; body: %s", path, rec.Body.String())
		assert.True(t, json.Valid(rec.Body.Bytes()), "path: %s", path)
	}
}
