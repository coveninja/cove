package nuvio

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/coveninja/cove/internal/addons"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type nuvioRoundTripFunc func(*http.Request) (*http.Response, error)

func (f nuvioRoundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) {
	return f(r)
}

func nuvioResponse(status int, body string) *http.Response {
	return &http.Response{
		StatusCode: status,
		Body:       io.NopCloser(strings.NewReader(body)),
		Header:     make(http.Header),
	}
}

func scraperManifest(version, filename string) string {
	return `{"scrapers":[{
		"id":"scraper-1",
		"name":"Scraper One",
		"description":"Test scraper",
		"version":"` + version + `",
		"filename":"` + filename + `",
		"supportedTypes":["movie"],
		"contentLanguage":["en"]
	}]}`
}

func TestResolveBranchAndManifestFallsBackToMaster(t *testing.T) {
	manager := testManager(t)
	var paths []string
	manager.client = &http.Client{Transport: nuvioRoundTripFunc(func(r *http.Request) (*http.Response, error) {
		paths = append(paths, r.URL.Path)
		switch r.URL.Path {
		case "/owner/repo/main/manifest.json":
			return nuvioResponse(http.StatusNotFound, "missing"), nil
		case "/owner/repo/master/manifest.json":
			return nuvioResponse(http.StatusOK, scraperManifest("1.0.0", "scraper.js")), nil
		default:
			return nuvioResponse(http.StatusNotFound, "unexpected"), nil
		}
	})}

	branch, data, err := manager.resolveBranchAndManifest(context.Background(), "owner", "repo", "")
	require.NoError(t, err)
	assert.Equal(t, "master", branch)
	assert.JSONEq(t, scraperManifest("1.0.0", "scraper.js"), string(data))
	assert.Equal(t, []string{
		"/owner/repo/main/manifest.json",
		"/owner/repo/master/manifest.json",
	}, paths)

	_, _, err = manager.resolveBranchAndManifest(context.Background(), "owner", "repo", "dev")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "HTTP 404")
	assert.Equal(t, "/owner/repo/dev/manifest.json", paths[len(paths)-1])
}

func TestAddRepoFetchesPersistsAndReplacesManifest(t *testing.T) {
	manager := testManager(t)
	version := "1.0.0"
	manager.client = &http.Client{Transport: nuvioRoundTripFunc(func(r *http.Request) (*http.Response, error) {
		assert.Equal(t, "/owner/repo/main/manifest.json", r.URL.Path)
		return nuvioResponse(http.StatusOK, scraperManifest(version, "scraper.js")), nil
	})}
	manager.streamCacheSet("movie|1|-|-", []addons.Stream{{Name: "stale"}})

	repo, err := manager.AddRepo(context.Background(), "https://github.com/owner/repo")
	require.NoError(t, err)
	assert.Equal(t, "owner/repo", repo.ID)
	assert.Equal(t, "main", repo.Branch)
	assert.False(t, repo.Enabled)
	require.Len(t, repo.Scrapers, 1)
	assert.False(t, repo.Scrapers[0].Enabled)
	assert.Empty(t, repo.Scrapers[0].Code)
	_, cached := manager.streamCacheGet("movie|1|-|-")
	assert.False(t, cached)

	reloaded := New("manager-test")
	require.Len(t, reloaded.GetRepos(), 1)
	assert.Equal(t, "1.0.0", reloaded.GetRepos()[0].Scrapers[0].Version)

	version = "2.0.0"
	replaced, err := manager.AddRepo(context.Background(), "github.com/owner/repo/tree/main")
	require.NoError(t, err)
	assert.Equal(t, "2.0.0", replaced.Scrapers[0].Version)
	assert.Len(t, manager.GetRepos(), 1)
}

