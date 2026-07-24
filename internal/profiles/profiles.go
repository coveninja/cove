// Package profiles manages local, Netflix-style user profiles — not to be
// confused with content-rating/kid-mode, which is a separate, unrelated
// concept inside internal/discover. Switching the active profile reloads
// library, settings, and addons in place via an onChange callback the caller
// registers in New(), so those packages never need their own profile-
// switching logic.
package profiles

import (
	"crypto/rand"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/coveninja/cove/internal/utils"
)

// validProfileID matches the IDs this store generates (UUIDv4) with a little
// slack for remote-issued IDs. Profile IDs become file-name components
// (ProfileFileName), so the character set must exclude path separators and "..".
var validProfileID = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

var ErrActiveProfileChanged = fmt.Errorf("active profile changed while operation was in flight")

const maxProfileRequestBytes = 64 << 10

// Profile is one named identity within a Cove installation. A single Supabase
// account can own many profiles; each profile has its own library, settings,
// and addon config stored in separate files on disk.
type Profile struct {
	ID            string    `json:"id"`
	Name          string    `json:"name"`
	IsPrimary     bool      `json:"is_primary"`
	SupabaseUID   *string   `json:"supabase_uid"`    // nil = guest / local-only
	NameUpdatedAt time.Time `json:"name_updated_at"` // last time Name was changed; zero = unknown
}

type diskStore struct {
	Profiles        []Profile `json:"profiles"`
	ActiveProfileID string    `json:"active_profile_id"`
}

// Store owns the profile list and the currently active profile ID.
type Store struct {
	mu           sync.RWMutex
	transitionMu sync.Mutex
	disk         diskStore
	path         string
	revision     uint64
	onChange     func(previousID, profileID string) error                    // called when active profile switches
	onDelete     func(profileID string, supabaseUID *string, userJWT string) // called after deletion for remote cleanup
}

func cloneProfile(p Profile) Profile {
	if p.SupabaseUID != nil {
		uid := *p.SupabaseUID
		p.SupabaseUID = &uid
	}
	return p
}

func cloneProfiles(in []Profile) []Profile {
	out := make([]Profile, len(in))
	for i := range in {
		out[i] = cloneProfile(in[i])
	}
	return out
}

func cloneDiskStore(in diskStore) diskStore {
	return diskStore{
		Profiles:        cloneProfiles(in.Profiles),
		ActiveProfileID: in.ActiveProfileID,
	}
}

// normalizeDiskStore validates the invariants relied on by profile-scoped
// stores and repairs safe inconsistencies caused by an interrupted or
// hand-edited profiles.json. Invalid or duplicate IDs cannot be repaired
// without risking association with the wrong data files, so those fail
// startup explicitly.
func normalizeDiskStore(d *diskStore) (bool, error) {
	if len(d.Profiles) == 0 {
		primary := Profile{ID: newUUID(), Name: "Primary", IsPrimary: true}
		d.Profiles = []Profile{primary}
		d.ActiveProfileID = primary.ID
		return true, nil
	}

	changed := false
	seen := make(map[string]struct{}, len(d.Profiles))
	primaryIdx := -1
	activeFound := false
	for i := range d.Profiles {
		p := &d.Profiles[i]
		if !validProfileID.MatchString(p.ID) {
			return false, fmt.Errorf("invalid profile id %q in profiles store", p.ID)
		}
		if _, ok := seen[p.ID]; ok {
			return false, fmt.Errorf("duplicate profile id %q in profiles store", p.ID)
		}
		seen[p.ID] = struct{}{}
		if p.ID == d.ActiveProfileID {
			activeFound = true
		}
		if p.IsPrimary {
			if primaryIdx == -1 {
				primaryIdx = i
			} else {
				p.IsPrimary = false
				changed = true
			}
		}
	}
	if primaryIdx == -1 {
		primaryIdx = 0
		d.Profiles[primaryIdx].IsPrimary = true
		changed = true
	}
	if !activeFound {
		d.ActiveProfileID = d.Profiles[primaryIdx].ID
		changed = true
	}
	return changed, nil
}

