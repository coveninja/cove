// Package trakt provides Trakt.tv account linking, live scrobbling, and
// two-way history/watchlist sync. Always compiled (no build tag) — Trakt is
// an "official" integration like JustWatch, not a proprietary subsystem like
// Supabase. All Trakt-API credentials stay server-side; the frontend only
// receives connection status (username, expiry) and sends scrobble events.
package trakt

import (
	"encoding/json"
	"fmt"
	"os"
	"sync"
	"time"

	"github.com/coveninja/cove/internal/utils"
)

// tokenState is the persisted Trakt OAuth token for one profile.
type tokenState struct {
	AccessToken  string    `json:"access_token"`
	RefreshToken string    `json:"refresh_token"`
	ExpiresAt    time.Time `json:"expires_at"`
	Username     string    `json:"username"`
	LastSyncAt   time.Time `json:"last_sync_at"`
}

// Store persists tokenState to trakt-{profileID}.json in the app config dir.
// The file is 0600 (token is sensitive) and written atomically.
type Store struct {
	mu    sync.RWMutex
	state tokenState
	path  string
}

// newStore constructs a Store for the given profile and loads any existing
// token from disk. Missing file is normal (profile not yet linked).
func newStore(profileID string) (*Store, error) {
	s := &Store{}
	return s, s.load(profileID)
}

func (s *Store) load(profileID string) error {
	path, err := utils.ConfigPath(fmt.Sprintf("trakt-%s.json", profileID))
	if err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.path = path
	s.state = tokenState{}

	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return nil // not yet linked — not an error
	}
	if err != nil {
		return err
	}
	return json.Unmarshal(data, &s.state)
}

// SetProfile swaps the store to the new profile's sidecar file.
func (s *Store) SetProfile(profileID string) error {
	path, err := utils.ConfigPath(fmt.Sprintf("trakt-%s.json", profileID))
	if err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.path = path
	s.state = tokenState{}

	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	return json.Unmarshal(data, &s.state)
}

// Get returns a snapshot of the current token state. Safe for concurrent use.
func (s *Store) Get() tokenState {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.state
}

// Save persists st to disk and updates the in-memory state. Must be called
// with an accurate s.path (i.e. after SetProfile or newStore).
func (s *Store) Save(st tokenState) error {
	data, err := json.MarshalIndent(st, "", "  ")
	if err != nil {
		return err
	}
	if err := utils.AtomicWriteFile(s.path, data, 0o600); err != nil {
		return err
	}
	s.mu.Lock()
	s.state = st
	s.mu.Unlock()
	return nil
}

// Clear removes the sidecar file and resets in-memory state. Used by Unlink.
func (s *Store) Clear() error {
	s.mu.Lock()
	path := s.path
	s.state = tokenState{}
	s.mu.Unlock()

	err := os.Remove(path)
	if os.IsNotExist(err) {
		return nil
	}
	return err
}

// IsConnected reports whether an access token is present (does not check expiry).
func (s *Store) IsConnected() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.state.AccessToken != ""
}
