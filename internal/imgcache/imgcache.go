// Package imgcache is a disk-cache proxy for TMDB poster/backdrop/still
// images. Every image the app displays used to load straight from
// image.tmdb.org — fine while online, but it meant the library grid went
// blank offline (or on a re-hit of the same title) and every card re-fetched
// the same bytes over and over. This package fronts image.tmdb.org with a
// local on-disk cache: the first request for a given (size, file) pair
// fetches and persists it, every later request is served straight off disk
// with no network round-trip at all — which also means it works offline once
// warm.
//
// TMDB image paths are content-addressed (the file component is stable for a
// given image — TMDB never mutates an existing path's bytes), so there is no
// cache-invalidation problem to solve: once fetched, a cached copy is good
// forever. That's what justifies serving straight from disk on a hit with no
// revalidation request at all.
package imgcache

import (
	"bytes"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/coveninja/cove/internal/utils"
)

// validSizes mirrors the subset of TMDB's image size buckets the frontend
// actually requests (w45 for provider logos, w92/w300 for compact
// cards/stills, w500 for posters, "original" for backdrops/full-res). Not
// every TMDB size is listed here on purpose — an unrecognized size is
// rejected rather than proxied, so this can't become an arbitrary-path
// fetcher for whatever an attacker appends after /api/img/.
var validSizes = map[string]bool{
	"w45":      true,
	"w92":      true,
	"w300":     true,
	"w500":     true,
	"original": true,
}

// fileNameRe restricts the file component to TMDB's actual naming scheme — an
// alphanumeric ID plus a known image extension. Combined with validSizes,
// this makes path traversal structurally impossible: the on-disk cache
// filename is size + "_" + file, and neither component can contain "/", "..",
// or any other path metacharacter once it matches.
var fileNameRe = regexp.MustCompile(`^[A-Za-z0-9]+\.(jpg|jpeg|png|svg)$`)

var extContentType = map[string]string{
	".jpg":  "image/jpeg",
	".jpeg": "image/jpeg",
	".png":  "image/png",
	".svg":  "image/svg+xml",
}

// maxImageBytes bounds a single fetched image — TMDB originals are large but
// nowhere near this; it's a sanity ceiling against a misbehaving/compromised
// upstream, not a real-world limit.
const maxImageBytes = 15 << 20

// maxCacheBytes / evictToFraction bound total on-disk cache size. On overflow
// the oldest-by-mtime files are removed until usage is back at 80% of the
// cap, rather than trimming to exactly the cap — so eviction doesn't have to
// re-run on every single subsequent write while usage hovers right at the
// line. Vars (not consts) so tests can shrink the cap instead of writing
// real hundreds-of-megabytes fixtures to exercise eviction.
var maxCacheBytes int64 = 500 << 20
var evictToFraction = 0.8

// tmdbImageBase and upstreamClient are package vars (not consts) so tests can
// point them at a local httptest server — utils.SafeHTTPClient's transport
// deliberately refuses loopback/private addresses (SSRF hardening), which a
// real TMDB request never hits but a test server always does.
var tmdbImageBase = "https://image.tmdb.org/t/p"
var upstreamClient = utils.SafeHTTPClient

// Cache owns the on-disk image cache: its directory and a running total-bytes
// estimate used to decide when to evict. Fields are unexported, so tygo emits
// nothing for Cache — this package has no exported data types the frontend
// needs; it's a pure HTTP proxy.
type Cache struct {
	dir string

	mu         sync.Mutex
	totalBytes int64
}

// New resolves the image-cache directory, creates it if missing, and scans
// it once to seed the running size estimate that eviction decisions are based
// on. A scan failure (e.g. permissions) isn't fatal — the cache just starts
// believing it's empty, which only risks growing past maxCacheBytes before
// the next successful scan-equivalent (there isn't one; it's incremental
// after startup), not a crash.
//
// cacheDir, when non-empty, overrides the default os.UserCacheDir()/cove/images
// location — used by mobile builds (Android) where os.UserCacheDir is
// unavailable or returns an unwritable path, and by the COVE_CACHE_DIR env
// var on desktop.
func New(cacheDir string) (*Cache, error) {
	var dir string
	if cacheDir != "" {
		dir = cacheDir
	} else {
		base, err := os.UserCacheDir()
		if err != nil {
			return nil, err
		}
		dir = filepath.Join(base, "cove", "images")
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	return &Cache{dir: dir, totalBytes: dirSize(dir)}, nil
}

func dirSize(dir string) int64 {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return 0
	}
	var total int64
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		total += info.Size()
	}
	return total
}

