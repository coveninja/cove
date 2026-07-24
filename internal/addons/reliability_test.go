package addons

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"github.com/coveninja/cove/internal/utils"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestNewLoadsProfileAndRemainsUsableOnStoreErrors(t *testing.T) {
	configHome := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", configHome)
	t.Setenv("AppData", configHome)
	t.Setenv("HOME", configHome)

	path, err := utils.ConfigPath("addons-loaded.json")
	require.NoError(t, err)
	updatedAt := time.Now().Add(-time.Minute).UTC().Truncate(time.Millisecond)
	require.NoError(t, saveStore(path, addonStore{
		StremioAddons:   []AddonEntry{stremioEntry("loaded")},
		OfficialEnabled: map[string]bool{"cove.justwatch": false},
		UpdatedAt:       updatedAt,
	}))

	lookup := func(id int) string { return fmt.Sprintf("tt%d", id) }
	manager := New("loaded", lookup)
	require.NotNil(t, manager)
	assert.Equal(t, path, manager.storePath)
	assert.Equal(t, "tt7", manager.imdbLookup(7))
	assert.Equal(t, 30*time.Second, manager.client.Timeout)
	transport, ok := manager.client.Transport.(*http.Transport)
	require.True(t, ok)
	assert.Equal(t, 100, transport.MaxIdleConns)
	assert.Equal(t, 8, transport.MaxIdleConnsPerHost)
	assert.Equal(t, updatedAt, manager.UpdatedAt())

	entries := manager.GetEntries()
	assert.True(t, hasEntry(entries, "loaded"))
	assert.False(t, entryByID(entries, "cove.justwatch").Enabled)

	missing := New("missing", nil)
	require.NotNil(t, missing)
	assert.Empty(t, missing.stremioAddons)

	corruptPath, err := utils.ConfigPath("addons-corrupt.json")
	require.NoError(t, err)
	require.NoError(t, utils.AtomicWriteFile(corruptPath, []byte("{"), 0o600))
	corrupt := New("corrupt", nil)
	require.NotNil(t, corrupt)
	assert.Empty(t, corrupt.stremioAddons)
	assert.Equal(t, corruptPath, corrupt.storePath)

	unwritable := New("/../../", nil)
	require.NotNil(t, unwritable)
	assert.Empty(t, unwritable.storePath)
}

func TestGetEntriesReturnsDeepCopies(t *testing.T) {
	manager := newTestManager([]AddonEntry{{
		ID:      "deep",
		URL:     "https://deep.example",
		Enabled: true,
		Manifest: Manifest{
			ID:    "deep",
			Types: []string{"movie"},
			Resources: []ManifestResource{{
				Name:       "stream",
				Types:      []string{"movie"},
				IDPrefixes: []string{"tt"},
			}},
			Catalogs: []ManifestCatalog{{
				Type:  "movie",
				ID:    "top",
				Extra: []ManifestCatalogExtra{{Name: "skip"}},
			}},
		},
		DisabledCatalogs: map[string]bool{"movie/hidden": true},
	}})

	first := entryByID(manager.GetEntries(), "deep")
	first.Manifest.Types[0] = "tv"
	first.Manifest.Resources[0].Types[0] = "series"
	first.Manifest.Resources[0].IDPrefixes[0] = "kitsu"
	first.Manifest.Catalogs[0].Extra[0].Name = "search"
	first.DisabledCatalogs["movie/hidden"] = false

	second := entryByID(manager.GetEntries(), "deep")
	assert.Equal(t, "movie", second.Manifest.Types[0])
	assert.Equal(t, "movie", second.Manifest.Resources[0].Types[0])
	assert.Equal(t, "tt", second.Manifest.Resources[0].IDPrefixes[0])
	assert.Equal(t, "skip", second.Manifest.Catalogs[0].Extra[0].Name)
	assert.True(t, second.DisabledCatalogs["movie/hidden"])
}