// New loads profiles.json from the per-user config directory. On first run it
// creates a "Primary" profile and migrates any pre-existing library.json /
// settings.json / addons.json into profile-scoped filenames.
// onChange is invoked whenever the active profile changes; SetActive and
// AdoptID invoke it synchronously, while deletion invokes it asynchronously.
// The caller should reload library, settings, and addons for the new ID.
func New(onChange func(profileID string)) (*Store, error) {
	s := &Store{}
	if onChange != nil {
		s.onChange = func(_ string, profileID string) error {
			onChange(profileID)
			return nil
		}
	}

	path, err := utils.ConfigPath("profiles.json")
	if err != nil {
		return nil, err
	}
	s.path = path

	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		// First run: create primary profile and migrate legacy flat files.
		primary := Profile{ID: newUUID(), Name: "Primary", IsPrimary: true}
		s.disk = diskStore{
			Profiles:        []Profile{primary},
			ActiveProfileID: primary.ID,
		}
		if err := s.write(); err != nil {
			return nil, err
		}
		// Persist the ID before moving legacy data to it. If the write fails,
		// leaving the legacy filenames untouched makes the next startup safe
		// to retry instead of stranding data under an unrecorded random ID.
		migrateLegacyFiles(primary.ID)
		return s, nil
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(data, &s.disk); err != nil {
		return nil, err
	}
	changed, err := normalizeDiskStore(&s.disk)
	if err != nil {
		return nil, err
	}
	if changed {
		if err := s.write(); err != nil {
			return nil, err
		}
	}
	// Migration is idempotent, so retry it on later startups as well. This
	// completes any files left under legacy names if the first startup
	// stopped after profiles.json was persisted but before every rename.
	for _, p := range s.disk.Profiles {
		if p.IsPrimary {
			migrateLegacyFiles(p.ID)
			break
		}
	}
	return s, nil
}

// SetOnChange installs a transactional active-profile transition hook.
//
// The hook receives the previous and requested profile IDs. If it returns an
// error, Store restores the previous active ID on disk and invokes the hook in
// the reverse direction so callers can restore any subsystem state they changed
// before detecting the failure.
func (s *Store) SetOnChange(fn func(previousID, profileID string) error) {
	s.transitionMu.Lock()
	defer s.transitionMu.Unlock()
	s.mu.Lock()
	s.onChange = fn
	s.mu.Unlock()
}

// migrateLegacyFiles renames legacy single-user files to profile-scoped names.
func migrateLegacyFiles(profileID string) {
	for _, name := range []string{"library", "settings", "addons"} {
		src, err := utils.ConfigPath(name + ".json")
		if err != nil {
			continue
		}
		if _, err := os.Stat(src); os.IsNotExist(err) {
			continue
		}
		dst, err := utils.ConfigPath(name + "-" + profileID + ".json")
		if err != nil {
			continue
		}
		if _, err := os.Stat(dst); err == nil {
			continue // destination already exists; don't overwrite
		}
		if err := os.Rename(src, dst); err != nil {
			log.Printf("profiles: migrate %s.json: %v", name, err)
		} else {
			log.Printf("profiles: migrated %s.json → %s-%s.json", name, name, profileID)
		}
	}
}

func (s *Store) write() error {
	data, err := json.MarshalIndent(s.disk, "", "  ")
	if err != nil {
		return err
	}
	return utils.AtomicWriteFile(s.path, data, 0o644)
}

// ActiveProfile returns the currently active profile. Safe for concurrent use.
func (s *Store) ActiveProfile() Profile {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, p := range s.disk.Profiles {
		if p.ID == s.disk.ActiveProfileID {
			return cloneProfile(p)
		}
	}
	if len(s.disk.Profiles) > 0 {
		return cloneProfile(s.disk.Profiles[0])
	}
	return Profile{}
}

// ActiveProfileID returns the active profile's ID. Safe for concurrent use.
func (s *Store) ActiveProfileID() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.disk.ActiveProfileID
}

