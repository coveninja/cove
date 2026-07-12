// Package player owns the torrent client and streams playback sources as
// seekable HTTP: torrents stream a selected file directly via
// http.ServeContent — the largest file for a movie/single-file torrent, or
// the file matching the requested season/episode for a season-pack torrent
// (see selectFile) — mpv's Range requests just work, no transcoding
// involved. Direct-URL sources get a redirect straight to the origin.
// A background reaper (CleanupTorrents) drops idle torrents and their
// on-disk pieces after 10 minutes of no active readers, so a long-running
// process doesn't accumulate downloaded data forever.
package player

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"log/slog"

	"github.com/anacrolix/torrent"
	"github.com/coveninja/cove/internal/addons"
	"github.com/coveninja/cove/internal/nuvio"
	"github.com/coveninja/cove/internal/tmdb"
	"github.com/coveninja/cove/internal/utils"
)

// Player owns all of the package's mutable state — the torrent client and the
// active-torrent registry — plus the injected TMDB client and addon manager.
// Fields are unexported, so tygo emits nothing for Player.
type Player struct {
	client *torrent.Client

	activeTorrents   map[string]*torrentState
	activeTorrentsMu sync.RWMutex

	tmdbClient *tmdb.Client
	addonMgr   *addons.Manager
	nuvioMgr   *nuvio.Manager

	// streamHeaders remembers every stream URL the backend itself returned
	// from /api/streams (keyed by URL), together with any extra HTTP headers
	// (Referer/Origin, etc.) the origin CDN requires. /api/play only accepts
	// URLs found here — anything else is an open-redirect/SSRF attempt, not a
	// stream we offered. Entries expire after streamHeadersTTL, refreshed on
	// every lookup so long playback sessions stay valid.
	streamHeadersMu sync.Mutex
	streamHeaders   map[string]streamHeaderEntry

	// Background next-episode prefetch (F7) — a single slot, not a queue.
	// Switching shows (or advancing episodes) while an earlier prefetch is
	// still in flight deprioritizes it rather than downloading two things at
	// once; disk usage stays bounded to one background download plus whatever
	// the reaper hasn't yet collected.
	prefetchMu       sync.Mutex
	prefetchHash     string
	prefetchFile     *torrent.File
	prefetchInFlight atomic.Bool // true while a PrefetchTorrent goroutine is running

	// dataDir is the resolved directory for downloaded torrent pieces, set
	// once in New and shared by CleanupTorrents for removal of stale data.
	dataDir string

	// proxyTransport is the shared HTTP transport for proxyStream. mpv issues
	// many Range requests while seeking; reusing a single transport (and its
	// connection pool) instead of allocating one per request avoids leaking
	// idle connections and keeps TLS handshake overhead to a single session.
	proxyTransport *http.Transport
}

type streamHeaderEntry struct {
	headers map[string]string
	expires time.Time
}

const streamHeadersTTL = 30 * time.Minute

// torrentDataDirDefault is the fallback location for downloaded torrent pieces
// when no override is passed to New. Mobile builds (Android) supply an
// explicit path because os.TempDir() returns /tmp, which is unavailable or
// undesirable on Android. New() and CleanupTorrents must agree on the
// resolved path — it is stored on the Player struct after construction.
var torrentDataDirDefault = filepath.Join(os.TempDir(), "cove-torrents")

// infoHashRe validates a BitTorrent v1 infohash: 40 hex characters (SHA-1,
// hex-encoded). Used to reject garbage before /api/prefetch-download burns a
// background metadata-fetch attempt on it.
var infoHashRe = regexp.MustCompile(`^[0-9a-fA-F]{40}$`)

type torrentState struct {
	torrent      *torrent.Torrent
	lastBytes    int64
	lastCheck    time.Time
	speedByteSec int64

	// lastUsed is refreshed whenever something reads the torrent or polls its
	// progress, and readers counts the live stream handlers attached to it.
	// The reaper drops a torrent only when readers == 0 AND lastUsed is older
	// than the idle cutoff, so an actively-watched title is never collected.
	lastUsed time.Time
	readers  int

	// prefetched marks this torrent as the current background next-episode
	// download (F7) — it intentionally has zero readers (nothing is streaming
	// it yet) but shouldn't be reaped just for being idle as long as it's
	// still making progress. lastReapBytes is the BytesCompleted() reading
	// from the last reaper pass, used to detect that progress.
	prefetched    bool
	lastReapBytes int64
}

