// Package settings persists a single flat Settings struct per profile as
// JSON, served whole-object over GET/PUT with no partial-merge semantics —
// a PUT that omits a field writes its Go zero value, not the previous
// value. Select-style preferences (e.g. streamSelectionMode,
// discoveryAlgorithm) are plain strings with no server-side enum
// validation; the frontend owns the allowed-value metadata and UI.
package settings

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"sync"
	"time"

	"github.com/coveninja/cove/internal/utils"
)

// Settings holds all user-configurable preferences persisted to disk.
type Settings struct {
	// Playback
	OpenOnMute       bool    `json:"openOnMute"`       // start videos muted
	DefaultVolume    float64 `json:"defaultVolume"`    // 0.0–1.0
	AutoPlay         bool    `json:"autoPlay"`         // autoplay next episode
	RememberPosition bool    `json:"rememberPosition"` // resume from last position

	// Provider / streams
	DefaultProvider string `json:"defaultProvider"`

	// Stream auto-selection
	AutoSelectStream      bool    `json:"autoSelectStream"`      // skip the manual stream list and play immediately
	StreamSelectionMode   string  `json:"streamSelectionMode"`   // "balanced" | "seeders" | "quality" | "smallest" | "bandwidth"
	MeasuredBandwidthMbps float64 `json:"measuredBandwidthMbps"` // from the in-app speed test; 0 = never measured
	SourcePreference      string  `json:"sourcePreference"`      // "" (none) | "torrent" | "direct" — additive ranking boost, doesn't exclude

	// Subtitles
	SubtitlesEnabled    bool    `json:"subtitlesEnabled"`
	DefaultSubtitleLang string  `json:"defaultSubtitleLang"` // ISO 639-1 e.g. "en"
	DefaultAudioLang    string  `json:"defaultAudioLang"`    // ISO 639-1 e.g. "en", or "original" to match the title's original_language
	SubtitleSize        float64 `json:"subtitleSize"`        // percentage, 50–200
	SubtitlePosition    float64 `json:"subtitlePosition"`    // percent from bottom, 2–90
	SubtitleBackground  bool    `json:"subtitleBackground"`  // dark box behind subtitle text

	// UI
	ShowStreamDetails bool `json:"showStreamDetails"` // show codec/resolution badges on stream list
	HideSpoilers      bool `json:"hideSpoilers"`

	// Segment skip (via IntroDB)
	AutoSkipIntro   bool `json:"autoSkipIntro"`
	AutoSkipRecap   bool `json:"autoSkipRecap"`
	AutoSkipCredits bool `json:"autoSkipCredits"`
	AutoSkipPreview bool `json:"autoSkipPreview"`

	// Onboarding
	OnboardingDone bool `json:"onboardingDone"`

	// Discovery
	DiscoveryAlgorithm string `json:"discoveryAlgorithm"` // "smart" | "popularity" | "custom"
	CustomAlgorithmURL string `json:"customAlgorithmUrl"` // used when discoveryAlgorithm == "custom"

	// Predictive prefetch (Phase E) — background-warms stream caches for
	// continue-watching titles and next episodes so playback starts near-
	// instantly. Default true; the worker checks this each cycle and the
	// completion-trigger respects it too.
	PrefetchStreams bool `json:"prefetchStreams"`

	// PrefetchNextEpisode background-downloads the next TV episode's top-ranked
	// torrent stream once the current episode's file finishes downloading, so
	// playback of the next episode starts near-instantly. Default true.
	PrefetchNextEpisode bool `json:"prefetchNextEpisode"`

	// AllowUploading enables uploading pieces to peers while streaming (capped
	// at 1 MiB/s in the player). Improves download speed via BitTorrent
	// reciprocity — tit-for-tat peers unchoke us faster when we reciprocate.
	// Off means never upload. Default true.
	AllowUploading bool `json:"allowUploading"`

	// ProbeStreams enables the pre-playback liveness probe: before auto-select
	// commits to a stream, the top direct-URL candidates are HEAD-checked so
	// dead scraper links get demoted instead of failing at playback. Costs
	// ~700ms of auto-select latency; some CDNs refuse HEAD, in which case the
	// probe falls back to a 1-byte Range GET.
	ProbeStreams bool `json:"probeStreams"`

	// Remote access (Phase 5) — opens a separate LAN listener so other devices
	// (e.g. the Android app in thin-client mode) can reach this backend over the
	// local network. Settings are per-profile; remote access follows whichever
	// profile is currently active.
	//
	// RemoteAccessEnabled: when true the server opens a separate TCP listener on
	// 0.0.0.0:<main-port+1> (default 0.0.0.0:6970). The main loopback listener
	// (127.0.0.1:6969) is never touched. When false, the LAN port is simply
	// closed — a port scan gets connection-refused, not a 403.
	//
	// RemoteAccessToken: a 32-byte hex string generated once (crypto/rand) the
	// first time RemoteAccessEnabled is set to true, then persisted and never
	// auto-rotated. Every request arriving on the LAN listener must supply this
	// token via X-Cove-Token header or ?token= query param.
	//
	// NOTE: these two fields are intentionally excluded from Supabase sync
	// (see MergeFrom). They are device-local security config and must not
	// propagate to other devices.
	RemoteAccessEnabled bool   `json:"remoteAccessEnabled"`
	RemoteAccessToken   string `json:"remoteAccessToken"`

	// Sync bookkeeping — stamped server-side on every local write, used to resolve
	// Supabase merge conflicts (see MergeFrom). Never trust a client-supplied value.
	UpdatedAt time.Time `json:"updatedAt"`
}

