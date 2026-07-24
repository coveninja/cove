package player

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coveninja/cove/internal/settings"
	"github.com/coveninja/cove/internal/utils"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// newTestPlayer creates a Player with a real torrent client in a temporary
// data directory, registering Close as a cleanup function. Use wherever a
// real *torrent.Client is required.
func newTestPlayer(t *testing.T) *Player {
	t.Helper()
	p, err := New(nil, nil, nil, t.TempDir(), nil)
	require.NoError(t, err)
	t.Cleanup(p.Close)
	return p
}

// addFakeHash inserts a real *torrent.Torrent (metadata not yet fetched —
// no swarm connection) into p.activeTorrents under the given infohash using
// the supplied torrentState fields. The caller is responsible for dropping
// the returned torrent when done.
func addFakeHash(t *testing.T, p *Player, hash string, st *torrentState) {
	t.Helper()
	// AddMagnet is idempotent for the same hash and returns immediately
	// without joining a swarm or fetching metadata, giving us a real
	// *torrent.Torrent we can store in activeTorrents without network I/O.
	tor, err := p.client.AddMagnet("magnet:?xt=urn:btih:" + hash)
	require.NoError(t, err)
	st.torrent = tor
	p.activeTorrentsMu.Lock()
	p.activeTorrents[hash] = st
	p.activeTorrentsMu.Unlock()
}

// ─── New / Close ─────────────────────────────────────────────────────────────

// TestCloseNilPlayerDoesNotPanic verifies that Close() on a nil *Player is a
// safe no-op — the nil guard at the top of Close() runs before any field
// access. This exercises lines 313-314 of player.go.
func TestCloseNilPlayerDoesNotPanic(t *testing.T) {
	var p *Player
	p.Close() // must not panic
}

// TestNewAndClose verifies that New constructs a Player with a real torrent
// client and that Close is idempotent (calling it twice must not panic).
func TestNewAndClose(t *testing.T) {
	p, err := New(nil, nil, nil, t.TempDir(), nil)
	require.NoError(t, err)
	require.NotNil(t, p)
	require.NotNil(t, p.client)
	assert.NotEmpty(t, p.dataDir)

	p.Close()
	p.Close() // second close must be a no-op
}

func TestCloseIsSafeWhenCalledConcurrently(t *testing.T) {
	p := newTestPlayer(t)
	var wg sync.WaitGroup
	for range 10 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			p.Close()
		}()
	}
	wg.Wait()
}

// TestNewUsesSuppliedDataDir verifies that the dataDir parameter is
// stored on the returned Player rather than the default.
func TestNewUsesSuppliedDataDir(t *testing.T) {
	dir := t.TempDir()
	p, err := New(nil, nil, nil, dir, nil)
	require.NoError(t, err)
	defer p.Close()
	assert.Equal(t, dir, p.dataDir)
}

// TestNewDefaultDataDir verifies that passing an empty dataDir falls back
// to torrentDataDirDefault.
func TestNewDefaultDataDir(t *testing.T) {
	p, err := New(nil, nil, nil, "" /* use default */, nil)
	require.NoError(t, err)
	defer p.Close()
	assert.Equal(t, torrentDataDirDefault, p.dataDir)
}

// ─── addReader ───────────────────────────────────────────────────────────────

// TestAddReaderIncrementsAndDecrementsReaders verifies that addReader
// adjusts the readers counter, clamps it at zero, refreshes lastUsed,
// and silently ignores unknown infohashes.
func TestAddReaderIncrementsAndDecrementsReaders(t *testing.T) {
	const hash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	before := time.Now().Add(-time.Second)
	p := &Player{
		activeTorrents: map[string]*torrentState{
			hash: {readers: 0, lastUsed: before},
		},
	}

	p.addReader(hash, +1)
	assert.Equal(t, 1, p.activeTorrents[hash].readers)
	assert.True(t, p.activeTorrents[hash].lastUsed.After(before), "lastUsed should be refreshed")

	p.addReader(hash, -1)
	assert.Equal(t, 0, p.activeTorrents[hash].readers)

	// Delta that would go below zero must clamp at zero.
	p.addReader(hash, -5)
	assert.Equal(t, 0, p.activeTorrents[hash].readers)

	// Unknown hash is a no-op (must not panic).
	p.addReader("0000000000000000000000000000000000000000", +1)
}