// New constructs a Player: it creates the torrent client and stores the
// injected TMDB client, addon manager, and Nuvio plugin manager. The torrent
// client is core functionality, so a failure here is returned for the caller
// to treat as fatal.
//
// dataDir, when non-empty, overrides the default torrentDataDirDefault
// (os.TempDir()/cove-torrents) as the directory where downloaded torrent
// pieces are stored. Pass an empty string to use the platform default.
func New(tmdbClient *tmdb.Client, addonMgr *addons.Manager, nuvioMgr *nuvio.Manager, dataDir string) (*Player, error) {
	if dataDir == "" {
		dataDir = torrentDataDirDefault
	}
	cfg := torrent.NewDefaultClientConfig()
	// Suppress the spurious per-piece WARN "finished hashing piece" (correct=true)
	// that upstream's classic file IO emits due to a limitWriter/os.File.WriteTo
	// mismatch. hashNoiseFilter passes everything else through unchanged.
	cfg.Slogger = slog.New(hashNoiseFilter{slog.Default().Handler()})
	cfg.DataDir = dataDir
	cfg.EstablishedConnsPerTorrent = 20 // default 50; playback needs far fewer peers
	cfg.MaxUnverifiedBytes = 32 << 20   // default 64 MiB of un-hashed piece data in RAM
	cfg.NoUpload = true                 // playback client, not a seed box
	client, err := torrent.NewClient(cfg)
	if err != nil {
		return nil, err
	}
	// Shared transport for proxyStream — see field comment on Player.
	transport := &http.Transport{
		Proxy:                 http.ProxyFromEnvironment,
		DialContext:           (&net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
		TLSHandshakeTimeout:   15 * time.Second,
		ResponseHeaderTimeout: 30 * time.Second,
	}
	return &Player{
		client:         client,
		activeTorrents: map[string]*torrentState{},
		tmdbClient:     tmdbClient,
		addonMgr:       addonMgr,
		nuvioMgr:       nuvioMgr,
		streamHeaders:  map[string]streamHeaderEntry{},
		dataDir:        dataDir,
		proxyTransport: transport,
	}, nil
}

// rememberStream records a stream URL the backend returned to the client
// (with any extra headers its origin requires — nil for most), authorizing
// it for later /api/play use. Sweeps expired entries on every call instead
// of running a background goroutine, since inserts only happen when streams
// are listed and that stays cheap.
func (p *Player) rememberStream(streamURL string, headers map[string]string) {
	if streamURL == "" {
		return
	}
	p.streamHeadersMu.Lock()
	defer p.streamHeadersMu.Unlock()
	now := time.Now()
	for k, v := range p.streamHeaders {
		if now.After(v.expires) {
			delete(p.streamHeaders, k)
		}
	}
	p.streamHeaders[streamURL] = streamHeaderEntry{headers: headers, expires: now.Add(streamHeadersTTL)}
}

// lookupStream reports whether streamURL is one the backend itself offered,
// returning its remembered headers (usually nil). A hit refreshes the entry's
// TTL so mpv's follow-up Range requests during a long watch never expire it.
func (p *Player) lookupStream(streamURL string) (headers map[string]string, known bool) {
	p.streamHeadersMu.Lock()
	defer p.streamHeadersMu.Unlock()
	entry, ok := p.streamHeaders[streamURL]
	if !ok || time.Now().After(entry.expires) {
		return nil, false
	}
	entry.expires = time.Now().Add(streamHeadersTTL)
	p.streamHeaders[streamURL] = entry
	return entry.headers, true
}

// proxyStream forwards the request to streamURL with extra headers attached,
// for origins that reject a bare redirect (no Referer/Origin) but work fine
// when the request carries them. Uses httputil.ReverseProxy so Range
// requests (mpv's seek mechanism), status codes, and body streaming are
// handled correctly instead of hand-rolled.
func (p *Player) proxyStream(streamURL string, headers map[string]string, w http.ResponseWriter, r *http.Request) {
	target, err := url.Parse(streamURL)
	if err != nil {
		http.Error(w, "invalid stream url", http.StatusBadGateway)
		return
	}
	proxy := &httputil.ReverseProxy{
		Director: func(req *http.Request) {
			req.URL.Scheme = target.Scheme
			req.URL.Host = target.Host
			req.URL.Path = target.Path
			req.URL.RawQuery = target.RawQuery
			req.Host = target.Host
			for k, v := range headers {
				req.Header.Set(k, v)
			}
		},
		// Bound connection setup but never the transfer itself — a stream
		// runs for the length of the film. FlushInterval -1 disables output
		// buffering so playback data reaches mpv as it arrives.
		// Transport is shared across calls (see Player.proxyTransport) so
		// mpv's per-seek Range requests reuse existing TLS connections.
		Transport:     p.proxyTransport,
		FlushInterval: -1,
		// Origin rejections (403 expired signature, 404 pulled file) reach mpv
		// but were otherwise invisible in the backend log, making dead direct
		// streams indistinguishable from player-side failures.
		ModifyResponse: func(resp *http.Response) error {
			if resp.StatusCode >= 400 {
				log.Printf("stream proxy: origin %s returned %d", target.Host, resp.StatusCode)
			}
			return nil
		},
		ErrorHandler: func(w http.ResponseWriter, r *http.Request, err error) {
			log.Println("stream proxy:", err)
			w.WriteHeader(http.StatusBadGateway)
		},
	}
	proxy.ServeHTTP(w, r)
}

// videoExtensions is the set of container extensions selectFile considers a
// playable video file — everything else in a torrent (nfo, srt, jpg, etc.)
// is ignored when picking which file to stream.
var videoExtensions = map[string]bool{
	".mkv":  true,
	".mp4":  true,
	".avi":  true,
	".m4v":  true,
	".webm": true,
	".ts":   true,
}

// videoFiles returns every file in the torrent that looks like a real video
// (by extension, excluding sample clips) — the base pool selectFile matches
// episode-number patterns against for season-pack torrents.
func videoFiles(t *torrent.Torrent) []*torrent.File {
	var out []*torrent.File
	for _, f := range t.Files() {
		if isVideoFile(f.DisplayPath()) {
			out = append(out, f)
		}
	}
	return out
}

func isVideoFile(path string) bool {
	if !videoExtensions[strings.ToLower(filepath.Ext(path))] {
		return false
	}
	return !strings.Contains(strings.ToLower(path), "sample")
}

// episodePatternBoundary keeps a season/episode-number match from firing
// inside a longer number (season 1 matching inside "S15E02") — Go's RE2
// engine has no lookaround, so this stands in for one: the char immediately
// before/after the number (if any) must not itself be a digit.
const episodePatternBoundary = `(?:^|[^0-9])`
const episodePatternEnd = `(?:$|[^0-9])`

// selectFile picks which file in a torrent to stream (D1). For a movie
// (season/episode nil) or when no episode-pattern match is found in the
// torrent's video files, it's simply the largest video file — the original,
// pre-D1 behavior, and the correct choice for a single-file torrent. For a
// TV episode it tries increasingly loose filename patterns against
// DisplayPath() (case-insensitive) — "S01E02"/"S1E2", then "1x02", then a
// bare episode marker ("E02"/"Ep02"/"Episode 2", only trusted when it
// matches exactly one file in the torrent, since without a season number
// it can't otherwise be disambiguated from another season's same episode
// number) — falling back to the largest video file if nothing matches.
// Multiple files matching the same tier (e.g. an extras file that happens
// to match) resolve to the largest of that tier's matches.
//
// The actual decision logic lives in selectFileIndex, a pure function over
// plain (path, length) pairs — torrent.File has no exported constructor, so
// keeping the logic torrent-package-agnostic is what makes it unit-testable.
func selectFile(t *torrent.Torrent, season, episode *int) (*torrent.File, error) {
	files := videoFiles(t)
	if len(files) == 0 {
		return nil, fmt.Errorf("no video files found in torrent")
	}

	infos := make([]fileCandidate, len(files))
	for i, f := range files {
		infos[i] = fileCandidate{path: f.DisplayPath(), length: f.Length()}
	}

	idx, reason := selectFileIndex(infos, season, episode)
	chosen := files[idx]
	log.Printf("selectFile: %s -> %s", reason, chosen.DisplayPath())
	return chosen, nil
}

// fileCandidate is the torrent-package-agnostic shape selectFileIndex works
// over — a file's display path and byte length, nothing else.
type fileCandidate struct {
	path   string
	length int64
}

// selectFileIndex is selectFile's pure decision logic. files must be
// non-empty (callers filter to video files first). Returns the chosen
// index and a short human-readable reason (logged by selectFile).
func selectFileIndex(files []fileCandidate, season, episode *int) (int, string) {
	if season != nil && episode != nil {
		s, e := *season, *episode
		tiers := []struct {
			label string
			re    *regexp.Regexp
		}{
			// "S01E02" / "S1E2" — padding-flexible via 0*.
			{fmt.Sprintf("matched S%02dE%02d", s, e), regexp.MustCompile(fmt.Sprintf(`(?i)%ss0*%de0*%d%s`, episodePatternBoundary, s, e, episodePatternEnd))},
			// "1x02"
			{fmt.Sprintf("matched %dx%02d", s, e), regexp.MustCompile(fmt.Sprintf(`(?i)%s%dx0*%d%s`, episodePatternBoundary, s, e, episodePatternEnd))},
		}
		for _, tier := range tiers {
			if idx := largestMatchIndex(files, tier.re); idx >= 0 {
				return idx, tier.label
			}
		}

		episodeOnly := regexp.MustCompile(fmt.Sprintf(`(?i)%s(?:e|ep|episode\s?)0*%d%s`, episodePatternBoundary, e, episodePatternEnd))
		if matches := matchIndices(files, episodeOnly); len(matches) == 1 {
			return matches[0], fmt.Sprintf("matched episode-only E%02d", e)
		}
	}

	return largestIndex(files), "no episode match, using largest video file"
}

// matchIndices returns the indices of every file whose path matches re.
func matchIndices(files []fileCandidate, re *regexp.Regexp) []int {
	var out []int
	for i, f := range files {
		if re.MatchString(f.path) {
			out = append(out, i)
		}
	}
	return out
}

// largestMatchIndex returns the index of the largest file matching re, or -1
// if nothing matches.
func largestMatchIndex(files []fileCandidate, re *regexp.Regexp) int {
	matches := matchIndices(files, re)
	if len(matches) == 0 {
		return -1
	}
	best := matches[0]
	for _, i := range matches[1:] {
		if files[i].length > files[best].length {
			best = i
		}
	}
	return best
}

// largestIndex returns the index of the largest file in files (non-empty).
func largestIndex(files []fileCandidate) int {
	best := 0
	for i, f := range files {
		if f.length > files[best].length {
			best = i
		}
	}
	return best
}

// addReader adjusts readers (+1 on open, -1 on return) and refreshes lastUsed.
func (p *Player) addReader(infoHash string, delta int) {
	p.activeTorrentsMu.Lock()
	if st, ok := p.activeTorrents[infoHash]; ok {
		st.readers += delta
		if st.readers < 0 {
			st.readers = 0
		}
		st.lastUsed = time.Now()
	}
	p.activeTorrentsMu.Unlock()
}

// getTorrentFile resolves the torrent for infoHash (fetching its metadata if
// this is the first request for it) and selects which file within it to
// stream — see selectFile for the season/episode matching logic. season and
// episode are nil for movies.
func (p *Player) getTorrentFile(infoHash string, season, episode *int) (*torrent.File, error) {
	// Reuse a torrent we've already fetched metadata for. AddMagnet is
	// idempotent, but reusing also avoids re-running the GotInfo wait and keeps
	// the idle timer fresh.
	p.activeTorrentsMu.Lock()
	if st, ok := p.activeTorrents[infoHash]; ok && st.torrent.Info() != nil {
		t := st.torrent
		st.lastUsed = time.Now()
		p.activeTorrentsMu.Unlock()
		return selectFile(t, season, episode)
	}
	p.activeTorrentsMu.Unlock()

	t, err := p.client.AddMagnet("magnet:?xt=urn:btih:" + infoHash)
	if err != nil {
		return nil, fmt.Errorf("invalid magnet for %s: %w", infoHash, err)
	}

	// Bound the metadata fetch. A dead swarm never fires GotInfo, and without a
	// deadline this blocks the request goroutine forever — the original cause
	// of goroutine pile-up under bad hashes. On timeout we drop the torrent so
	// it doesn't sit in the client holding resources.
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	select {
	case <-t.GotInfo():
	case <-ctx.Done():
		t.Drop()
		return nil, fmt.Errorf("timed out fetching metadata for %s", infoHash)
	}

	now := time.Now()
	p.activeTorrentsMu.Lock()
	// A concurrent first play of the same hash may have registered while we
	// waited on GotInfo (AddMagnet dedupes to the same *Torrent). Keep the
	// existing state — overwriting would discard its live reader count and
	// let the reaper drop a torrent that's still being streamed.
	if st, ok := p.activeTorrents[infoHash]; ok {
		st.lastUsed = now
	} else {
		p.activeTorrents[infoHash] = &torrentState{
			torrent:   t,
			lastCheck: now,
			lastUsed:  now,
		}
	}
	p.activeTorrentsMu.Unlock()

	return selectFile(t, season, episode)
}

// PrefetchTorrent background-downloads infoHash's selected file (F7): once
// the current episode of a TV show finishes downloading, the caller resolves
// the next episode, ranks its streams, and — if the winner is a torrent —
// calls this so it's already sitting on disk by the time the user gets there.
// Reuses getTorrentFile for metadata fetch + file selection (same season-pack
// matching playback itself uses), then calls File.Download(), which only
// raises piece priorities to Normal — additive, so it never disturbs a reader
// actively streaming the same torrent (e.g. a season pack whose current
// episode is still playing while this call targets a different file in the
// same swarm).
//
// Bookkeeping is a single slot, not a queue (see Player.prefetchHash/File):
// starting a prefetch for a different hash than whatever was previously
// prefetching deprioritizes that old file back to PiecePriorityNone first, so
// at most one background download is ever in flight.
func (p *Player) PrefetchTorrent(infoHash string, season, episode *int) error {
	file, err := p.getTorrentFile(infoHash, season, episode)
	if err != nil {
		return err
	}

	p.prefetchMu.Lock()
	prevHash, prevFile := p.prefetchHash, p.prefetchFile
	if prevHash != "" && prevHash != infoHash && prevFile != nil {
		prevFile.SetPriority(torrent.PiecePriorityNone)
		p.activeTorrentsMu.Lock()
		if st, ok := p.activeTorrents[prevHash]; ok {
			st.prefetched = false
		}
		p.activeTorrentsMu.Unlock()
	}
	p.prefetchHash = infoHash
	p.prefetchFile = file
	p.prefetchMu.Unlock()

	p.activeTorrentsMu.Lock()
	if st, ok := p.activeTorrents[infoHash]; ok {
		st.prefetched = true
		st.lastReapBytes = 0
	}
	p.activeTorrentsMu.Unlock()

	file.Download()
	return nil
}

// CleanupTorrents drops torrents that have no live readers and haven't been
// touched within the idle cutoff. anacrolix
// torrents hold open file handles plus on-disk pieces under torrentDataDir;
// without this they accumulate for the life of the process and eventually
// fill /tmp. Dropping removes the torrent from the client; we then RemoveAll
// its data directory to reclaim disk (unlinking is safe even if a handle is
// briefly still open on Linux).
//
// A background prefetch (F7) is idle by this same definition — nothing is
// streaming it, since it's downloading ahead of playback rather than during
// it — so without special-casing it, a season-pack prefetch that takes longer
// than 10 minutes to finish would get reaped mid-download. Instead, an idle
// prefetch candidate is only reaped once it's stopped making progress (a
// stalled swarm, or a completed download nobody's touched since); as long as
// bytes are still landing each pass, it's kept alive and its idle clock reset.
func (p *Player) CleanupTorrents() {
	cutoff := time.Now().Add(-10 * time.Minute)

	type dropped struct {
		hash string
		t    *torrent.Torrent
	}
	var toDrop []dropped

	p.activeTorrentsMu.Lock()
	for hash, st := range p.activeTorrents {
		if st.readers > 0 || !st.lastUsed.Before(cutoff) {
			continue
		}
		if st.prefetched {
			complete := st.torrent.BytesCompleted()
			if complete > st.lastReapBytes {
				st.lastReapBytes = complete
				st.lastUsed = time.Now()
				continue
			}
		}
		toDrop = append(toDrop, dropped{hash, st.torrent})
		delete(p.activeTorrents, hash)
	}
	p.activeTorrentsMu.Unlock()

	for _, d := range toDrop {
		name := d.t.Name() // capture before Drop; valid once metadata is known
		d.t.Drop()
		if name != "" {
			if err := os.RemoveAll(filepath.Join(p.dataDir, name)); err != nil {
				log.Printf("torrent %s: could not remove data: %v", d.hash, err)
			}
		}
		log.Printf("torrent %s dropped (idle)", d.hash)
	}
}

// dropIdleNonPrefetch drops every active torrent that has no live readers,
// is not exceptHash (the torrent just being streamed), and is not the current
// prefetch target. Called from StreamTorrent on every stream start so torrents
// from previous plays are freed immediately instead of waiting for the reaper.
// Data-removal semantics mirror CleanupTorrents exactly.
func (p *Player) dropIdleNonPrefetch(exceptHash string) {
	p.prefetchMu.Lock()
	currentPrefetch := p.prefetchHash
	p.prefetchMu.Unlock()

	type dropped struct {
		hash string
		t    *torrent.Torrent
	}
	var toDrop []dropped

	p.activeTorrentsMu.Lock()
	for hash, st := range p.activeTorrents {
		if hash == exceptHash || hash == currentPrefetch {
			continue
		}
		if st.readers > 0 {
			continue
		}
		toDrop = append(toDrop, dropped{hash, st.torrent})
		delete(p.activeTorrents, hash)
	}
	p.activeTorrentsMu.Unlock()

	for _, d := range toDrop {
		name := d.t.Name() // capture before Drop; valid once metadata is known
		d.t.Drop()
		if name != "" {
			if err := os.RemoveAll(filepath.Join(p.dataDir, name)); err != nil {
				log.Printf("torrent %s: could not remove data: %v", d.hash, err)
			}
		}
		log.Printf("torrent %s dropped (stream switch)", d.hash)
	}
}

// StreamTorrent serves infoHash's selected file (see selectFile) as seekable
// HTTP. season/episode (nil for movies) pick the right file out of a
// season-pack torrent instead of always streaming its largest file.
func (p *Player) StreamTorrent(infoHash string, season, episode *int, w http.ResponseWriter, r *http.Request) {
	file, err := p.getTorrentFile(infoHash, season, episode)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	// Mark the torrent as in-use for as long as this handler streams. The
	// reaper will not drop a torrent with readers > 0, so a long-running
	// read is protected.
	p.addReader(infoHash, +1)
	defer p.addReader(infoHash, -1)

	// Free stale torrents from previous plays immediately — don't wait for
	// the reaper. The new stream (infoHash) and the current prefetch target
	// are kept; everything else with no live readers is dropped now.
	p.dropIdleNonPrefetch(infoHash)

	reader := file.NewReader()
	// Closing the reader matters: anacrolix readers hold piece-download
	// priorities until Close(), and the player opens a new request (new reader)
	// on each seek. Closing on handler return releases stale prioritisation so
	// the swarm bandwidth follows the region the user actually seeked to.
	defer reader.Close()

	// Responsive mode hands the consumer whatever bytes have arrived instead of
	// blocking until a full readahead window is downloaded, and a generous
	// readahead lets the client fetch pieces ahead of playback so a seek
	// doesn't stall.
	reader.SetResponsive()
	reader.SetReadahead(16 << 20) // 16 MiB

	http.ServeContent(w, r, file.DisplayPath(), time.Time{}, reader)
}

// parseSeasonEpisode reads optional ?season=&episode= query params, returning
// nil for either that's missing or non-numeric. Shared by /api/play,
// /api/progress, and /api/progress/stream so a season-pack torrent's episode
// selection stays consistent across playback and progress reporting.
func parseSeasonEpisode(r *http.Request) (season, episode *int) {
	if s := r.URL.Query().Get("season"); s != "" {
		if v, err := strconv.Atoi(s); err == nil {
			season = &v
		}
	}
	if e := r.URL.Query().Get("episode"); e != "" {
		if v, err := strconv.Atoi(e); err == nil {
			episode = &v
		}
	}
	return season, episode
}

// GetProgress reports download progress for infoHash. When season/episode are
// given and the torrent's metadata is available, progress is computed over
// just the selected episode's file rather than the whole torrent — a season
// pack torrent can be 8% complete overall while the one file being played is
// already 100% done, and the whole-torrent number was wildly misleading in
// that case. Falls back to whole-torrent progress when metadata isn't ready
// yet or the torrent has no recognizable video files. Speed/peers stay
// torrent-wide either way — a per-file speed isn't meaningful (pieces for the
// selected file aren't fetched in isolation from the rest of the swarm
// bookkeeping) and torrent-wide is a reasonable proxy for "is this moving".
func (p *Player) GetProgress(infoHash string, season, episode *int) map[string]interface{} {
	p.activeTorrentsMu.Lock()
	state, ok := p.activeTorrents[infoHash]
	if !ok {
		p.activeTorrentsMu.Unlock()
		return map[string]interface{}{"found": false}
	}

	now := time.Now()
	stats := state.torrent.Stats()
	currentBytes := stats.BytesReadUsefulData.Int64()
	elapsed := now.Sub(state.lastCheck).Seconds()
	if elapsed > 0 {
		state.speedByteSec = int64(float64(currentBytes-state.lastBytes) / elapsed)
	}
	state.lastBytes = currentBytes
	state.lastCheck = now
	// Only refresh the idle clock while something is actively streaming.
	// The /api/progress/stream SSE endpoint polls every 2s for the lifetime of
	// playback; keeping lastUsed fresh unconditionally here would prevent the
	// reaper from ever collecting a torrent the user has stopped watching.
	if state.readers > 0 {
		state.lastUsed = now
	}
	t := state.torrent
	p.activeTorrentsMu.Unlock()

	info := t.Info()
	if info == nil {
		return map[string]interface{}{
			"found": true, "progress": 0, "peers": 0, "speed": "0 B/s",
			"seeders": 0, "totalPeers": 0, "speedBps": int64(0),
			"downloadedBytes": int64(0), "totalBytes": int64(0),
		}
	}

	var complete, total int64
	// Reuse the pure selection path (selectFileIndex), NOT selectFile — the
	// latter logs on every call, and this runs on a 2s SSE tick for the
	// lifetime of playback.
	files := videoFiles(t)
	if len(files) > 0 {
		infos := make([]fileCandidate, len(files))
		for i, f := range files {
			infos[i] = fileCandidate{path: f.DisplayPath(), length: f.Length()}
		}
		idx, _ := selectFileIndex(infos, season, episode)
		f := files[idx]
		complete = f.BytesCompleted()
		total = f.Length()
	} else {
		complete = t.BytesCompleted()
		total = t.Length()
	}

	var pct float64
	if total > 0 {
		pct = float64(complete) / float64(total) * 100
	}

	return map[string]interface{}{
		"found":           true,
		"progress":        pct,
		"peers":           stats.ActivePeers,
		"speed":           formatSpeed(state.speedByteSec),
		"seeders":         stats.ConnectedSeeders,
		"totalPeers":      stats.TotalPeers,
		"speedBps":        state.speedByteSec,
		"downloadedBytes": complete,
		"totalBytes":      total,
	}
}

func formatSpeed(bytesPerSec int64) string {
	switch {
	case bytesPerSec >= 1024*1024:
		return fmt.Sprintf("%.1f MB/s", float64(bytesPerSec)/1024/1024)
	case bytesPerSec >= 1024:
		return fmt.Sprintf("%.1f KB/s", float64(bytesPerSec)/1024)
	default:
		return fmt.Sprintf("%d B/s", bytesPerSec)
	}
}

func (p *Player) SetupHandlers(mux *http.ServeMux) {
	mux.HandleFunc("/api/subtitles", utils.CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		tmdbID := r.URL.Query().Get("id")
		mediaType := r.URL.Query().Get("type")
		id := 0
		if _, err := fmt.Sscanf(tmdbID, "%d", &id); err != nil {
			http.Error(w, "invalid id", http.StatusBadRequest)
			return
		}

		var imdbID string
		var err error
		if mediaType == "tv" {
			imdbID, err = p.tmdbClient.GetTVIMDBId(id)
		} else {
			imdbID, err = p.tmdbClient.GetIMDBId(id)
		}
		if err != nil || imdbID == "" {
			http.Error(w, "could not get IMDB id", http.StatusInternalServerError)
			return
		}

		stremioID := imdbID
		if mediaType == "tv" {
			season := r.URL.Query().Get("season")
			episode := r.URL.Query().Get("episode")
			if season != "" && episode != "" {
				stremioID = fmt.Sprintf("%s:%s:%s", imdbID, season, episode)
			}
		}

		allSubs := p.addonMgr.GetAllSubtitles(r.Context(), mediaType, stremioID)
		if allSubs == nil {
			allSubs = []addons.Subtitle{}
		}
		w.Header().Set("Content-Type", "application/json")
		err = json.NewEncoder(w).Encode(allSubs)
		if err != nil {
			log.Println(err)
			return
		}
	}))

	// /api/streams?id=<tmdbID>&type=movie|tv[&season=N&episode=N]
	mux.HandleFunc("/api/streams", utils.CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		tmdbID := r.URL.Query().Get("id")
		mediaType := r.URL.Query().Get("type")
		if mediaType == "" {
			mediaType = "movie"
		}

		id := 0
		_, err := fmt.Sscanf(tmdbID, "%d", &id)
		if err != nil {
			http.Error(w, "invalid id", http.StatusBadRequest)
			return
		}

		var imdbID string
		if mediaType == "tv" {
			imdbID, err = p.tmdbClient.GetTVIMDBId(id)
		} else {
			imdbID, err = p.tmdbClient.GetIMDBId(id)
		}
		if err != nil || imdbID == "" {
			http.Error(w, "could not get IMDB id", http.StatusInternalServerError)
			return
		}

		// For TV, append season:episode to build the Stremio stream ID
		stremioID := imdbID
		var seasonNum, episodeNum *int
		if mediaType == "tv" {
			season := r.URL.Query().Get("season")
			episode := r.URL.Query().Get("episode")
			if season == "" || episode == "" {
				http.Error(w, "season and episode are required for tv streams", http.StatusBadRequest)
				return
			}
			stremioID = fmt.Sprintf("%s:%s:%s", imdbID, season, episode)
			if sv, serr := strconv.Atoi(season); serr == nil {
				seasonNum = &sv
			}
			if ev, eerr := strconv.Atoi(episode); eerr == nil {
				episodeNum = &ev
			}
		}

		// Stremio addons and Nuvio scrapers are independent legs with no shared
		// state — run them concurrently so the response latency is max(leg),
		// not sum(leg). (A4)
		ctx := r.Context()
		var addonStreams []addons.Stream
		var addonErr error
		var nuvioStreams []addons.Stream

		var wg sync.WaitGroup
		wg.Add(1)
		go func() {
			defer wg.Done()
			addonStreams, addonErr = p.addonMgr.GetAllStreams(ctx, mediaType, stremioID)
		}()

		// Skip the extra TMDB lookup entirely for the common case (no Nuvio
		// scrapers enabled) — it exists purely to feed Nuvio's metadata.
		if p.nuvioMgr != nil && p.nuvioMgr.HasEnabledScrapers() {
			wg.Add(1)
			go func() {
				defer wg.Done()
				var title string
				var year int
				if media, mErr := p.tmdbClient.GetMediaByID(id, mediaType); mErr == nil && media != nil {
					title = firstNonEmpty(media.Title, media.Name)
					year = parseYear(firstNonEmpty(media.Released, media.FirstAir))
				}
				nuvioStreams = p.nuvioMgr.GetStreams(ctx, mediaType, id, imdbID, title, year, seasonNum, episodeNum)
			}()
		}
		wg.Wait()

		if addonErr != nil {
			http.Error(w, addonErr.Error(), http.StatusInternalServerError)
			return
		}

		// Response stays a single JSON array, nuvio appended after addons
		// (frontend contract unchanged).
		streams := addonStreams
		if streams == nil {
			streams = []addons.Stream{}
		}
		streams = append(streams, nuvioStreams...)

		// Register every direct-URL stream we're about to offer — /api/play
		// only accepts URLs from this registry (see rememberStream).
		for _, s := range streams {
			p.rememberStream(s.URL, s.Headers)
		}

		err = json.NewEncoder(w).Encode(streams)
		if err != nil {
			log.Println(err)
			return
		}
	}))

	mux.HandleFunc("/api/play", utils.CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		infoHash := r.URL.Query().Get("hash")
		streamURL := r.URL.Query().Get("url")

		// Direct http(s) sources: redirect mpv straight to them, unless the
		// origin needs extra headers (e.g. Referer) that a redirect can't
		// carry — in that case proxy the request instead. Only URLs this
		// backend itself returned from /api/streams are accepted; anything
		// else would make this an open redirect / request proxy.
		if streamURL != "" {
			if u, err := url.Parse(streamURL); err != nil ||
				(u.Scheme != "http" && u.Scheme != "https") {
				http.Error(w, "invalid stream url", http.StatusBadRequest)
				return
			}
			headers, known := p.lookupStream(streamURL)
			if !known {
				// Loud on purpose: mpv sees this 403 but the UI only shows a
				// generic spinner-then-fail, and the registry losing an entry
				// (backend restart, 30-min TTL) is otherwise undiagnosable.
				log.Printf("play: rejected stream url not in registry (restarted backend or entry expired?): %.120s", streamURL)
				http.Error(w, "unknown stream url (list streams first)", http.StatusForbidden)
				return
			}
			if len(headers) > 0 {
				p.proxyStream(streamURL, headers, w, r)
				return
			}
			http.Redirect(w, r, streamURL, http.StatusTemporaryRedirect)
			return
		}

		// Torrent sources: stream the selected file (largest, or the matching
		// episode of a season pack — see selectFile/D1) as seekable http. mpv
		// handles every codec/container natively, so no transcoding involved.
		if infoHash != "" {
			season, episode := parseSeasonEpisode(r)
			p.StreamTorrent(infoHash, season, episode, w, r)
			return
		}

		http.Error(w, "missing hash or url", http.StatusBadRequest)
	}))

	// POST /api/prefetch-download?hash=&season=&episode= — background-download
	// the next episode's torrent (F7) ahead of the user getting there. Fires
	// PrefetchTorrent in a goroutine and returns immediately: metadata fetch
	// alone can take up to 45s (getTorrentFile's GotInfo timeout), and this is
	// a fire-and-forget warm, not something the caller waits on. Same policy
	// as /api/play?hash= — a hash play isn't gated on rememberStream, so this
	// isn't either — but the hash format IS validated here (unlike /api/play,
	// which just lets AddMagnet reject garbage) since a malformed value would
	// otherwise burn a full metadata-fetch attempt in the background goroutine
	// before failing.
	//
	// A single-flight guard (prefetchInFlight) ensures at most one goroutine
	// is running at a time — PrefetchTorrent's bookkeeping is a single slot,
	// so concurrent metadata fetches are never useful and only waste resources.
	// Callers that arrive while a fetch is already in flight get 202 with
	// {"started": false} and should not retry immediately.
	mux.HandleFunc("/api/prefetch-download", utils.CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		infoHash := r.URL.Query().Get("hash")
		if !infoHashRe.MatchString(infoHash) {
			http.Error(w, "invalid or missing ?hash= (expected a 40-char hex infohash)", http.StatusBadRequest)
			return
		}
		season, episode := parseSeasonEpisode(r)

		if !p.prefetchInFlight.CompareAndSwap(false, true) {
			// Another PrefetchTorrent is already running; a second concurrent
			// fetch would race on the same single-slot bookkeeping and waste a
			// full metadata-fetch attempt for nothing.
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusAccepted)
			_ = json.NewEncoder(w).Encode(map[string]bool{"started": false})
			return
		}
		go func() {
			defer p.prefetchInFlight.Store(false)
			if err := p.PrefetchTorrent(infoHash, season, episode); err != nil {
				log.Println("prefetch-download:", err)
			}
		}()

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusAccepted)
		_ = json.NewEncoder(w).Encode(map[string]bool{"started": true})
	}))

	// Legacy polling endpoint — kept for compatibility; prefer /api/progress/stream (SSE).
	mux.HandleFunc("/api/progress", utils.CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		hash := r.URL.Query().Get("hash")
		season, episode := parseSeasonEpisode(r)
		err := json.NewEncoder(w).Encode(p.GetProgress(hash, season, episode))
		if err != nil {
			log.Println(err)
		}
	}))

	mux.HandleFunc("/api/progress/stream", utils.CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		hash := r.URL.Query().Get("hash")
		season, episode := parseSeasonEpisode(r)
		w.Header().Set("Content-Type", "text/event-stream")
		w.Header().Set("Cache-Control", "no-cache")
		w.Header().Set("Connection", "keep-alive")

		flusher, ok := w.(http.Flusher)
		if !ok {
			http.Error(w, "streaming not supported", http.StatusInternalServerError)
			return
		}

		ticker := time.NewTicker(2 * time.Second)
		defer ticker.Stop()

		for {
			select {
			case <-r.Context().Done():
				return
			case <-ticker.C:
				data, _ := json.Marshal(p.GetProgress(hash, season, episode))
				fmt.Fprintf(w, "data: %s\n\n", data)
				flusher.Flush()
			}
		}
	}))

	// GET /api/speedtest — streams a fixed-size payload so the client can
	// measure raw download throughput for the "Match My Internet Speed"
	// stream-selection mode. Not a rigorous benchmark (single connection,
	// no compression, local network only) but good enough as a rough guide.
	mux.HandleFunc("/api/speedtest", utils.CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		const payloadSize = 25 * 1024 * 1024 // 25 MiB
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("Content-Length", strconv.Itoa(payloadSize))
		w.Header().Set("Cache-Control", "no-store")

		buf := make([]byte, 1<<20) // 1 MiB chunks
		flusher, _ := w.(http.Flusher)
		for written := 0; written < payloadSize; {
			n := len(buf)
			if remaining := payloadSize - written; remaining < n {
				n = remaining
			}
			if _, err := w.Write(buf[:n]); err != nil {
				return // client aborted — nothing to clean up
			}
			written += n
			if flusher != nil {
				flusher.Flush()
			}
		}
	}))

	mux.HandleFunc("/api/subtitle-proxy", utils.CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		rawURL := r.URL.Query().Get("url")
		if rawURL == "" {
			http.Error(w, "missing url", http.StatusBadRequest)
			return
		}
		// Subtitle URLs come from third-party addons; only plain http(s) to
		// public hosts is allowed (SafeHTTPClient refuses local/private IPs).
		if _, err := utils.ValidatePublicURL(rawURL); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, rawURL, nil)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		resp, err := utils.SafeHTTPClient.Do(req)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadGateway)
			return
		}
		defer func(Body io.ReadCloser) {
			err := Body.Close()
			if err != nil {
				log.Println(err)
			}
		}(resp.Body)

		// 10 MiB is far beyond any real subtitle file.
		body, err := io.ReadAll(io.LimitReader(resp.Body, 10<<20))
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "text/vtt; charset=utf-8")

		// If it's SRT, convert to WebVTT (browser only accepts VTT for <track>)
		content := string(body)
		if !strings.HasPrefix(strings.TrimSpace(content), "WEBVTT") {
			content = utils.SrtToVTT(content)
		}
		_, err = fmt.Fprint(w, content)
		if err != nil {
			log.Println(err)
			return
		}
	}))
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}

// parseYear extracts the year from a TMDB-style "YYYY-MM-DD" date string.
// Returns 0 (not an error) for an empty or malformed date, since a Nuvio
// scraper's metadata.year is best-effort context, not something to fail over.
func parseYear(date string) int {
	if len(date) < 4 {
		return 0
	}
	year, err := strconv.Atoi(date[:4])
	if err != nil {
		return 0
	}
	return year
}