func TestStreamCacheDeepCopiesNestedState(t *testing.T) {
	manager := newTestManager(nil)
	fileIndex := 2
	source := []Stream{{
		Name:      "original",
		Subtitles: []Subtitle{{ID: "sub", Lang: "en"}},
		Headers:   map[string]string{"Referer": "https://origin.example"},
		BehaviorHints: &StreamBehaviorHints{
			VideoSize: 100,
			Filename:  "movie.mkv",
		},
		FileIdx: &fileIndex,
	}}
	manager.streamCacheSet("key", source, time.Minute)

	source[0].Subtitles[0].Lang = "fr"
	source[0].Headers["Referer"] = "changed"
	source[0].BehaviorHints.Filename = "changed.mkv"
	*source[0].FileIdx = 9

	first, hit := manager.streamCacheGet("key")
	require.True(t, hit)
	assert.Equal(t, "en", first[0].Subtitles[0].Lang)
	assert.Equal(t, "https://origin.example", first[0].Headers["Referer"])
	assert.Equal(t, "movie.mkv", first[0].BehaviorHints.Filename)
	assert.Equal(t, 2, *first[0].FileIdx)

	first[0].Subtitles[0].Lang = "de"
	first[0].Headers["Referer"] = "mutated"
	first[0].BehaviorHints.Filename = "mutated.mkv"
	*first[0].FileIdx = 11

	second, hit := manager.streamCacheGet("key")
	require.True(t, hit)
	assert.Equal(t, "en", second[0].Subtitles[0].Lang)
	assert.Equal(t, "https://origin.example", second[0].Headers["Referer"])
	assert.Equal(t, "movie.mkv", second[0].BehaviorHints.Filename)
	assert.Equal(t, 2, *second[0].FileIdx)
}

func TestMutationsRollbackWhenPersistenceFails(t *testing.T) {
	initialTime := time.Now().Add(-time.Hour).UTC()
	failingPath := filepath.Join(t.TempDir(), "missing", "addons.json")

	newFailingManager := func() *Manager {
		manager := newTestManager([]AddonEntry{{
			ID:       "local",
			URL:      "https://local.example",
			Enabled:  true,
			Source:   SourceStremio,
			Kind:     KindProvider,
			Manifest: Manifest{ID: "local"},
			DisabledCatalogs: map[string]bool{
				"movie/existing": true,
			},
		}})
		manager.officialEnabled["cove.justwatch"] = true
		manager.updatedAt = initialTime
		manager.storePath = failingPath
		return manager
	}

	t.Run("add", func(t *testing.T) {
		srv := httptest.NewServer(manifestHandler("remote", "Remote"))
		defer srv.Close()
		manager := newFailingManager()

		_, err := manager.AddStremioAddon(context.Background(), srv.URL)
		require.Error(t, err)
		assert.False(t, hasEntry(manager.GetEntries(), "remote"))
		assert.Equal(t, initialTime, manager.UpdatedAt())
	})

	t.Run("remove", func(t *testing.T) {
		manager := newFailingManager()
		require.Error(t, manager.RemoveAddon("local", ""))
		assert.True(t, hasEntry(manager.GetEntries(), "local"))
		assert.Equal(t, initialTime, manager.UpdatedAt())
	})

	t.Run("toggle stremio", func(t *testing.T) {
		manager := newFailingManager()
		require.Error(t, manager.SetEnabled("local", "", false))
		assert.True(t, entryByID(manager.GetEntries(), "local").Enabled)
		assert.Equal(t, initialTime, manager.UpdatedAt())
	})

	t.Run("toggle official", func(t *testing.T) {
		manager := newFailingManager()
		require.Error(t, manager.SetEnabled("cove.justwatch", "", false))
		assert.True(t, entryByID(manager.GetEntries(), "cove.justwatch").Enabled)
		assert.Equal(t, initialTime, manager.UpdatedAt())
	})

	t.Run("toggle catalog", func(t *testing.T) {
		manager := newFailingManager()
		require.Error(t, manager.SetCatalogEnabled("local", "", "movie/top", false))
		entry := entryByID(manager.GetEntries(), "local")
		assert.True(t, entry.DisabledCatalogs["movie/existing"])
		assert.False(t, entry.DisabledCatalogs["movie/top"])
		assert.Equal(t, initialTime, manager.UpdatedAt())
	})

	t.Run("merge", func(t *testing.T) {
		manager := newFailingManager()
		manager.MergeFrom([]AddonEntry{
			stremioEntry("remote"),
			officialEntry("cove.justwatch", false),
		}, initialTime.Add(2*time.Hour))
		assert.True(t, hasEntry(manager.GetEntries(), "local"))
		assert.False(t, hasEntry(manager.GetEntries(), "remote"))
		assert.True(t, entryByID(manager.GetEntries(), "cove.justwatch").Enabled)
		assert.Equal(t, initialTime, manager.UpdatedAt())
	})
}

