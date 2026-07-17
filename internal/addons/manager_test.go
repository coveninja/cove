package addons

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
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