var defaultSettings = Settings{
	OpenOnMute:            false,
	DefaultVolume:         1.0,
	AutoPlay:              false,
	RememberPosition:      true,
	DefaultProvider:       "",
	AutoSelectStream:      false,
	StreamSelectionMode:   "balanced",
	MeasuredBandwidthMbps: 0,
	SubtitlesEnabled:      false,
	DefaultSubtitleLang:   "en",
	DefaultAudioLang:      "en",
	SubtitleSize:          100,
	SubtitlePosition:      8,
	SubtitleBackground:    true,
	ShowStreamDetails:     true,
	HideSpoilers:          false,
	AutoSkipIntro:         false,
	AutoSkipRecap:         false,
	AutoSkipCredits:       false,
	AutoSkipPreview:       false,
	DiscoveryAlgorithm:    "smart",
	PrefetchStreams:       true,
	SourcePreference:      "",
	PrefetchNextEpisode:   true,
	AllowUploading:        true,
	ProbeStreams:          true,
	RemoteAccessEnabled:   false,
	RemoteAccessToken:     "",
}

// Store owns the package's mutable state. Fields are unexported, so tygo emits
// nothing for Store — only the Settings data type crosses into the generated TS.
type Store struct {
	mu       sync.RWMutex
	cached   Settings
	path     string
	onChange func(Settings) // called in a goroutine after every successful write; nil-safe
}

// SetOnChange registers a hook that fires (in its own goroutine) after every
// successful settings write. The hook receives a snapshot of the newly-saved
// settings. Used by the server layer to react to RemoteAccessEnabled changes
// without polling. Pass nil to clear.
func (s *Store) SetOnChange(fn func(Settings)) {
	s.mu.Lock()
	s.onChange = fn
	s.mu.Unlock()
}

// New resolves settings-{profileID}.json in the per-user config directory (see
// utils.ConfigPath) and loads it, or writes the defaults on first run. It always
// returns a usable (non-nil) *Store even on error, so the caller can register
// handlers against in-memory defaults rather than crashing.
func New(profileID string) (*Store, error) {
	s := &Store{cached: defaultSettings}

	path, err := utils.ConfigPath(fmt.Sprintf("settings-%s.json", profileID))
	if err != nil {
		return s, err
	}
	s.path = path

	data, err := os.ReadFile(s.path)
	if os.IsNotExist(err) {
		// First run — persist defaults so the file exists for the user to inspect.
		return s, s.write()
	}
	if err != nil {
		return s, err
	}

	// cached already holds defaults, so unmarshalling the file over it means
	// newly-added fields are never left zero-valued.
	if err := json.Unmarshal(data, &s.cached); err != nil {
		return s, err
	}
	return s, nil
}

// write persists s.cached to disk and fires the onChange hook in a new goroutine.
// Must be called with s.mu held. The goroutine receives a snapshot so it never
// races against subsequent mutations.
func (s *Store) write() error {
	data, err := json.MarshalIndent(s.cached, "", "  ")
	if err != nil {
		return err
	}
	if err := utils.AtomicWriteFile(s.path, data, 0o644); err != nil {
		return err
	}
	if s.onChange != nil {
		snap := s.cached
		go s.onChange(snap)
	}
	return nil
}

// generateToken returns a 32-byte cryptographically random hex string.
func generateToken() (string, error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return hex.EncodeToString(buf), nil
}

// Get returns the current settings value. Safe for concurrent use.
func (s *Store) Get() Settings {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.cached
}

// Set replaces the in-memory settings and persists them to disk. It is the
// programmatic equivalent of a PUT /api/settings request and applies the same
// token-preservation logic. Safe for concurrent use.
func (s *Store) Set(incoming Settings) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	incoming = s.applyTokenPolicy(incoming)
	incoming.UpdatedAt = time.Now().UTC()
	s.cached = incoming
	return s.write()
}