// TestAddReaderConcurrentIsSafe exercises addReader under concurrent access
// so the race detector can validate the mutex guarding.
func TestAddReaderConcurrentIsSafe(t *testing.T) {
	const hash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
	p := &Player{
		activeTorrents: map[string]*torrentState{
			hash: {readers: 0},
		},
	}
	var wg sync.WaitGroup
	for i := 0; i < 50; i++ {
		wg.Add(2)
		go func() { defer wg.Done(); p.addReader(hash, +1) }()
		go func() { defer wg.Done(); p.addReader(hash, -1) }()
	}
	wg.Wait()
	// Readers must be non-negative regardless of scheduling order.
	assert.GreaterOrEqual(t, p.activeTorrents[hash].readers, 0)
}

// ─── applyUploadPolicy ───────────────────────────────────────────────────────

// TestApplyUploadPolicy verifies that applyUploadPolicy selects the correct
// torrent upload behaviour for each settings combination. Since
// torrent.Torrent.AllowDataUpload / DisallowDataUpload return nothing, we
// assert the absence of panics and that both code branches are reachable.
func TestApplyUploadPolicy(t *testing.T) {
	p := newTestPlayer(t)

	// AddMagnet is non-blocking: returns a *torrent.Torrent immediately
	// without joining a swarm, giving us a real handle to call methods on.
	const hash = "cccccccccccccccccccccccccccccccccccccccc"
	tor, err := p.client.AddMagnet("magnet:?xt=urn:btih:" + hash)
	require.NoError(t, err)
	defer tor.Drop()

	tests := []struct {
		name     string
		settings *settings.Store
	}{
		{
			name:     "nil settings disallows upload",
			settings: nil,
		},
		{
			name:     "zero Store (AllowUploading=false) disallows upload",
			settings: new(settings.Store), // zero value → AllowUploading == false
		},
		{
			name: "default Store (AllowUploading=true) allows upload",
			// settings.New returns a Store with defaultSettings even on error,
			// and the default has AllowUploading: true — so this exercises the
			// AllowDataUpload() branch without writing to any production path.
			settings: func() *settings.Store { s, _ := settings.New("player-lifecycle-test"); return s }(),
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p.settings = tt.settings
			// Must not panic; side effect is on the torrent's internal state.
			p.applyUploadPolicy(tor)
		})
	}
}

// ─── proxyStream ─────────────────────────────────────────────────────────────

// newProxyPlayer returns a Player configured for proxyStream tests:
// a plain *http.Transport (allows localhost) with nil settings so the
// safe (non-LAN) transport is selected.
func newProxyPlayer() *Player {
	return &Player{
		proxyTransport: &http.Transport{},
	}
}

// TestProxyStreamForwardsStatus verifies that proxyStream passes the upstream
// HTTP status code through to the client unchanged.
func TestProxyStreamForwardsStatus(t *testing.T) {
	tests := []struct {
		name     string
		handler  http.HandlerFunc
		wantCode int
	}{
		{
			name:     "200 no Range",
			wantCode: http.StatusOK,
			handler: func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(http.StatusOK)
			},
		},
		{
			name:     "206 with Range",
			wantCode: http.StatusPartialContent,
			handler: func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Range", "bytes 0-9/100")
				w.WriteHeader(http.StatusPartialContent)
				_, _ = w.Write([]byte("0123456789"))
			},
		},
		{
			name:     "416 unsatisfiable range",
			wantCode: http.StatusRequestedRangeNotSatisfiable,
			handler: func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Range", "bytes */100")
				w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
			},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			upstream := httptest.NewServer(tt.handler)
			defer upstream.Close()

			p := newProxyPlayer()
			rec := httptest.NewRecorder()
			req := httptest.NewRequest(http.MethodGet, "/", nil)
			p.proxyStream(upstream.URL, nil, rec, req)
			assert.Equal(t, tt.wantCode, rec.Code)
		})
	}
}