// ActiveSnapshot returns the active profile and a transition revision. The
// transition lock closes the window where profiles.json has changed but
// profile-scoped stores are still reloading.
func (s *Store) ActiveSnapshot() (Profile, uint64) {
	s.transitionMu.Lock()
	defer s.transitionMu.Unlock()
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, p := range s.disk.Profiles {
		if p.ID == s.disk.ActiveProfileID {
			return cloneProfile(p), s.revision
		}
	}
	return Profile{}, s.revision
}

// WithActiveSnapshot runs fn only while the requested active profile revision
// is still current. Profile transitions wait for fn, preventing a caller from
// reading one profile's live stores and labelling the result with another
// profile ID.
func (s *Store) WithActiveSnapshot(profileID string, revision uint64, fn func() error) error {
	s.transitionMu.Lock()
	defer s.transitionMu.Unlock()
	s.mu.RLock()
	currentID := s.disk.ActiveProfileID
	currentRevision := s.revision
	s.mu.RUnlock()
	if currentID != profileID || currentRevision != revision {
		return ErrActiveProfileChanged
	}
	return fn()
}

// All returns a snapshot of all profiles.
func (s *Store) All() []Profile {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return cloneProfiles(s.disk.Profiles)
}

// SetOnDelete registers a hook called after a profile is deleted, so the
// caller can clean up remote data. It runs asynchronously from the HTTP
// handler goroutine.
func (s *Store) SetOnDelete(fn func(profileID string, supabaseUID *string, userJWT string)) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.onDelete = fn
}

// Get returns a copy of the profile with the given ID, and whether it was found.
func (s *Store) Get(id string) (Profile, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	for _, p := range s.disk.Profiles {
		if p.ID == id {
			return cloneProfile(p), true
		}
	}
	return Profile{}, false
}

// SetActive switches to the given profile ID and triggers onChange.
func (s *Store) SetActive(id string) error {
	s.transitionMu.Lock()
	defer s.transitionMu.Unlock()

	s.mu.Lock()
	found := false
	for _, p := range s.disk.Profiles {
		if p.ID == id {
			found = true
			break
		}
	}
	if !found {
		s.mu.Unlock()
		return fmt.Errorf("profile %q not found", id)
	}
	if s.disk.ActiveProfileID == id {
		s.mu.Unlock()
		return nil
	}
	before := cloneDiskStore(s.disk)
	previousID := s.disk.ActiveProfileID
	s.disk.ActiveProfileID = id
	if err := s.write(); err != nil {
		s.disk = before
		s.mu.Unlock()
		return err
	}
	onChange := s.onChange
	s.mu.Unlock()
	if onChange != nil {
		if err := onChange(previousID, id); err != nil {
			s.mu.Lock()
			s.disk = before
			rollbackErr := s.write()
			s.mu.Unlock()
			reloadErr := onChange(id, previousID)
			if rollbackErr != nil || reloadErr != nil {
				return fmt.Errorf("switch profile: %w (rollback store: %v; rollback reload: %v)", err, rollbackErr, reloadErr)
			}
			return fmt.Errorf("switch profile: %w", err)
		}
	}
	s.mu.Lock()
	s.revision++
	s.mu.Unlock()
	return nil
}

// Create adds a new profile and returns it.
func (s *Store) Create(name string) (Profile, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	before := cloneDiskStore(s.disk)
	p := Profile{ID: newUUID(), Name: name}
	s.disk.Profiles = append(s.disk.Profiles, p)
	if err := s.write(); err != nil {
		s.disk = before
		return Profile{}, err
	}
	return p, nil
}

// Rename updates a profile's display name and records the change timestamp.
func (s *Store) Rename(id, name string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i, p := range s.disk.Profiles {
		if p.ID == id {
			before := cloneDiskStore(s.disk)
			s.disk.Profiles[i].Name = name
			s.disk.Profiles[i].NameUpdatedAt = time.Now()
			if err := s.write(); err != nil {
				s.disk = before
				return err
			}
			return nil
		}
	}
	return fmt.Errorf("profile %q not found", id)
}

