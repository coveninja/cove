package settings

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
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

// TestMergeFrom_PreservesDeviceLocalNetworkFields verifies that a Supabase
// pull never overwrites settings controlling this device's LAN exposure.
func TestMergeFrom_PreservesDeviceLocalNetworkFields(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	st, err := New("test")
	require.NoError(t, err)

	// Establish local remote-access config (device-specific).
	// Use Set (not MergeFrom) to simulate a local user action — MergeFrom is
	// for incoming remote pulls and now preserves the cached values of these
	// fields, so it cannot be used to write them in the first place.
	local := st.Get()
	local.RemoteAccessEnabled = true
	local.RemoteAccessToken = "local-device-token"
	local.AllowLanStreamSources = true
	require.NoError(t, st.Set(local))
	require.True(t, st.Get().RemoteAccessEnabled)
	require.Equal(t, "local-device-token", st.Get().RemoteAccessToken)
	require.True(t, st.Get().AllowLanStreamSources)

	// A newer remote pull with different remote-access values arrives.
	remote := st.Get()
	remote.RemoteAccessEnabled = false        // remote device has it disabled
	remote.RemoteAccessToken = "remote-token" // remote device's token
	remote.AllowLanStreamSources = false      // remote device rejects LAN sources
	remote.HideSpoilers = true                // some regular setting that should merge
	remote.UpdatedAt = st.Get().UpdatedAt.Add(time.Hour)
	st.MergeFrom(remote)

	s := st.Get()
	// Regular setting from the remote pull should win (it's newer).
	assert.True(t, s.HideSpoilers, "newer remote regular setting must be accepted")
	// Device-local remote-access config must NOT have been overwritten.
	assert.True(t, s.RemoteAccessEnabled, "RemoteAccessEnabled must be preserved from local")
	assert.Equal(t, "local-device-token", s.RemoteAccessToken, "RemoteAccessToken must be preserved from local")
	assert.True(t, s.AllowLanStreamSources, "AllowLanStreamSources must be preserved from local")
}

func TestDeviceLocalChangesDoNotAdvanceRoamingTimestamp(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	st, err := New("test")
	require.NoError(t, err)

	roaming := st.Get()
	roaming.HideSpoilers = true
	require.NoError(t, st.Set(roaming))
	roamingUpdatedAt := st.Get().UpdatedAt
	require.False(t, roamingUpdatedAt.IsZero())

	deviceLocal := st.Get()
	deviceLocal.RemoteAccessEnabled = true
	deviceLocal.RemoteAccessToken = "local-token"
	deviceLocal.AllowLanStreamSources = true
	require.NoError(t, st.Set(deviceLocal))
	assert.Equal(t, roamingUpdatedAt, st.Get().UpdatedAt)

	nextRoaming := st.Get()
	nextRoaming.AutoPlay = true
	require.NoError(t, st.Set(nextRoaming))
	assert.True(t, st.Get().UpdatedAt.After(roamingUpdatedAt))
}

func TestRemoteAccessTokenPolicy(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	st, err := New("test")
	require.NoError(t, err)

	enabled := st.Get()
	enabled.RemoteAccessEnabled = true
	require.NoError(t, st.Set(enabled))

	generated := st.Get().RemoteAccessToken
	require.Len(t, generated, 64)
	_, err = hex.DecodeString(generated)
	require.NoError(t, err)
	assert.True(t, st.Get().UpdatedAt.IsZero(), "device-local changes must not advance roaming state")

	withoutToken := st.Get()
	withoutToken.RemoteAccessToken = ""
	require.NoError(t, st.Set(withoutToken))
	assert.Equal(t, generated, st.Get().RemoteAccessToken, "an omitted token must preserve the generated secret")

	disabledMasked := st.Get()
	disabledMasked.RemoteAccessEnabled = false
	disabledMasked.RemoteAccessToken = "***"
	require.NoError(t, st.Set(disabledMasked))
	assert.Equal(t, generated, st.Get().RemoteAccessToken, "the GET sentinel must never be persisted")

	explicit := st.Get()
	explicit.RemoteAccessEnabled = true
	explicit.RemoteAccessToken = "explicit-device-token"
	require.NoError(t, st.Set(explicit))
	assert.Equal(t, "explicit-device-token", st.Get().RemoteAccessToken)
}