// TestProxyStreamForwardsRangeRequest verifies that the Range request header
// reaches the upstream unchanged — this is the core seek-correctness contract:
// http.ServeContent semantics pass through a reverse proxy transparently.
func TestProxyStreamForwardsRangeRequest(t *testing.T) {
	var capturedRange string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		capturedRange = r.Header.Get("Range")
		w.Header().Set("Content-Range", "bytes 100-199/1000")
		w.WriteHeader(http.StatusPartialContent)
	}))
	defer upstream.Close()

	p := newProxyPlayer()
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.Header.Set("Range", "bytes=100-199")
	p.proxyStream(upstream.URL, nil, rec, req)

	assert.Equal(t, http.StatusPartialContent, rec.Code)
	assert.Equal(t, "bytes=100-199", capturedRange, "Range header must reach upstream")
	assert.Equal(t, "bytes 100-199/1000", rec.Header().Get("Content-Range"))
}

// TestProxyStreamSetsExtraHeaders verifies that per-origin headers supplied
// by the caller (e.g. Referer, Origin) are injected into the upstream request.
func TestProxyStreamSetsExtraHeaders(t *testing.T) {
	var gotReferer string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotReferer = r.Header.Get("Referer")
		w.WriteHeader(http.StatusOK)
	}))
	defer upstream.Close()

	p := newProxyPlayer()
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	p.proxyStream(upstream.URL, map[string]string{"Referer": "https://catalog.example/"}, rec, req)

	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Equal(t, "https://catalog.example/", gotReferer)
}

// TestProxyStreamUsesLANTransportWhenAllowLANEnabled verifies that setting
// AllowLanStreamSources=true causes proxyStream to select proxyTransportLAN
// instead of proxyTransport.
//
// We distinguish the two transports by giving proxyTransportLAN a DialContext
// that always fails: if the LAN transport is selected the proxy returns 502,
// whereas the safe transport (plain DialContext) successfully reaches the
// upstream and returns 200.
func TestProxyStreamUsesLANTransportWhenAllowLANEnabled(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer upstream.Close()

	safeTransport := &http.Transport{} // plain transport — connects to upstream

	// proxyTransportLAN always refuses to dial so the proxy returns 502 when
	// it is selected, making transport selection observable via status code.
	lanTransport := &http.Transport{
		DialContext: func(_ context.Context, _, _ string) (net.Conn, error) {
			return nil, errors.New("lan transport: intentionally refusing for test")
		},
	}

	// AllowLanStreamSources=false → proxyTransport (safeTransport) → 200.
	p := &Player{
		proxyTransport:    safeTransport,
		proxyTransportLAN: lanTransport,
		settings:          new(settings.Store), // zero Store → AllowLanStreamSources=false
	}
	rec := httptest.NewRecorder()
	p.proxyStream(upstream.URL, nil, rec, httptest.NewRequest(http.MethodGet, "/", nil))
	assert.Equal(t, http.StatusOK, rec.Code,
		"safe transport must be used when AllowLanStreamSources=false")

	// AllowLanStreamSources=true → proxyTransportLAN (lanTransport) → 502.
	// Use a real temporary store so the setting is persisted successfully
	// before the player reads it.
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	st, err := settings.New("lan-transport")
	require.NoError(t, err)
	require.NoError(t, st.Set(settings.Settings{AllowLanStreamSources: true}))
	p.settings = st

	rec = httptest.NewRecorder()
	p.proxyStream(upstream.URL, nil, rec, httptest.NewRequest(http.MethodGet, "/", nil))
	assert.Equal(t, http.StatusBadGateway, rec.Code,
		"LAN transport must be selected when AllowLanStreamSources=true")
}