// RenameFromRemote updates a profile's display name using an explicit timestamp
// (from a remote pull), rather than time.Now(). Used by the sync pull path so
// the stored NameUpdatedAt reflects the actual remote mutation time and LWW
// comparisons stay accurate across devices.
func (s *Store) RenameFromRemote(id, name string, updatedAt time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i, p := range s.disk.Profiles {
		if p.ID == id {
			before := cloneDiskStore(s.disk)
			s.disk.Profiles[i].Name = name
			s.disk.Profiles[i].NameUpdatedAt = updatedAt
			if err := s.write(); err != nil {
				s.disk = before
				return err
			}
			return nil
		}
	}
	return fmt.Errorf("profile %q not found", id)
}

// UnlinkSupabase clears the Supabase user ID from a profile, making it local-only.
func (s *Store) UnlinkSupabase(profileID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i, p := range s.disk.Profiles {
		if p.ID == profileID {
			before := cloneDiskStore(s.disk)
			s.disk.Profiles[i].SupabaseUID = nil
			if err := s.write(); err != nil {
				s.disk = before
				return err
			}
			return nil
		}
	}
	return fmt.Errorf("profile %q not found", profileID)
}

// Delete removes a profile and its per-profile data files from disk. Returns
// an error if the profile does not exist or is the primary profile. If the
// deleted profile was active, the primary profile becomes active through the
// same synchronous, reversible transition used by SetActive.
func (s *Store) Delete(id string) error {
	s.transitionMu.Lock()
	defer s.transitionMu.Unlock()

	s.mu.Lock()
	idx := -1
	for i, p := range s.disk.Profiles {
		if p.ID == id {
			if p.IsPrimary {
				s.mu.Unlock()
				return fmt.Errorf("cannot delete the primary profile")
			}
			idx = i
			break
		}
	}
	if idx == -1 {
		s.mu.Unlock()
		return fmt.Errorf("profile %q not found", id)
	}
	before := cloneDiskStore(s.disk)
	wasActive := s.disk.ActiveProfileID == id
	s.disk.Profiles = append(s.disk.Profiles[:idx], s.disk.Profiles[idx+1:]...)
	newActiveID := s.disk.ActiveProfileID
	if wasActive {
		newActiveID = ""
		for _, pp := range s.disk.Profiles {
			if pp.IsPrimary {
				newActiveID = pp.ID
				break
			}
		}
		if newActiveID == "" && len(s.disk.Profiles) > 0 {
			newActiveID = s.disk.Profiles[0].ID
		}
		s.disk.ActiveProfileID = newActiveID
	}
	if err := s.write(); err != nil {
		s.disk = before
		s.mu.Unlock()
		return err
	}
	onChange := s.onChange
	s.mu.Unlock()

	if wasActive && onChange != nil {
		if err := onChange(id, newActiveID); err != nil {
			s.mu.Lock()
			s.disk = before
			rollbackErr := s.write()
			s.mu.Unlock()
			reloadErr := onChange(newActiveID, id)
			if rollbackErr != nil || reloadErr != nil {
				return fmt.Errorf("delete profile: switch active profile: %w (rollback store: %v; rollback reload: %v)", err, rollbackErr, reloadErr)
			}
			return fmt.Errorf("delete profile: switch active profile: %w", err)
		}
	}
	if wasActive {
		s.mu.Lock()
		s.revision++
		s.mu.Unlock()
	}

	for _, base := range []string{"library", "settings", "addons", "nuvio", "activity"} {
		path, err := utils.ConfigPath(ProfileFileName(base, id))
		if err != nil {
			continue
		}
		if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
			log.Printf("profiles: remove %s: %v", ProfileFileName(base, id), err)
		}
	}

	return nil
}

