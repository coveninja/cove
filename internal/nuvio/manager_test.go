package nuvio

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coveninja/cove/internal/addons"
	"github.com/coveninja/cove/internal/utils"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func testManager(t *testing.T) *Manager {
	t.Helper()
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	return New("manager-test")
}

func enabledRepo() Repo {
	return Repo{
		ID: "owner/repo", Owner: "owner", Name: "repo", Branch: "main", Enabled: true,
		Scrapers: []Scraper{{
			ID: "working", Name: "Working", Filename: "working.js", Enabled: true,
			SupportedTypes: []string{"movie"}, ContentLanguage: []string{"en"},
			Code: `function getStreams() {
				return [{name: "source", quality: "1080p", url: "https://example.com/video", size: 1234,
					headers: {Referer: "https://example.com/"}}];
			}
			module.exports = {getStreams};`,
		}},
	}
}

func TestManagerLoadsPersistsAndSwitchesProfiles(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	m := New("first")
	m.repos = []Repo{enabledRepo()}
	m.mu.Lock()
	require.NoError(t, m.saveL())
	m.mu.Unlock()

	reloaded := New("first")
	require.Len(t, reloaded.GetRepos(), 1)
	assert.False(t, reloaded.updatedAt.IsZero())

	reloaded.streamCacheSet("movie|1|-|-", []addons.Stream{{Name: "stale"}})
	require.NoError(t, reloaded.SetProfile("second"))
	assert.Empty(t, reloaded.GetRepos())
	_, cached := reloaded.streamCacheGet("movie|1|-|-")
	assert.False(t, cached)
}

func TestGetReposReturnsDeepCopy(t *testing.T) {
	m := testManager(t)
	m.repos = []Repo{enabledRepo()}
	copyOfRepos := m.GetRepos()
	copyOfRepos[0].Enabled = false
	copyOfRepos[0].Scrapers[0].Enabled = false
	copyOfRepos[0].Scrapers[0].SupportedTypes[0] = "tv"
	copyOfRepos[0].Scrapers[0].ContentLanguage[0] = "fr"

	got := m.GetRepos()
	assert.True(t, got[0].Enabled)
	assert.True(t, got[0].Scrapers[0].Enabled)
	assert.Equal(t, []string{"movie"}, got[0].Scrapers[0].SupportedTypes)
	assert.Equal(t, []string{"en"}, got[0].Scrapers[0].ContentLanguage)
}

func TestMergeFromJSONUsesLWWAndInvalidatesCache(t *testing.T) {
	m := testManager(t)
	localTime := time.Now().UTC()
	m.updatedAt = localTime
	m.repos = []Repo{enabledRepo()}
	m.streamCacheSet("movie|1|-|-", []addons.Stream{{Name: "stale"}})

	older, err := json.Marshal(nuvioStore{Repos: nil})
	require.NoError(t, err)
	require.NoError(t, m.MergeFromJSON(older, localTime.Add(-time.Minute)))
	require.Len(t, m.GetRepos(), 1)

	remoteRepos := []Repo{{ID: "remote/repo", Enabled: false}}
	newer, err := json.Marshal(nuvioStore{Repos: remoteRepos})
	require.NoError(t, err)
	remoteTime := localTime.Add(time.Minute)
	require.NoError(t, m.MergeFromJSON(newer, remoteTime))
	assert.Equal(t, remoteRepos, m.GetRepos())
	_, cached := m.streamCacheGet("movie|1|-|-")
	assert.False(t, cached)

	snapshot, updatedAt := m.SnapshotJSON()
	assert.Equal(t, remoteTime, updatedAt)
	var persisted nuvioStore
	require.NoError(t, json.Unmarshal(snapshot, &persisted))
	assert.Equal(t, remoteRepos, persisted.Repos)
}

func TestStreamCacheCopiesAndExpires(t *testing.T) {
	m := testManager(t)
	key := nuvioCacheKey("tv", 5, intPtr(2), intPtr(3))
	assert.Equal(t, "tv|5|2|3", key)
	original := []addons.Stream{{Name: "cached", Headers: map[string]string{"Referer": "original"}}}
	m.streamCacheSet(key, original)
	original[0].Name = "caller changed"
	original[0].Headers["Referer"] = "caller changed"
	got, ok := m.streamCacheGet(key)
	require.True(t, ok)
	got[0].Name = "changed"
	got[0].Headers["Referer"] = "changed"
	again, ok := m.streamCacheGet(key)
	require.True(t, ok)
	assert.Equal(t, "cached", again[0].Name)
	assert.Equal(t, "original", again[0].Headers["Referer"])

	m.streamCacheMu.Lock()
	entry := m.streamCache[key]
	entry.expires = time.Now().Add(-time.Second)
	m.streamCache[key] = entry
	m.streamCacheMu.Unlock()
	_, ok = m.streamCacheGet(key)
	assert.False(t, ok)

	m.streamCacheSet("expired", []addons.Stream{{Name: "old"}})
	m.streamCacheMu.Lock()
	expired := m.streamCache["expired"]
	expired.expires = time.Now().Add(-time.Second)
	m.streamCache["expired"] = expired
	m.streamCacheMu.Unlock()
	m.streamCacheSet("fresh", []addons.Stream{{Name: "new"}})
	m.streamCacheMu.Lock()
	_, expiredStillPresent := m.streamCache["expired"]
	m.streamCacheMu.Unlock()
	assert.False(t, expiredStillPresent)
}