// applyTokenPolicy ensures RemoteAccessToken is populated whenever remote
// access is enabled. Called under s.mu — must not acquire the lock itself.
//
//   - If the client sends a non-empty token, keep it as-is.
//   - If the client sends an empty token but one already exists (e.g. a full
//     read-modify-write that omitted the field), preserve the existing token.
//   - If no token exists yet and remote access is being enabled for the first
//     time, generate one with crypto/rand.
//   - If remote access is disabled, leave the token untouched (so it's still
//     there when the user re-enables it without needing to re-pair).
func (s *Store) applyTokenPolicy(incoming Settings) Settings {
	if !incoming.RemoteAccessEnabled {
		return incoming
	}
	if incoming.RemoteAccessToken != "" {
		// Client supplied a token explicitly — respect it.
		return incoming
	}
	if s.cached.RemoteAccessToken != "" {
		// Preserve the existing generated token across full-object PUTs that
		// omit the field (common when clients read-then-write the settings blob).
		incoming.RemoteAccessToken = s.cached.RemoteAccessToken
		return incoming
	}
	// First time enabling remote access: generate a token.
	token, err := generateToken()
	if err != nil {
		log.Println("settings: generate remote-access token:", err)
		return incoming
	}
	incoming.RemoteAccessToken = token
	return incoming
}

// MergeFrom replaces the cached settings with incoming (from a Supabase pull), but
// only if incoming is actually newer — otherwise a stale pull would silently revert
// a local edit that hasn't been pushed to Supabase yet (see library.MergeFrom for
// the same pattern applied to library entries).
//
// RemoteAccessEnabled and RemoteAccessToken are always preserved from the LOCAL
// (cached) values and are never overwritten by an incoming merge, regardless of
// which side has the newer UpdatedAt. These fields are device-local security
// configuration: propagating them via Supabase would silently open a LAN listener
// on every synced device (e.g. the phone's embedded backend), which is both a
// security concern and a functional bug. This mirrors the nuvio-config exclusion
// precedent — per-device runtime configuration must not roam across devices.
//
// OnboardingDone is a monotonic flag: once true it must never be reset. There is
// no redo-onboarding feature, so a fresh-device pull must not clobber a flag that
// was set on another device.
func (s *Store) MergeFrom(incoming Settings) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !incoming.UpdatedAt.After(s.cached.UpdatedAt) {
		return
	}
	// Always preserve device-local remote-access config (see comment above).
	incoming.RemoteAccessEnabled = s.cached.RemoteAccessEnabled
	incoming.RemoteAccessToken = s.cached.RemoteAccessToken
	// Ratchet: onboarding completion is irreversible — once true, never reset.
	incoming.OnboardingDone = incoming.OnboardingDone || s.cached.OnboardingDone
	s.cached = incoming
	if err := s.write(); err != nil {
		log.Println("settings: merge write:", err)
	}
}

// SetProfile reloads settings from the given profile's data file.
func (s *Store) SetProfile(profileID string) error {
	path, err := utils.ConfigPath(fmt.Sprintf("settings-%s.json", profileID))
	if err != nil {
		return err
	}
	cur := defaultSettings
	data, err := os.ReadFile(path)
	if err != nil && !os.IsNotExist(err) {
		return err
	}
	if err == nil {
		if err := json.Unmarshal(data, &cur); err != nil {
			return err
		}
	}
	s.mu.Lock()
	s.cached = cur
	s.path = path
	s.mu.Unlock()
	return nil
}

// SetupHandlers registers GET/PUT /api/settings on mux.
func (s *Store) SetupHandlers(mux *http.ServeMux) {
	mux.HandleFunc("/api/settings", utils.CorsMiddleware(func(w http.ResponseWriter, r *http.Request) {
		// GET /api/settings — return current settings
		if r.Method == http.MethodGet {
			s.mu.RLock()
			current := s.cached
			s.mu.RUnlock()

			w.Header().Set("Content-Type", "application/json")
			if err := json.NewEncoder(w).Encode(current); err != nil {
				log.Println("settings encode:", err)
			}
			return
		}

		// PUT /api/settings — validate, apply token policy, persist
		if r.Method == http.MethodPut {
			var incoming Settings
			if err := json.NewDecoder(r.Body).Decode(&incoming); err != nil {
				http.Error(w, "invalid body: "+err.Error(), http.StatusBadRequest)
				return
			}

			s.mu.Lock()
			incoming = s.applyTokenPolicy(incoming)
			incoming.UpdatedAt = time.Now().UTC()
			// Stale frontend stores PUT full blobs; once onboarding is done it
			// must not be reset by a store that loaded before completion was persisted.
			if s.cached.OnboardingDone {
				incoming.OnboardingDone = true
			}
			s.cached = incoming
			err := s.write()
			s.mu.Unlock()

			if err != nil {
				log.Println("settings write:", err)
				http.Error(w, "could not save settings", http.StatusInternalServerError)
				return
			}

			w.Header().Set("Content-Type", "application/json")
			s.mu.RLock()
			_ = json.NewEncoder(w).Encode(s.cached)
			s.mu.RUnlock()
			return
		}

		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}))
}
