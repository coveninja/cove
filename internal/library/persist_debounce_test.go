package library

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func titleOnDisk(t *testing.T, path, wantTitle string) bool {
	t.Helper()
	data, err := os.ReadFile(path)
	require.NoError(t, err)
	var disk diskStore
	require.NoError(t, json.Unmarshal(data, &disk))
	for _, e := range disk.Entries {
		if e.Title == wantTitle {
			return true
		}
	}
	return false
}

func postLibraryEntry(t *testing.T, mux *http.ServeMux, tmdbID int, title string) {
	t.Helper()
	body, err := json.Marshal(map[string]any{
		"tmdb_id": tmdbID, "media_type": "movie", "title": title, "status": "watch_later",
	})
	require.NoError(t, err)
	req := httptest.NewRequest(http.MethodPost, "/api/library", bytes.NewBuffer(body))
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	require.Equal(t, http.StatusOK, rr.Code)
}

func TestMarkDirty_DebouncesDiskWrite(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	before, err := os.ReadFile(l.path)
	require.NoError(t, err)

	postLibraryEntry(t, mux, 1, "Debounced")

	// Immediately after the handler returns, the write is only scheduled —
	// disk should not have changed yet.
	immediately, err := os.ReadFile(l.path)
	require.NoError(t, err)
	assert.Equal(t, before, immediately, "disk write should be debounced, not synchronous")

	// After the debounce window elapses, it should have landed.
	require.Eventually(t, func() bool {
		return titleOnDisk(t, l.path, "Debounced")
	}, 3*time.Second, 50*time.Millisecond)
}

func TestFlush_WritesImmediately(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	postLibraryEntry(t, mux, 2, "Flushed")
	l.Flush()

	assert.True(t, titleOnDisk(t, l.path, "Flushed"), "Flush should write the pending debounced state immediately")
}

func TestFlush_NoOpWhenNothingPending(t *testing.T) {
	l := newLib(t)
	// Should not panic or block when there's nothing dirty.
	l.Flush()
	l.Flush()
}

func TestMergeFrom_WritesSynchronously(t *testing.T) {
	l := newLib(t)
	l.MergeFrom([]*LibraryEntry{{
		ID: "id1", TmdbID: 3, MediaType: "movie", Title: "Synced",
		Status: StatusWatchLater, AddedAt: time.Now(), UpdatedAt: time.Now(),
	}}, nil, nil)

	assert.True(t, titleOnDisk(t, l.path, "Synced"), "MergeFrom should write to disk synchronously, not debounced")
}

func TestSetProfile_FlushesPendingWriteForOldProfile(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	l, err := New("profileA")
	require.NoError(t, err)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	postLibraryEntry(t, mux, 9, "PendingWrite")
	oldPath := l.path // capture before the swap changes it

	require.NoError(t, l.SetProfile("profileB"))

	assert.True(t, titleOnDisk(t, oldPath, "PendingWrite"),
		"SetProfile should flush the old profile's pending debounced write before swapping")
}