func TestInvalidationRejectsInFlightCacheWrite(t *testing.T) {
	m := testManager(t)
	generation := m.cacheGenerationSnapshot()
	m.invalidateStreamCache()
	m.streamCacheSetIfCurrent("movie|9|-|-", []addons.Stream{{Name: "obsolete"}}, generation)
	_, ok := m.streamCacheGet("movie|9|-|-")
	assert.False(t, ok)
}

func TestRegistryPersistenceFailuresRollBackState(t *testing.T) {
	t.Run("save without a configured path", func(t *testing.T) {
		m := &Manager{}
		m.mu.Lock()
		require.NoError(t, m.saveL())
		m.mu.Unlock()
		assert.False(t, m.updatedAt.IsZero())
	})

	t.Run("local mutation", func(t *testing.T) {
		m := testManager(t)
		previous := []Repo{{ID: "previous"}}
		previousTime := time.Now().Add(-time.Hour).UTC()
		m.repos = []Repo{{ID: "mutated"}}
		m.updatedAt = time.Now().UTC()
		blocker := filepath.Join(t.TempDir(), "not-a-directory")
		require.NoError(t, os.WriteFile(blocker, []byte("blocked"), 0o600))
		m.storePath = filepath.Join(blocker, "nuvio.json")

		m.mu.Lock()
		err := m.commitMutationL(previous, previousTime)
		m.mu.Unlock()
		require.Error(t, err)
		assert.Equal(t, previous, m.GetRepos())
		assert.Equal(t, previousTime, m.updatedAt)
	})

	t.Run("remote merge", func(t *testing.T) {
		m := testManager(t)
		previous := []Repo{{ID: "previous"}}
		previousTime := time.Now().Add(-time.Hour).UTC()
		m.repos = previous
		m.updatedAt = previousTime
		blocker := filepath.Join(t.TempDir(), "not-a-directory")
		require.NoError(t, os.WriteFile(blocker, []byte("blocked"), 0o600))
		m.storePath = filepath.Join(blocker, "nuvio.json")
		data, err := json.Marshal(nuvioStore{Repos: []Repo{{ID: "remote"}}})
		require.NoError(t, err)

		err = m.MergeFromJSON(data, previousTime.Add(time.Hour))
		require.Error(t, err)
		assert.Equal(t, previous, m.GetRepos())
		assert.Equal(t, previousTime, m.updatedAt)
	})
}

func TestGetStreamsHonorsCanceledWaiterWhileFlightContinues(t *testing.T) {
	m := testManager(t)
	key := nuvioCacheKey("movie", 77, nil, nil)
	flightKey := fmt.Sprintf("%d|%s", m.cacheGenerationSnapshot(), key)
	release := make(chan struct{})
	flight := m.streamSF.DoChan(flightKey, func() (interface{}, error) {
		<-release
		return []addons.Stream{{Name: "late"}}, nil
	})

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	assert.Nil(t, m.GetStreams(ctx, "movie", 77, "", "Canceled", 2026, nil, nil))
	close(release)
	<-flight
}

func TestGetStreamsDoesNotCacheResultsAfterRepoDisabledInFlight(t *testing.T) {
	useLocalScraperTransport(t)
	requestStarted := make(chan struct{})
	releaseResponse := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(requestStarted)
		<-releaseResponse
		_, _ = w.Write([]byte(`{"url":"https://example.com/in-flight"}`))
	}))
	defer server.Close()

	m := testManager(t)
	repo := enabledRepo()
	repo.Scrapers[0].Code = `
		async function getStreams() {
			const response = await fetch("` + server.URL + `");
			const stream = await response.json();
			return [{name: "in-flight", url: stream.url}];
		}
		module.exports = {getStreams};
	`
	m.repos = []Repo{repo}

	results := make(chan []addons.Stream, 1)
	go func() {
		results <- m.GetStreams(context.Background(), "movie", 44, "", "Example", 2025, nil, nil)
	}()

	select {
	case <-requestStarted:
	case <-time.After(5 * time.Second):
		t.Fatal("scraper did not begin its request")
	}
	require.NoError(t, m.SetRepoEnabled(repo.ID, false))
	close(releaseResponse)

	select {
	case streams := <-results:
		require.Len(t, streams, 1, "the already-running caller may still receive its completed result")
	case <-time.After(5 * time.Second):
		t.Fatal("GetStreams did not finish after the scraper response was released")
	}

	key := nuvioCacheKey("movie", 44, nil, nil)
	_, cached := m.streamCacheGet(key)
	assert.False(t, cached, "results from the disabled repo must not repopulate the invalidated cache")
	assert.Empty(t, m.GetStreams(context.Background(), "movie", 44, "", "Example", 2025, nil, nil))
}