func TestAddRepoSupportsCustomRawManifestPathAcrossRefresh(t *testing.T) {
	manager := testManager(t)
	const rawURL = "https://raw.githubusercontent.com/owner/repo/refs/heads/dev/providers/custom.json"
	var mu sync.Mutex
	manifestVersion := "1.0.0"
	var requestedPaths []string
	manager.client = &http.Client{Transport: nuvioRoundTripFunc(func(r *http.Request) (*http.Response, error) {
		mu.Lock()
		requestedPaths = append(requestedPaths, r.URL.Path)
		mu.Unlock()
		switch r.URL.Path {
		case "/owner/repo/dev/providers/custom.json":
			return nuvioResponse(http.StatusOK, scraperManifest(manifestVersion, "src/scraper.js")), nil
		case "/owner/repo/dev/src/scraper.js":
			return nuvioResponse(http.StatusOK, `module.exports = {getStreams: () => []};`), nil
		default:
			return nuvioResponse(http.StatusNotFound, "not found"), nil
		}
	})}

	repo, err := manager.AddRepo(context.Background(), rawURL)
	require.NoError(t, err)
	require.NoError(t, manager.SetRepoEnabled(repo.ID, true))
	require.NoError(t, manager.SetScraperEnabled(context.Background(), repo.ID, "scraper-1", true))
	assert.True(t, manager.HasEnabledScrapers())

	manifestVersion = "2.0.0"
	require.NoError(t, manager.RefreshRepo(context.Background(), repo.ID))
	refreshed := manager.GetRepos()[0]
	assert.Equal(t, "2.0.0", refreshed.Scrapers[0].Version)
	assert.True(t, refreshed.Scrapers[0].Enabled)
	assert.NotEmpty(t, refreshed.Scrapers[0].Code)

	mu.Lock()
	defer mu.Unlock()
	assert.NotContains(t, requestedPaths, "/owner/repo/dev/manifest.json")
	assert.GreaterOrEqual(t, strings.Count(strings.Join(requestedPaths, "\n"), "/owner/repo/dev/providers/custom.json"), 2)
}

func TestSetScraperEnabledPersistsFetchFailureAndInvalidatesCache(t *testing.T) {
	manager := testManager(t)
	manager.repos = []Repo{{
		ID: "owner/repo", Owner: "owner", Name: "repo", Branch: "main", Enabled: true,
		Scrapers: []Scraper{{ID: "broken", Name: "Broken", Filename: "broken.js"}},
	}}
	manager.mu.Lock()
	require.NoError(t, manager.saveL())
	manager.mu.Unlock()
	manager.streamCacheSet("movie|1|-|-", []addons.Stream{{Name: "stale"}})
	manager.client = &http.Client{Transport: nuvioRoundTripFunc(func(r *http.Request) (*http.Response, error) {
		return nuvioResponse(http.StatusServiceUnavailable, "unavailable"), nil
	})}

	err := manager.SetScraperEnabled(context.Background(), "owner/repo", "broken", true)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "could not fetch scraper code")
	repo := manager.GetRepos()[0]
	assert.False(t, repo.Scrapers[0].Enabled)
	assert.Contains(t, repo.Scrapers[0].CodeErr, "HTTP 503")
	_, cached := manager.streamCacheGet("movie|1|-|-")
	assert.False(t, cached)

	reloaded := New("manager-test")
	require.Len(t, reloaded.GetRepos(), 1)
	assert.Contains(t, reloaded.GetRepos()[0].Scrapers[0].CodeErr, "HTTP 503")
}

func TestSetScraperEnabledFetchesCachesAndReusesCode(t *testing.T) {
	manager := testManager(t)
	manager.repos = []Repo{{
		ID: "owner/repo", Owner: "owner", Name: "repo", Branch: "main", Enabled: true,
		Scrapers: []Scraper{{ID: "working", Name: "Working", Filename: "working.js"}},
	}}
	fetches := 0
	manager.client = &http.Client{Transport: nuvioRoundTripFunc(func(r *http.Request) (*http.Response, error) {
		fetches++
		return nuvioResponse(http.StatusOK, `module.exports = {getStreams: () => []};`), nil
	})}

	require.NoError(t, manager.SetScraperEnabled(context.Background(), "owner/repo", "working", true))
	repo := manager.GetRepos()[0]
	assert.True(t, repo.Scrapers[0].Enabled)
	assert.NotEmpty(t, repo.Scrapers[0].Code)
	assert.NotNil(t, repo.Scrapers[0].CodeFetchedAt)
	assert.True(t, manager.HasEnabledScrapers())
	assert.Equal(t, 1, fetches)

	require.NoError(t, manager.SetScraperEnabled(context.Background(), "owner/repo", "working", false))
	assert.False(t, manager.HasEnabledScrapers())
	require.NoError(t, manager.SetScraperEnabled(context.Background(), "owner/repo", "working", true))
	assert.Equal(t, 1, fetches, "cached scraper code should be reused")
}