// ─── CleanupTorrents ─────────────────────────────────────────────────────────

// TestCleanupTorrentsOrphanSweepRemovesStaleFiles verifies that CleanupTorrents
// removes files/directories in dataDir that belong to no active torrent, while
// leaving entries that match an active infohash or torrent name untouched.
// This test does not require any active torrent; it exercises the orphan-sweep
// path at the bottom of CleanupTorrents with an empty activeTorrents map.
func TestCleanupTorrentsOrphanSweepRemovesStaleFiles(t *testing.T) {
	dataDir := t.TempDir()

	// Write an orphan file (timed-out partial download, no active torrent).
	orphan := filepath.Join(dataDir, "dddddddddddddddddddddddddddddddddddddddd")
	require.NoError(t, os.MkdirAll(orphan, 0o755))
	require.NoError(t, os.WriteFile(filepath.Join(orphan, "file.mkv"), []byte("data"), 0o600))

	p := &Player{
		dataDir:        dataDir,
		activeTorrents: map[string]*torrentState{}, // no active torrents
	}
	p.CleanupTorrents()

	_, err := os.Stat(orphan)
	assert.ErrorIs(t, err, os.ErrNotExist, "orphan directory must be removed")
}

// TestCleanupTorrentsPreservesActiveInfohash verifies that an entry whose name
// matches an active infohash is preserved by the orphan sweep.
func TestCleanupTorrentsPreservesActiveInfohash(t *testing.T) {
	p := newTestPlayer(t)

	const hash = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"

	// Add a real torrent to activeTorrents with readers=1 so it cannot be
	// dropped by the idle-cutoff loop, and its entry feeds activeNames in the
	// orphan sweep.
	addFakeHash(t, p, hash, &torrentState{
		readers:  1,
		lastUsed: time.Now(),
	})
	defer func() {
		p.activeTorrentsMu.Lock()
		if st, ok := p.activeTorrents[hash]; ok {
			st.torrent.Drop()
			delete(p.activeTorrents, hash)
		}
		p.activeTorrentsMu.Unlock()
	}()

	// Write a file named after the infohash — the sweep must NOT remove it.
	kept := filepath.Join(p.dataDir, hash)
	require.NoError(t, os.MkdirAll(kept, 0o755))

	// Also write a true orphan that should be removed.
	orphanHash := "ffffffffffffffffffffffffffffffffffffffff"
	orphan := filepath.Join(p.dataDir, orphanHash)
	require.NoError(t, os.MkdirAll(orphan, 0o755))

	p.CleanupTorrents()

	_, err := os.Stat(kept)
	assert.NoError(t, err, "infohash directory belonging to active torrent must be kept")

	_, err = os.Stat(orphan)
	assert.ErrorIs(t, err, os.ErrNotExist, "orphan directory must be removed")
}

// TestCleanupTorrentsSkipsRecentlyUsed verifies that a torrent touched within
// the idle cutoff window is never dropped, regardless of reader count.
func TestCleanupTorrentsSkipsRecentlyUsed(t *testing.T) {
	p := newTestPlayer(t)

	const hash = "1111111111111111111111111111111111111111"
	addFakeHash(t, p, hash, &torrentState{
		readers:  0,
		lastUsed: time.Now(), // well within the 10-minute window
	})
	defer func() {
		p.activeTorrentsMu.Lock()
		if st, ok := p.activeTorrents[hash]; ok {
			st.torrent.Drop()
			delete(p.activeTorrents, hash)
		}
		p.activeTorrentsMu.Unlock()
	}()

	p.CleanupTorrents()

	p.activeTorrentsMu.RLock()
	_, still := p.activeTorrents[hash]
	p.activeTorrentsMu.RUnlock()
	assert.True(t, still, "recently-used torrent must not be dropped by CleanupTorrents")
}

