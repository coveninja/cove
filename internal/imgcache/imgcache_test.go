package imgcache

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) {
	return f(r)
}

type failingReadCloser struct{}

func (failingReadCloser) Read([]byte) (int, error) {
	return 0, errors.New("read failed")
}

func (failingReadCloser) Close() error {
	return nil
}

// newTestCache builds a Cache rooted in a temp dir and points the package's
// upstream vars at an httptest server standing in for image.tmdb.org.
// utils.SafeHTTPClient refuses to dial loopback addresses (SSRF hardening),
// so tests must swap in a plain client — see the package-level var comment.
func newTestCache(t *testing.T, upstream *httptest.Server) *Cache {
	t.Helper()
	dir := t.TempDir()

	origBase, origClient := tmdbImageBase, upstreamClient
	tmdbImageBase = upstream.URL
	upstreamClient = upstream.Client()
	t.Cleanup(func() {
		tmdbImageBase = origBase
		upstreamClient = origClient
	})

	return &Cache{dir: dir}
}

func TestNew(t *testing.T) {
	t.Run("explicit directory includes existing files", func(t *testing.T) {
		dir := filepath.Join(t.TempDir(), "images")
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, "w500_existing.jpg"), make([]byte, 73), 0o644); err != nil {
			t.Fatal(err)
		}

		c, err := New(dir)
		if err != nil {
			t.Fatalf("New: %v", err)
		}
		if c.dir != dir {
			t.Errorf("dir = %q, want %q", c.dir, dir)
		}
		if c.totalBytes != 73 {
			t.Errorf("totalBytes = %d, want 73", c.totalBytes)
		}
	})

	t.Run("default directory", func(t *testing.T) {
		base := t.TempDir()
		t.Setenv("XDG_CACHE_HOME", base)

		c, err := New("")
		if err != nil {
			t.Fatalf("New: %v", err)
		}
		want := filepath.Join(base, "cove", "images")
		if c.dir != want {
			t.Errorf("dir = %q, want %q", c.dir, want)
		}
		if info, err := os.Stat(want); err != nil {
			t.Fatalf("cache directory: %v", err)
		} else if !info.IsDir() {
			t.Errorf("%q is not a directory", want)
		}
	})

	t.Run("user cache directory failure", func(t *testing.T) {
		t.Setenv("XDG_CACHE_HOME", "relative-cache")
		t.Setenv("HOME", "")
		if _, err := New(""); err == nil {
			t.Fatal("New succeeded with an invalid user cache directory")
		}
	})

	t.Run("create directory failure", func(t *testing.T) {
		parentFile := filepath.Join(t.TempDir(), "not-a-directory")
		if err := os.WriteFile(parentFile, []byte("file"), 0o644); err != nil {
			t.Fatal(err)
		}
		if _, err := New(filepath.Join(parentFile, "images")); err == nil {
			t.Fatal("New succeeded beneath a regular file")
		}
	})
}

func TestHandle_PathValidation(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		t.Errorf("upstream should not be hit for an invalid request: %s", r.URL.Path)
	}))
	defer upstream.Close()
	c := newTestCache(t, upstream)

	cases := []struct {
		name       string
		path       string
		wantStatus int
	}{
		{"missing file component", "/api/img/w500/", http.StatusBadRequest},
		{"invalid size bucket", "/api/img/w9999/abc123.jpg", http.StatusBadRequest},
		{"unsupported extension", "/api/img/w500/abc123.gif", http.StatusBadRequest},
		{"path traversal in file", "/api/img/w500/..%2f..%2fetc%2fpasswd.jpg", http.StatusBadRequest},
		{"non-alphanumeric file id", "/api/img/w500/abc-123.jpg", http.StatusBadRequest},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodGet, tc.path, nil)
			// Exercise through the real mux registration (SetupHandlers) so
			// the /api/img/ prefix stripping is covered too, not just handle().
			mux := http.NewServeMux()
			c.SetupHandlers(mux)
			rec := httptest.NewRecorder()
			mux.ServeHTTP(rec, req)
			if rec.Code != tc.wantStatus {
				t.Errorf("status = %d, want %d (body: %s)", rec.Code, tc.wantStatus, rec.Body.String())
			}
		})
	}
}

func TestHandle_MethodNotAllowed(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	defer upstream.Close()
	c := newTestCache(t, upstream)

	req := httptest.NewRequest(http.MethodPost, "/api/img/w500/abc123.jpg", nil)
	rec := httptest.NewRecorder()
	c.handle(rec, req)
	if rec.Code != http.StatusMethodNotAllowed {
		t.Errorf("status = %d, want %d", rec.Code, http.StatusMethodNotAllowed)
	}
}