// SetupHandlers registers GET /api/img/{size}/{file} on mux.
func (c *Cache) SetupHandlers(mux *http.ServeMux) {
	mux.HandleFunc("/api/img/", utils.CorsMiddleware(utils.MethodGuard(http.MethodGet, c.handle)))
}

func (c *Cache) handle(w http.ResponseWriter, r *http.Request) {
	trimmed := strings.TrimPrefix(r.URL.Path, "/api/img/")
	parts := strings.SplitN(trimmed, "/", 2)
	if len(parts) != 2 {
		http.Error(w, "expected /api/img/{size}/{file}", http.StatusBadRequest)
		return
	}
	size, file := parts[0], parts[1]

	if !validSizes[size] {
		http.Error(w, "invalid size", http.StatusBadRequest)
		return
	}
	if !fileNameRe.MatchString(file) {
		http.Error(w, "invalid file", http.StatusBadRequest)
		return
	}

	name := size + "_" + file
	// fileNameRe and validSizes already forbid separators and "..", but
	// filepath.IsLocal makes the containment explicit (and is a sanitizer
	// static analyzers recognize, unlike the regex above).
	if !filepath.IsLocal(name) {
		http.Error(w, "invalid path", http.StatusBadRequest)
		return
	}
	cachePath := filepath.Join(c.dir, name)

	if serveFromDisk(w, r, cachePath, file) {
		return
	}

	data, err := fetchUpstream(size, file)
	if err != nil {
		log.Println("imgcache: upstream fetch failed:", err)
		// A concurrent request for the same image may have finished writing
		// the cache file while this one was fetching (or failing to) — worth
		// one more disk check before giving up, so a transient upstream blip
		// doesn't fail a request another in-flight one is about to satisfy.
		if serveFromDisk(w, r, cachePath, file) {
			return
		}
		http.Error(w, "could not fetch image", http.StatusBadGateway)
		return
	}

	// Concurrent duplicate downloads (two requests racing on the same miss)
	// are harmless here: AtomicWriteFile's rename is atomic, so the loser
	// just overwrites with byte-identical content (TMDB paths are
	// content-addressed) — no torn file, no need for a singleflight guard.
	if err := c.writeCacheFile(cachePath, data); err != nil {
		log.Println("imgcache: write failed:", err)
		// Caching is an optimization, not a correctness requirement — still
		// serve the bytes we already fetched even though persisting failed.
		serveBytes(w, r, file, data)
		return
	}
	if !serveFromDisk(w, r, cachePath, file) {
		// Vanishingly unlikely (would mean the file we just wrote vanished
		// before we could re-open it) — fall back to the in-memory bytes.
		serveBytes(w, r, file, data)
	}
}

// serveFromDisk serves cachePath if it exists, touching its mtime first (the
// LRU clock eviction reads) so a hit keeps a hot image from looking like the
// oldest thing in the cache. Returns false (having written nothing) on any
// stat/open failure, so the caller falls through to fetching upstream.
func serveFromDisk(w http.ResponseWriter, r *http.Request, cachePath, file string) bool {
	now := time.Now()
	if err := os.Chtimes(cachePath, now, now); err != nil {
		return false
	}
	f, err := os.Open(cachePath)
	if err != nil {
		return false
	}
	defer func() { _ = f.Close() }()

	setImageHeaders(w, file)
	// A zero mod time (rather than the file's real one) sidesteps
	// conditional-request (If-Modified-Since) complexity entirely, matching
	// the pattern used for torrent streaming (player.go's StreamTorrent) —
	// the far-future Cache-Control below is what actually governs caching,
	// not Last-Modified.
	http.ServeContent(w, r, file, time.Time{}, f)
	return true
}

