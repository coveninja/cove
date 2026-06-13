package player

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
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

// StreamWithAudio uses ffmpeg to select a specific audio track, transcode it to AAC,
// and serve the result as a seekable MP4 with correct duration.
// It writes to a temp file with -movflags +faststart so the moov atom (which carries
// duration) is placed at the front of the file, enabling seeking and correct timeline display.
func StreamWithAudio(input string, audioIndex int, w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	cmd := exec.CommandContext(ctx, "ffmpeg",
		"-i", input,
		"-map", "0:v:0",
		"-map", fmt.Sprintf("0:a:%d", audioIndex),
		"-c:v", "copy",
		"-c:a", "aac",
		"-b:a", "192k",
		"-movflags", "frag_keyframe+empty_moov+default_base_moof",
		"-f", "mp4",
		"pipe:1",
	)

	w.Header().Set("Content-Type", "video/mp4")
	cmd.Stdout = w

	var ffmpegErr bytes.Buffer
	cmd.Stderr = &ffmpegErr

	if err := cmd.Run(); err != nil {
		if ctx.Err() != nil {
			return // client disconnected, normal
		}
		log.Printf("ffmpeg error: %v\n%s", err, ffmpegErr.String())
	}
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