func TestGetStreamsRunsEnabledScraperAndCachesResult(t *testing.T) {
	m := testManager(t)
	m.repos = []Repo{enabledRepo(), {ID: "disabled", Enabled: false}}
	streams := m.GetStreams(context.Background(), "movie", 22, "tt22", "Example", 2025, nil, nil)
	require.Len(t, streams, 1)
	assert.Equal(t, "source", streams[0].Name)
	assert.Equal(t, "1080p", streams[0].Title)
	assert.Equal(t, "Nuvio: Working", streams[0].AddonName)
	assert.Equal(t, int64(1234), streams[0].SizeBytes)
	assert.Equal(t, "https://example.com/", streams[0].Headers["Referer"])

	m.repos = nil
	cached := m.GetStreams(context.Background(), "movie", 22, "tt22", "Example", 2025, nil, nil)
	require.Len(t, cached, 1)
	assert.Equal(t, "source", cached[0].Name)
}

func TestGetStreamsCoalescesConcurrentColdCacheMisses(t *testing.T) {
	useLocalScraperTransport(t)
	requestStarted := make(chan struct{})
	releaseResponse := make(chan struct{})
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if requests.Add(1) == 1 {
			close(requestStarted)
		}
		<-releaseResponse
		_, _ = w.Write([]byte(`{"url":"https://example.com/shared"}`))
	}))
	defer server.Close()

	m := testManager(t)
	repo := enabledRepo()
	repo.Scrapers[0].Code = `
		async function getStreams() {
			const response = await fetch("` + server.URL + `");
			const stream = await response.json();
			return [{name: "shared", url: stream.url}];
		}
		module.exports = {getStreams};
	`
	m.repos = []Repo{repo}

	const callers = 8
	results := make([][]addons.Stream, callers)
	var wg sync.WaitGroup
	for i := range callers {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			results[i] = m.GetStreams(context.Background(), "movie", 77, "", "Example", 2025, nil, nil)
		}(i)
	}
	select {
	case <-requestStarted:
	case <-time.After(5 * time.Second):
		t.Fatal("shared scraper request did not start")
	}
	// Give all callers time to join the in-flight key before releasing it.
	time.Sleep(20 * time.Millisecond)
	close(releaseResponse)
	wg.Wait()

	assert.EqualValues(t, 1, requests.Load())
	for _, streams := range results {
		require.Len(t, streams, 1)
		assert.Equal(t, "shared", streams[0].Name)
	}
}

func TestRepoAndScraperMutationsInvalidateCache(t *testing.T) {
	m := testManager(t)
	m.repos = []Repo{enabledRepo()}
	key := "movie|1|-|-"
	cache := func() { m.streamCacheSet(key, []addons.Stream{{Name: "stale"}}) }
	assertCleared := func() {
		_, ok := m.streamCacheGet(key)
		assert.False(t, ok)
	}

	cache()
	require.NoError(t, m.SetRepoEnabled("owner/repo", false))
	assertCleared()
	cache()
	require.NoError(t, m.SetScraperEnabled(context.Background(), "owner/repo", "working", false))
	assertCleared()
	cache()
	require.NoError(t, m.RemoveRepo("owner/repo"))
	assertCleared()
}

func TestNuvioHandlersListPatchDeleteAndValidation(t *testing.T) {
	m := testManager(t)
	m.repos = []Repo{enabledRepo()}
	mux := http.NewServeMux()
	m.SetupHandlers(mux)

	request := func(method, target, body string) *httptest.ResponseRecorder {
		recorder := httptest.NewRecorder()
		mux.ServeHTTP(recorder, httptest.NewRequest(method, target, strings.NewReader(body)))
		return recorder
	}
	assert.Equal(t, http.StatusOK, request(http.MethodGet, "/api/nuvio/repos", "").Code)
	assert.Equal(t, http.StatusBadRequest, request(http.MethodPatch, "/api/nuvio/repos", `{}`).Code)
	assert.Equal(t, http.StatusNoContent, request(http.MethodPatch, "/api/nuvio/repos?id=owner/repo", `{"enabled":false}`).Code)
	assert.False(t, m.GetRepos()[0].Enabled)
	assert.Equal(t, http.StatusBadRequest, request(http.MethodPatch, "/api/nuvio/scrapers?repoId=owner/repo", `{}`).Code)
	assert.Equal(t, http.StatusNoContent, request(http.MethodPatch, "/api/nuvio/scrapers?repoId=owner/repo&scraperId=working", `{"enabled":false}`).Code)
	assert.Equal(t, http.StatusMethodNotAllowed, request(http.MethodPut, "/api/nuvio/repos", "").Code)
	assert.Equal(t, http.StatusNoContent, request(http.MethodDelete, "/api/nuvio/repos?id=owner/repo", "").Code)
}

