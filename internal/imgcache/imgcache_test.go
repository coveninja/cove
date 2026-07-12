package imgcache

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

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

// TestFetchUpstream_LimitsBodySize guards against an upstream (or a
// misconfigured/compromised one) streaming an unbounded body.
func TestFetchUpstream_LimitsBodySize(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Slightly over the cap — fetchUpstream should truncate via
		// io.LimitReader rather than erroring, so just assert the returned
		// length never exceeds maxImageBytes.
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
	if err != nil {
		t.Fatalf("fetchUpstream: %v", err)
	}
	if len(data) > maxImageBytes {
		t.Errorf("len(data) = %d, exceeds maxImageBytes = %d", len(data), maxImageBytes)
	}
}
