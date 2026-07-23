package addons

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// newTestManager builds a Manager directly (bypassing New, which wires up
// utils.SafeTransport — that transport deliberately refuses loopback
// addresses, which is exactly what httptest.Server listens on) with a plain
// http.Client so tests can talk to local test servers.
func newTestManager(entries []AddonEntry) *Manager {
	return &Manager{
		client:          &http.Client{},
		stremioAddons:   entries,
		officialEnabled: make(map[string]bool),
		streamCache:     make(map[string]streamCacheEntry),
	}
}

func streamAddon(id string, url string) AddonEntry {
	return AddonEntry{
		ID:      id,
		URL:     url,
		Kind:    KindProvider,
		Source:  SourceStremio,
		Enabled: true,
		Manifest: Manifest{
			ID:        id,
			Name:      id,
			Resources: []ManifestResource{{Name: "stream"}},
		},
	}
}

func TestGetAllStreams_ParallelNotSequential(t *testing.T) {
	orig := fanOutDeadline
	fanOutDeadline = 300 * time.Millisecond
	t.Cleanup(func() { fanOutDeadline = orig })

	fast := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"streams":[{"name":"fast-stream","title":"t","url":"http://fast/1"}]}`)
	}))
	defer fast.Close()

	slow := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(2 * time.Second) // far longer than fanOutDeadline
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"streams":[{"name":"slow-stream","title":"t","url":"http://slow/1"}]}`)
	}))
	defer slow.Close()

	m := newTestManager([]AddonEntry{
		streamAddon("fast", fast.URL),
		streamAddon("slow", slow.URL),
	})

	start := time.Now()
	streams, err := m.GetAllStreams(context.Background(), "movie", "tt123")
	elapsed := time.Since(start)

	require.NoError(t, err)
	assert.Less(t, elapsed, 1*time.Second, "fan-out should return around the deadline, not wait for the slow addon (would be 2s+)")
	require.Len(t, streams, 1)
	assert.Equal(t, "fast-stream", streams[0].Name)
	assert.Equal(t, "fast", streams[0].AddonName)
}

func TestGetAllStreams_RegistrationOrderDeterministic(t *testing.T) {
	a1 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"streams":[{"name":"s1","title":"t","url":"http://a1/1"}]}`)
	}))
	defer a1.Close()
	a2 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"streams":[{"name":"s2","title":"t","url":"http://a2/1"}]}`)
	}))
	defer a2.Close()

	m := newTestManager([]AddonEntry{
		streamAddon("addon1", a1.URL),
		streamAddon("addon2", a2.URL),
	})

	for i := 0; i < 5; i++ {
		streams, err := m.GetAllStreams(context.Background(), "movie", fmt.Sprintf("tt%d", i))
		require.NoError(t, err)
		require.Len(t, streams, 2)
		assert.Equal(t, "s1", streams[0].Name, "registration order should be stable across calls")
		assert.Equal(t, "s2", streams[1].Name)
	}
}

func TestGetAllStreams_ContextCancellationAbortsFast(t *testing.T) {
	slow := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(2 * time.Second)
		w.WriteHeader(http.StatusOK)
	}))
	defer slow.Close()

	m := newTestManager([]AddonEntry{streamAddon("slow", slow.URL)})

	ctx, cancel := context.WithCancel(context.Background())
	cancel() // already cancelled before the call starts

	start := time.Now()
	streams, err := m.GetAllStreams(ctx, "movie", "tt999")
	elapsed := time.Since(start)

	require.NoError(t, err)
	assert.Empty(t, streams)
	assert.Less(t, elapsed, 1*time.Second, "an already-cancelled context should abort the fan-out immediately")
}

func TestGetAllStreams_PerAddonCache(t *testing.T) {
	var hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"streams":[{"name":"cached","title":"t","url":"http://cached/1"}]}`)
	}))
	defer srv.Close()

	m := newTestManager([]AddonEntry{streamAddon("addon1", srv.URL)})

	streams1, err := m.GetAllStreams(context.Background(), "movie", "tt42")
	require.NoError(t, err)
	require.Len(t, streams1, 1)

	streams2, err := m.GetAllStreams(context.Background(), "movie", "tt42")
	require.NoError(t, err)
	require.Len(t, streams2, 1)

	assert.Equal(t, int32(1), atomic.LoadInt32(&hits), "second GetAllStreams within the TTL should be served from cache")
	assert.Equal(t, "addon1", streams2[0].AddonName)
}

func TestGetAllStreams_CacheIsPerAddonNotMerged(t *testing.T) {
	// Different mediaType/stremioID must be separate cache entries — same
	// addon, different key.
	var hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"streams":[{"name":"x","title":"t","url":"http://x/1"}]}`)
	}))
	defer srv.Close()

	m := newTestManager([]AddonEntry{streamAddon("addon1", srv.URL)})

	_, err := m.GetAllStreams(context.Background(), "movie", "tt1")
	require.NoError(t, err)
	_, err = m.GetAllStreams(context.Background(), "tv", "tt1:1:2")
	require.NoError(t, err)

	assert.Equal(t, int32(2), atomic.LoadInt32(&hits), "different stremioID/mediaType must not share a cache entry")
}

