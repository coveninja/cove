package settings

import (
	"encoding/json"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sync"

	"github.com/Arcadyi/cove/internal/utils"
)

// Settings holds all user-configurable preferences persisted to disk.
// Add new fields here; they'll be zero-valued on first load and safe to extend.
type Settings struct {
	// Playback
	OpenOnMute       bool    `json:"openOnMute"`       // start videos muted
	DefaultVolume    float64 `json:"defaultVolume"`    // 0.0–1.0
	AutoPlay         bool    `json:"autoPlay"`         // autoplay next episode
	RememberPosition bool    `json:"rememberPosition"` // resume from last position

	// Provider / streams
	DefaultProvider string `json:"defaultProvider"` // e.g. "torrentio", "debrid"
	PreferHLS       bool   `json:"preferHLS"`       // use HLS pipeline over direct stream

	// Subtitles
	SubtitlesEnabled    bool   `json:"subtitlesEnabled"`
	DefaultSubtitleLang string `json:"defaultSubtitleLang"` // ISO 639-1 e.g. "en"
	DefaultAudioLang    string `json:"defaultAudioLang"`    // ISO 639-1 e.g. "en"

	// UI
	ShowStreamDetails bool `json:"showStreamDetails"` // show codec/resolution badges on stream list
}

var defaultSettings = Settings{
	OpenOnMute:          false,
	DefaultVolume:       1.0,
	AutoPlay:            false,
	RememberPosition:    true,
	DefaultProvider:     "torrentio",
	PreferHLS:           true,
	SubtitlesEnabled:    false,
	DefaultSubtitleLang: "en",
	DefaultAudioLang:    "en",
	ShowStreamDetails:   true,
}

var (
	settingsMu     sync.RWMutex
	cachedSettings Settings
	settingsPath   string
)

// initSettings resolves the path for settings.json (next to the binary),
// loads it if it exists, or writes the defaults.
func InitSettings() error {
	ex, err := os.Executable()
	if err != nil {
		return err
	}
	settingsPath = filepath.Join(filepath.Dir(ex), "settings.json")

	data, err := os.ReadFile(settingsPath)
	if os.IsNotExist(err) {
		// First run — persist defaults so the file exists for the user to inspect.
		cachedSettings = defaultSettings
		return writeSettings()
	}
	if err != nil {
		return err
	}

	// Start from defaults so newly-added fields are never zero-valued.
	cachedSettings = defaultSettings
	return json.Unmarshal(data, &cachedSettings)
}

func writeSettings() error {
	data, err := json.MarshalIndent(cachedSettings, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(settingsPath, data, 0o644)
}

// SetupHandlers registers GET/PUT /api/settings.
func SetupHandlers() {
	// GET /api/settings — return current settings
	http.HandleFunc("/api/settings", utils.CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodOptions {
			w.Header().Set("Access-Control-Allow-Methods", "GET, PUT, OPTIONS")
			w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
			w.WriteHeader(http.StatusNoContent)
			return
		}

		if r.Method == http.MethodGet {
			settingsMu.RLock()
			s := cachedSettings
			settingsMu.RUnlock()

			w.Header().Set("Content-Type", "application/json")
			if err := json.NewEncoder(w).Encode(s); err != nil {
				log.Println("settings encode:", err)
			}
			return
		}

		// PUT /api/settings — merge & persist
		if r.Method == http.MethodPut {
			var incoming Settings
			if err := json.NewDecoder(r.Body).Decode(&incoming); err != nil {
				http.Error(w, "invalid body: "+err.Error(), http.StatusBadRequest)
				return
			}

			settingsMu.Lock()
			cachedSettings = incoming
			err := writeSettings()
			settingsMu.Unlock()

			if err != nil {
				log.Println("settings write:", err)
				http.Error(w, "could not save settings", http.StatusInternalServerError)
				return
			}

			w.Header().Set("Content-Type", "application/json")
			settingsMu.RLock()
			_ = json.NewEncoder(w).Encode(cachedSettings)
			settingsMu.RUnlock()
			return
		}

		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}))
}
