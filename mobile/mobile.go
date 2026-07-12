// Package mobile is the gomobile bind surface for the Android (and later iOS)
// app. It wraps internal/server with a flat, scalar-only API because gomobile
// restricts exported signatures to basic types — no structs, slices, maps, or
// interfaces may cross the JNI boundary.
//
// gomobile generates a Java class `mobile.Mobile` (one class per Go package)
// with one static method per exported package-level function. The Go `error`
// return type becomes a Java checked Exception, so callers must try/catch.
//
// On the desktop this package is imported by nobody; it still compiles as part
// of `go build ./...` because gomobile bind compiles the whole module. That
// keeps CI from silently breaking the mobile path while iterating on the
// desktop build.
package mobile

import (
	"fmt"
	"sync"

	"github.com/coveninja/cove/internal/server"
)

var (
	mu     sync.Mutex
	handle *server.Handle
)

// Start initialises and starts the Cove HTTP backend. It returns an error if
// the server is already running or if server.Start fails.
//
// Arguments map one-to-one to server.Config fields.
//   - bindAddr should be "127.0.0.1:6969" on Android.
//   - dataDir, cacheDir, and torrentDir must be absolute paths within the
//     app's private storage: Android does not have the XDG directories that
//     os.UserConfigDir / os.UserCacheDir / os.TempDir rely on. Use
//     Context.filesDir for data, Context.cacheDir for images, and a subdir of
//     filesDir (not cacheDir) for torrent pieces — OS cache eviction of a
//     partially-downloaded piece mid-stream would break seeking.
//   - Empty supabaseURL / supabaseAnonKey disables Supabase auth/sync.
func Start(bindAddr, dataDir, cacheDir, torrentDir, tmdbAPIKey, supabaseURL, supabaseAnonKey, version string) error {
	mu.Lock()
	defer mu.Unlock()

	if handle != nil {
		return fmt.Errorf("mobile: server is already running")
	}

	h, err := server.Start(server.Config{
		BindAddr:        bindAddr,
		DataDir:         dataDir,
		CacheDir:        cacheDir,
		TorrentDir:      torrentDir,
		TMDBAPIKey:      tmdbAPIKey,
		SupabaseURL:     supabaseURL,
		SupabaseAnonKey: supabaseAnonKey,
		Version:         version,
	})
	if err != nil {
		return fmt.Errorf("mobile: %w", err)
	}

	handle = h
	return nil
}

// Stop shuts down the running backend cleanly, flushing any pending writes.
// It is safe to call when the server is not running.
func Stop() {
	mu.Lock()
	defer mu.Unlock()

	if handle == nil {
		return
	}
	handle.Stop()
	handle = nil
}

// IsRunning reports whether the backend is currently running.
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return handle != nil
}