func TestInFlightScraperEnableCannotOverrideLaterDisable(t *testing.T) {
	manager := testManager(t)
	manager.repos = []Repo{{
		ID: "owner/repo", Owner: "owner", Name: "repo", Branch: "main", Enabled: true,
		Scrapers: []Scraper{{ID: "working", Name: "Working", Filename: "working.js"}},
	}}
	started := make(chan struct{})
	release := make(chan struct{})
	manager.client = &http.Client{Transport: nuvioRoundTripFunc(func(*http.Request) (*http.Response, error) {
		close(started)
		<-release
		return nuvioResponse(http.StatusOK, `module.exports = {getStreams: () => []};`), nil
	})}

	result := make(chan error, 1)
	go func() {
		result <- manager.SetScraperEnabled(context.Background(), "owner/repo", "working", true)
	}()
	<-started
	require.NoError(t, manager.SetScraperEnabled(context.Background(), "owner/repo", "working", false))
	close(release)

	require.ErrorIs(t, <-result, errRegistryChanged)
	scraper := manager.GetRepos()[0].Scrapers[0]
	assert.False(t, scraper.Enabled)
	assert.Empty(t, scraper.Code)
}

func TestInFlightAddRepoCannotMutateNewProfile(t *testing.T) {
	manager := testManager(t)
	started := make(chan struct{})
	release := make(chan struct{})
	manager.client = &http.Client{Transport: nuvioRoundTripFunc(func(*http.Request) (*http.Response, error) {
		close(started)
		<-release
		return nuvioResponse(http.StatusOK, scraperManifest("1.0.0", "scraper.js")), nil
	})}

	result := make(chan error, 1)
	go func() {
		_, err := manager.AddRepo(context.Background(), "https://github.com/owner/repo/tree/main")
		result <- err
	}()
	<-started
	require.NoError(t, manager.SetProfile("second"))
	close(release)

	require.ErrorIs(t, <-result, errRegistryChanged)
	assert.Empty(t, manager.GetRepos())
	reloaded := New("second")
	assert.Empty(t, reloaded.GetRepos())
}

func TestRefreshRepoRecordsFailuresAndUpdatesEnabledScrapers(t *testing.T) {
	manager := testManager(t)
	manager.repos = []Repo{{
		ID: "owner/repo", Owner: "owner", Name: "repo", Branch: "main", Enabled: true,
		Scrapers: []Scraper{{
			ID: "scraper-1", Name: "Old", Version: "1.0.0", Filename: "old.js",
			Enabled: true, Code: "old code",
		}},
	}}
	manager.client = &http.Client{Transport: nuvioRoundTripFunc(func(r *http.Request) (*http.Response, error) {
		switch r.URL.Path {
		case "/owner/repo/main/manifest.json":
			return nuvioResponse(http.StatusOK, scraperManifest("2.0.0", "new.js")), nil
		case "/owner/repo/main/new.js":
			return nuvioResponse(http.StatusBadGateway, "broken"), nil
		default:
			return nuvioResponse(http.StatusNotFound, "missing"), nil
		}
	})}

	require.NoError(t, manager.RefreshRepo(context.Background(), "owner/repo"))
	refreshed := manager.GetRepos()[0]
	require.Len(t, refreshed.Scrapers, 1)
	assert.Equal(t, "2.0.0", refreshed.Scrapers[0].Version)
	assert.False(t, refreshed.Scrapers[0].Enabled)
	assert.Contains(t, refreshed.Scrapers[0].CodeErr, "HTTP 502")
	assert.Empty(t, refreshed.FetchErr)

	manager.client = &http.Client{Transport: nuvioRoundTripFunc(func(r *http.Request) (*http.Response, error) {
		return nuvioResponse(http.StatusServiceUnavailable, "offline"), nil
	})}
	err := manager.RefreshRepo(context.Background(), "owner/repo")
	require.Error(t, err)
	assert.Contains(t, manager.GetRepos()[0].FetchErr, "HTTP 503")
	reloaded := New("manager-test")
	require.Len(t, reloaded.GetRepos(), 1)
	assert.Contains(t, reloaded.GetRepos()[0].FetchErr, "HTTP 503")

	manager.client = &http.Client{Transport: nuvioRoundTripFunc(func(r *http.Request) (*http.Response, error) {
		return nuvioResponse(http.StatusOK, "{invalid"), nil
	})}
	err = manager.RefreshRepo(context.Background(), "owner/repo")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "could not parse manifest.json")
	assert.Contains(t, manager.GetRepos()[0].FetchErr, "could not parse manifest.json")
	reloaded = New("manager-test")
	assert.Contains(t, reloaded.GetRepos()[0].FetchErr, "could not parse manifest.json")
}