func TestSetOnChangeReceivesSuccessfulSnapshot(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	st, err := New("test")
	require.NoError(t, err)

	changed := make(chan Settings, 1)
	st.SetOnChange(func(snapshot Settings) {
		changed <- snapshot
	})

	next := st.Get()
	next.AutoPlay = true
	require.NoError(t, st.Set(next))

	select {
	case snapshot := <-changed:
		assert.True(t, snapshot.AutoPlay)
	case <-time.After(time.Second):
		t.Fatal("settings onChange hook did not run")
	}

	st.SetOnChange(nil)
}

func TestWriteFailuresRollbackCachedSettings(t *testing.T) {
	missingParent := filepath.Join(t.TempDir(), "missing", "settings.json")
	st := &Store{cached: defaultSettings, path: missingParent}

	next := st.Get()
	next.AutoPlay = true
	require.Error(t, st.Set(next))
	assert.False(t, st.Get().AutoPlay, "Set must not expose settings that failed to persist")

	remote := st.Get()
	remote.HideSpoilers = true
	remote.UpdatedAt = time.Now().Add(time.Hour)
	st.MergeFrom(remote)
	assert.False(t, st.Get().HideSpoilers, "MergeFrom must roll back a failed persistence attempt")
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

func TestHandlers_TokenMaskRevealAndOnboardingRatchet(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	st, err := New("test")
	require.NoError(t, err)

	initial := st.Get()
	initial.OnboardingDone = true
	initial.RemoteAccessEnabled = true
	initial.RemoteAccessToken = "real-device-token"
	require.NoError(t, st.Set(initial))

	mux := http.NewServeMux()
	st.SetupHandlers(mux)

	get := httptest.NewRequest(http.MethodGet, "/api/settings", nil)
	getResult := httptest.NewRecorder()
	mux.ServeHTTP(getResult, get)
	require.Equal(t, http.StatusOK, getResult.Code)
	var masked Settings
	require.NoError(t, json.NewDecoder(getResult.Body).Decode(&masked))
	assert.Equal(t, "***", masked.RemoteAccessToken)

	reveal := httptest.NewRequest(http.MethodPost, "/api/settings/reveal-token", nil)
	revealResult := httptest.NewRecorder()
	mux.ServeHTTP(revealResult, reveal)
	require.Equal(t, http.StatusOK, revealResult.Code)
	var tokenResponse map[string]string
	require.NoError(t, json.NewDecoder(revealResult.Body).Decode(&tokenResponse))
	assert.Equal(t, "real-device-token", tokenResponse["token"])

	wrongRevealMethod := httptest.NewRequest(http.MethodGet, "/api/settings/reveal-token", nil)
	wrongRevealResult := httptest.NewRecorder()
	mux.ServeHTTP(wrongRevealResult, wrongRevealMethod)
	assert.Equal(t, http.StatusMethodNotAllowed, wrongRevealResult.Code)

	stale := masked
	stale.OnboardingDone = false
	body, err := json.Marshal(stale)
	require.NoError(t, err)
	put := httptest.NewRequest(http.MethodPut, "/api/settings", bytes.NewReader(body))
	putResult := httptest.NewRecorder()
	mux.ServeHTTP(putResult, put)
	require.Equal(t, http.StatusOK, putResult.Code)
	assert.True(t, st.Get().OnboardingDone)
	assert.Equal(t, "real-device-token", st.Get().RemoteAccessToken)
}

func TestHandlers_SettingsErrorsDoNotMutateCache(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	st := &Store{
		cached: defaultSettings,
		path:   filepath.Join(t.TempDir(), "missing", "settings.json"),
	}
	mux := http.NewServeMux()
	st.SetupHandlers(mux)

	invalid := httptest.NewRequest(http.MethodPut, "/api/settings", strings.NewReader("{"))
	invalidResult := httptest.NewRecorder()
	mux.ServeHTTP(invalidResult, invalid)
	assert.Equal(t, http.StatusBadRequest, invalidResult.Code)

	wrongMethod := httptest.NewRequest(http.MethodDelete, "/api/settings", nil)
	wrongMethodResult := httptest.NewRecorder()
	mux.ServeHTTP(wrongMethodResult, wrongMethod)
	assert.Equal(t, http.StatusMethodNotAllowed, wrongMethodResult.Code)

	next := defaultSettings
	next.AutoPlay = true
	body, err := json.Marshal(next)
	require.NoError(t, err)
	failedWrite := httptest.NewRequest(http.MethodPut, "/api/settings", bytes.NewReader(body))
	failedWriteResult := httptest.NewRecorder()
	mux.ServeHTTP(failedWriteResult, failedWrite)
	assert.Equal(t, http.StatusInternalServerError, failedWriteResult.Code)
	assert.False(t, st.Get().AutoPlay, "handler must roll back settings after a failed write")
}

// TestHandlers_MpvConf covers GET (no file), PUT+GET round-trip, and oversize body.
func TestHandlers_MpvConf(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	st, err := New("test")
	require.NoError(t, err)

	mux := http.NewServeMux()
	st.SetupHandlers(mux)

	// GET when the file doesn't exist yet — should return the empty JSON string.
	t.Run("GET_no_file", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodGet, "/api/settings/mpv-conf", nil)
		rr := httptest.NewRecorder()
		mux.ServeHTTP(rr, req)

		assert.Equal(t, http.StatusOK, rr.Code)
		assert.Equal(t, "application/json", rr.Header().Get("Content-Type"))

		var got string
		require.NoError(t, json.NewDecoder(rr.Body).Decode(&got))
		assert.Equal(t, "", got)
	})

	// PUT then GET round-trips content including quotes, backslashes, and newlines.
	t.Run("PUT_GET_roundtrip", func(t *testing.T) {
		content := "# hwdec=auto\n# volume=80\nvo=gpu\npath=\"C:\\\\test\"\n"
		body, err := json.Marshal(content)
		require.NoError(t, err)

		req := httptest.NewRequest(http.MethodPut, "/api/settings/mpv-conf", bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		rr := httptest.NewRecorder()
		mux.ServeHTTP(rr, req)

		assert.Equal(t, http.StatusNoContent, rr.Code)

		req2 := httptest.NewRequest(http.MethodGet, "/api/settings/mpv-conf", nil)
		rr2 := httptest.NewRecorder()
		mux.ServeHTTP(rr2, req2)

		assert.Equal(t, http.StatusOK, rr2.Code)
		var got string
		require.NoError(t, json.NewDecoder(rr2.Body).Decode(&got))
		assert.Equal(t, content, got)
	})

	// PUT with a body over 1 MiB — should return a 4xx error.
	t.Run("PUT_oversize_body", func(t *testing.T) {
		// Build a string just over 1 MiB, then JSON-encode it.
		big := strings.Repeat("x", (1<<20)+1)
		body, err := json.Marshal(big)
		require.NoError(t, err)

		req := httptest.NewRequest(http.MethodPut, "/api/settings/mpv-conf", bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		rr := httptest.NewRecorder()
		mux.ServeHTTP(rr, req)

		assert.GreaterOrEqual(t, rr.Code, 400)
		assert.Less(t, rr.Code, 500)
	})

	// Method other than GET/PUT — should return 405.
	t.Run("wrong_method", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodDelete, "/api/settings/mpv-conf", nil)
		rr := httptest.NewRecorder()
		mux.ServeHTTP(rr, req)

		assert.Equal(t, http.StatusMethodNotAllowed, rr.Code)
	})
}