// AdoptID rewrites a local profile's ID to newID, renaming its per-profile
// data files to the new name. Used when a signed-in account already owns a
// remote profile: the local profile adopts the remote identity so every
// device pulls and pushes the same rows. Unlike SetActive, onChange runs
// synchronously so the caller can merge remote data into the reloaded stores
// as soon as this returns.
func (s *Store) AdoptID(oldID, newID string) error {
	s.transitionMu.Lock()
	defer s.transitionMu.Unlock()

	if !validProfileID.MatchString(newID) {
		return fmt.Errorf("invalid profile id %q", newID)
	}
	s.mu.Lock()
	idx := -1
	for i, p := range s.disk.Profiles {
		if p.ID == newID {
			s.mu.Unlock()
			return fmt.Errorf("profile %q already exists", newID)
		}
		if p.ID == oldID {
			idx = i
		}
	}
	if idx == -1 {
		s.mu.Unlock()
		return fmt.Errorf("profile %q not found", oldID)
	}

	type fileMove struct {
		src string
		dst string
	}
	var moves []fileMove
	rollbackMoves := func() {
		for i := len(moves) - 1; i >= 0; i-- {
			if err := os.Rename(moves[i].dst, moves[i].src); err != nil {
				log.Printf("profiles: roll back adoption file %s: %v", moves[i].src, err)
			}
		}
	}
	for _, base := range []string{"library", "settings", "addons", "nuvio", "activity"} {
		src, err1 := utils.ConfigPath(ProfileFileName(base, oldID))
		dst, err2 := utils.ConfigPath(ProfileFileName(base, newID))
		if err1 != nil {
			rollbackMoves()
			s.mu.Unlock()
			return fmt.Errorf("resolve source profile data path for %s: %w", base, err1)
		}
		if err2 != nil {
			rollbackMoves()
			s.mu.Unlock()
			return fmt.Errorf("resolve destination profile data path for %s: %w", base, err2)
		}
		info, err := os.Stat(src)
		if os.IsNotExist(err) {
			continue
		}
		if err != nil {
			rollbackMoves()
			s.mu.Unlock()
			return fmt.Errorf("stat %s: %w", ProfileFileName(base, oldID), err)
		}
		if !info.Mode().IsRegular() {
			rollbackMoves()
			s.mu.Unlock()
			return fmt.Errorf("%s is not a regular file", ProfileFileName(base, oldID))
		}
		if _, err := os.Stat(dst); err == nil {
			rollbackMoves()
			s.mu.Unlock()
			return fmt.Errorf("%s already exists", ProfileFileName(base, newID))
		} else if !os.IsNotExist(err) {
			rollbackMoves()
			s.mu.Unlock()
			return fmt.Errorf("stat %s: %w", ProfileFileName(base, newID), err)
		}
		if err := os.Rename(src, dst); err != nil {
			rollbackMoves()
			s.mu.Unlock()
			return fmt.Errorf("adopt %s: %w", ProfileFileName(base, oldID), err)
		}
		moves = append(moves, fileMove{src: src, dst: dst})
	}
	before := cloneDiskStore(s.disk)
	s.disk.Profiles[idx].ID = newID
	isActive := s.disk.ActiveProfileID == oldID
	if isActive {
		s.disk.ActiveProfileID = newID
	}
	if err := s.write(); err != nil {
		s.disk = before
		rollbackMoves()
		s.mu.Unlock()
		return err
	}
	onChange := s.onChange
	s.mu.Unlock()
	if isActive && onChange != nil {
		if err := onChange(oldID, newID); err != nil {
			s.mu.Lock()
			s.disk = before
			rollbackStoreErr := s.write()
			s.mu.Unlock()
			rollbackMoves()
			reloadErr := onChange(newID, oldID)
			if rollbackStoreErr != nil || reloadErr != nil {
				return fmt.Errorf("adopt profile id: reload: %w (rollback store: %v; rollback reload: %v)", err, rollbackStoreErr, reloadErr)
			}
			return fmt.Errorf("adopt profile id: reload: %w", err)
		}
	}
	if isActive {
		s.mu.Lock()
		s.revision++
		s.mu.Unlock()
	}
	return nil
}