func TestGetAllStreams_ConcurrentCallsCoalesce(t *testing.T) {
	var hits int32
	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		<-release
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"streams":[{"name":"x","title":"t","url":"http://x/1"}]}`)
	}))
	defer srv.Close()

	m := newTestManager([]AddonEntry{streamAddon("addon1", srv.URL)})

	done := make(chan struct{}, 5)
	for i := 0; i < 5; i++ {
		go func() {
			_, _ = m.GetAllStreams(context.Background(), "movie", "tt7")
			done <- struct{}{}
		}()
	}
	time.Sleep(100 * time.Millisecond)
	close(release)
	for i := 0; i < 5; i++ {
		<-done
	}

	assert.Equal(t, int32(1), atomic.LoadInt32(&hits), "concurrent calls for the same key should coalesce into one upstream fetch")
}

func TestGetAllStreams_DisabledAndNonProviderAddonsSkipped(t *testing.T) {
	var hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"streams":[]}`)
	}))
	defer srv.Close()

	disabled := streamAddon("disabled", srv.URL)
	disabled.Enabled = false
	subtitleKind := streamAddon("subs", srv.URL)
	subtitleKind.Kind = KindSubtitle

	m := newTestManager([]AddonEntry{disabled, subtitleKind})

	streams, err := m.GetAllStreams(context.Background(), "movie", "tt1")
	require.NoError(t, err)
	assert.Empty(t, streams)
	assert.Equal(t, int32(0), atomic.LoadInt32(&hits), "disabled/non-provider addons must never be fetched")
}

func TestGetAllStreams_NoStreamResourceSkipped(t *testing.T) {
	var hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"streams":[{"name":"x","title":"t","url":"http://x/1"}]}`)
	}))
	defer srv.Close()

	// An addon with no Resources at all should be skipped even if KindProvider.
	noResources := AddonEntry{
		ID:       "no-res",
		URL:      srv.URL,
		Kind:     KindProvider,
		Source:   SourceStremio,
		Enabled:  true,
		Manifest: Manifest{ID: "no-res", Name: "no-res"},
	}
	m := newTestManager([]AddonEntry{noResources})

	streams, err := m.GetAllStreams(context.Background(), "movie", "tt1")
	require.NoError(t, err)
	assert.Empty(t, streams)
	assert.Equal(t, int32(0), atomic.LoadInt32(&hits), "addon with no stream resource must never be fetched")
}

func TestManifestCatalogUnmarshal_LegacyForm(t *testing.T) {
	// Legacy form: extraRequired + extraSupported arrays of strings.
	raw := `{
		"type": "movie",
		"id":   "top",
		"name": "Top List",
		"extraRequired":  ["genre"],
		"extraSupported": ["skip", "genre"]
	}`
	var c ManifestCatalog
	require.NoError(t, json.Unmarshal([]byte(raw), &c))
	assert.Equal(t, "movie", c.Type)
	assert.Equal(t, "top", c.ID)
	// genre appears in extraRequired → IsRequired true
	// skip appears only in extraSupported → IsRequired false
	// genre should not appear twice (dedup)
	require.Len(t, c.Extra, 2)
	assert.Equal(t, ManifestCatalogExtra{Name: "genre", IsRequired: true}, c.Extra[0])
	assert.Equal(t, ManifestCatalogExtra{Name: "skip", IsRequired: false}, c.Extra[1])
}

func TestManifestCatalogUnmarshal_ModernForm(t *testing.T) {
	raw := `{"type":"series","id":"popular","name":"Popular","extra":[{"name":"genre","isRequired":false},{"name":"skip","isRequired":false}]}`
	var c ManifestCatalog
	require.NoError(t, json.Unmarshal([]byte(raw), &c))
	assert.Equal(t, "series", c.Type)
	require.Len(t, c.Extra, 2)
	assert.False(t, c.Extra[0].IsRequired)
}

func TestManifestCatalog_IsHomeEligible(t *testing.T) {
	searchOnly := ManifestCatalog{Extra: []ManifestCatalogExtra{{Name: "search", IsRequired: true}}}
	assert.False(t, searchOnly.IsHomeEligible(), "required non-skip extra must exclude from home")

	skipOnly := ManifestCatalog{Extra: []ManifestCatalogExtra{{Name: "skip", IsRequired: true}}}
	assert.True(t, skipOnly.IsHomeEligible(), "required skip extra is pagination, not search — home eligible")

	noExtras := ManifestCatalog{}
	assert.True(t, noExtras.IsHomeEligible())
}

func TestGetAllSubtitles_Parallel(t *testing.T) {
	orig := fanOutDeadline
	fanOutDeadline = 300 * time.Millisecond
	t.Cleanup(func() { fanOutDeadline = orig })

	srv1 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"subtitles":[{"id":"1","url":"http://s/1","lang":"en"}]}`)
	}))
	defer srv1.Close()
	srv2 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(2 * time.Second)
		w.WriteHeader(http.StatusOK)
	}))
	defer srv2.Close()

	e1 := streamAddon("sub1", srv1.URL)
	e1.Kind = KindSubtitle
	e2 := streamAddon("sub2", srv2.URL)
	e2.Kind = KindSubtitle

	m := newTestManager([]AddonEntry{e1, e2})

	start := time.Now()
	subs := m.GetAllSubtitles(context.Background(), "movie", "tt1")
	elapsed := time.Since(start)

	assert.Less(t, elapsed, 1*time.Second)
	require.Len(t, subs, 1)
	assert.Equal(t, "en", subs[0].Lang)
}

