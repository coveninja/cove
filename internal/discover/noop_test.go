//go:build !discover

package discover

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"

	"github.com/coveninja/cove/internal/settings"
	"github.com/coveninja/cove/internal/tmdb"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type fakeTMDBDiscoverer struct {
	result      *tmdb.DiscoverResult
	discoverErr error
	genres      []tmdb.Keyword
	genreErr    error
	params      []tmdb.DiscoverParams
}

func (f *fakeTMDBDiscoverer) Discover(p tmdb.DiscoverParams) (*tmdb.DiscoverResult, error) {
	f.params = append(f.params, p)
	return f.result, f.discoverErr
}

func (f *fakeTMDBDiscoverer) GenreList(string) ([]tmdb.Keyword, error) {
	return f.genres, f.genreErr
}

func customSettings(t *testing.T, algorithmURL string) *settings.Store {
	t.Helper()
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	store, err := settings.New("discover-noop")
	require.NoError(t, err)
	cfg := store.Get()
	cfg.DiscoveryAlgorithm = "custom"
	cfg.CustomAlgorithmURL = algorithmURL
	require.NoError(t, store.Set(cfg))
	return store
}

func TestRecommendWithoutCustomAlgorithmIsEmpty(t *testing.T) {
	fake := &fakeTMDBDiscoverer{}
	service := &Service{tmdb: fake}
	recorder := httptest.NewRecorder()
	service.handleRecommend(recorder, httptest.NewRequest(http.MethodGet, "/api/discover?type=invalid&limit=4", nil))

	assert.Equal(t, http.StatusOK, recorder.Code)
	assert.JSONEq(t, `[]`, recorder.Body.String())
	assert.Empty(t, fake.params)
}

func TestRecommendUsesCustomScoresFiltersAndLimits(t *testing.T) {
	algorithm := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, "application/json", r.Header.Get("Content-Type"))
		var request ossAlgorithmRequest
		require.NoError(t, json.NewDecoder(r.Body).Decode(&request))
		assert.Equal(t, "tv", request.MediaType)
		require.Len(t, request.Candidates, 2)
		assert.NotNil(t, request.Profile.TopGenres)
		assert.NotNil(t, request.Profile.DislikedGenres)
		assert.NotNil(t, request.Profile.TopKeywords)
		assert.NotNil(t, request.Profile.TopPeople)
		json.NewEncoder(w).Encode(ossAlgorithmResponse{Scores: map[string]float64{"1": 2, "2": 9}})
	}))
	defer algorithm.Close()

	fake := &fakeTMDBDiscoverer{result: &tmdb.DiscoverResult{Results: []tmdb.Media{
		{ID: 1, Name: "First", PosterURL: "poster-1"},
		{ID: 3, Name: "No poster"},
		{ID: 2, Name: "Second", PosterURL: "poster-2"},
	}}}
	service := &Service{tmdb: fake, settings: customSettings(t, algorithm.URL)}
	recorder := httptest.NewRecorder()
	service.handleRecommend(recorder, httptest.NewRequest(http.MethodGet, "/api/discover?type=tv&limit=1", nil))

	require.Len(t, fake.params, 1)
	assert.Equal(t, "tv", fake.params[0].MediaType)
	assert.Equal(t, "popularity.desc", fake.params[0].SortBy)
	assert.Equal(t, float64(50), fake.params[0].MinVoteCount)
	var got []tmdb.Media
	require.NoError(t, json.NewDecoder(recorder.Body).Decode(&got))
	require.Len(t, got, 1)
	assert.Equal(t, 2, got[0].ID)
}

func TestRecommendFallsBackToTMDBOrderWhenAlgorithmFails(t *testing.T) {
	algorithm := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "down", http.StatusServiceUnavailable)
	}))
	defer algorithm.Close()
	fake := &fakeTMDBDiscoverer{result: &tmdb.DiscoverResult{Results: []tmdb.Media{
		{ID: 7, PosterURL: "poster-7"}, {ID: 8, PosterURL: "poster-8"},
	}}}
	service := &Service{tmdb: fake, settings: customSettings(t, algorithm.URL)}
	recorder := httptest.NewRecorder()
	service.handleRecommend(recorder, httptest.NewRequest(http.MethodGet, "/api/discover?limit=bogus", nil))

	var got []tmdb.Media
	require.NoError(t, json.NewDecoder(recorder.Body).Decode(&got))
	require.Len(t, got, 2)
	assert.Equal(t, 7, got[0].ID)
	assert.Equal(t, "movie", fake.params[0].MediaType)
}