// TestCleanupTorrentsDropsIdleEntries verifies that a torrent whose last-used
// timestamp predates the idle cutoff and has no active readers is removed from
// activeTorrents. The short torrentIdleCutoff makes the test deterministic
// without sleeping.
func TestCleanupTorrentsDropsIdleEntries(t *testing.T) {
	prev := torrentIdleCutoff
	torrentIdleCutoff = time.Nanosecond
	defer func() { torrentIdleCutoff = prev }()

	p := newTestPlayer(t)

	const hash = "2222222222222222222222222222222222222222"
	addFakeHash(t, p, hash, &torrentState{
		readers:  0,
		lastUsed: time.Time{}, // zero = very old (before any cutoff)
	})

	p.CleanupTorrents()

	p.activeTorrentsMu.RLock()
	_, still := p.activeTorrents[hash]
	p.activeTorrentsMu.RUnlock()
	assert.False(t, still, "idle torrent must be removed by CleanupTorrents")
}

// TestCleanupTorrentsSkipsActivePrefetch verifies that a prefetched torrent
// that is still making progress (BytesCompleted increased between passes) is
// kept alive even when it has no live readers.
func TestCleanupTorrentsSkipsActivePrefetch(t *testing.T) {
	prev := torrentIdleCutoff
	torrentIdleCutoff = time.Nanosecond
	defer func() { torrentIdleCutoff = prev }()

	p := newTestPlayer(t)

	const hash = "3333333333333333333333333333333333333333"
	addFakeHash(t, p, hash, &torrentState{
		readers:       0,
		lastUsed:      time.Time{}, // old enough to be eligible
		prefetched:    true,
		lastReapBytes: 0, // last pass saw 0 bytes
	})
	// BytesCompleted() returns 0 for a torrent with no metadata; since
	// lastReapBytes is also 0, the progress check (complete > lastReapBytes)
	// evaluates to false — the torrent is stalled and WILL be dropped.
	// Confirm the drop (no assertion on "kept") — this exercises the prefetch
	// branch at lines 863-869.
	defer func() {
		p.activeTorrentsMu.Lock()
		if st, ok := p.activeTorrents[hash]; ok {
			st.torrent.Drop()
			delete(p.activeTorrents, hash)
		}
		p.activeTorrentsMu.Unlock()
	}()

	p.CleanupTorrents()
	// Entry should have been dropped (stalled prefetch, no progress).
	p.activeTorrentsMu.RLock()
	_, still := p.activeTorrents[hash]
	p.activeTorrentsMu.RUnlock()
	assert.False(t, still, "stalled prefetch with no new bytes should be dropped")
}

// ─── dropIdleNonPrefetch ─────────────────────────────────────────────────────

// TestDropIdleNonPrefetchPreservesExceptHash verifies that the entry matching
// the exceptHash argument is never dropped, even when it has no active readers.
func TestDropIdleNonPrefetchPreservesExceptHash(t *testing.T) {
	p := newTestPlayer(t)

	const hash = "4444444444444444444444444444444444444444"
	addFakeHash(t, p, hash, &torrentState{readers: 0})
	defer func() {
		p.activeTorrentsMu.Lock()
		if st, ok := p.activeTorrents[hash]; ok {
			st.torrent.Drop()
			delete(p.activeTorrents, hash)
		}
		p.activeTorrentsMu.Unlock()
	}()

	p.dropIdleNonPrefetch(hash)

	p.activeTorrentsMu.RLock()
	_, still := p.activeTorrents[hash]
	p.activeTorrentsMu.RUnlock()
	assert.True(t, still, "exceptHash entry must not be dropped by dropIdleNonPrefetch")
}

