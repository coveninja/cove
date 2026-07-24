package mobile

import (
	"errors"
	"sync/atomic"
	"testing"

	"github.com/coveninja/cove/internal/server"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type fakeBackendHandle struct {
	stops atomic.Int32
}

func (h *fakeBackendHandle) Stop() {
	h.stops.Add(1)
}

func resetMobileGlobals(t *testing.T) {
	t.Helper()
	mu.Lock()
	oldHandle := handle
	oldStartServer := startServer
	handle = nil
	mu.Unlock()
	t.Cleanup(func() {
		mu.Lock()
		handle = oldHandle
		startServer = oldStartServer
		mu.Unlock()
	})
}

func TestStartStopLifecycleAndConfigForwarding(t *testing.T) {
	resetMobileGlobals(t)
	fake := &fakeBackendHandle{}
	var got server.Config
	startServer = func(cfg server.Config) (backendHandle, error) {
		got = cfg
		return fake, nil
	}

	err := Start(
		"127.0.0.1:6969", "/data", "/cache", "/torrents", "tmdb",
		"supabase", "anon", "trakt-id", "trakt-secret", "v1.2.3",
	)
	require.NoError(t, err)
	assert.True(t, IsRunning())
	assert.Equal(t, server.Config{
		BindAddr:          "127.0.0.1:6969",
		DataDir:           "/data",
		CacheDir:          "/cache",
		TorrentDir:        "/torrents",
		TMDBAPIKey:        "tmdb",
		SupabaseURL:       "supabase",
		SupabaseAnonKey:   "anon",
		TraktClientID:     "trakt-id",
		TraktClientSecret: "trakt-secret",
		Version:           "v1.2.3",
	}, got)

	err = Start("", "", "", "", "", "", "", "", "", "")
	require.ErrorContains(t, err, "already running")

	Stop()
	assert.False(t, IsRunning())
	assert.EqualValues(t, 1, fake.stops.Load())
	Stop()
	assert.EqualValues(t, 1, fake.stops.Load(), "stopping an idle backend must be a no-op")
}

func TestStartFailureDoesNotLeaveMobileRunning(t *testing.T) {
	resetMobileGlobals(t)
	startServer = func(server.Config) (backendHandle, error) {
		return nil, errors.New("bind failed")
	}

	err := Start("", "", "", "", "", "", "", "", "", "")
	require.Error(t, err)
	assert.ErrorContains(t, err, "mobile: bind failed")
	assert.False(t, IsRunning())
}