func TestHandle_FetchesAndCachesOnMiss(t *testing.T) {
	var upstreamHits int
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		upstreamHits++
		w.Header().Set("Content-Type", "image/jpeg")
		_, _ = w.Write([]byte("fake-jpeg-bytes"))
	}))
	defer upstream.Close()
	c := newTestCache(t, upstream)

	req := httptest.NewRequest(http.MethodGet, "/api/img/w500/abc123.jpg", nil)
	rec := httptest.NewRecorder()
	c.handle(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 (body: %s)", rec.Code, rec.Body.String())
	}
	if rec.Body.String() != "fake-jpeg-bytes" {
		t.Errorf("body = %q, want %q", rec.Body.String(), "fake-jpeg-bytes")
	}
	if ct := rec.Header().Get("Content-Type"); ct != "image/jpeg" {
		t.Errorf("Content-Type = %q, want image/jpeg", ct)
	}
	if cc := rec.Header().Get("Cache-Control"); cc == "" {
		t.Error("Cache-Control header missing")
	}
	if upstreamHits != 1 {
		t.Fatalf("upstream hits = %d, want 1", upstreamHits)
	}

	// File should now be on disk under the expected {size}_{file} name.
	cachePath := filepath.Join(c.dir, "w500_abc123.jpg")
	if _, err := os.Stat(cachePath); err != nil {
		t.Fatalf("expected cache file at %s: %v", cachePath, err)
	}

	// A second request must be served from disk — no additional upstream hit.
	req2 := httptest.NewRequest(http.MethodGet, "/api/img/w500/abc123.jpg", nil)
	rec2 := httptest.NewRecorder()
	c.handle(rec2, req2)
	if rec2.Code != http.StatusOK {
		t.Fatalf("second request status = %d, want 200", rec2.Code)
	}
	if rec2.Body.String() != "fake-jpeg-bytes" {
		t.Errorf("second request body = %q, want %q", rec2.Body.String(), "fake-jpeg-bytes")
	}
	if upstreamHits != 1 {
		t.Errorf("upstream hits after cache hit = %d, want still 1 (should not re-fetch)", upstreamHits)
	}
}

func TestHandle_UpstreamFailureNoCacheReturnsBadGateway(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "not found upstream", http.StatusNotFound)
	}))
	defer upstream.Close()
	c := newTestCache(t, upstream)

	req := httptest.NewRequest(http.MethodGet, "/api/img/w500/missing123.jpg", nil)
	rec := httptest.NewRecorder()
	c.handle(rec, req)

	if rec.Code != http.StatusBadGateway {
		t.Errorf("status = %d, want %d", rec.Code, http.StatusBadGateway)
	}
}

func TestHandle_UpstreamFailureUsesCacheCreatedDuringFetch(t *testing.T) {
	dir := t.TempDir()
	c := &Cache{dir: dir}
	cachePath := filepath.Join(dir, "w500_racing123.jpg")

	origBase, origClient := tmdbImageBase, upstreamClient
	tmdbImageBase = "https://images.example.test"
	upstreamClient = &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
		if err := os.WriteFile(cachePath, []byte("concurrent-cache"), 0o644); err != nil {
			t.Fatalf("write concurrent cache: %v", err)
		}
		return nil, errors.New("upstream unavailable")
	})}
	t.Cleanup(func() {
		tmdbImageBase = origBase
		upstreamClient = origClient
	})

	req := httptest.NewRequest(http.MethodGet, "/api/img/w500/racing123.jpg", nil)
	rec := httptest.NewRecorder()
	c.handle(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 (body: %s)", rec.Code, rec.Body.String())
	}
	if got := rec.Body.String(); got != "concurrent-cache" {
		t.Errorf("body = %q, want %q", got, "concurrent-cache")
	}
}

func TestHandle_CacheWriteFailureStillServesFetchedImage(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("uncached-image"))
	}))
	defer upstream.Close()
	c := newTestCache(t, upstream)

	notDir := filepath.Join(t.TempDir(), "not-a-directory")
	if err := os.WriteFile(notDir, []byte("file"), 0o644); err != nil {
		t.Fatal(err)
	}
	c.dir = notDir

	req := httptest.NewRequest(http.MethodGet, "/api/img/w300/uncached123.png", nil)
	rec := httptest.NewRecorder()
	c.handle(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 (body: %s)", rec.Code, rec.Body.String())
	}
	if got := rec.Body.String(); got != "uncached-image" {
		t.Errorf("body = %q, want %q", got, "uncached-image")
	}
	if got := rec.Header().Get("Content-Type"); got != "image/png" {
		t.Errorf("Content-Type = %q, want image/png", got)
	}
	if c.totalBytes != 0 {
		t.Errorf("totalBytes = %d, want 0 after failed write", c.totalBytes)
	}
}