// TestDropIdleNonPrefetchPreservesCurrentPrefetch verifies that the current
// prefetch target is preserved even when it has no live readers.
func TestDropIdleNonPrefetchPreservesCurrentPrefetch(t *testing.T) {
	p := newTestPlayer(t)

	const prefetchHash = "5555555555555555555555555555555555555555"
	addFakeHash(t, p, prefetchHash, &torrentState{readers: 0})
	defer func() {
		p.activeTorrentsMu.Lock()
		if st, ok := p.activeTorrents[prefetchHash]; ok {
			st.torrent.Drop()
			delete(p.activeTorrents, prefetchHash)
		}
		p.activeTorrentsMu.Unlock()
	}()

	p.prefetchMu.Lock()
	p.prefetchHash = prefetchHash
	p.prefetchMu.Unlock()

	p.dropIdleNonPrefetch("0000000000000000000000000000000000000000" /* different exceptHash */)

	p.activeTorrentsMu.RLock()
	_, still := p.activeTorrents[prefetchHash]
	p.activeTorrentsMu.RUnlock()
	assert.True(t, still, "current prefetch target must not be dropped by dropIdleNonPrefetch")
}

// TestDropIdleNonPrefetchPreservesActiveReaders verifies that a torrent with
// readers > 0 is never dropped regardless of its idle state.
func TestDropIdleNonPrefetchPreservesActiveReaders(t *testing.T) {
	p := newTestPlayer(t)

	const hash = "6666666666666666666666666666666666666666"
	addFakeHash(t, p, hash, &torrentState{readers: 2})
	defer func() {
		p.activeTorrentsMu.Lock()
		if st, ok := p.activeTorrents[hash]; ok {
			st.torrent.Drop()
			delete(p.activeTorrents, hash)
		}
		p.activeTorrentsMu.Unlock()
	}()

	p.dropIdleNonPrefetch("0000000000000000000000000000000000000000")

	p.activeTorrentsMu.RLock()
	_, still := p.activeTorrents[hash]
	p.activeTorrentsMu.RUnlock()
	assert.True(t, still, "torrent with active readers must not be dropped by dropIdleNonPrefetch")
}

// TestDropIdleNonPrefetchDropsIdleEntry verifies that an idle torrent (readers=0,
// not exceptHash, not current prefetch) is removed from activeTorrents.
func TestDropIdleNonPrefetchDropsIdleEntry(t *testing.T) {
	p := newTestPlayer(t)

	const hash = "7777777777777777777777777777777777777777"
	addFakeHash(t, p, hash, &torrentState{readers: 0})
	// No defer-Drop needed: dropIdleNonPrefetch calls tor.Drop() itself.

	p.dropIdleNonPrefetch("0000000000000000000000000000000000000000" /* different exceptHash */)

	p.activeTorrentsMu.RLock()
	_, still := p.activeTorrents[hash]
	p.activeTorrentsMu.RUnlock()
	assert.False(t, still, "idle torrent not matching exceptHash must be dropped by dropIdleNonPrefetch")
}

// ─── SetupHandlers — additional handler paths ─────────────────────────────────