func TestRepoLifecycleErrorsAndHTTPCreateRefresh(t *testing.T) {
	manager := testManager(t)
	assert.Error(t, manager.RemoveRepo("missing"))
	assert.Error(t, manager.SetRepoEnabled("missing", true))
	assert.Error(t, manager.SetScraperEnabled(context.Background(), "missing", "missing", true))
	assert.Error(t, manager.RefreshRepo(context.Background(), "missing"))
	_, err := manager.AddRepo(context.Background(), "https://example.com/not-github")
	require.Error(t, err)

	manager.client = &http.Client{Transport: nuvioRoundTripFunc(func(r *http.Request) (*http.Response, error) {
		return nuvioResponse(http.StatusOK, scraperManifest("1.0.0", "scraper.js")), nil
	})}
	mux := http.NewServeMux()
	manager.SetupHandlers(mux)
	request := func(method, target, body string) *httptest.ResponseRecorder {
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, httptest.NewRequest(method, target, strings.NewReader(body)))
		return rec
	}

	rec := request(http.MethodPost, "/api/nuvio/repos", `{"url":"https://github.com/owner/repo/tree/main"}`)
	require.Equal(t, http.StatusOK, rec.Code, rec.Body.String())
	assert.Contains(t, rec.Body.String(), `"id":"owner/repo"`)
	assert.Equal(t, http.StatusNoContent, request(http.MethodPost, "/api/nuvio/repos/refresh?id=owner/repo", "").Code)
	assert.Equal(t, http.StatusBadRequest, request(http.MethodPost, "/api/nuvio/repos/refresh", "").Code)
	assert.Equal(t, http.StatusMethodNotAllowed, request(http.MethodGet, "/api/nuvio/repos/refresh?id=owner/repo", "").Code)
}

// TestSetScraperEnabledFetchFailurePersistErrorSkipsOtherRepos covers the
// second apply loop in SetScraperEnabled: a non-matching repo is skipped
// (repo.go line 249) and, when the code fetch fails, persisting the disabled
// state also fails and the two errors are wrapped together (line 261).
func TestSetScraperEnabledFetchFailurePersistErrorSkipsOtherRepos(t *testing.T) {
	manager := testManager(t)
	manager.repos = []Repo{
		{ID: "owner/a", Owner: "owner", Name: "a", Branch: "main", Enabled: true,
			Scrapers: []Scraper{{ID: "other", Name: "Other", Filename: "other.js"}}},
		{ID: "owner/b", Owner: "owner", Name: "b", Branch: "main", Enabled: true,
			Scrapers: []Scraper{{ID: "target", Name: "Target", Filename: "target.js"}}},
	}
	manager.client = &http.Client{Transport: nuvioRoundTripFunc(func(*http.Request) (*http.Response, error) {
		return nuvioResponse(http.StatusInternalServerError, "boom"), nil
	})}
	blocker := filepath.Join(t.TempDir(), "not-a-directory")
	require.NoError(t, os.WriteFile(blocker, []byte("blocked"), 0o600))
	manager.storePath = filepath.Join(blocker, "nuvio.json")

	err := manager.SetScraperEnabled(context.Background(), "owner/b", "target", true)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "could not fetch scraper code")
	assert.Contains(t, err.Error(), "save state")
}