// TestEvict verifies the oldest-mtime-first eviction policy: given files that
// together exceed maxCacheBytes, evict() should remove the oldest ones first,
// stopping once usage is back at (roughly) evictToFraction of the cap, and
// leaving the newest file(s) in place.
func TestEvict(t *testing.T) {
	dir := t.TempDir()
	c := &Cache{dir: dir}

	// Shrink the cap so four 100-byte files (400 total) count as "over" —
	// avoids writing real hundreds-of-megabytes fixtures to exercise this.
	origMax, origFraction := maxCacheBytes, evictToFraction
	maxCacheBytes = 250
	evictToFraction = 0.5 // target <= 125 bytes after eviction
	t.Cleanup(func() {
		maxCacheBytes = origMax
		evictToFraction = origFraction
	})

	const fileSize = 100
	names := []string{"a_oldest.jpg", "b_old.jpg", "c_newer.jpg", "d_newest.jpg"}
	base := time.Now().Add(-time.Hour)
	for i, name := range names {
		path := filepath.Join(dir, name)
		if err := os.WriteFile(path, make([]byte, fileSize), 0o644); err != nil {
			t.Fatal(err)
		}
		mtime := base.Add(time.Duration(i) * time.Minute)
		if err := os.Chtimes(path, mtime, mtime); err != nil {
			t.Fatal(err)
		}
	}

	c.evict()

	// Only enough of the newest files should survive to be at/under the
	// target (125 bytes) — with 100-byte files that's exactly one survivor,
	// and it must be the newest one.
	remaining, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	var remainingNames []string
	for _, e := range remaining {
		remainingNames = append(remainingNames, e.Name())
	}
	if len(remainingNames) != 1 || remainingNames[0] != "d_newest.jpg" {
		t.Errorf("remaining files = %v, want only [d_newest.jpg]", remainingNames)
	}

	if c.totalBytes != int64(len(remainingNames))*fileSize {
		t.Errorf("totalBytes = %d, want %d", c.totalBytes, int64(len(remainingNames))*fileSize)
	}
}

func TestEvictBelowLimitRefreshesAccounting(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "cached.jpg"), make([]byte, 25), 0o644); err != nil {
		t.Fatal(err)
	}
	c := &Cache{dir: dir, totalBytes: 999}

	origMax := maxCacheBytes
	maxCacheBytes = 100
	t.Cleanup(func() { maxCacheBytes = origMax })

	c.evict()
	if c.totalBytes != 25 {
		t.Errorf("totalBytes = %d, want refreshed size 25", c.totalBytes)
	}
}

func TestEvictScanFailureReturns(t *testing.T) {
	c := &Cache{dir: filepath.Join(t.TempDir(), "missing"), totalBytes: 17}
	c.evict()
	if c.totalBytes != 17 {
		t.Errorf("totalBytes = %d, want unchanged 17", c.totalBytes)
	}
}

func TestWriteCacheFileAccountsForReplacement(t *testing.T) {
	dir := t.TempDir()
	c := &Cache{dir: dir}
	path := filepath.Join(dir, "w500_same123.jpg")

	if err := c.writeCacheFile(path, []byte("old")); err != nil {
		t.Fatalf("first write: %v", err)
	}
	if c.totalBytes != 3 {
		t.Fatalf("totalBytes after first write = %d, want 3", c.totalBytes)
	}
	if err := c.writeCacheFile(path, []byte("replacement")); err != nil {
		t.Fatalf("replacement write: %v", err)
	}
	if c.totalBytes != int64(len("replacement")) {
		t.Errorf("totalBytes after replacement = %d, want %d", c.totalBytes, len("replacement"))
	}

	badPath := filepath.Join(path, "child.jpg")
	if err := c.writeCacheFile(badPath, []byte("never written")); err == nil {
		t.Fatal("writeCacheFile succeeded beneath a regular file")
	}
	if c.totalBytes != int64(len("replacement")) {
		t.Errorf("totalBytes after failed write = %d, want %d", c.totalBytes, len("replacement"))
	}
}

