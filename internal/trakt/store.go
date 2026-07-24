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
	mu       sync.RWMutex
	state    tokenState
	path     string
	revision uint64 // increments when profile identity or link state is reset
}

// newStore constructs a Store for the given profile and loads any existing
// token from disk. Missing file is normal (profile not yet linked).
func newStore(profileID string) (*Store, error) {
	s := &Store{}
	return s, s.load(profileID)
}

func readTokenState(profileID string) (string, tokenState, error) {
	path, err := utils.ConfigPath(fmt.Sprintf("trakt-%s.json", profileID))
	if err != nil {
		return "", tokenState{}, err
	}

	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return path, tokenState{}, nil // not yet linked — not an error
	}
	if err != nil {
		return path, tokenState{}, err
	}
	var state tokenState
	if err := json.Unmarshal(data, &state); err != nil {
		return path, tokenState{}, err
	}
	return path, state, nil
}

func (s *Store) load(profileID string) error {
	path, state, err := readTokenState(profileID)
	s.mu.Lock()
	if path != "" {
		// Retain the resolved path even when an existing sidecar is corrupt so
		// a subsequent successful authorization can replace and recover it.
		s.path = path
	}
	if err == nil {
		s.state = state
	}
	s.mu.Unlock()
	return err
}

// SetProfile swaps the store to the new profile's sidecar file.
func (s *Store) SetProfile(profileID string) error {
	path, state, err := readTokenState(profileID)
	if err != nil {
		return err
	}
	s.mu.Lock()
	s.path = path
	s.state = state
	s.revision++
	s.mu.Unlock()
	return nil
}

// Get returns a snapshot of the current token state. Safe for concurrent use.
func (s *Store) Get() tokenState {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.state
}

// Snapshot returns the token state together with the current store revision.
// Long-running operations use the revision with SaveIfRevision so a response
// started for an old profile cannot write into a newly selected profile.
func (s *Store) Snapshot() (tokenState, uint64) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.state, s.revision
}

// Save persists st to disk and updates the in-memory state. Must be called
// with an accurate s.path (i.e. after SetProfile or newStore).
func (s *Store) Save(st tokenState) error {
	return s.save(st, nil)
}

// SaveIfRevision is Save with an optimistic profile/link-state guard.
func (s *Store) SaveIfRevision(st tokenState, revision uint64) error {
	return s.save(st, &revision)
}

func (s *Store) save(st tokenState, expectedRevision *uint64) error {
	data, err := json.MarshalIndent(st, "", "  ")
	if err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if expectedRevision != nil && s.revision != *expectedRevision {
		return fmt.Errorf("trakt: token store changed while request was in flight")
	}
	path := s.path
	if path == "" {
		return fmt.Errorf("trakt: token store path is not set")
	}
	if err := utils.AtomicWriteFile(path, data, 0o600); err != nil {
		return err
	}
	s.state = st
	return nil
}

// Clear removes the sidecar file and resets in-memory state. Used by Unlink.
func (s *Store) Clear() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	path := s.path
	if path == "" {
		return fmt.Errorf("trakt: token store path is not set")
	}

	err := os.Remove(path)
	if err != nil && !os.IsNotExist(err) {
		return err
	}
	s.state = tokenState{}
	s.revision++
	return nil
}

// IsConnected reports whether an access token is present (does not check expiry).
func (s *Store) IsConnected() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.state.AccessToken != ""
}