// ── MergeFrom tests ───────────────────────────────────────────────────────────

func stremioEntry(id string) AddonEntry {
	return AddonEntry{
		ID:       id,
		URL:      "https://example.com/" + id,
		Kind:     KindProvider,
		Source:   SourceStremio,
		Enabled:  true,
		Manifest: Manifest{ID: id, Name: id},
	}
}

func officialEntry(id string, enabled bool) AddonEntry {
	return AddonEntry{
		ID:       id,
		Source:   SourceOfficial,
		Enabled:  enabled,
		Manifest: Manifest{ID: id, Name: id},
	}
}

func TestMergeFrom_RemoteNewerReplaces(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	m := &Manager{
		client:          &http.Client{},
		stremioAddons:   []AddonEntry{stremioEntry("local-addon")},
		officialEnabled: make(map[string]bool),
		streamCache:     make(map[string]streamCacheEntry),
		storePath:       t.TempDir() + "/addons-test.json",
	}

	localTime := time.Now().Add(-1 * time.Hour)
	m.updatedAt = localTime

	remote := []AddonEntry{stremioEntry("remote-addon")}
	remoteTime := time.Now()

	m.MergeFrom(remote, remoteTime)

	entries := m.GetEntries()
	// Should contain official addons + the one remote stremio addon (not local-addon).
	var stremioNames []string
	for _, e := range entries {
		if e.Source == SourceStremio {
			stremioNames = append(stremioNames, e.ID)
		}
	}
	require.Len(t, stremioNames, 1)
	assert.Equal(t, "remote-addon", stremioNames[0])
	assert.Equal(t, remoteTime, m.UpdatedAt())
}

func TestMergeFrom_LocalNewerNoOp(t *testing.T) {
	m := &Manager{
		client:          &http.Client{},
		stremioAddons:   []AddonEntry{stremioEntry("local-addon")},
		officialEnabled: make(map[string]bool),
		streamCache:     make(map[string]streamCacheEntry),
	}

	m.updatedAt = time.Now() // local is newer

	remote := []AddonEntry{stremioEntry("remote-addon")}
	remoteTime := time.Now().Add(-1 * time.Hour) // remote is older

	m.MergeFrom(remote, remoteTime)

	// Local should be unchanged.
	entries := m.GetEntries()
	var stremioNames []string
	for _, e := range entries {
		if e.Source == SourceStremio {
			stremioNames = append(stremioNames, e.ID)
		}
	}
	require.Len(t, stremioNames, 1)
	assert.Equal(t, "local-addon", stremioNames[0])
}

func TestMergeFrom_OfficialEnabledRebuilt(t *testing.T) {
	m := &Manager{
		client: &http.Client{},
		officialEnabled: map[string]bool{
			"cove.justwatch": true,
		},
		streamCache: make(map[string]streamCacheEntry),
	}
	m.updatedAt = time.Now().Add(-1 * time.Hour)

	// Remote has JustWatch disabled and IntroSkip enabled.
	remote := []AddonEntry{
		officialEntry("cove.justwatch", false),
		officialEntry("cove.introdb", true),
	}
	m.MergeFrom(remote, time.Now())

	m.mu.RLock()
	jw := m.officialEnabled["cove.justwatch"]
	intro := m.officialEnabled["cove.introdb"]
	m.mu.RUnlock()

	assert.False(t, jw, "JustWatch should be disabled per remote")
	assert.True(t, intro, "IntroSkip should be enabled per remote")
}