func serveBytes(w http.ResponseWriter, r *http.Request, file string, data []byte) {
	setImageHeaders(w, file)
	http.ServeContent(w, r, file, time.Time{}, bytes.NewReader(data))
}

func setImageHeaders(w http.ResponseWriter, file string) {
	ct := extContentType[strings.ToLower(filepath.Ext(file))]
	if ct == "" {
		ct = "application/octet-stream"
	}
	w.Header().Set("Content-Type", ct)
	// TMDB image paths are content-addressed (see package doc) — safe to tell
	// the browser to never revalidate.
	w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
}

func fetchUpstream(size, file string) ([]byte, error) {
	upstreamURL := fmt.Sprintf("%s/%s/%s", tmdbImageBase, size, file)
	resp, err := upstreamClient.Get(upstreamURL)
	if err != nil {
		return nil, err
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("tmdb image fetch: HTTP %d", resp.StatusCode)
	}
	// Read one byte beyond the limit so an oversized response is rejected
	// rather than silently truncated and then cached as a corrupt image.
	data, err := io.ReadAll(io.LimitReader(resp.Body, maxImageBytes+1))
	if err != nil {
		return nil, err
	}
	if len(data) > maxImageBytes {
		return nil, fmt.Errorf("tmdb image fetch: body exceeds %d bytes", maxImageBytes)
	}
	return data, nil
}

// writeCacheFile atomically persists data and updates the running size
// estimate by the change in file size. Accounting the delta (rather than
// always adding len(data)) matters when concurrent misses both fetch and
// write the same content-addressed TMDB path.
//
// The write and accounting update share c.mu so duplicate writes cannot both
// observe a missing old file and double-count it.
func (c *Cache) writeCacheFile(path string, data []byte) error {
	c.mu.Lock()
	var oldSize int64
	if info, err := os.Stat(path); err == nil {
		oldSize = info.Size()
	}
	if err := utils.AtomicWriteFile(path, data, 0o644); err != nil {
		c.mu.Unlock()
		return err
	}
	c.totalBytes += int64(len(data)) - oldSize
	over := c.totalBytes > maxCacheBytes
	c.mu.Unlock()

	if over {
		go c.evict()
	}
	return nil
}

// evict scans the cache directory and removes the oldest-mtime files until
// total usage is back at evictToFraction of maxCacheBytes. Recomputes the
// size from a fresh scan rather than trusting the running estimate, since
// that estimate only ever accounts for writes this process made — a
// difference that matters if the process restarted without evicting first.
//
// The lock is held for the scan + victim selection, released during the
// os.Remove loop (I/O must not block concurrent cache writes), then
// re-acquired to subtract successfully removed bytes. Seeding totalBytes from
// the scan before unlocking preserves writes that finish while removal is in
// progress.
func (c *Cache) evict() {
	c.mu.Lock()

	entries, err := os.ReadDir(c.dir)
	if err != nil {
		c.mu.Unlock()
		log.Println("imgcache: evict scan:", err)
		return
	}

	type fileInfo struct {
		path  string
		size  int64
		mtime time.Time
	}
	files := make([]fileInfo, 0, len(entries))
	var total int64
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		files = append(files, fileInfo{filepath.Join(c.dir, e.Name()), info.Size(), info.ModTime()})
		total += info.Size()
	}

	if total <= maxCacheBytes {
		c.totalBytes = total
		c.mu.Unlock()
		return
	}

	sort.Slice(files, func(i, j int) bool { return files[i].mtime.Before(files[j].mtime) })

	target := int64(float64(maxCacheBytes) * evictToFraction)
	var victims []fileInfo
	running := total
	for _, f := range files {
		if running <= target {
			break
		}
		victims = append(victims, f)
		running -= f.size
	}

	c.totalBytes = total
	c.mu.Unlock() // release before I/O so cache writes aren't blocked during removes

	var removed int64
	for _, f := range victims {
		if err := os.Remove(f.path); err != nil {
			log.Println("imgcache: evict remove:", err)
			continue
		}
		removed += f.size
	}

	c.mu.Lock()
	c.totalBytes -= removed
	if c.totalBytes < 0 {
		c.totalBytes = 0
	}
	c.mu.Unlock()
}
