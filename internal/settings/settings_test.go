package settings

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestNew_Defaults(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	st, err := New("test")
	require.NoError(t, err)

	s := st.Get()
	assert.Equal(t, defaultSettings, s)
}

func TestNew_LoadExisting(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())

	// First create with defaults
	st, err := New("test")
	require.NoError(t, err)

	// Change one field and persist via MergeFrom. MergeFrom only accepts an
	// incoming value newer than what's cached, so it needs a fresher UpdatedAt
	// to simulate a real remote pull.
	modified := st.Get()
	modified.DefaultVolume = 0.5
	modified.AutoPlay = true
	modified.UpdatedAt = time.Now()
	st.MergeFrom(modified)

	// New store loading the same profile should see the saved values
	st2, err := New("test")
	require.NoError(t, err)
	s := st2.Get()
	assert.Equal(t, 0.5, s.DefaultVolume)
	assert.True(t, s.AutoPlay)
	// Other defaults preserved
	assert.Equal(t, defaultSettings.DefaultProvider, s.DefaultProvider)
}

func TestMergeFrom(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	st, err := New("test")
	require.NoError(t, err)

	updated := st.Get()
	updated.HideSpoilers = true
	updated.SubtitleSize = 150
	updated.UpdatedAt = time.Now()
	st.MergeFrom(updated)

	s := st.Get()
	assert.True(t, s.HideSpoilers)
	assert.Equal(t, float64(150), s.SubtitleSize)
}

// TestMergeFrom_RejectsStale reproduces the onboarding-reappears bug: a pull that
// arrives with an older UpdatedAt than what's cached must not revert a local write
// that hasn't been pushed to Supabase yet.
func TestMergeFrom_RejectsStale(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	st, err := New("test")
	require.NoError(t, err)

	// Simulate a local edit (e.g. completing onboarding) stamped "now".
	local := st.Get()
	local.OnboardingDone = true
	local.UpdatedAt = time.Now()
	st.MergeFrom(local)

	// A stale remote pull, timestamped before the local edit, must not win.
	stale := st.Get()
	stale.OnboardingDone = false
	stale.UpdatedAt = local.UpdatedAt.Add(-time.Hour)
	st.MergeFrom(stale)

	assert.True(t, st.Get().OnboardingDone, "stale incoming merge must not revert a newer local write")
}

// TestMergeFrom_AcceptsNewer confirms genuine cross-device sync still works:
// an incoming value newer than the cached one is accepted.
func TestMergeFrom_AcceptsNewer(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	st, err := New("test")
	require.NoError(t, err)

	local := st.Get()
	local.OnboardingDone = false
	local.UpdatedAt = time.Now()
	st.MergeFrom(local)

	newer := st.Get()
	newer.OnboardingDone = true
	newer.UpdatedAt = local.UpdatedAt.Add(time.Hour)
	st.MergeFrom(newer)

	assert.True(t, st.Get().OnboardingDone, "a genuinely newer incoming merge must be accepted")
}

func TestSetProfile(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())

	st, err := New("primary")
	require.NoError(t, err)

	// Modify primary profile
	m := st.Get()
	m.DefaultVolume = 0.3
	m.UpdatedAt = time.Now()
	st.MergeFrom(m)
	require.Equal(t, 0.3, st.Get().DefaultVolume)

	// Switch to a fresh kid profile
	require.NoError(t, st.SetProfile("kid"))
	assert.Equal(t, defaultSettings.DefaultVolume, st.Get().DefaultVolume)
}

func TestHandlers_GetSettings(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	st, err := New("test")
	require.NoError(t, err)

	mux := http.NewServeMux()
	st.SetupHandlers(mux)

	req := httptest.NewRequest(http.MethodGet, "/api/settings", nil)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)

	assert.Equal(t, http.StatusOK, rr.Code)

	var s Settings
	require.NoError(t, json.NewDecoder(rr.Body).Decode(&s))
	assert.Equal(t, defaultSettings, s)
}

func TestHandlers_PutSettings(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	st, err := New("test")
	require.NoError(t, err)

	mux := http.NewServeMux()
	st.SetupHandlers(mux)

	updated := st.Get()
	updated.AutoPlay = true
	updated.DefaultVolume = 0.7
	body, err := json.Marshal(updated)
	require.NoError(t, err)

	req := httptest.NewRequest(http.MethodPut, "/api/settings", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)

	assert.Equal(t, http.StatusOK, rr.Code)
	assert.True(t, st.Get().AutoPlay)
	assert.Equal(t, 0.7, st.Get().DefaultVolume)
}