func TestSuccessfulInMemoryMutationAdvancesTimestamp(t *testing.T) {
	manager := newTestManager([]AddonEntry{stremioEntry("local")})
	require.True(t, manager.UpdatedAt().IsZero())
	require.NoError(t, manager.SetEnabled("local", "", false))
	assert.False(t, manager.UpdatedAt().IsZero())
}

func TestMergeFromRebuildsOfficialOverrides(t *testing.T) {
	manager := newTestManager(nil)
	manager.officialEnabled["stale.override"] = false
	manager.updatedAt = time.Now().Add(-time.Hour)

	manager.MergeFrom([]AddonEntry{officialEntry("cove.introdb", false)}, time.Now())

	manager.mu.RLock()
	_, stalePresent := manager.officialEnabled["stale.override"]
	introEnabled := manager.officialEnabled["cove.introdb"]
	manager.mu.RUnlock()
	assert.False(t, stalePresent)
	assert.False(t, introEnabled)
}

func hasEntry(entries []AddonEntry, id string) bool {
	for _, entry := range entries {
		if entry.ID == id {
			return true
		}
	}
	return false
}

func entryByID(entries []AddonEntry, id string) AddonEntry {
	for _, entry := range entries {
		if entry.ID == id {
			return entry
		}
	}
	return AddonEntry{}
}

// TestAddStremioAddonUpdateInPlaceRollsBackOnPersistFailure covers the
// update-existing branch of AddStremioAddon (manager.go lines 246-249): when a
// re-add matches an existing addon URL but persisting the update fails, the
// in-memory state is restored.
func TestAddStremioAddonUpdateInPlaceRollsBackOnPersistFailure(t *testing.T) {
	srv := httptest.NewServer(manifestHandler("remote", "Remote"))
	defer srv.Close()

	manager := newTestManager(nil)
	manager.storePath = filepath.Join(t.TempDir(), "addons.json")
	added, err := manager.AddStremioAddon(context.Background(), srv.URL)
	require.NoError(t, err)
	require.True(t, hasEntry(manager.GetEntries(), "remote"))

	// Break persistence, then re-add the same URL so the update-in-place path
	// runs and its save fails.
	manager.storePath = filepath.Join(t.TempDir(), "missing", "addons.json")
	before := manager.UpdatedAt()
	_, err = manager.AddStremioAddon(context.Background(), added.URL)
	require.Error(t, err)
	assert.True(t, hasEntry(manager.GetEntries(), "remote"), "the addon must survive a failed update")
	assert.Equal(t, before, manager.UpdatedAt())
}

// TestSetEnabledOfficialAddonSucceeds covers the success return of toggling an
// official addon (manager.go line 304).
func TestSetEnabledOfficialAddonSucceeds(t *testing.T) {
	manager := newTestManager(nil)
	require.NoError(t, manager.SetEnabled("cove.justwatch", "", true))
	assert.True(t, entryByID(manager.GetEntries(), "cove.justwatch").Enabled)
	require.NoError(t, manager.SetEnabled("cove.justwatch", "", false))
	assert.False(t, entryByID(manager.GetEntries(), "cove.justwatch").Enabled)
}

// TestCloneStreamsNilReturnsNil covers the nil guard in cloneStreams
// (manager.go lines 438-440).
func TestCloneStreamsNilReturnsNil(t *testing.T) {
	assert.Nil(t, cloneStreams(nil))
}