func TestMergeFrom_EqualTimeNoOp(t *testing.T) {
	ts := time.Now()
	m := &Manager{
		client:          &http.Client{},
		stremioAddons:   []AddonEntry{stremioEntry("local")},
		officialEnabled: make(map[string]bool),
		streamCache:     make(map[string]streamCacheEntry),
		updatedAt:       ts,
	}

	// Same timestamp — should not replace.
	m.MergeFrom([]AddonEntry{stremioEntry("remote")}, ts)

	entries := m.GetEntries()
	var stremioNames []string
	for _, e := range entries {
		if e.Source == SourceStremio {
			stremioNames = append(stremioNames, e.ID)
		}
	}
	require.Len(t, stremioNames, 1)
	assert.Equal(t, "local", stremioNames[0])
}

// ── Duplicate manifest ID tests ───────────────────────────────────────────────

// manifestHandler builds an httptest handler that serves a Stremio manifest
// with the given id and name.
func manifestHandler(id, name string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/manifest.json" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"id":%q,"name":%q,"version":"1.0.0","resources":["stream"],"types":["movie"]}`, id, name)
	}
}

func TestAddStremioAddon_SameIDDifferentURLKeepsBoth(t *testing.T) {
	// Two addons with the same manifest ID but different config URLs should
	// both be retained — the second install must not overwrite the first.
	srv1 := httptest.NewServer(manifestHandler("shared.id", "Addon A"))
	defer srv1.Close()
	srv2 := httptest.NewServer(manifestHandler("shared.id", "Addon B"))
	defer srv2.Close()

	m := newTestManager(nil)

	e1, err := m.AddStremioAddon(context.Background(), srv1.URL)
	require.NoError(t, err)
	assert.Equal(t, "shared.id", e1.ID)

	e2, err := m.AddStremioAddon(context.Background(), srv2.URL)
	require.NoError(t, err)
	assert.Equal(t, "shared.id", e2.ID)

	m.mu.RLock()
	count := len(m.stremioAddons)
	m.mu.RUnlock()
	assert.Equal(t, 2, count, "both installs should be kept when URLs differ")
}