func TestWriteCacheFileTriggersEviction(t *testing.T) {
	dir := t.TempDir()
	c := &Cache{dir: dir}

	origMax, origFraction := maxCacheBytes, evictToFraction
	maxCacheBytes = 3
	evictToFraction = 0.5
	t.Cleanup(func() {
		maxCacheBytes = origMax
		evictToFraction = origFraction
	})

	path := filepath.Join(dir, "w500_large123.jpg")
	if err := c.writeCacheFile(path, []byte("four")); err != nil {
		t.Fatalf("writeCacheFile: %v", err)
	}

	deadline := time.Now().Add(2 * time.Second)
	for {
		c.mu.Lock()
		total := c.totalBytes
		c.mu.Unlock()
		_, statErr := os.Stat(path)
		if total == 0 && os.IsNotExist(statErr) {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("eviction did not finish: totalBytes=%d statErr=%v", total, statErr)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// TestDirSize checks the startup scan totals plain file sizes and ignores
// subdirectories (the cache dir is expected to be flat, but a stray
// subdirectory should never crash the scan or corrupt the estimate).
func TestDirSize(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "w500_abc.jpg"), make([]byte, 500), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "w92_def.png"), make([]byte, 250), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(dir, "stray-subdir"), 0o755); err != nil {
		t.Fatal(err)
	}

	got := dirSize(dir)
	if got != 750 {
		t.Errorf("dirSize = %d, want 750", got)
	}
}

func TestDirSizeMissingDirectory(t *testing.T) {
	if got := dirSize(filepath.Join(t.TempDir(), "missing")); got != 0 {
		t.Errorf("dirSize(missing) = %d, want 0", got)
	}
}

// TestFetchUpstream_RejectsOversizedBody guards against silently caching a
// truncated, corrupt image from a misconfigured or compromised upstream.
func TestFetchUpstream_RejectsOversizedBody(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		chunk := make([]byte, 1<<20)
		for written := 0; written < maxImageBytes+(5<<20); written += len(chunk) {
			if _, err := w.Write(chunk); err != nil {
				return
			}
		}
	}))
	defer upstream.Close()

	origBase, origClient := tmdbImageBase, upstreamClient
	tmdbImageBase = upstream.URL
	upstreamClient = upstream.Client()
	defer func() {
		tmdbImageBase = origBase
		upstreamClient = origClient
	}()

	data, err := fetchUpstream("original", "big123.jpg")
	if err == nil {
		t.Fatalf("fetchUpstream returned %d bytes, want an oversized-body error", len(data))
	}
	if !strings.Contains(err.Error(), "body exceeds") {
		t.Errorf("error = %q, want oversized-body error", err)
	}
}

func TestFetchUpstreamReadFailure(t *testing.T) {
	origBase, origClient := tmdbImageBase, upstreamClient
	tmdbImageBase = "https://images.example.test"
	upstreamClient = &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
		return &http.Response{
			StatusCode: http.StatusOK,
			Body:       failingReadCloser{},
			Header:     make(http.Header),
		}, nil
	})}
	t.Cleanup(func() {
		tmdbImageBase = origBase
		upstreamClient = origClient
	})

	if _, err := fetchUpstream("w500", "broken123.jpg"); err == nil {
		t.Fatal("fetchUpstream succeeded despite a response-body read failure")
	}
}

func TestSetImageHeaders(t *testing.T) {
	cases := []struct {
		file string
		want string
	}{
		{"poster.jpg", "image/jpeg"},
		{"poster.JPEG", "image/jpeg"},
		{"still.png", "image/png"},
		{"logo.svg", "image/svg+xml"},
		{"unknown.bin", "application/octet-stream"},
	}

	for _, tc := range cases {
		t.Run(tc.file, func(t *testing.T) {
			rec := httptest.NewRecorder()
			setImageHeaders(rec, tc.file)
			if got := rec.Header().Get("Content-Type"); got != tc.want {
				t.Errorf("Content-Type = %q, want %q", got, tc.want)
			}
			if got := rec.Header().Get("Cache-Control"); got != "public, max-age=31536000, immutable" {
				t.Errorf("Cache-Control = %q", got)
			}
		})
	}
}

func TestServeBytesSupportsRangeRequests(t *testing.T) {
	req := httptest.NewRequest(http.MethodGet, "/image.jpg", nil)
	req.Header.Set("Range", "bytes=2-4")
	rec := httptest.NewRecorder()

	serveBytes(rec, req, "image.jpg", []byte("012345"))

	if rec.Code != http.StatusPartialContent {
		t.Fatalf("status = %d, want %d", rec.Code, http.StatusPartialContent)
	}
	if got := rec.Body.String(); got != "234" {
		t.Errorf("body = %q, want %q", got, "234")
	}
}
