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

// ── RenameFromRemote ──────────────────────────────────────────────────────────

func TestRenameFromRemote(t *testing.T) {
	s := newStore(t)
	active := s.ActiveProfile()
	remoteTime := time.Now().Add(-time.Hour)

	require.NoError(t, s.RenameFromRemote(active.ID, "Remote Name", remoteTime))

	p := s.ActiveProfile()
	assert.Equal(t, "Remote Name", p.Name)
	assert.Equal(t, remoteTime.UTC(), p.NameUpdatedAt.UTC())
}

func TestRenameFromRemote_NotFound(t *testing.T) {
	s := newStore(t)
	err := s.RenameFromRemote("nonexistent-id", "Name", time.Now())
	assert.Error(t, err)
}

// ── UnlinkSupabase ────────────────────────────────────────────────────────────

func TestLinkSupabase(t *testing.T) {
	s := newStore(t)
	active := s.ActiveProfile()

	require.NoError(t, s.LinkSupabase(active.ID, "supabase-uid-abc"))
	p, ok := s.Get(active.ID)
	require.True(t, ok)
	require.NotNil(t, p.SupabaseUID)
	assert.Equal(t, "supabase-uid-abc", *p.SupabaseUID)
}

func TestLinkSupabase_NotFound(t *testing.T) {
	s := newStore(t)
	err := s.LinkSupabase("nonexistent-id", "uid")
	assert.Error(t, err)
}

func TestUnlinkSupabase(t *testing.T) {
	s := newStore(t)
	active := s.ActiveProfile()

	require.NoError(t, s.LinkSupabase(active.ID, "user-uid-123"))
	p, _ := s.Get(active.ID)
	require.NotNil(t, p.SupabaseUID)
	assert.Equal(t, "user-uid-123", *p.SupabaseUID)

	require.NoError(t, s.UnlinkSupabase(active.ID))
	p, _ = s.Get(active.ID)
	assert.Nil(t, p.SupabaseUID, "SupabaseUID must be nil after UnlinkSupabase")
}

func TestUnlinkSupabase_NotFound(t *testing.T) {
	s := newStore(t)
	err := s.UnlinkSupabase("nonexistent-id")
	assert.Error(t, err)
}

// ── AdoptID ───────────────────────────────────────────────────────────────────

func TestAdoptID_ChangesProfileID(t *testing.T) {
	s := newStore(t)
	active := s.ActiveProfile()
	oldID := active.ID
	newID := "new-profile-id-1234"

	require.NoError(t, s.AdoptID(oldID, newID))

	_, ok := s.Get(oldID)
	assert.False(t, ok, "old ID must not exist after adoption")

	p, ok := s.Get(newID)
	require.True(t, ok, "new ID must exist after adoption")
	assert.Equal(t, newID, p.ID)
	assert.Equal(t, active.Name, p.Name)
}

func TestAdoptID_UpdatesActiveProfileID(t *testing.T) {
	s := newStore(t)
	active := s.ActiveProfile()
	oldID := active.ID
	newID := "new-active-id-5678"

	require.NoError(t, s.AdoptID(oldID, newID))
	assert.Equal(t, newID, s.ActiveProfileID())
}

func TestAdoptID_TriggersOnChange(t *testing.T) {
	ch := make(chan string, 1)
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	s, err := New(func(id string) { ch <- id })
	require.NoError(t, err)

	active := s.ActiveProfile()
	newID := "adopted-id-9999"

	require.NoError(t, s.AdoptID(active.ID, newID))

	select {
	case gotID := <-ch:
		assert.Equal(t, newID, gotID)
	case <-time.After(2 * time.Second):
		t.Fatal("onChange not fired after AdoptID")
	}
}

func TestAdoptID_RenamesPerProfileFiles(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	s, err := New(nil)
	require.NoError(t, err)

	active := s.ActiveProfile()
	oldID := active.ID
	newID := "file-adopted-id"

	for _, base := range []string{"library", "settings", "addons"} {
		path, ferr := utils.ConfigPath(ProfileFileName(base, oldID))
		require.NoError(t, ferr)
		require.NoError(t, os.WriteFile(path, []byte(`{}`), 0o644))
	}

	require.NoError(t, s.AdoptID(oldID, newID))

	for _, base := range []string{"library", "settings", "addons"} {
		newPath, ferr := utils.ConfigPath(ProfileFileName(base, newID))
		require.NoError(t, ferr)
		_, statErr := os.Stat(newPath)
		assert.NoError(t, statErr, "file %s-%s.json must exist after adoption", base, newID)

		oldPath, ferr := utils.ConfigPath(ProfileFileName(base, oldID))
		require.NoError(t, ferr)
		_, statErr = os.Stat(oldPath)
		assert.True(t, os.IsNotExist(statErr), "file %s-%s.json must not exist after adoption", base, oldID)
	}
}