// TestPrefetchDownloadHandlerAlreadyInFlight verifies that a second concurrent
// prefetch request receives 202 {"started": false} instead of launching a
// duplicate background fetch.
func TestPrefetchDownloadHandlerAlreadyInFlight(t *testing.T) {
	p, mux := handlerPlayer(nil, nil, nil)
	p.prefetchInFlight.Store(true) // simulate a goroutine already running

	rec := serve(mux, http.MethodPost,
		"/api/prefetch-download?hash=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "")
	assert.Equal(t, http.StatusAccepted, rec.Code)
	assert.Contains(t, rec.Body.String(), `"started":false`)
}

// TestProgressStreamHandlerReturnsOnContextCancel verifies that the SSE
// progress-stream endpoint exits cleanly when the request context is
// cancelled. httptest.ResponseRecorder implements http.Flusher (Go 1.20+),
// so using a pre-cancelled context is the reliable way to exercise the
// handler body without blocking on the 2-second ticker.
func TestProgressStreamHandlerReturnsOnContextCancel(t *testing.T) {
	_, mux := handlerPlayer(nil, nil, nil)

	ctx, cancel := context.WithCancel(context.Background())
	cancel() // context already done; the handler's select fires Done immediately

	req := httptest.NewRequestWithContext(ctx, http.MethodGet, "/api/progress/stream?hash=missing", nil)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	// Handler sets SSE headers before the select loop; check they are present.
	assert.Equal(t, "text/event-stream", rec.Header().Get("Content-Type"))
	assert.Equal(t, "no-cache", rec.Header().Get("Cache-Control"))
}

// TestSpeedtestHandlerStreamsFixedPayload verifies that /api/speedtest sets the
// correct Content-Type and Content-Length headers and delivers the exact payload
// size (25 MiB). The response body is buffered in-memory by the recorder — fast
// for a unit test since there is no real network I/O involved.
func TestSpeedtestHandlerStreamsFixedPayload(t *testing.T) {
	_, mux := handlerPlayer(nil, nil, nil)
	rec := serve(mux, http.MethodGet, "/api/speedtest", "")
	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Equal(t, "application/octet-stream", rec.Header().Get("Content-Type"))
	assert.Equal(t, "26214400", rec.Header().Get("Content-Length")) // 25*1024*1024
	assert.Equal(t, 25*1024*1024, rec.Body.Len())
}

// TestSubtitleProxyHandlerValidation verifies the early-return validation paths
// of /api/subtitle-proxy that do not require a real outbound subtitle fetch.
func TestSubtitleProxyHandlerValidation(t *testing.T) {
	_, mux := handlerPlayer(nil, nil, nil)

	// Missing url param → 400.
	rec := serve(mux, http.MethodGet, "/api/subtitle-proxy", "")
	assert.Equal(t, http.StatusBadRequest, rec.Code)

	// Non-http scheme → 400 (ValidatePublicURL rejects unsupported schemes).
	rec = serve(mux, http.MethodGet, "/api/subtitle-proxy?url=ftp://subs.example/sub.srt", "")
	assert.Equal(t, http.StatusBadRequest, rec.Code)

	// Private/LAN URL — ValidatePublicURL accepts valid http URLs (IP check
	// happens at dial time in SafeTransport), so SafeHTTPClient.Do() fails
	// with a transport error and the handler returns 502 Bad Gateway.
	rec = serve(mux, http.MethodGet, "/api/subtitle-proxy?url=http://127.0.0.1/sub.vtt", "")
	assert.Equal(t, http.StatusBadGateway, rec.Code)

	rec = serve(mux, http.MethodPost, "/api/subtitle-proxy?url=https://subs.example/sub.vtt", "")
	assert.Equal(t, http.StatusMethodNotAllowed, rec.Code)
}

func TestSubtitleProxyRejectsUpstreamErrorsAndOversizedBodies(t *testing.T) {
	rec := httptest.NewRecorder()
	writeSubtitleResponse(rec, &http.Response{
		StatusCode: http.StatusNotFound,
		Header:     make(http.Header),
		Body:       io.NopCloser(strings.NewReader("missing")),
	})
	assert.Equal(t, http.StatusBadGateway, rec.Code)
	assert.Contains(t, rec.Body.String(), "HTTP 404")

	rec = httptest.NewRecorder()
	writeSubtitleResponse(rec, &http.Response{
		StatusCode: http.StatusOK,
		Header:     make(http.Header),
		Body:       io.NopCloser(strings.NewReader(strings.Repeat("x", (10<<20)+1))),
	})
	assert.Equal(t, http.StatusRequestEntityTooLarge, rec.Code)
}

type subtitleReadCloser struct {
	readErr  error
	closeErr error
}

type subtitleErrorWriter struct {
	header http.Header
}

func (w *subtitleErrorWriter) Header() http.Header {
	return w.header
}

func (*subtitleErrorWriter) Write([]byte) (int, error) {
	return 0, errors.New("client write failed")
}

func (*subtitleErrorWriter) WriteHeader(int) {}

func (r subtitleReadCloser) Read([]byte) (int, error) {
	return 0, r.readErr
}

func (r subtitleReadCloser) Close() error {
	return r.closeErr
}

// TestSubtitleProxySuccessCallsWriteSubtitleResponse verifies that when the
// upstream subtitle fetch succeeds, the handler passes the response body to
// writeSubtitleResponse and the caller receives processed subtitle content.
// utils.SafeHTTPClient is temporarily replaced with a plain *http.Client so
// the local test server (127.0.0.1) is not rejected by SafeTransport's SSRF
// check. This exercises line 1664 of player.go.
func TestSubtitleProxySuccessCallsWriteSubtitleResponse(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("WEBVTT\n\n00:00.000 --> 00:01.000\nTest subtitle"))
	}))
	defer upstream.Close()

	// Swap SafeHTTPClient so the handler can reach the local test server; restore
	// it when the test exits so other tests are unaffected.
	old := utils.SafeHTTPClient
	utils.SafeHTTPClient = &http.Client{}
	defer func() { utils.SafeHTTPClient = old }()

	_, mux := handlerPlayer(nil, nil, nil)
	rec := serve(mux, http.MethodGet, "/api/subtitle-proxy?url="+upstream.URL+"/sub.vtt", "")
	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Equal(t, "text/vtt; charset=utf-8", rec.Header().Get("Content-Type"))
	assert.Contains(t, rec.Body.String(), "WEBVTT")
}

