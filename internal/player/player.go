package player

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/exec"
	"strconv"
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

// SubtitleTrackInfo describes a single subtitle track returned by ffprobe.
type SubtitleTrackInfo struct {
	Index    int    `json:"index"`
	Language string `json:"language"`
	Title    string `json:"title"`
	Codec    string `json:"codec"`
}

// imageBasedSubtitleCodecs cannot be converted to WebVTT by ffmpeg without OCR.
var imageBasedSubtitleCodecs = map[string]bool{
	"hdmv_pgs_subtitle": true,
	"pgssub":            true,
	"dvd_subtitle":      true,
	"dvdsub":            true,
	"xsub":              true,
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

func ProbeDuration(mediaURL string) (float64, error) {
	cmd := exec.Command("ffprobe",
		"-v", "quiet",
		"-print_format", "json",
		"-show_entries", "format=duration",
		mediaURL,
	)
	out, err := cmd.Output()
	if err != nil {
		return 0, fmt.Errorf("ffprobe duration: %w", err)
	}
	var result struct {
		Format struct {
			Duration string `json:"duration"`
		} `json:"format"`
	}
	if err := json.Unmarshal(out, &result); err != nil {
		return 0, err
	}
	return strconv.ParseFloat(result.Format.Duration, 64)
}

// ProbeAudioTracks runs ffprobe on the given URL and returns all audio tracks found.
func ProbeAudioTracks(mediaURL string) ([]AudioTrackInfo, error) {
	cmd := exec.Command("ffprobe",
		"-v", "quiet",
		"-print_format", "json",
		"-show_streams",
		"-select_streams", "a",
		mediaURL,
	)
	out, err := cmd.Output()
	if err != nil {
		return nil, fmt.Errorf("ffprobe: %w", err)
	}

	var result struct {
		Streams []struct {
			Tags struct {
				Language string `json:"language"`
				Title    string `json:"title"`
			} `json:"tags"`
			CodecName string `json:"codec_name"`
		} `json:"streams"`
	}
	if err := json.Unmarshal(out, &result); err != nil {
		return nil, err
	}

	tracks := make([]AudioTrackInfo, len(result.Streams))
	for i, s := range result.Streams {
		tracks[i] = AudioTrackInfo{
			Index:    i,
			Language: s.Tags.Language,
			Title:    s.Tags.Title,
			Codec:    s.CodecName,
		}
	}
	return tracks, nil
}

// ProbeSubtitleTracks runs ffprobe on the given URL and returns all
// text-based subtitle tracks (image-based PGS/DVDSUB are excluded).
func ProbeSubtitleTracks(mediaURL string) ([]SubtitleTrackInfo, error) {
	cmd := exec.Command("ffprobe",
		"-v", "quiet",
		"-print_format", "json",
		"-show_streams",
		"-select_streams", "s",
		mediaURL,
	)
	out, err := cmd.Output()
	if err != nil {
		return nil, fmt.Errorf("ffprobe subtitles: %w", err)
	}

	var result struct {
		Streams []struct {
			Tags struct {
				Language string `json:"language"`
				Title    string `json:"title"`
			} `json:"tags"`
			CodecName string `json:"codec_name"`
		} `json:"streams"`
	}
	if err := json.Unmarshal(out, &result); err != nil {
		return nil, err
	}

	var tracks []SubtitleTrackInfo
	for i, s := range result.Streams {
		if imageBasedSubtitleCodecs[s.CodecName] {
			continue
		}
		tracks = append(tracks, SubtitleTrackInfo{
			Index:    i,
			Language: s.Tags.Language,
			Title:    s.Tags.Title,
			Codec:    s.CodecName,
		})
	}
	return tracks, nil
}

// ExtractSubtitle extracts a single subtitle track and serves it as WebVTT.
// Results are cached so repeated requests don't re-run ffmpeg.
var (
	subtitleCacheMu sync.RWMutex
	subtitleCache   = map[string][]byte{}
)

func subtitleCacheKey(input string, index int) string {
	return fmt.Sprintf("%s::sub::%d", input, index)
}

func ExtractSubtitle(input string, subtitleIndex int, w http.ResponseWriter) {
	key := subtitleCacheKey(input, subtitleIndex)

	subtitleCacheMu.RLock()
	cached, ok := subtitleCache[key]
	subtitleCacheMu.RUnlock()

	if !ok {
		cmd := exec.Command("ffmpeg",
			"-i", input,
			"-map", fmt.Sprintf("0:s:%d", subtitleIndex),
			"-c:s", "webvtt",
			"-f", "webvtt",
			"pipe:1",
		)
		out, err := cmd.Output()
		if err != nil {
			http.Error(w, "subtitle extraction failed: "+err.Error(), http.StatusInternalServerError)
			return
		}
		subtitleCacheMu.Lock()
		subtitleCache[key] = out
		subtitleCacheMu.Unlock()
		cached = out
	}

	w.Header().Set("Content-Type", "text/vtt; charset=utf-8")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Write(cached)
}

// StreamWithAudio uses ffmpeg to select a specific audio track, transcode it to AAC,
// and serve the result as a seekable MP4 with correct duration.
func StreamWithAudio(input string, audioIndex int, w http.ResponseWriter, r *http.Request) {
	tmp, err := os.CreateTemp("", "cove-audio-*.mp4")
	if err != nil {
		http.Error(w, "temp file error: "+err.Error(), http.StatusInternalServerError)
		return
	}
	tmpPath := tmp.Name()
	tmp.Close()
	defer os.Remove(tmpPath)

	ctx := r.Context()
	cmd := exec.CommandContext(ctx, "ffmpeg",
		"-i", input,
		"-map", "0:v:0",
		"-map", fmt.Sprintf("0:a:%d", audioIndex),
		"-c:v", "copy",
		"-c:a", "aac",
		"-b:a", "192k",
		"-movflags", "+faststart",
		"-y",
		tmpPath,
	)

	var ffmpegErr bytes.Buffer
	cmd.Stderr = &ffmpegErr

	if err := cmd.Run(); err != nil {
		if ctx.Err() != nil {
			return
		}
		log.Printf("ffmpeg error: %v\n%s", err, ffmpegErr.String())
		http.Error(w, "ffmpeg failed", http.StatusInternalServerError)
		return
	}

	f, err := os.Open(tmpPath)
	if err != nil {
		http.Error(w, "failed to open output: "+err.Error(), http.StatusInternalServerError)
		return
	}
	defer f.Close()

	http.ServeContent(w, r, "stream.mp4", time.Time{}, f)
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