// TestRefreshRepoAbortsWhenRegistryChangesMidFlight covers the revision guard
// in RefreshRepo's apply phase (repo.go lines 335-336): switching profiles
// while the manifest fetch is in flight bumps the config revision, so the
// captured refresh is rejected instead of writing into the new profile.
func TestRefreshRepoAbortsWhenRegistryChangesMidFlight(t *testing.T) {
	manager := testManager(t)
	manager.repos = []Repo{{
		ID: "owner/repo", Owner: "owner", Name: "repo", Branch: "main", Enabled: true,
		Scrapers: []Scraper{{ID: "s", Name: "S", Filename: "s.js"}},
	}}
	started := make(chan struct{})
	release := make(chan struct{})
	manager.client = &http.Client{Transport: nuvioRoundTripFunc(func(*http.Request) (*http.Response, error) {
		close(started)
		<-release
		return nuvioResponse(http.StatusOK, scraperManifest("2.0.0", "s.js")), nil
	})}

	result := make(chan error, 1)
	go func() { result <- manager.RefreshRepo(context.Background(), "owner/repo") }()
	<-started
	require.NoError(t, manager.SetProfile("second"))
	close(release)
	require.ErrorIs(t, <-result, errRegistryChanged)
}

// TestRecordRepoRefreshErrorAbortsWhenRegistryChangesMidFlight covers the
// revision guard in recordRepoRefreshError (repo.go lines 357-358): when the
// manifest fetch fails and the registry has since changed, the error is not
// persisted onto a stale repo.
func TestRecordRepoRefreshErrorAbortsWhenRegistryChangesMidFlight(t *testing.T) {
	manager := testManager(t)
	manager.repos = []Repo{{ID: "owner/repo", Owner: "owner", Name: "repo", Branch: "main", Enabled: true}}
	started := make(chan struct{})
	release := make(chan struct{})
	manager.client = &http.Client{Transport: nuvioRoundTripFunc(func(*http.Request) (*http.Response, error) {
		close(started)
		<-release
		return nuvioResponse(http.StatusServiceUnavailable, "offline"), nil
	})}

	result := make(chan error, 1)
	go func() { result <- manager.RefreshRepo(context.Background(), "owner/repo") }()
	<-started
	require.NoError(t, manager.SetProfile("second"))
	close(release)
	require.ErrorIs(t, <-result, errRegistryChanged)
}

// TestRecordRepoRefreshErrorPersistFailureSkipsOtherRepos covers
// recordRepoRefreshError skipping a non-matching repo (repo.go line 361) and
// wrapping a persistence failure alongside the refresh error (line 367).
func TestRecordRepoRefreshErrorPersistFailureSkipsOtherRepos(t *testing.T) {
	manager := testManager(t)
	manager.repos = []Repo{
		{ID: "owner/a", Owner: "owner", Name: "a", Branch: "main", Enabled: true},
		{ID: "owner/b", Owner: "owner", Name: "b", Branch: "main", Enabled: true},
	}
	manager.client = &http.Client{Transport: nuvioRoundTripFunc(func(*http.Request) (*http.Response, error) {
		return nuvioResponse(http.StatusServiceUnavailable, "offline"), nil
	})}
	blocker := filepath.Join(t.TempDir(), "not-a-directory")
	require.NoError(t, os.WriteFile(blocker, []byte("blocked"), 0o600))
	manager.storePath = filepath.Join(blocker, "nuvio.json")

	err := manager.RefreshRepo(context.Background(), "owner/b")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "save state")
}