func TestAddStremioAddon_SameURLUpdatesInPlace(t *testing.T) {
	// Re-adding the same URL must refresh the manifest in place (no duplicate).
	var callCount int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := atomic.AddInt32(&callCount, 1)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"id":"same.url.id","name":"Addon v%d","version":"1.0.0","resources":["stream"],"types":["movie"]}`, n)
	}))
	defer srv.Close()

	m := newTestManager(nil)

	_, err := m.AddStremioAddon(context.Background(), srv.URL)
	require.NoError(t, err)

	e2, err := m.AddStremioAddon(context.Background(), srv.URL)
	require.NoError(t, err)

	m.mu.RLock()
	count := len(m.stremioAddons)
	m.mu.RUnlock()
	assert.Equal(t, 1, count, "re-adding the same URL should update in place, not append")
	assert.Equal(t, "Addon v2", e2.Manifest.Name, "manifest should be refreshed on re-add")
}

func TestRemoveAddon_URLRemovesOnlyMatchingEntry(t *testing.T) {
	// When two addons share a manifest ID, RemoveAddon with addonURL must
	// remove only the URL-matched entry, leaving the sibling intact.
	m := newTestManager([]AddonEntry{
		{ID: "dup.id", URL: "https://a.example.com", Kind: KindProvider, Source: SourceStremio, Enabled: true, Manifest: Manifest{ID: "dup.id"}},
		{ID: "dup.id", URL: "https://b.example.com", Kind: KindProvider, Source: SourceStremio, Enabled: true, Manifest: Manifest{ID: "dup.id"}},
	})

	err := m.RemoveAddon("dup.id", "https://a.example.com")
	require.NoError(t, err)

	m.mu.RLock()
	remaining := make([]AddonEntry, len(m.stremioAddons))
	copy(remaining, m.stremioAddons)
	m.mu.RUnlock()

	require.Len(t, remaining, 1, "only the URL-matched entry should be removed")
	assert.Equal(t, "https://b.example.com", remaining[0].URL)
}

func TestSetEnabled_URLTogglesOnlyMatchingEntry(t *testing.T) {
	// When two addons share a manifest ID, SetEnabled with addonURL must
	// toggle only the URL-matched entry.
	m := newTestManager([]AddonEntry{
		{ID: "dup.id", URL: "https://a.example.com", Kind: KindProvider, Source: SourceStremio, Enabled: true, Manifest: Manifest{ID: "dup.id"}},
		{ID: "dup.id", URL: "https://b.example.com", Kind: KindProvider, Source: SourceStremio, Enabled: true, Manifest: Manifest{ID: "dup.id"}},
	})

	err := m.SetEnabled("dup.id", "https://a.example.com", false)
	require.NoError(t, err)

	m.mu.RLock()
	enabledA := m.stremioAddons[0].Enabled
	enabledB := m.stremioAddons[1].Enabled
	m.mu.RUnlock()

	assert.False(t, enabledA, "URL-matched entry should be disabled")
	assert.True(t, enabledB, "sibling entry should remain enabled")
}

// ── GetAllStreamsPrefetch fault-isolation (the non-fatal invariant) ───────────

// TestGetAllStreamsPrefetch_FaultIsolation is the primary test for the
// documented contract: one broken addon (HTTP 500) and one hanging addon must
// not prevent the healthy addon's results from being returned.
func TestGetAllStreamsPrefetch_FaultIsolation(t *testing.T) {
	orig := fanOutDeadline
	fanOutDeadline = 200 * time.Millisecond
	t.Cleanup(func() { fanOutDeadline = orig })

	// Healthy addon: responds immediately with two streams.
	healthy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"streams":[{"name":"ok-1","title":"t","url":"http://ok/1"},{"name":"ok-2","title":"t","url":"http://ok/2"}]}`)
	}))
	defer healthy.Close()

	// Broken addon: returns HTTP 500 — FetchStreams returns an error.
	broken := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "internal error", http.StatusInternalServerError)
	}))
	defer broken.Close()

	// Hanging addon: never responds until the handler returns (blocked forever
	// from the test perspective; the fanCtx deadline unblocks the caller).
	hangs := make(chan struct{})
	hanging := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		<-hangs // blocks until the test is done — goroutine cleaned up by defer
	}))
	defer func() {
		close(hangs)
		hanging.Close()
	}()

	m := newTestManager([]AddonEntry{
		streamAddon("healthy", healthy.URL),
		streamAddon("broken", broken.URL),
		streamAddon("hanging", hanging.URL),
	})

	start := time.Now()
	streams, err := m.GetAllStreamsPrefetch(context.Background(), "movie", "tt404")
	elapsed := time.Since(start)

	require.NoError(t, err, "fan-out must never propagate individual addon errors")
	assert.Less(t, elapsed, 2*time.Second, "fan-out must not wait forever for the hanging addon")
	// Only the two streams from the healthy addon should be present.
	require.Len(t, streams, 2, "broken and hanging addons must not block the healthy result")
	for _, s := range streams {
		assert.Equal(t, "healthy", s.AddonName, "stream must be tagged with the healthy addon name")
	}
}

// ── GetEnabledCatalogs / SetCatalogEnabled round-trip ─────────────────────────

func catalogAddon(id, url string, cats []ManifestCatalog) AddonEntry {
	return AddonEntry{
		ID:      id,
		URL:     url,
		Kind:    KindProvider,
		Source:  SourceStremio,
		Enabled: true,
		Manifest: Manifest{
			ID:       id,
			Name:     id,
			Catalogs: cats,
		},
	}
}

func TestGetEnabledCatalogs_SetCatalogEnabled_RoundTrip(t *testing.T) {
	tmpDir := t.TempDir()
	storePath := filepath.Join(tmpDir, "addons.json")

	cats := []ManifestCatalog{
		{Type: "movie", ID: "top", Name: "Top Movies"},
		{Type: "series", ID: "popular", Name: "Popular Series"},
	}
	m := &Manager{
		client: &http.Client{},
		stremioAddons: []AddonEntry{
			catalogAddon("cat-addon", "https://example.com/cat", cats),
		},
		officialEnabled: make(map[string]bool),
		streamCache:     make(map[string]streamCacheEntry),
		storePath:       storePath,
	}

	// Both catalogs should be visible initially.
	refs := m.GetEnabledCatalogs()
	require.Len(t, refs, 2)
	keys := make([]string, 0, 2)
	for _, r := range refs {
		keys = append(keys, r.CatalogType+"/"+r.CatalogID)
	}
	assert.Contains(t, keys, "movie/top")
	assert.Contains(t, keys, "series/popular")

	// Disable the movie catalog.
	err := m.SetCatalogEnabled("cat-addon", "", "movie/top", false)
	require.NoError(t, err)

	refs = m.GetEnabledCatalogs()
	require.Len(t, refs, 1)
	assert.Equal(t, "popular", refs[0].CatalogID)

	// Re-enable it — should be back.
	err = m.SetCatalogEnabled("cat-addon", "", "movie/top", true)
	require.NoError(t, err)

	refs = m.GetEnabledCatalogs()
	require.Len(t, refs, 2)
}