func TestAdoptID_InvalidNewID(t *testing.T) {
	s := newStore(t)
	active := s.ActiveProfile()
	err := s.AdoptID(active.ID, "invalid/id")
	assert.Error(t, err)
}

func TestAdoptID_ConflictWithExistingID(t *testing.T) {
	s := newStore(t)
	active := s.ActiveProfile()

	kid, err := s.Create("Kid")
	require.NoError(t, err)

	err = s.AdoptID(active.ID, kid.ID)
	assert.Error(t, err, "AdoptID must fail if newID already exists")
}

func TestAdoptID_NotFound(t *testing.T) {
	s := newStore(t)
	err := s.AdoptID("nonexistent-id", "some-new-id")
	assert.Error(t, err)
}

// ── SetOnDelete ───────────────────────────────────────────────────────────────

func TestSetOnDelete_FiresAfterHTTPDelete(t *testing.T) {
	type deleteCall struct {
		id  string
		uid *string
		jwt string
	}
	ch := make(chan deleteCall, 1)

	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	s, err := New(nil)
	require.NoError(t, err)
	s.SetOnDelete(func(profileID string, supabaseUID *string, userJWT string) {
		ch <- deleteCall{profileID, supabaseUID, userJWT}
	})

	mux := http.NewServeMux()
	s.SetupHandlers(mux)

	kid, err := s.Create("Kid")
	require.NoError(t, err)

	req := httptest.NewRequest(http.MethodDelete, "/api/profiles/"+kid.ID, nil)
	req.Header.Set("Authorization", "Bearer test-jwt-token")
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	assert.Equal(t, http.StatusNoContent, rr.Code)

	select {
	case got := <-ch:
		assert.Equal(t, kid.ID, got.id)
		assert.Equal(t, "test-jwt-token", got.jwt)
		assert.Nil(t, got.uid, "SupabaseUID should be nil for an unlinked profile")
	case <-time.After(2 * time.Second):
		t.Fatal("onDelete was not called after profile deletion")
	}
}

func TestSetOnDelete_FiresWithSupabaseUID(t *testing.T) {
	type deleteCall struct {
		id  string
		uid *string
	}
	ch := make(chan deleteCall, 1)

	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	s, err := New(nil)
	require.NoError(t, err)
	s.SetOnDelete(func(profileID string, supabaseUID *string, _ string) {
		ch <- deleteCall{profileID, supabaseUID}
	})

	mux := http.NewServeMux()
	s.SetupHandlers(mux)

	kid, err := s.Create("LinkedKid")
	require.NoError(t, err)
	require.NoError(t, s.LinkSupabase(kid.ID, "uid-for-linked-kid"))

	req := httptest.NewRequest(http.MethodDelete, "/api/profiles/"+kid.ID, nil)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	assert.Equal(t, http.StatusNoContent, rr.Code)

	select {
	case got := <-ch:
		assert.Equal(t, kid.ID, got.id)
		require.NotNil(t, got.uid)
		assert.Equal(t, "uid-for-linked-kid", *got.uid)
	case <-time.After(2 * time.Second):
		t.Fatal("onDelete was not called")
	}
}

// ── bearerFromRequest ─────────────────────────────────────────────────────────

func TestBearerFromRequest_ValidToken(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set("Authorization", "Bearer my-jwt-token")
	assert.Equal(t, "my-jwt-token", bearerFromRequest(req))
}

func TestBearerFromRequest_MissingHeader(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	assert.Equal(t, "", bearerFromRequest(req))
}

func TestBearerFromRequest_WrongScheme(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set("Authorization", "Basic dXNlcjpwYXNz")
	assert.Equal(t, "", bearerFromRequest(req))
}

func TestBearerFromRequest_EmptyToken(t *testing.T) {
	// "Bearer " (with trailing space) — prefix matches, but the trimmed value is "".
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set("Authorization", "Bearer ")
	assert.Equal(t, "", bearerFromRequest(req))
}

func TestBearerFromRequest_MalformedNoSpace(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set("Authorization", "BearerNoSpace")
	assert.Equal(t, "", bearerFromRequest(req))
}