func TestRecommendReturnsEmptyWhenTMDBFails(t *testing.T) {
	service := &Service{
		tmdb:     &fakeTMDBDiscoverer{discoverErr: errors.New("tmdb unavailable")},
		settings: customSettings(t, "http://algorithm.invalid"),
	}
	recorder := httptest.NewRecorder()
	service.handleRecommend(recorder, httptest.NewRequest(http.MethodGet, "/api/discover", nil))
	assert.JSONEq(t, `[]`, recorder.Body.String())
}

func TestGenreListValidationSuccessAndFailure(t *testing.T) {
	t.Run("invalid media type", func(t *testing.T) {
		recorder := httptest.NewRecorder()
		(&Service{}).handleGenreList(recorder, httptest.NewRequest(http.MethodGet, "/api/genres?type=book", nil))
		assert.Equal(t, http.StatusBadRequest, recorder.Code)
	})

	t.Run("success", func(t *testing.T) {
		service := &Service{tmdb: &fakeTMDBDiscoverer{genres: []tmdb.Keyword{{ID: 28, Name: "Action"}}}}
		recorder := httptest.NewRecorder()
		service.handleGenreList(recorder, httptest.NewRequest(http.MethodGet, "/api/genres?type=movie", nil))
		assert.Equal(t, http.StatusOK, recorder.Code)
		assert.JSONEq(t, `[{"id":28,"name":"Action"}]`, recorder.Body.String())
	})

	t.Run("provider error", func(t *testing.T) {
		service := &Service{tmdb: &fakeTMDBDiscoverer{genreErr: errors.New("no genres")}}
		recorder := httptest.NewRecorder()
		service.handleGenreList(recorder, httptest.NewRequest(http.MethodGet, "/api/genres?type=tv", nil))
		assert.Equal(t, http.StatusInternalServerError, recorder.Code)
	})
}

func TestFetchCustomScoresOSSParsesValidIDs(t *testing.T) {
	algorithm := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(ossAlgorithmResponse{Scores: map[string]float64{"12": 4.5, "bad": 99}})
	}))
	defer algorithm.Close()

	scores, err := fetchCustomScoresOSS(algorithm.URL, "movie", []tmdb.Media{{ID: 12}})
	require.NoError(t, err)
	assert.Equal(t, map[int]float64{12: 4.5}, scores)
	_, err = fetchCustomScoresOSS("://invalid", "movie", nil)
	assert.ErrorContains(t, err, "build request")
}

func TestAlgorithmTestHandlerValidationAndResult(t *testing.T) {
	service := &Service{}

	recorder := httptest.NewRecorder()
	service.handleTestAlgorithm(recorder, httptest.NewRequest(http.MethodGet, "/api/discover/algorithm/test", nil))
	assert.Equal(t, http.StatusMethodNotAllowed, recorder.Code)

	recorder = httptest.NewRecorder()
	service.handleTestAlgorithm(recorder, httptest.NewRequest(http.MethodPost, "/api/discover/algorithm/test", nil))
	assert.Equal(t, http.StatusBadRequest, recorder.Code)

	algorithm := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(ossAlgorithmResponse{Scores: map[string]float64{"1": 1}})
	}))
	defer algorithm.Close()
	recorder = httptest.NewRecorder()
	body := `{"url":` + strconv.Quote(algorithm.URL) + `}`
	service.handleTestAlgorithm(recorder, httptest.NewRequest(http.MethodPost, "/api/discover/algorithm/test", strings.NewReader(body)))
	assert.JSONEq(t, `{"ok":true}`, recorder.Body.String())
}

func TestSetupHandlersServesOSSPlaceholders(t *testing.T) {
	mux := http.NewServeMux()
	(&Service{}).SetupHandlers(mux)

	for _, path := range []string{"/api/discover/genres", "/api/discover/keywords", "/api/discover/genre", "/api/discover/keyword"} {
		recorder := httptest.NewRecorder()
		mux.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, path, nil))
		assert.Equal(t, "application/json", recorder.Header().Get("Content-Type"))
		assert.JSONEq(t, `[]`, recorder.Body.String())
	}
	recorder := httptest.NewRecorder()
	mux.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "/api/discover/insights", nil))
	assert.JSONEq(t, `{}`, recorder.Body.String())
}