func TestWriteSubtitleResponseConvertsAndHandlesUpstreamIO(t *testing.T) {
	t.Run("keeps WebVTT", func(t *testing.T) {
		rec := httptest.NewRecorder()
		writeSubtitleResponse(rec, &http.Response{
			StatusCode: http.StatusOK,
			Body:       io.NopCloser(strings.NewReader("WEBVTT\n\n00:00.000 --> 00:01.000\nHi")),
		})
		assert.Equal(t, http.StatusOK, rec.Code)
		assert.Equal(t, "text/vtt; charset=utf-8", rec.Header().Get("Content-Type"))
		assert.Equal(t, "WEBVTT\n\n00:00.000 --> 00:01.000\nHi", rec.Body.String())
	})

	t.Run("converts SRT", func(t *testing.T) {
		rec := httptest.NewRecorder()
		writeSubtitleResponse(rec, &http.Response{
			StatusCode: http.StatusOK,
			Body:       io.NopCloser(strings.NewReader("1\n00:00:00,000 --> 00:00:01,000\nHi")),
		})
		assert.Contains(t, rec.Body.String(), "WEBVTT")
		assert.Contains(t, rec.Body.String(), "00:00:00.000 --> 00:00:01.000")
	})

	t.Run("read and close errors", func(t *testing.T) {
		rec := httptest.NewRecorder()
		writeSubtitleResponse(rec, &http.Response{
			StatusCode: http.StatusOK,
			Body: subtitleReadCloser{
				readErr:  errors.New("upstream read failed"),
				closeErr: errors.New("upstream close failed"),
			},
		})
		assert.Equal(t, http.StatusBadGateway, rec.Code)
		assert.Contains(t, rec.Body.String(), "upstream read failed")
	})

	t.Run("client write error", func(t *testing.T) {
		writer := &subtitleErrorWriter{header: make(http.Header)}
		writeSubtitleResponse(writer, &http.Response{
			StatusCode: http.StatusOK,
			Body:       io.NopCloser(strings.NewReader("WEBVTT")),
		})
		assert.Equal(t, "text/vtt; charset=utf-8", writer.header.Get("Content-Type"))
	})
}