func TestSetCatalogEnabled_ByURL(t *testing.T) {
	tmpDir := t.TempDir()
	storePath := filepath.Join(tmpDir, "addons.json")

	m := &Manager{
		client: &http.Client{},
		stremioAddons: []AddonEntry{
			catalogAddon("addon1", "https://a.example.com", []ManifestCatalog{
				{Type: "movie", ID: "top", Name: "Top"},
			}),
		},
		officialEnabled: make(map[string]bool),
		streamCache:     make(map[string]streamCacheEntry),
		storePath:       storePath,
	}

	// Match by URL — disable.
	err := m.SetCatalogEnabled("", "https://a.example.com", "movie/top", false)
	require.NoError(t, err)
	assert.Empty(t, m.GetEnabledCatalogs())
}

func TestSetCatalogEnabled_NotFound(t *testing.T) {
	m := newTestManager(nil)
	err := m.SetCatalogEnabled("does-not-exist", "", "movie/top", false)
	assert.ErrorContains(t, err, "addon not found")
}

// ── FindAddonURL / HasAddonURL ─────────────────────────────────────────────────

func TestFindAddonURL_Found(t *testing.T) {
	m := newTestManager([]AddonEntry{
		{ID: "my-addon", URL: "https://myaddon.example.com", Kind: KindProvider, Source: SourceStremio, Enabled: true, Manifest: Manifest{ID: "my-addon"}},
	})
	url, ok := m.FindAddonURL("my-addon")
	assert.True(t, ok)
	assert.Equal(t, "https://myaddon.example.com", url)
}

func TestFindAddonURL_NotFound(t *testing.T) {
	m := newTestManager(nil)
	_, ok := m.FindAddonURL("no-such-addon")
	assert.False(t, ok)
}

func TestHasAddonURL_True(t *testing.T) {
	m := newTestManager([]AddonEntry{
		{ID: "a", URL: "https://known.example.com", Kind: KindProvider, Source: SourceStremio, Enabled: true, Manifest: Manifest{ID: "a"}},
	})
	assert.True(t, m.HasAddonURL("https://known.example.com"))
}

func TestHasAddonURL_False(t *testing.T) {
	m := newTestManager(nil)
	assert.False(t, m.HasAddonURL("https://unknown.example.com"))
}

// ── GetTimestamps ──────────────────────────────────────────────────────────────

func TestGetTimestamps_Disabled(t *testing.T) {
	m := &Manager{
		client:          &http.Client{},
		officialEnabled: map[string]bool{"cove.introdb": false},
		streamCache:     make(map[string]streamCacheEntry),
	}
	data, err := m.GetTimestamps(12345, nil, nil)
	require.NoError(t, err)
	assert.NotNil(t, data)
	assert.Empty(t, data.Intro)
	assert.Empty(t, data.Credits)
}

func TestGetTimestamps_Enabled_Movie(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"intro":[{"start_ms":1000,"end_ms":90000}],"credits":[{"start_ms":3300000,"end_ms":3400000}]}`)
	}))
	defer srv.Close()

	origURL := introdDbURL
	introdDbURL = srv.URL
	t.Cleanup(func() { introdDbURL = origURL })

	m := &Manager{
		client:          &http.Client{},
		officialEnabled: map[string]bool{"cove.introdb": true},
		streamCache:     make(map[string]streamCacheEntry),
	}
	data, err := m.GetTimestamps(550, nil, nil)
	require.NoError(t, err)
	require.NotNil(t, data)
	require.Len(t, data.Intro, 1)
	assert.Equal(t, int64(1000), *data.Intro[0].StartMs)
	require.Len(t, data.Credits, 1)
}

func TestGetTimestamps_Enabled_TVWithImdbLookup(t *testing.T) {
	// theintrodb.org stub: has intro only.
	tmdbSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"intro":[{"start_ms":5000,"end_ms":85000}]}`)
	}))
	defer tmdbSrv.Close()

	// introdb.app stub: has recap (not in tmdbSrv) and intro (should be ignored — base wins).
	appSrv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"intro":{"start_ms":9999,"end_ms":99999},"recap":{"start_ms":0,"end_ms":30000},"outro":null}`)
	}))
	defer appSrv.Close()

	origURL := introdDbURL
	origAppURL := introdDbAppURL
	introdDbURL = tmdbSrv.URL
	introdDbAppURL = appSrv.URL
	t.Cleanup(func() {
		introdDbURL = origURL
		introdDbAppURL = origAppURL
	})

	season, episode := 1, 3
	m := &Manager{
		client:          &http.Client{},
		officialEnabled: map[string]bool{"cove.introdb": true},
		streamCache:     make(map[string]streamCacheEntry),
		imdbLookup:      func(tmdbID int) string { return "tt7654321" },
	}
	data, err := m.GetTimestamps(99, &season, &episode)
	require.NoError(t, err)
	require.NotNil(t, data)
	// Base intro from theintrodb.org is preserved.
	require.Len(t, data.Intro, 1)
	assert.Equal(t, int64(5000), *data.Intro[0].StartMs)
	// Recap comes from introdb.app fill.
	require.Len(t, data.Recap, 1)
}

func TestGetTimestamps_OfficialDefaultEnabled(t *testing.T) {
	// When officialEnabled map has no entry, isOfficialEnabledL must default to true.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{}`)
	}))
	defer srv.Close()

	origURL := introdDbURL
	introdDbURL = srv.URL
	t.Cleanup(func() { introdDbURL = origURL })

	m := &Manager{
		client:          &http.Client{},
		officialEnabled: make(map[string]bool), // empty — default-on
		streamCache:     make(map[string]streamCacheEntry),
	}
	data, err := m.GetTimestamps(1, nil, nil)
	require.NoError(t, err)
	assert.NotNil(t, data)
}

