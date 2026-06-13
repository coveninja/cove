package player

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/exec"
	"sync"
	"time"

	"github.com/anacrolix/torrent"
)

var client *torrent.Client

var (
	activeTorrents   = map[string]*torrentState{}
	activeTorrentsMu sync.RWMutex
)

type torrentState struct {
	torrent      *torrent.Torrent
	lastBytes    int64
	lastCheck    time.Time
	speedByteSec int64
}

// AudioTrackInfo describes a single audio track returned by ffprobe.
type AudioTrackInfo struct {
	Index    int    `json:"index"`
	Language string `json:"language"`
	Title    string `json:"title"`
	Codec    string `json:"codec"`
}

// browserSafeAudioCodecs are codecs the browser can play natively.
// For these we only remux (copy streams) rather than transcode — takes seconds, not minutes.
var browserSafeAudioCodecs = map[string]bool{
	"aac": true, "mp3": true, "opus": true, "vorbis": true, "flac": true,
}

// ── Transcode/remux cache ────────────────────────────────────────────────────
//
// First request for a (input, audioIndex) pair starts processing in the
// background and blocks until done.  All subsequent requests (including every
// range/seek request the browser fires) are served instantly from the cached
// file, giving correct Content-Length, duration, and full seek support.

type transcodeEntry struct {
	done chan struct{} // closed when processing finishes
	path string        // temp file path, set after done is closed
	err  error
}

var (
	transcodeMu    sync.Mutex
	transcodeCache = map[string]*transcodeEntry{}
)

func transcodeKey(input string, audioIndex int) string {
	return fmt.Sprintf("%s::%d", input, audioIndex)
}

func Init() error {
	cfg := torrent.NewDefaultClientConfig()
	cfg.DataDir = "/tmp/cove-torrents"
	var err error
	client, err = torrent.NewClient(cfg)
	return err
}

func getLargestTorrentFile(infoHash string) (*torrent.File, error) {
	t, _ := client.AddMagnet("magnet:?xt=urn:btih:" + infoHash)
	<-t.GotInfo()

	activeTorrentsMu.Lock()
	activeTorrents[infoHash] = &torrentState{
		torrent:   t,
		lastCheck: time.Now(),
	}
	activeTorrentsMu.Unlock()

	var largest *torrent.File
	for _, f := range t.Files() {
		if largest == nil || f.Length() > largest.Length() {
			largest = f
		}
	}
	if largest == nil {
		return nil, fmt.Errorf("no files found in torrent")
	}
	return largest, nil
}

func StreamTorrent(infoHash string, w http.ResponseWriter, r *http.Request) {
	largest, err := getLargestTorrentFile(infoHash)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}
	http.ServeContent(w, r, largest.DisplayPath(), time.Time{}, largest.NewReader())
}

// ProbeResult contains audio tracks and total duration.
type ProbeResult struct {
	Tracks   []AudioTrackInfo `json:"tracks"`
	Duration float64          `json:"duration"`
}

// ProbeAudioTracks runs ffprobe on the given URL and returns all audio tracks and duration.
func ProbeAudioTracks(mediaURL string) (*ProbeResult, error) {
	cmd := exec.Command("ffprobe",
		"-v", "quiet",
		"-print_format", "json",
		"-show_streams",
		"-show_format", // ← adds format.duration
		mediaURL,
	)
	out, err := cmd.Output()
	if err != nil {
		return nil, fmt.Errorf("ffprobe: %w", err)
	}

	var result struct {
		Streams []struct {
			CodecType string `json:"codec_type"`
			Tags      struct {
				Language string `json:"language"`
				Title    string `json:"title"`
			} `json:"tags"`
			CodecName string `json:"codec_name"`
		} `json:"streams"`
		Format struct {
			Duration string `json:"duration"`
		} `json:"format"`
	}
	if err := json.Unmarshal(out, &result); err != nil {
		return nil, err
	}

	var tracks []AudioTrackInfo
	audioIndex := 0
	for _, s := range result.Streams {
		if s.CodecType != "audio" {
			continue
		}
		tracks = append(tracks, AudioTrackInfo{
			Index:    audioIndex,
			Language: s.Tags.Language,
			Title:    s.Tags.Title,
			Codec:    s.CodecName,
		})
		audioIndex++
	}

	var duration float64
	_, err = fmt.Sscanf(result.Format.Duration, "%f", &duration)
	if err != nil {
		log.Println(err)
	}

	return &ProbeResult{Tracks: tracks, Duration: duration}, nil
}

