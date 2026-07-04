package profiles

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"github.com/coveninja/cove/internal/utils"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func newStore(t *testing.T) *Store {
	t.Helper()
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	s, err := New(nil)
	require.NoError(t, err)
	return s
}

func TestNew_CreatesPrimaryProfile(t *testing.T) {
	s := newStore(t)
	profiles := s.All()
	require.Len(t, profiles, 1)
	assert.Equal(t, "Primary", profiles[0].Name)
	assert.True(t, profiles[0].IsPrimary)
	assert.Equal(t, profiles[0].ID, s.ActiveProfileID())
}

func TestCreate(t *testing.T) {
	s := newStore(t)
	p, err := s.Create("Kid")
	require.NoError(t, err)
	assert.Equal(t, "Kid", p.Name)
	assert.Len(t, s.All(), 2)
}

func TestRename(t *testing.T) {
	s := newStore(t)
	active := s.ActiveProfile()
	require.NoError(t, s.Rename(active.ID, "Renamed"))
	assert.Equal(t, "Renamed", s.ActiveProfile().Name)
}

func TestDelete(t *testing.T) {
	s := newStore(t)
	kid, err := s.Create("Kid")
	require.NoError(t, err)
	require.NoError(t, s.Delete(kid.ID))
	assert.Len(t, s.All(), 1)
	// Cannot delete last profile
	last := s.All()[0]
	assert.Error(t, s.Delete(last.ID))
}

func TestSetActive(t *testing.T) {
	ch := make(chan string, 1)
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	s, err := New(func(id string) { ch <- id })
	require.NoError(t, err)

	kid, err := s.Create("Kid")
	require.NoError(t, err)

	require.NoError(t, s.SetActive(kid.ID))
	assert.Equal(t, kid.ID, s.ActiveProfileID())

	gotID := <-ch
	assert.Equal(t, kid.ID, gotID)
}

func TestHandlers_GetProfiles(t *testing.T) {
	s := newStore(t)
	mux := http.NewServeMux()
	s.SetupHandlers(mux)

	req := httptest.NewRequest(http.MethodGet, "/api/profiles", nil)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)

	assert.Equal(t, http.StatusOK, rr.Code)
	var result struct {
		Profiles        []Profile `json:"profiles"`
		ActiveProfileID string    `json:"active_profile_id"`
	}
	require.NoError(t, json.NewDecoder(rr.Body).Decode(&result))
	assert.Len(t, result.Profiles, 1)
	assert.Equal(t, result.ActiveProfileID, result.Profiles[0].ID)
}

func TestDelete_RemovesPerProfileFiles(t *testing.T) {
	s := newStore(t)
	kid, err := s.Create("Kid")
	require.NoError(t, err)

	// Write dummy data files for the secondary profile.
	for _, base := range []string{"library", "settings", "addons", "nuvio", "activity"} {
		path, err := utils.ConfigPath(ProfileFileName(base, kid.ID))
		require.NoError(t, err)
		require.NoError(t, os.WriteFile(path, []byte("{}"), 0o644))
	}

	require.NoError(t, s.Delete(kid.ID))
	assert.Len(t, s.All(), 1)

	for _, base := range []string{"library", "settings", "addons", "nuvio", "activity"} {
		path, err := utils.ConfigPath(ProfileFileName(base, kid.ID))
		require.NoError(t, err)
		_, statErr := os.Stat(path)
		assert.True(t, os.IsNotExist(statErr), "expected %s to be removed", path)
	}
}

func TestDelete_ActiveFallbackFiresOnChange(t *testing.T) {
	ch := make(chan string, 1)
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	s, err := New(func(id string) { ch <- id })
	require.NoError(t, err)

	primary := s.ActiveProfile()

	kid, err := s.Create("Kid")
	require.NoError(t, err)

	require.NoError(t, s.SetActive(kid.ID))
	// Drain the SetActive onChange event.
	<-ch

	require.NoError(t, s.Delete(kid.ID))

	select {
	case gotID := <-ch:
		assert.Equal(t, primary.ID, gotID)
	case <-time.After(2 * time.Second):
		t.Fatal("onChange not fired after deleting active profile")
	}
	assert.Equal(t, primary.ID, s.ActiveProfileID())
}

func TestHandleDelete(t *testing.T) {
	s := newStore(t)
	mux := http.NewServeMux()
	s.SetupHandlers(mux)

	// Create a secondary profile.
	kid, err := s.Create("Kid")
	require.NoError(t, err)

	// DELETE secondary → 204.
	req := httptest.NewRequest(http.MethodDelete, "/api/profiles/"+kid.ID, nil)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	assert.Equal(t, http.StatusNoContent, rr.Code)
	assert.Len(t, s.All(), 1)

	// DELETE primary → 400.
	primary := s.All()[0]
	req = httptest.NewRequest(http.MethodDelete, "/api/profiles/"+primary.ID, nil)
	rr = httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	assert.Equal(t, http.StatusBadRequest, rr.Code)
}