// ── GetWatchOptions ────────────────────────────────────────────────────────────

func TestGetWatchOptions_Disabled(t *testing.T) {
	m := &Manager{
		client:          &http.Client{},
		officialEnabled: map[string]bool{"cove.justwatch": false},
		streamCache:     make(map[string]streamCacheEntry),
	}
	opts, err := m.GetWatchOptions("movie", "550")
	require.NoError(t, err)
	assert.Empty(t, opts)
}

func TestGetWatchOptions_Enabled_Happy(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"results":{"US":{"flatrate":[{"provider_id":8,"provider_name":"Netflix","logo_path":"/netflix.jpg"}],"rent":[{"provider_id":2,"provider_name":"Apple TV","logo_path":"/apple.jpg"}],"link":"https://jw.link"}}}`)
	}))
	defer srv.Close()

	t.Setenv("TMDB_API_KEY", "test-key")
	origBase := watchOptionsBaseURL
	watchOptionsBaseURL = srv.URL
	origClient := watchOptionsClient
	watchOptionsClient = &http.Client{}
	t.Cleanup(func() {
		watchOptionsBaseURL = origBase
		watchOptionsClient = origClient
	})

	m := &Manager{
		client:          &http.Client{},
		officialEnabled: map[string]bool{"cove.justwatch": true},
		streamCache:     make(map[string]streamCacheEntry),
	}
	opts, err := m.GetWatchOptions("movie", "550")
	require.NoError(t, err)
	require.Len(t, opts, 2)
	assert.Equal(t, "flatrate", opts[0].Type)
	assert.Equal(t, "Netflix", opts[0].ProviderName)
	assert.Equal(t, "rent", opts[1].Type)
}

func TestGetWatchOptions_Enabled_NoUSResults(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"results":{"GB":{"flatrate":[],"link":"https://jw.link"}}}`)
	}))
	defer srv.Close()

	t.Setenv("TMDB_API_KEY", "test-key")
	origBase := watchOptionsBaseURL
	watchOptionsBaseURL = srv.URL
	origClient := watchOptionsClient
	watchOptionsClient = &http.Client{}
	t.Cleanup(func() {
		watchOptionsBaseURL = origBase
		watchOptionsClient = origClient
	})

	m := &Manager{
		client:          &http.Client{},
		officialEnabled: map[string]bool{"cove.justwatch": true},
		streamCache:     make(map[string]streamCacheEntry),
	}
	opts, err := m.GetWatchOptions("movie", "550")
	require.NoError(t, err)
	assert.Empty(t, opts)
}

// ── SetProfile ─────────────────────────────────────────────────────────────────

func TestSetProfile_LoadsFromFile(t *testing.T) {
	tmpDir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", tmpDir)

	// Write a store file that SetProfile will pick up via utils.ConfigPath.
	coveDir := filepath.Join(tmpDir, "cove")
	require.NoError(t, os.MkdirAll(coveDir, 0o755))
	storePath := filepath.Join(coveDir, "addons-testprofile.json")
	storeJSON := `{"stremioAddons":[{"id":"loaded-addon","url":"https://loaded.example.com","manifest":{"id":"loaded-addon","name":"Loaded"},"kind":"provider","source":"stremio","enabled":true}]}`
	require.NoError(t, os.WriteFile(storePath, []byte(storeJSON), 0o644))

	m := newTestManager(nil)
	err := m.SetProfile("testprofile")
	require.NoError(t, err)

	m.mu.RLock()
	count := len(m.stremioAddons)
	id := ""
	if count > 0 {
		id = m.stremioAddons[0].ID
	}
	m.mu.RUnlock()

	require.Equal(t, 1, count)
	assert.Equal(t, "loaded-addon", id)
}

