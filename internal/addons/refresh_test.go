package addons

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// refreshManifestHandler serves a manifest that carries catalogs and a
// configurable behaviorHints flag, so RefreshAddon tests can assert both the
// new field parses and that stale catalog opt-outs are pruned. The plain
// manifestHandler in manager_test.go emits neither.
func refreshManifestHandler(id, name string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/manifest.json" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"id":%q,"name":%q,"version":"2.0.0","resources":["stream"],"types":["movie"],"behaviorHints":{"configurable":true},"catalogs":[{"type":"movie","id":"keep","name":"Keep"}]}`, id, name)
	}
}

func TestRefreshAddon(t *testing.T) {
	t.Run("updates manifest, preserves enabled, prunes stale catalogs", func(t *testing.T) {
		srv := httptest.NewServer(refreshManifestHandler("refresh.me", "New Name"))
		defer srv.Close()

		m := newTestManager([]AddonEntry{{
			ID:       "refresh.me",
			URL:      srv.URL,
			Kind:     KindProvider,
			Source:   SourceStremio,
			Enabled:  false, // non-default: a plain re-add would flip this back to true
			Manifest: Manifest{ID: "refresh.me", Name: "Old Name"},
			DisabledCatalogs: map[string]bool{
				"movie/keep":  true, // still present after refresh — must survive
				"movie/stale": true, // gone after refresh — must be pruned
			},
		}})

		entry, err := m.RefreshAddon(context.Background(), "refresh.me", "")
		require.NoError(t, err)

		// Manifest and Kind are refreshed.
		assert.Equal(t, "New Name", entry.Manifest.Name)
		assert.Equal(t, "2.0.0", entry.Manifest.Version)
		assert.Equal(t, KindProvider, entry.Kind)

		// The new behaviorHints.configurable field parses end-to-end.
		require.NotNil(t, entry.Manifest.BehaviorHints)
		assert.True(t, entry.Manifest.BehaviorHints.Configurable)

		// Enabled state is preserved (the whole point of a dedicated refresh).
		assert.False(t, entry.Enabled, "Enabled must be preserved across refresh")

		// Valid catalog opt-out kept; stale one pruned.
		assert.True(t, entry.DisabledCatalogs["movie/keep"], "valid catalog opt-out preserved")
		_, staleExists := entry.DisabledCatalogs["movie/stale"]
		assert.False(t, staleExists, "stale catalog opt-out should be pruned")
	})

	t.Run("matches by URL when id is empty", func(t *testing.T) {
		srv := httptest.NewServer(refreshManifestHandler("by.url", "Fresh"))
		defer srv.Close()

		m := newTestManager([]AddonEntry{{
			ID:       "by.url",
			URL:      srv.URL,
			Kind:     KindProvider,
			Source:   SourceStremio,
			Enabled:  true,
			Manifest: Manifest{ID: "by.url", Name: "Stale"},
		}})

		entry, err := m.RefreshAddon(context.Background(), "", srv.URL)
		require.NoError(t, err)
		assert.Equal(t, "Fresh", entry.Manifest.Name)
	})

	t.Run("returns error when addon not found", func(t *testing.T) {
		m := newTestManager(nil)
		_, err := m.RefreshAddon(context.Background(), "missing", "")
		require.Error(t, err)
		assert.Contains(t, err.Error(), "not found")
	})

	t.Run("refuses official addons", func(t *testing.T) {
		require.NotEmpty(t, officialAddons, "expected at least one built-in addon")
		m := newTestManager(nil)
		_, err := m.RefreshAddon(context.Background(), officialAddons[0].ID, "")
		require.Error(t, err)
	})
}
