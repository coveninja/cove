package nuvio

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coveninja/cove/internal/addons"
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
}

func TestInvalidationRejectsInFlightCacheWrite(t *testing.T) {
	m := testManager(t)
	generation := m.cacheGenerationSnapshot()
	m.invalidateStreamCache()
	m.streamCacheSetIfCurrent("movie|9|-|-", []addons.Stream{{Name: "obsolete"}}, generation)
	_, ok := m.streamCacheGet("movie|9|-|-")
	assert.False(t, ok)
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

func intPtr(value int) *int { return &value }