// ── loadStore ──────────────────────────────────────────────────────────────────

func TestLoadStore_NotExist(t *testing.T) {
	s, err := loadStore(filepath.Join(t.TempDir(), "nonexistent.json"))
	require.NoError(t, err)
	assert.Empty(t, s.StremioAddons)
}

func TestLoadStore_MalformedJSON(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bad.json")
	require.NoError(t, os.WriteFile(path, []byte("not json {{{"), 0o644))
	_, err := loadStore(path)
	assert.Error(t, err)
}

func TestLoadStore_Valid(t *testing.T) {
	path := filepath.Join(t.TempDir(), "store.json")
	storeJSON := `{"stremioAddons":[{"id":"x","url":"https://x.com","manifest":{"id":"x","name":"X"},"kind":"provider","source":"stremio","enabled":true}],"officialEnabled":{"cove.justwatch":false}}`
	require.NoError(t, os.WriteFile(path, []byte(storeJSON), 0o644))
	s, err := loadStore(path)
	require.NoError(t, err)
	require.Len(t, s.StremioAddons, 1)
	assert.Equal(t, "x", s.StremioAddons[0].ID)
	assert.False(t, s.OfficialEnabled["cove.justwatch"])
}

func TestLoadStore_ZeroUpdatedAtAdoptsFileMtime(t *testing.T) {
	path := filepath.Join(t.TempDir(), "store.json")
	// Store with data but no updatedAt (zero time).
	storeJSON := `{"stremioAddons":[{"id":"y","url":"https://y.com","manifest":{"id":"y","name":"Y"},"kind":"provider","source":"stremio","enabled":true}]}`
	require.NoError(t, os.WriteFile(path, []byte(storeJSON), 0o644))
	s, err := loadStore(path)
	require.NoError(t, err)
	assert.False(t, s.UpdatedAt.IsZero(), "UpdatedAt must fall back to file mtime when the JSON field is zero")
}

// ── FetchCatalog / CatalogKey ──────────────────────────────────────────────────

func TestCatalogKey(t *testing.T) {
	c := ManifestCatalog{Type: "movie", ID: "top"}
	assert.Equal(t, "movie/top", c.CatalogKey())
}

func TestFetchCatalog_Skip0(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/catalog/movie/top.json", r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"metas":[{"id":"tt1","type":"movie","name":"Film One","poster":"","description":"","releaseInfo":"2024"}]}`)
	}))
	defer srv.Close()

	m := newTestManager(nil)
	metas, err := m.FetchCatalog(context.Background(), srv.URL, "movie", "top", 0)
	require.NoError(t, err)
	require.Len(t, metas, 1)
	assert.Equal(t, "tt1", metas[0].ID)
	assert.Equal(t, "Film One", metas[0].Name)
}

func TestFetchCatalog_SkipN(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/catalog/movie/top/skip=20.json", r.URL.Path)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"metas":[{"id":"tt2","type":"movie","name":"Film Two","poster":"","description":"","releaseInfo":"2023"}]}`)
	}))
	defer srv.Close()

	m := newTestManager(nil)
	metas, err := m.FetchCatalog(context.Background(), srv.URL, "movie", "top", 20)
	require.NoError(t, err)
	require.Len(t, metas, 1)
	assert.Equal(t, "tt2", metas[0].ID)
}

func TestFetchCatalog_NonOK(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "not found", http.StatusNotFound)
	}))
	defer srv.Close()

	m := newTestManager(nil)
	_, err := m.FetchCatalog(context.Background(), srv.URL, "movie", "top", 0)
	assert.Error(t, err)
}

func TestFetchCatalog_MalformedJSON(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{bad json`)
	}))
	defer srv.Close()

	m := newTestManager(nil)
	_, err := m.FetchCatalog(context.Background(), srv.URL, "movie", "top", 0)
	assert.Error(t, err)
}

func TestGetEnabledCatalogs_HomeEligibleFilter(t *testing.T) {
	// A catalog with a required non-skip extra must be excluded from home.
	searchOnlyCat := ManifestCatalog{
		Type:  "movie",
		ID:    "search",
		Name:  "Search Only",
		Extra: []ManifestCatalogExtra{{Name: "search", IsRequired: true}},
	}
	normalCat := ManifestCatalog{Type: "movie", ID: "normal", Name: "Normal"}

	m := newTestManager([]AddonEntry{
		catalogAddon("addon", "https://addon.example.com", []ManifestCatalog{searchOnlyCat, normalCat}),
	})

	refs := m.GetEnabledCatalogs()
	require.Len(t, refs, 1, "search-only catalog must be excluded")
	assert.Equal(t, "normal", refs[0].CatalogID)
}