// LinkSupabase stores the Supabase user ID on a profile.
func (s *Store) LinkSupabase(profileID, supabaseUID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i, p := range s.disk.Profiles {
		if p.ID == profileID {
			before := cloneDiskStore(s.disk)
			s.disk.Profiles[i].SupabaseUID = &supabaseUID
			if err := s.write(); err != nil {
				s.disk = before
				return err
			}
			return nil
		}
	}
	return fmt.Errorf("profile %q not found", profileID)
}

// ProfileFileName returns the profile-scoped filename for a data file.
// e.g. ProfileFileName("library", profileID) → "library-<id>.json"
func ProfileFileName(base, profileID string) string {
	return base + "-" + profileID + ".json"
}

// SetupHandlers registers profile CRUD endpoints on mux.
func (s *Store) SetupHandlers(mux *http.ServeMux) {
	mux.HandleFunc("/api/profiles", utils.CorsMiddleware(s.handleList))
	mux.HandleFunc("/api/profiles/", utils.CorsMiddleware(s.handleByID))
}

func jsonOK(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}

func (s *Store) handleList(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		s.mu.RLock()
		resp := map[string]any{
			"profiles":          cloneProfiles(s.disk.Profiles),
			"active_profile_id": s.disk.ActiveProfileID,
		}
		s.mu.RUnlock()
		jsonOK(w, resp)

	case http.MethodPost:
		r.Body = http.MaxBytesReader(w, r.Body, maxProfileRequestBytes)
		var body struct {
			Name string `json:"name"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil || strings.TrimSpace(body.Name) == "" {
			http.Error(w, "name required", http.StatusBadRequest)
			return
		}
		p, err := s.Create(strings.TrimSpace(body.Name))
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		jsonOK(w, p)

	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (s *Store) handleByID(w http.ResponseWriter, r *http.Request) {
	trimmed := strings.TrimPrefix(r.URL.Path, "/api/profiles/")
	parts := strings.SplitN(trimmed, "/", 2)
	id := parts[0]
	sub := ""
	if len(parts) == 2 {
		sub = parts[1]
	}

	if !validProfileID.MatchString(id) {
		http.Error(w, "invalid profile id", http.StatusBadRequest)
		return
	}

	if sub != "" {
		if sub != "activate" {
			http.NotFound(w, r)
			return
		}
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		if _, ok := s.Get(id); !ok {
			http.Error(w, "profile not found", http.StatusBadRequest)
			return
		}
		if err := s.SetActive(id); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		jsonOK(w, s.ActiveProfile())
		return
	}

	switch r.Method {
	case http.MethodPatch:
		r.Body = http.MaxBytesReader(w, r.Body, maxProfileRequestBytes)
		var body struct {
			Name string `json:"name"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil || strings.TrimSpace(body.Name) == "" {
			http.Error(w, "name required", http.StatusBadRequest)
			return
		}
		if _, ok := s.Get(id); !ok {
			http.Error(w, "profile not found", http.StatusBadRequest)
			return
		}
		name := strings.TrimSpace(body.Name)
		if err := s.Rename(id, name); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		jsonOK(w, map[string]string{"id": id, "name": name})

	case http.MethodDelete:
		p, ok := s.Get(id)
		if !ok {
			http.Error(w, "profile not found", http.StatusBadRequest)
			return
		}
		if p.IsPrimary {
			http.Error(w, "cannot delete the primary profile", http.StatusBadRequest)
			return
		}
		if err := s.Delete(id); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		s.mu.RLock()
		onDelete := s.onDelete
		s.mu.RUnlock()
		if onDelete != nil {
			jwt := bearerFromRequest(r)
			go onDelete(id, p.SupabaseUID, jwt)
		}
		w.WriteHeader(http.StatusNoContent)

	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

// bearerFromRequest extracts the JWT from an Authorization: Bearer header.
// Returns "" if the header is absent or malformed.
func bearerFromRequest(r *http.Request) string {
	h := r.Header.Get("Authorization")
	if !strings.HasPrefix(h, "Bearer ") {
		return ""
	}
	return strings.TrimPrefix(h, "Bearer ")
}

// newUUID generates a random UUIDv4.
func newUUID() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x",
		b[0:4], b[4:6], b[6:8], b[8:10], b[10:])
}