func TestNuvioHandlersRejectInvalidRequestsAndSurfaceLifecycleErrors(t *testing.T) {
	m := testManager(t)
	mux := http.NewServeMux()
	m.SetupHandlers(mux)

	tests := []struct {
		name   string
		method string
		target string
		body   string
		status int
	}{
		{"post missing URL", http.MethodPost, "/api/nuvio/repos", `{}`, http.StatusBadRequest},
		{"post unsupported URL", http.MethodPost, "/api/nuvio/repos", `{"url":"https://example.com/repo"}`, http.StatusBadRequest},
		{"patch invalid body", http.MethodPatch, "/api/nuvio/repos?id=missing", `{`, http.StatusBadRequest},
		{"patch missing repo", http.MethodPatch, "/api/nuvio/repos?id=missing", `{"enabled":true}`, http.StatusNotFound},
		{"delete missing ID", http.MethodDelete, "/api/nuvio/repos", "", http.StatusBadRequest},
		{"delete missing repo", http.MethodDelete, "/api/nuvio/repos?id=missing", "", http.StatusBadRequest},
		{"refresh missing repo", http.MethodPost, "/api/nuvio/repos/refresh?id=missing", "", http.StatusBadRequest},
		{"scraper wrong method", http.MethodGet, "/api/nuvio/scrapers", "", http.StatusMethodNotAllowed},
		{"scraper missing IDs", http.MethodPatch, "/api/nuvio/scrapers?repoId=missing", `{"enabled":true}`, http.StatusBadRequest},
		{"scraper invalid body", http.MethodPatch, "/api/nuvio/scrapers?repoId=missing&scraperId=missing", `{`, http.StatusBadRequest},
		{"scraper missing", http.MethodPatch, "/api/nuvio/scrapers?repoId=missing&scraperId=missing", `{"enabled":true}`, http.StatusBadRequest},
	}
	for _, testCase := range tests {
		t.Run(testCase.name, func(t *testing.T) {
			recorder := httptest.NewRecorder()
			request := httptest.NewRequest(testCase.method, testCase.target, strings.NewReader(testCase.body))
			mux.ServeHTTP(recorder, request)
			assert.Equal(t, testCase.status, recorder.Code)
		})
	}
}

func TestManagerRejectsCorruptProfileAndRemoteStoresWithoutMutation(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	m := New("good")
	m.repos = []Repo{enabledRepo()}
	m.updatedAt = time.Now().UTC()
	key := nuvioCacheKey("movie", 1, nil, nil)
	m.streamCacheSet(key, []addons.Stream{{Name: "cached"}})

	corruptPath, err := utils.ConfigPath("nuvio-corrupt.json")
	require.NoError(t, err)
	require.NoError(t, os.WriteFile(corruptPath, []byte("{invalid"), 0o644))
	require.Error(t, m.SetProfile("corrupt"))
	require.Len(t, m.GetRepos(), 1)
	_, cached := m.streamCacheGet(key)
	assert.True(t, cached)

	require.Error(t, m.MergeFromJSON([]byte("{invalid"), m.updatedAt.Add(time.Minute)))
	require.Len(t, m.GetRepos(), 1)
	_, cached = m.streamCacheGet(key)
	assert.True(t, cached)
}

func TestManifestShapesAndManagerPredicates(t *testing.T) {
	bare, err := parseManifest([]byte(`[{"id":"bare","filename":"bare.js"}]`))
	require.NoError(t, err)
	require.Len(t, bare, 1)
	assert.Equal(t, "bare", bare[0].ID)

	providers, err := parseManifest([]byte(`{"providers":[{"id":"provider","filename":"provider.js"}]}`))
	require.NoError(t, err)
	require.Len(t, providers, 1)
	assert.Equal(t, "provider", providers[0].ID)
	_, err = parseManifest([]byte("{invalid"))
	require.Error(t, err)

	m := testManager(t)
	m.repos = []Repo{
		{Enabled: false, Scrapers: []Scraper{{Enabled: true, Code: "code"}}},
		{Enabled: true, Scrapers: []Scraper{{Enabled: true}}},
	}
	assert.False(t, m.HasEnabledScrapers())
	assert.Equal(t, "", firstNonEmpty("", ""))
}

func intPtr(value int) *int { return &value }