// StreamWithAudio serves the given input with a specific audio track selected.
//
// It chooses between two ffmpeg strategies automatically:
//   - Remux  (codec already browser-safe): copies all streams as-is, ~5–30s for a feature film
//   - Transcode (AC3, DTS, EAC3, etc.):    re-encodes audio to AAC, can take several minutes
//
// Either way the output is written to a temp file with -movflags +faststart so the
// moov atom is at the front of the file.  http.ServeContent then handles
// Content-Length, Range requests, and ETags, giving the browser correct duration
// and fully working seek from the very first request.
//
// A simple in-process cache means the expensive work only runs once per
// (input, audioIndex) pair — every seek/range request after the first is instant.
func StreamWithAudio(input string, audioIndex int, audioCodec string, w http.ResponseWriter, r *http.Request) {
	key := transcodeKey(input, audioIndex)

	transcodeMu.Lock()
	entry, exists := transcodeCache[key]
	if !exists {
		entry = &transcodeEntry{done: make(chan struct{})}
		transcodeCache[key] = entry
		go processAudio(input, audioIndex, audioCodec, entry)
	}
	transcodeMu.Unlock()

	// Wait for processing to finish, or bail out if the client disconnects.
	// The background goroutine continues either way so the cache is warm next time.
	select {
	case <-entry.done:
	case <-r.Context().Done():
		return
	}

	if entry.err != nil {
		log.Printf("audio processing error for %s track %d: %v", input, audioIndex, entry.err)
		http.Error(w, "audio processing failed", http.StatusInternalServerError)
		return
	}

	f, err := os.Open(entry.path)
	if err != nil {
		http.Error(w, "failed to open output file: "+err.Error(), http.StatusInternalServerError)
		return
	}
	defer func(f *os.File) {
		err := f.Close()
		if err != nil {
			log.Println(err)
		}
	}(f)

	http.ServeContent(w, r, "stream.mp4", time.Time{}, f)
}

// processAudio runs ffmpeg and writes the result to a temp file.
// It signals entry.done when complete (success or failure).
func processAudio(input string, audioIndex int, audioCodec string, entry *transcodeEntry) {
	defer close(entry.done)

	tmp, err := os.CreateTemp("", "cove-audio-*.mp4")
	if err != nil {
		entry.err = fmt.Errorf("create temp file: %w", err)
		return
	}
	tmpPath := tmp.Name()
	err = tmp.Close()
	if err != nil {
		log.Println(err)
		return
	}

	var audioArgs []string
	if browserSafeAudioCodecs[audioCodec] {
		// Fast path: just copy the audio stream, no re-encoding needed
		audioArgs = []string{"-c:a", "copy"}
		log.Printf("remuxing audio track %d (%s) — no transcode needed", audioIndex, audioCodec)
	} else {
		// Slow path: transcode to AAC so the browser can play it
		audioArgs = []string{"-c:a", "aac", "-b:a", "192k"}
		log.Printf("transcoding audio track %d (%s → aac)", audioIndex, audioCodec)
	}

	args := []string{
		"-i", input,
		"-map", "0:v:0",
		"-map", fmt.Sprintf("0:a:%d", audioIndex),
		"-c:v", "copy",
	}
	args = append(args, audioArgs...)
	args = append(args, "-movflags", "+faststart", "-y", tmpPath)

	cmd := exec.Command("ffmpeg", args...)
	var ffmpegErr bytes.Buffer
	cmd.Stderr = &ffmpegErr

	if err := cmd.Run(); err != nil {
		err := os.Remove(tmpPath)
		if err != nil {
			log.Println(err)
			return
		}
		entry.err = fmt.Errorf("ffmpeg: %w\n%s", err, ffmpegErr.String())
		return
	}

	entry.path = tmpPath
}

func GetProgress(infoHash string) map[string]interface{} {
	activeTorrentsMu.Lock()
	state, ok := activeTorrents[infoHash]
	if !ok {
		activeTorrentsMu.Unlock()
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
	t := state.torrent
	activeTorrentsMu.Unlock()

	info := t.Info()
	if info == nil {
		return map[string]interface{}{"found": true, "progress": 0, "peers": 0, "speed": "0 B/s"}
	}

	complete := t.BytesCompleted()
	total := t.Length()
	var pct float64
	if total > 0 {
		pct = float64(complete) / float64(total) * 100
	}

	return map[string]interface{}{
		"found":    true,
		"progress": pct,
		"peers":    stats.ActivePeers,
		"speed":    formatSpeed(state.speedByteSec),
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
