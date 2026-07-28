// Package library is the local watch-history store: library entries (status,
// rating), per-episode watch progress, and "not interested" dismissals,
// persisted as JSON per profile under the OS config directory. It exposes
// TasteSignals() and Generation() as the sole interface the recommendation
// engine (internal/discover) consumes — neither package imports the other,
// so a personalization feature can evolve independently of how watch history
// is stored.
package library

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coveninja/cove/internal/utils"
)

// ── Types ──────────────────────────────────────────────────────────────────────
//
// All struct fields use JSON snake_case to match Supabase column names directly.

type Status = string

const (
	StatusWatchLater Status = "watch_later"
	StatusWatching   Status = "watching"
	StatusFinished   Status = "finished"
	StatusDropped    Status = "dropped"
)

func validStatus(status Status) bool {
	switch status {
	case StatusWatchLater, StatusWatching, StatusFinished, StatusDropped:
		return true
	default:
		return false
	}
}

// LibraryEntry mirrors the `library_entries` Supabase table.
type LibraryEntry struct {
	ID          string   `json:"id"` // UUIDv4
	ProfileID   *string  `json:"profile_id"`
	TmdbID      int      `json:"tmdb_id"`
	MediaType   string   `json:"media_type"` // "movie" | "tv"
	Title       string   `json:"title"`
	PosterPath  string   `json:"poster_path"`
	Status      Status   `json:"status"`
	Rating      *float64 `json:"rating"`       // 0.0–5.0; null = unrated
	VoteAverage float64  `json:"vote_average"` // TMDB community rating, stored for offline display
	// TV tracking — used for "new episodes" detection and resume labels
	LastAirDate        string     `json:"last_air_date"`        // TMDB last_air_date; empty for movies
	LastWatchedAt      *time.Time `json:"last_watched_at"`      // updated every time progress is saved
	LastWatchedSeason  *int       `json:"last_watched_season"`  // most recently watched TV episode
	LastWatchedEpisode *int       `json:"last_watched_episode"` // most recently watched TV episode
	LastAiredSeason    *int       `json:"last_aired_season"`    // TMDB last_episode_to_air.season_number
	LastAiredEpisode   *int       `json:"last_aired_episode"`   // TMDB last_episode_to_air.episode_number
	AddedAt            time.Time  `json:"added_at"`
	UpdatedAt          time.Time  `json:"updated_at"`
}

// WatchProgress mirrors the `watch_progress` Supabase table.
// One row per unique (tmdb_id, media_type, season, episode) tuple.
type WatchProgress struct {
	ID              string    `json:"id"`
	ProfileID       *string   `json:"profile_id"`
	LibraryEntryID  string    `json:"library_entry_id"`
	TmdbID          int       `json:"tmdb_id"`
	MediaType       string    `json:"media_type"`
	Season          *int      `json:"season"`  // null for movies
	Episode         *int      `json:"episode"` // null for movies
	PositionSeconds float64   `json:"position_seconds"`
	DurationSeconds float64   `json:"duration_seconds"`
	Completed       bool      `json:"completed"`
	WatchedAt       time.Time `json:"watched_at"`
}

// Dismissal records a "not interested" — a title to keep out of recommendations
// and to nudge taste away from, without it being a library shelf entry.
type Dismissal struct {
	TmdbID      int       `json:"tmdb_id"`
	MediaType   string    `json:"media_type"`
	DismissedAt time.Time `json:"dismissed_at"`
}

// Removal is a tombstone recording that a title was intentionally removed
// from the library, so a sync pull cannot resurrect it. Synced like Dismissal.
type Removal struct {
	TmdbID    int       `json:"tmdb_id"`
	MediaType string    `json:"media_type"`
	RemovedAt time.Time `json:"removed_at"`
}

// diskStore is the on-disk JSON format.
type diskStore struct {
	Entries   map[string]*LibraryEntry  `json:"entries"`   // key: entryKey()
	Progress  map[string]*WatchProgress `json:"progress"`  // key: progressKey()
	Dismissed map[string]*Dismissal     `json:"dismissed"` // key: entryKey()
	Removed   map[string]*Removal       `json:"removed"`   // key: entryKey()
}

func emptyDiskStore() diskStore {
	return diskStore{
		Entries:   make(map[string]*LibraryEntry),
		Progress:  make(map[string]*WatchProgress),
		Dismissed: make(map[string]*Dismissal),
		Removed:   make(map[string]*Removal),
	}
}

func cloneDiskStore(source diskStore) diskStore {
	cloned := emptyDiskStore()
	for key, entry := range source.Entries {
		cloned.Entries[key] = cloneEntry(entry)
	}
	for key, progress := range source.Progress {
		cloned.Progress[key] = cloneProgress(progress)
	}
	for key, dismissal := range source.Dismissed {
		cloned.Dismissed[key] = cloneDismissal(dismissal)
	}
	for key, removal := range source.Removed {
		cloned.Removed[key] = cloneRemoval(removal)
	}
	return cloned
}

// ensureMaps normalizes syntactically valid files containing explicit null map
// fields. Missing fields retain the pre-initialized maps during unmarshal, but
// explicit null values replace them with nil and would otherwise make the next
// mutation panic.
func (d *diskStore) ensureMaps() {
	if d.Entries == nil {
		d.Entries = make(map[string]*LibraryEntry)
	}
	for key, entry := range d.Entries {
		if entry == nil {
			delete(d.Entries, key)
		}
	}
	if d.Progress == nil {
		d.Progress = make(map[string]*WatchProgress)
	}
	for key, progress := range d.Progress {
		if progress == nil {
			delete(d.Progress, key)
		}
	}
	if d.Dismissed == nil {
		d.Dismissed = make(map[string]*Dismissal)
	}
	for key, dismissal := range d.Dismissed {
		if dismissal == nil {
			delete(d.Dismissed, key)
		}
	}
	if d.Removed == nil {
		d.Removed = make(map[string]*Removal)
	}
	for key, removal := range d.Removed {
		if removal == nil {
			delete(d.Removed, key)
		}
	}
}

func clonePtr[T any](p *T) *T {
	if p == nil {
		return nil
	}
	cp := *p
	return &cp
}

func cloneEntry(e *LibraryEntry) *LibraryEntry {
	if e == nil {
		return nil
	}
	cp := *e
	cp.ProfileID = clonePtr(e.ProfileID)
	cp.Rating = clonePtr(e.Rating)
	cp.LastWatchedAt = clonePtr(e.LastWatchedAt)
	cp.LastWatchedSeason = clonePtr(e.LastWatchedSeason)
	cp.LastWatchedEpisode = clonePtr(e.LastWatchedEpisode)
	cp.LastAiredSeason = clonePtr(e.LastAiredSeason)
	cp.LastAiredEpisode = clonePtr(e.LastAiredEpisode)
	return &cp
}

func cloneProgress(p *WatchProgress) *WatchProgress {
	if p == nil {
		return nil
	}
	cp := *p
	cp.ProfileID = clonePtr(p.ProfileID)
	cp.Season = clonePtr(p.Season)
	cp.Episode = clonePtr(p.Episode)
	return &cp
}

func cloneDismissal(d *Dismissal) *Dismissal {
	if d == nil {
		return nil
	}
	cp := *d
	return &cp
}

func cloneRemoval(r *Removal) *Removal {
	if r == nil {
		return nil
	}
	cp := *r
	return &cp
}

// relinkProgress updates orphaned watch history after a removed title is
// re-added under a fresh entry UUID. Must be called with l.mu held.
func (l *Library) relinkProgress(entry *LibraryEntry) {
	for _, progress := range l.db.Progress {
		if progress.TmdbID == entry.TmdbID && progress.MediaType == entry.MediaType {
			progress.LibraryEntryID = entry.ID
		}
	}
}

// TasteSignal is the minimal per-title signal the discover package needs,
// without exposing the library's internals.
type TasteSignal struct {
	TmdbID     int
	MediaType  string
	Status     Status
	UserRating *float64 // user's 0–5 rating; nil if unrated
	Completed  bool     // any progress record for this title is completed
	Dismissed  bool
	// Title and PosterPath are populated for entry-backed signals so that
	// downstream consumers (e.g. discover's contributing-title view) can
	// render a poster row without a second TMDB lookup. The PosterPath is
	// already rewritten to route through the local image-cache proxy.
	// Progress-only and dismissal-only signals leave these empty.
	Title      string
	PosterPath string
	// LastInteractionAt is the most recent time the user touched this title
	// (rated/status change, a watch, or a dismissal). Lets discover decay old
	// signals instead of weighing a five-year-old favorite the same as
	// yesterday's.
	LastInteractionAt time.Time
}

// ProgressSaveEvent is emitted (best-effort, outside any lock) on every
// successful POST to /api/library/progress. It carries enough context for an
// activity log to credit watch-time deltas without knowing library internals.
type ProgressSaveEvent struct {
	ProgressKey string // library's progressKey() value for this record
	TmdbID      int
	MediaType   string
	Position    float64
	Duration    float64
	Completed   bool
	At          time.Time
}

// AllEntries returns a snapshot of all library entries. Used for sync.
func (l *Library) AllEntries() []*LibraryEntry {
	l.mu.RLock()
	defer l.mu.RUnlock()
	out := make([]*LibraryEntry, 0, len(l.db.Entries))
	for _, e := range l.db.Entries {
		out = append(out, cloneEntry(e))
	}
	return out
}

// AllProgress returns a snapshot of all watch progress records. Used for sync.
func (l *Library) AllProgress() []*WatchProgress {
	l.mu.RLock()
	defer l.mu.RUnlock()
	out := make([]*WatchProgress, 0, len(l.db.Progress))
	for _, p := range l.db.Progress {
		out = append(out, cloneProgress(p))
	}
	return out
}

// AllDismissals returns a snapshot of all dismissal records. Used for sync.
func (l *Library) AllDismissals() []*Dismissal {
	l.mu.RLock()
	defer l.mu.RUnlock()
	out := make([]*Dismissal, 0, len(l.db.Dismissed))
	for _, d := range l.db.Dismissed {
		out = append(out, cloneDismissal(d))
	}
	return out
}

// AllRemovals returns a snapshot of all removal tombstone records. Used for sync.
func (l *Library) AllRemovals() []*Removal {
	l.mu.RLock()
	defer l.mu.RUnlock()
	out := make([]*Removal, 0, len(l.db.Removed))
	for _, r := range l.db.Removed {
		out = append(out, cloneRemoval(r))
	}
	return out
}

// MergeFrom merges remote data (pulled from Supabase) into the local store.
// Remote entries replace local ones for the same (tmdb_id, media_type) key
// using last-write-wins semantics. Watch progress uses most-recent-write-wins.
// Dismissals are unioned. Removal tombstones are LWW: a tombstone beats an
// entry if the removal timestamp is >= the entry's UpdatedAt (removal wins
// ties), and a re-added entry beats a tombstone only if its UpdatedAt is
// strictly after the removal. Remote removals are applied to local entries and
// both sides' tombstone maps are kept consistent.
func (l *Library) MergeFrom(entries []*LibraryEntry, progress []*WatchProgress, dismissals []*Dismissal, removals []*Removal) error {
	l.mu.Lock()
	previous := cloneDiskStore(l.db)
	changed := false

	// Build a map of incoming removals for O(1) lookup in the entries loop.
	incomingRemovals := make(map[string]*Removal, len(removals))
	for _, r := range removals {
		if r == nil {
			continue
		}
		key := entryKey(r.TmdbID, r.MediaType)
		incomingRemovals[key] = r
	}

	// Merge remote entries with tombstone-aware last-write-wins.
	for _, e := range entries {
		if e == nil {
			continue
		}
		key := entryKey(e.TmdbID, e.MediaType)

		// Determine effective tombstone: the later of local and incoming.
		localRemoval := l.db.Removed[key]
		incomingRemoval := incomingRemovals[key]
		var effectiveTombstone *Removal
		if localRemoval != nil && incomingRemoval != nil {
			if localRemoval.RemovedAt.After(incomingRemoval.RemovedAt) {
				effectiveTombstone = localRemoval
			} else {
				effectiveTombstone = incomingRemoval
			}
		} else if localRemoval != nil {
			effectiveTombstone = localRemoval
		} else if incomingRemoval != nil {
			effectiveTombstone = incomingRemoval
		}

		if effectiveTombstone != nil && !e.UpdatedAt.After(effectiveTombstone.RemovedAt) {
			// Removal is at least as recent as the entry — removal wins; skip.
			continue
		}

		// Entry is strictly newer than the tombstone (or there is no tombstone).
		if effectiveTombstone != nil {
			// Re-add wins: the entry was added back after the deletion.
			// Clear the stale local tombstone so it does not block future merges.
			if _, ok := l.db.Removed[key]; ok {
				delete(l.db.Removed, key)
				changed = true
			}
		}

		if local, ok := l.db.Entries[key]; ok {
			if e.UpdatedAt.After(local.UpdatedAt) {
				if !reflect.DeepEqual(local, e) {
					l.db.Entries[key] = cloneEntry(e)
					changed = true
				}
			}
		} else {
			l.db.Entries[key] = cloneEntry(e)
			changed = true
		}
	}

	// Apply remote removals to local entries and merge tombstones.
	// This loop agrees with the entries loop: an entry survives only if its
	// UpdatedAt is strictly after the removal timestamp.
	for _, r := range removals {
		if r == nil {
			continue
		}
		key := entryKey(r.TmdbID, r.MediaType)
		if local, ok := l.db.Entries[key]; ok {
			if !local.UpdatedAt.After(r.RemovedAt) {
				// Remote removal is at least as recent as the local entry — delete it.
				delete(l.db.Entries, key)
				changed = true
				// Merge tombstone, keeping the later RemovedAt.
				if existing := l.db.Removed[key]; existing == nil || r.RemovedAt.After(existing.RemovedAt) {
					l.db.Removed[key] = cloneRemoval(r)
					changed = true
				}
			} else {
				// Local entry is strictly newer (re-add wins) — do not keep the tombstone.
				if _, ok := l.db.Removed[key]; ok {
					delete(l.db.Removed, key)
					changed = true
				}
			}
		} else {
			// No local entry. Merge the tombstone, keeping the later RemovedAt.
			if existing := l.db.Removed[key]; existing == nil || r.RemovedAt.After(existing.RemovedAt) {
				l.db.Removed[key] = cloneRemoval(r)
				changed = true
			}
		}
	}

	// Merge watch progress: most-recent-write wins.
	for _, p := range progress {
		if p == nil {
			continue
		}
		key := progressKey(p.TmdbID, p.MediaType, p.Season, p.Episode)
		if local, ok := l.db.Progress[key]; ok {
			// Most-recent-write wins (same rule as entries' UpdatedAt above).
			// This used to be "higher PositionSeconds wins", which made any
			// position regression un-syncable: marking an episode unwatched
			// (position 0) or rewatching from the start always lost to the
			// remote copy's older-but-larger position — the next sync pull
			// silently reverted the change, and the push that follows a pull
			// then uploaded the reverted record, cementing it.
			if p.WatchedAt.After(local.WatchedAt) {
				if !reflect.DeepEqual(local, p) {
					l.db.Progress[key] = cloneProgress(p)
					changed = true
				}
			}
		} else {
			l.db.Progress[key] = cloneProgress(p)
			changed = true
		}
	}

	// Union dismissals.
	for _, d := range dismissals {
		if d == nil {
			continue
		}
		key := entryKey(d.TmdbID, d.MediaType)
		if _, ok := l.db.Dismissed[key]; !ok {
			l.db.Dismissed[key] = cloneDismissal(d)
			changed = true
		}
	}

	// Synchronous, not debounced (D3) — this is external data pulled from
	// Supabase sync, not a local UI mutation, so it should hit disk right
	// away rather than wait out persistDebounce.
	if changed {
		if err := l.writeNow(); err != nil {
			l.db = previous
			l.mu.Unlock()
			return fmt.Errorf("library persist: %w", err)
		}
		l.gen.Add(1)
	}
	l.mu.Unlock()
	// tasteGen is a distinct counter (D2): MergeFrom pulls in
	// entries/progress/dismissals/removals wholesale, all taste-relevant.
	if changed {
		l.tasteGen.Add(1)
	}
	return nil
}

// RemoteRowID identifies a remote Supabase row by its natural key and UUID,
// used by AdoptRemoteIDs. Season and Episode are only meaningful for progress
// rows; nil means the column is NULL in Supabase (movies have no season/episode).
type RemoteRowID struct {
	ID        string
	TmdbID    int
	MediaType string
	Season    *int
	Episode   *int
}

// AdoptRemoteIDs replaces local UUIDs with the corresponding remote UUIDs for
// every row that matches on natural key but carries a divergent local ID. This
// resolves Supabase 23505 unique-violation errors that occur when the same
// media title exists locally and remotely under different UUIDs — for example,
// after adding a title independently on two devices or re-adding it after a
// local reset. Once the local IDs match the remote ones, the next PK-resolved
// upsert updates in place rather than attempting a duplicate insert.
//
// When an entry ID is adopted, progress rows whose LibraryEntryID referenced
// the old local ID are patched to the adopted remote ID. Persists synchronously
// and bumps the library generation.
func (l *Library) AdoptRemoteIDs(remoteEntries, remoteProgress []RemoteRowID) {
	l.mu.Lock()
	// remap: old local entry ID → adopted remote entry ID, for patching progress.
	remap := make(map[string]string)
	for _, r := range remoteEntries {
		key := entryKey(r.TmdbID, r.MediaType)
		e, ok := l.db.Entries[key]
		if !ok || e.ID == r.ID {
			continue
		}
		oldID := e.ID
		e.ID = r.ID
		l.db.Entries[key] = e
		remap[oldID] = r.ID
	}
	// Index remote progress by natural key for O(1) lookup below.
	remoteByKey := make(map[string]string, len(remoteProgress))
	for _, r := range remoteProgress {
		remoteByKey[progressKey(r.TmdbID, r.MediaType, r.Season, r.Episode)] = r.ID
	}
	for key, p := range l.db.Progress {
		changed := false
		// Patch LibraryEntryID if the parent entry was adopted.
		if newID, ok := remap[p.LibraryEntryID]; ok {
			p.LibraryEntryID = newID
			changed = true
		}
		// Adopt remote progress ID if natural key matches and ID differs.
		if remoteID, ok := remoteByKey[key]; ok && p.ID != remoteID {
			p.ID = remoteID
			changed = true
		}
		if changed {
			l.db.Progress[key] = p
		}
	}
	if err := l.writeNow(); err != nil {
		log.Println("library: AdoptRemoteIDs persist:", err)
	}
	l.gen.Add(1)
	l.mu.Unlock()
}

// RegenerateIDsNotIn reassigns new UUIDs to every entry and progress row whose
// ID is non-empty and NOT present in the corresponding owned set. This is called
// after a Supabase RLS 42501 rejection to repair cross-user row-ID contamination
// (rows whose ID collides with a row owned by a different user) before retrying
// the upsert.
//
// When an entry ID changes, all progress rows whose LibraryEntryID referenced
// the old ID are updated to the new ID. Persists synchronously and bumps the
// library generation.
func (l *Library) RegenerateIDsNotIn(ownedEntryIDs, ownedProgressIDs map[string]bool) {
	l.mu.Lock()
	// remap: old entry ID → new entry ID, for patching progress rows.
	remap := make(map[string]string)
	for key, e := range l.db.Entries {
		if e.ID == "" || ownedEntryIDs[e.ID] {
			continue
		}
		oldID := e.ID
		e.ID = utils.NewUUID()
		l.db.Entries[key] = e
		remap[oldID] = e.ID
	}
	for key, p := range l.db.Progress {
		// Remap LibraryEntryID if the parent entry was regenerated.
		if newID, ok := remap[p.LibraryEntryID]; ok {
			p.LibraryEntryID = newID
		}
		if p.ID == "" || ownedProgressIDs[p.ID] {
			if remap[p.LibraryEntryID] != "" {
				// LibraryEntryID changed even if progress ID is fine — persist.
				l.db.Progress[key] = p
			}
			continue
		}
		p.ID = utils.NewUUID()
		l.db.Progress[key] = p
	}
	if err := l.writeNow(); err != nil {
		log.Println("library: RegenerateIDsNotIn persist:", err)
	}
	l.gen.Add(1)
	l.mu.Unlock()
}

// TasteSignals returns one signal per title the user has any history with —
// every library entry, plus titles with completed watch history even if the
// entry was later removed. Safe to call concurrently.
func (l *Library) TasteSignals() []TasteSignal {
	l.mu.RLock()
	defer l.mu.RUnlock()

	// Latest WatchedAt among completed progress rows per title, so both the
	// completed flag and its timestamp come from the same pass.
	completed := make(map[string]time.Time)
	for _, p := range l.db.Progress {
		if !p.Completed {
			continue
		}
		key := entryKey(p.TmdbID, p.MediaType)
		if p.WatchedAt.After(completed[key]) {
			completed[key] = p.WatchedAt
		}
	}

	out := make([]TasteSignal, 0, len(l.db.Entries))
	seen := make(map[string]bool, len(l.db.Entries))
	for _, e := range l.db.Entries {
		key := entryKey(e.TmdbID, e.MediaType)
		seen[key] = true
		ts := e.UpdatedAt
		if e.LastWatchedAt != nil && e.LastWatchedAt.After(ts) {
			ts = *e.LastWatchedAt
		}
		if watchedAt, ok := completed[key]; ok && watchedAt.After(ts) {
			ts = watchedAt
		}
		dismissal := l.db.Dismissed[key]
		if dismissal != nil && dismissal.DismissedAt.After(ts) {
			ts = dismissal.DismissedAt
		}
		out = append(out, TasteSignal{
			TmdbID:            e.TmdbID,
			MediaType:         e.MediaType,
			Status:            e.Status,
			UserRating:        clonePtr(e.Rating),
			Completed:         !completed[key].IsZero(),
			Dismissed:         dismissal != nil,
			Title:             e.Title,
			PosterPath:        utils.RewriteTMDBImageURL(e.PosterPath),
			LastInteractionAt: ts,
		})
	}

	// Titles removed from the list but still finished: don't re-recommend them,
	// and keep them as a positive taste signal.
	for _, p := range l.db.Progress {
		if !p.Completed {
			continue
		}
		key := entryKey(p.TmdbID, p.MediaType)
		if seen[key] {
			continue
		}
		seen[key] = true
		out = append(out, TasteSignal{
			TmdbID:            p.TmdbID,
			MediaType:         p.MediaType,
			Completed:         true, // no entry => no status
			LastInteractionAt: completed[key],
		})
	}
	for key, d := range l.db.Dismissed {
		if seen[key] {
			continue // already represented; exclusion is handled either way
		}
		seen[key] = true
		out = append(out, TasteSignal{
			TmdbID:            d.TmdbID,
			MediaType:         d.MediaType,
			Dismissed:         true,
			LastInteractionAt: d.DismissedAt,
		})
	}
	return out
}

type Stats struct {
	Total      int            `json:"total"`     // library entries (dismissals excluded)
	ByType     map[string]int `json:"by_type"`   // entries per media type
	ByStatus   map[string]int `json:"by_status"` // status -> count
	Finished   map[string]int `json:"finished"`  // finished entries per type
	Dismissed  int            `json:"dismissed"`
	Rated      int            `json:"rated"`
	AvgRating  float64        `json:"avg_rating"`  // mean user rating over rated titles, 0–5
	MovieShare float64        `json:"movie_share"` // preference, from finished+watching counts
	TVShare    float64        `json:"tv_share"`
}

func (l *Library) Stats() Stats {
	l.mu.RLock()
	defer l.mu.RUnlock()

	st := Stats{
		ByType:   map[string]int{"movie": 0, "tv": 0},
		ByStatus: map[string]int{},
		Finished: map[string]int{"movie": 0, "tv": 0},
	}
	engaged := map[string]int{"movie": 0, "tv": 0}
	var ratingSum float64

	for _, e := range l.db.Entries {
		st.Total++
		st.ByType[e.MediaType]++
		st.ByStatus[e.Status]++
		if e.Status == StatusFinished {
			st.Finished[e.MediaType]++
		}
		if e.Status == StatusFinished || e.Status == StatusWatching {
			engaged[e.MediaType]++
		}
		if e.Rating != nil {
			st.Rated++
			ratingSum += *e.Rating
		}
	}
	if st.Rated > 0 {
		st.AvgRating = ratingSum / float64(st.Rated)
	}
	st.Dismissed = len(l.db.Dismissed)

	if total := engaged["movie"] + engaged["tv"]; total > 0 {
		st.MovieShare = float64(engaged["movie"]) / float64(total)
		st.TVShare = float64(engaged["tv"]) / float64(total)
	}
	return st
}

// Library ── Service ──────────────────────────────────────────────────────────────────
//
// Library owns all of the package's mutable state. Fields are unexported, so
// tygo emits nothing for this type — only the JSON data types (LibraryEntry,
// WatchProgress, diskStore) cross into the generated TS.
type Library struct {
	mu   sync.RWMutex
	db   diskStore
	path string
	gen  atomic.Uint64

	// tasteGen (D2) is bumped only by taste-relevant mutations — entry
	// upsert/remove, status, rating, dismissal, a progress write that
	// transitions to Completed, and MergeFrom — unlike gen/Generation(),
	// which bumps on every persist(), including the playback-progress tick
	// that fires every ~10s during any watch. internal/discover's profile
	// cache keys off TasteGeneration() instead of Generation() so a taste
	// rebuild (and the GetDetails stampede across the whole library that
	// implies) only happens when taste actually changed, not on every tick.
	tasteGen atomic.Uint64

	// onNearComplete, if set, is called (best-effort, outside any lock) when
	// a progress write reports a title/episode as completed or ≥90% watched
	// — the moment "next episode" becomes the likely next watch. Deliberately
	// its own single-purpose hook, not coupled to Generation() (D2/D3 give
	// that a different meaning) — see internal/prefetch.
	onNearComplete func()

	// onProgressSave, if set, is called (best-effort, outside any lock) on
	// every successful POST to /api/library/progress — including the ~10s
	// playback ticks — so an activity log can credit watch-time deltas in
	// near-real-time. See internal/activity.
	onProgressSave func(ProgressSaveEvent)

	// Debounced persistence (D3). markDirty() marshals the store (cheap — it's
	// small) synchronously while the caller still holds l.mu, then stages the
	// bytes in utils.DebouncedPersist, which fires the actual disk write ~1s
	// out if a timer isn't already pending — so a burst of mutations (e.g.
	// rapid progress ticks) coalesces into a single AtomicWriteFile instead of
	// one per mutation. Only the I/O is debounced; persist always carries the
	// latest-staged bytes at the moment markDirty() was last called.
	persist utils.DebouncedPersist
}

// persistDebounce is how long markDirty waits before actually writing —
// long enough to coalesce a realistic burst (rapid status/rating clicks, a
// progress-save retry), short enough that a crash right after a mutation
// loses at most this much.
const persistDebounce = 1 * time.Second

// markDirty marshals the current store and schedules a debounced write.
// Must be called with l.mu held (any mode — it only reads l.db/l.path).
// Callers that need to guarantee the write actually reached disk before
// returning (external sync data, a profile switch, first-run) use writeNow
// instead — see MergeFrom/SetProfile/New.
func (l *Library) markDirty() {
	raw, err := json.Marshal(l.db)
	if err != nil {
		log.Println("library: marshal failed:", err)
		return
	}
	l.persist.MarkDirty(l.path, raw, persistDebounce)
}

// Flush forces any pending debounced write (D3) to happen immediately —
// call on shutdown so the last mutation before exit isn't lost to the
// persistDebounce window the process didn't live to see.
func (l *Library) Flush() {
	l.persist.Flush()
}

// writeNow synchronously marshals and writes the store to disk, bypassing
// the debounce entirely. Must be called with l.mu held. Used only by the
// synchronous exceptions (D3): MergeFrom (external sync data should hit disk
// right away, not wait out a debounce window), SetProfile's flush-then-swap,
// and New's first-run write.
func (l *Library) writeNow() error {
	raw, err := json.Marshal(l.db)
	if err != nil {
		return err
	}
	return utils.AtomicWriteFile(l.path, raw, 0o644)
}

// SetOnNearComplete registers the near-complete callback (internal/prefetch's
// debounced re-warm trigger). Called once from main.go at startup.
func (l *Library) SetOnNearComplete(fn func()) {
	l.mu.Lock()
	l.onNearComplete = fn
	l.mu.Unlock()
}

// SetOnProgressSave registers the progress-save callback (internal/activity's
// delta-crediting hook). Called once from main.go at startup.
func (l *Library) SetOnProgressSave(fn func(ProgressSaveEvent)) {
	l.mu.Lock()
	l.onProgressSave = fn
	l.mu.Unlock()
}

// ── New ────────────────────────────────────────────────────────────────────────

// New loads library-{profileID}.json from the per-user config directory (see
// utils.ConfigPath), creating it with empty maps if it doesn't exist yet. It
// always returns a usable (non-nil) *Library even on error, so the caller can
// still register handlers against an empty store rather than crashing —
// matching the old Init's best-effort behaviour.
func New(profileID string) (*Library, error) {
	l := &Library{
		db: emptyDiskStore(),
	}

	path, err := utils.ConfigPath(fmt.Sprintf("library-%s.json", profileID))
	if err != nil {
		return l, err
	}
	l.path = path

	raw, err := os.ReadFile(l.path)
	if os.IsNotExist(err) {
		// First run — write empty maps synchronously so the file exists
		// immediately (a "synchronous exception" to D3's debounce — nothing
		// would trigger the debounced write otherwise, and the file existing
		// is a documented guarantee callers of New() may rely on).
		return l, l.writeNow()
	}
	if err != nil {
		return l, err
	}

	// Decode into a temporary store so malformed JSON cannot leave the usable
	// fallback library partially populated before the error is returned.
	loaded := emptyDiskStore()
	if err := json.Unmarshal(raw, &loaded); err != nil {
		return l, err
	}
	loaded.ensureMaps()
	l.db = loaded
	return l, nil
}

func (l *Library) Generation() uint64 { return l.gen.Load() }

// TasteGeneration returns the taste-relevant generation counter (D2) — see
// the Library.tasteGen field doc.
func (l *Library) TasteGeneration() uint64 { return l.tasteGen.Load() }

// SetProfile reloads the library from the given profile's data file.
// Safe to call while handlers are live — takes the write lock while swapping.
func (l *Library) SetProfile(profileID string) error {
	path, err := utils.ConfigPath(fmt.Sprintf("library-%s.json", profileID))
	if err != nil {
		return err
	}
	newDB := emptyDiskStore()
	raw, err := os.ReadFile(path)
	if err != nil && !os.IsNotExist(err) {
		return err
	}
	if err == nil {
		if err := json.Unmarshal(raw, &newDB); err != nil {
			return err
		}
		newDB.ensureMaps()
	}

	l.Flush()

	l.mu.Lock()
	l.db = newDB
	l.path = path
	l.mu.Unlock()
	l.gen.Add(1)
	l.tasteGen.Add(1)
	return nil
}

// ── External sync helpers ─────────────────────────────────────────────────────

// MarkExternallyWatched creates or updates a WatchProgress record as Completed
// for the given item, sourced from an external service (Trakt). If no library
// entry exists yet it is auto-created as StatusWatching (user controls status
// changes; Cove never auto-flips to Finished on external completion).
//
// title and posterPath are used only when creating a brand-new library entry;
// they may be empty when the entry already exists. watchedAt is set as the
// WatchedAt timestamp on the progress record. Additive only — an already-
// completed record is not overwritten unless the new watchedAt is more recent.
func (l *Library) MarkExternallyWatched(
	tmdbID int,
	mediaType string,
	season, episode *int,
	title, posterPath string,
	watchedAt time.Time,
) {
	l.mu.Lock()
	defer l.mu.Unlock()

	now := time.Now()
	eKey := entryKey(tmdbID, mediaType)
	entry, exists := l.db.Entries[eKey]
	entryCreated := !exists
	if !exists {
		entry = &LibraryEntry{
			ID:         utils.NewUUID(),
			TmdbID:     tmdbID,
			MediaType:  mediaType,
			Title:      title,
			PosterPath: posterPath,
			Status:     StatusWatching,
			AddedAt:    now,
			UpdatedAt:  now,
		}
		l.db.Entries[eKey] = entry
		delete(l.db.Removed, eKey)
		l.relinkProgress(entry)
	}

	pKey := progressKey(tmdbID, mediaType, season, episode)
	prog, progExists := l.db.Progress[pKey]
	if progExists && prog.Completed && !watchedAt.After(prog.WatchedAt) {
		// Already completed at the same or later time — nothing to do.
		if entryCreated {
			// Re-creating a removed entry and relinking its retained progress is
			// still a real mutation even when the completion row is unchanged.
			l.gen.Add(1)
			l.tasteGen.Add(1)
			l.markDirty()
		}
		return
	}
	if !progExists {
		prog = &WatchProgress{
			ID:             utils.NewUUID(),
			LibraryEntryID: entry.ID,
			TmdbID:         tmdbID,
			MediaType:      mediaType,
			Season:         clonePtr(season),
			Episode:        clonePtr(episode),
		}
		l.db.Progress[pKey] = prog
	}
	prog.Completed = true
	prog.WatchedAt = watchedAt

	entry.LastWatchedAt = &watchedAt
	if season != nil {
		entry.LastWatchedSeason = clonePtr(season)
	}
	if episode != nil {
		entry.LastWatchedEpisode = clonePtr(episode)
	}

	l.gen.Add(1)
	l.tasteGen.Add(1)
	l.markDirty()
}

// AddWatchLater adds a new library entry with StatusWatchLater if and only if
// the (tmdbID, mediaType) pair does not already exist in the library under any
// status. If an entry already exists, this is a no-op (additive, never
// overwrites). Designed for Trakt watchlist pull.
func (l *Library) AddWatchLater(
	tmdbID int,
	mediaType string,
	title, posterPath string,
	addedAt time.Time,
) {
	l.mu.Lock()
	defer l.mu.Unlock()

	eKey := entryKey(tmdbID, mediaType)
	if _, exists := l.db.Entries[eKey]; exists {
		return // already present under some status — additive only, never overwrite
	}

	now := time.Now()
	entry := &LibraryEntry{
		ID:         utils.NewUUID(),
		TmdbID:     tmdbID,
		MediaType:  mediaType,
		Title:      title,
		PosterPath: posterPath,
		Status:     StatusWatchLater,
		AddedAt:    addedAt,
		UpdatedAt:  now,
	}
	l.db.Entries[eKey] = entry
	delete(l.db.Removed, eKey)
	l.relinkProgress(entry)
	l.gen.Add(1)
	l.tasteGen.Add(1)
	l.markDirty()
}

// ── Key helpers ───────────────────────────────────────────────────────────────

func entryKey(tmdbID int, mediaType string) string {
	return fmt.Sprintf("%d:%s", tmdbID, mediaType)
}

// progressKey builds a unique key for a playback position record.
// Movies: "12345:movie"
// TV episodes: "12345:tv:1:3" (show id, type, season, episode)
func progressKey(tmdbID int, mediaType string, season, episode *int) string {
	if season != nil && episode != nil {
		return fmt.Sprintf("%d:%s:%d:%d", tmdbID, mediaType, *season, *episode)
	}
	return fmt.Sprintf("%d:%s", tmdbID, mediaType)
}

// ── SetupHandlers ─────────────────────────────────────────────────────────────

func (l *Library) SetupHandlers(mux *http.ServeMux) {
	// /api/library/progress must be registered before /api/library/ so Go's mux
	// matches it as the more-specific fixed path.
	mux.HandleFunc("/api/library/progress/bulk", utils.CorsMiddleware(utils.MethodGuard(http.MethodPost, l.handleProgressBulk)))
	mux.HandleFunc("/api/library/progress", utils.CorsMiddleware(l.handleProgress))
	mux.HandleFunc("/api/library", utils.CorsMiddleware(l.handleCollection))
	mux.HandleFunc("/api/library/", utils.CorsMiddleware(l.handleItem))
	mux.HandleFunc("/api/library/dismiss", utils.CorsMiddleware(l.handleDismiss))
	mux.HandleFunc("/api/library/stats", utils.CorsMiddleware(utils.MethodGuard(http.MethodGet, l.handleStats)))
}

// ── Handler: /api/library ─────────────────────────────────────────────────────
//
// GET  — list all entries; filter by ?status=watching etc.
// POST — upsert an entry (idempotent on tmdb_id+media_type)

func (l *Library) handleCollection(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		statusFilter := r.URL.Query().Get("status")
		l.mu.RLock()
		list := make([]*LibraryEntry, 0, len(l.db.Entries))
		for _, e := range l.db.Entries {
			if statusFilter == "" || e.Status == statusFilter {
				// Value copy — e is the actual pointer stored under l.mu, and
				// rewritePosterURL's result must never leak back into it (see
				// its doc comment).
				entryCopy := *e
				entryCopy.PosterPath = utils.RewriteTMDBImageURL(entryCopy.PosterPath)
				list = append(list, &entryCopy)
			}
		}
		l.mu.RUnlock()
		utils.WriteJSON(w, list)

	case http.MethodPost:
		var body struct {
			TmdbID           int     `json:"tmdb_id"`
			MediaType        string  `json:"media_type"`
			Title            string  `json:"title"`
			PosterPath       string  `json:"poster_path"`
			Status           Status  `json:"status"`
			VoteAverage      float64 `json:"vote_average"`
			LastAirDate      string  `json:"last_air_date"`
			LastAiredSeason  *int    `json:"last_aired_season"`
			LastAiredEpisode *int    `json:"last_aired_episode"`
		}
		r.Body = http.MaxBytesReader(w, r.Body, utils.SmallBodyLimit)
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, "invalid body: "+err.Error(), http.StatusBadRequest)
			return
		}
		if body.TmdbID <= 0 || !utils.ValidMediaType(body.MediaType) {
			http.Error(w, "positive tmdb_id and media_type movie or tv required", http.StatusBadRequest)
			return
		}
		if body.Status == "" {
			body.Status = StatusWatchLater
		}
		if !validStatus(body.Status) {
			http.Error(w, "invalid status", http.StatusBadRequest)
			return
		}

		l.mu.Lock()
		key := entryKey(body.TmdbID, body.MediaType)
		now := time.Now()
		entry, exists := l.db.Entries[key]
		if !exists {
			entry = &LibraryEntry{ID: utils.NewUUID(), AddedAt: now}
			l.db.Entries[key] = entry
			delete(l.db.Removed, key)
		}
		entry.TmdbID = body.TmdbID
		entry.MediaType = body.MediaType
		entry.Title = body.Title
		entry.PosterPath = body.PosterPath
		entry.Status = body.Status
		entry.VoteAverage = body.VoteAverage
		entry.LastAirDate = body.LastAirDate
		// Only overwrite if the caller actually sent values — avoids resetting
		// fields populated by other code paths (e.g. progressSave).
		if body.LastAiredSeason != nil {
			entry.LastAiredSeason = body.LastAiredSeason
		}
		if body.LastAiredEpisode != nil {
			entry.LastAiredEpisode = body.LastAiredEpisode
		}
		entry.UpdatedAt = now
		// Re-link any progress records orphaned when the entry was previously
		// removed — keeps library_entry_id consistent for Supabase sync.
		if !exists {
			for _, p := range l.db.Progress {
				if p.TmdbID == body.TmdbID && p.MediaType == body.MediaType {
					p.LibraryEntryID = entry.ID
				}
			}
		}
		l.gen.Add(1)
		l.markDirty()
		result := *entry // copy before unlock
		l.mu.Unlock()
		l.tasteGen.Add(1) // entry upsert — taste-relevant

		result.PosterPath = utils.RewriteTMDBImageURL(result.PosterPath)
		utils.WriteJSON(w, &result)

	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

// handleProgressBulk atomically marks a movie or a set of TV episodes watched,
// or resets every saved progress row for a title. The frontend resolves aired
// TV episodes through TMDB, then sends them in one request so a whole-show
// action cannot leave partially-updated local state.
func (l *Library) handleProgressBulk(w http.ResponseWriter, r *http.Request) {
	var body struct {
		TmdbID          int     `json:"tmdb_id"`
		MediaType       string  `json:"media_type"`
		Title           string  `json:"title"`
		PosterPath      string  `json:"poster_path"`
		VoteAverage     float64 `json:"vote_average"`
		Completed       bool    `json:"completed"`
		Status          Status  `json:"status"`
		DurationSeconds float64 `json:"duration_seconds"`
		Episodes        []struct {
			Season          int     `json:"season"`
			Episode         int     `json:"episode"`
			DurationSeconds float64 `json:"duration_seconds"`
		} `json:"episodes"`
	}
	r.Body = http.MaxBytesReader(w, r.Body, 2<<20)
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "invalid body: "+err.Error(), http.StatusBadRequest)
		return
	}
	if body.TmdbID <= 0 || !utils.ValidMediaType(body.MediaType) {
		http.Error(w, "positive tmdb_id and media_type movie or tv required", http.StatusBadRequest)
		return
	}
	if body.Status != "" && !validStatus(body.Status) {
		http.Error(w, "invalid status", http.StatusBadRequest)
		return
	}
	if body.DurationSeconds < 0 || len(body.Episodes) > 5000 {
		http.Error(w, "invalid duration or too many episodes", http.StatusBadRequest)
		return
	}

	type episodeInput struct {
		season, episode int
		duration        float64
	}
	episodes := make([]episodeInput, 0, len(body.Episodes))
	seen := make(map[[2]int]struct{}, len(body.Episodes))
	for _, ep := range body.Episodes {
		if ep.Season <= 0 || ep.Episode <= 0 || ep.DurationSeconds < 0 {
			http.Error(w, "episodes require positive season/episode and non-negative duration", http.StatusBadRequest)
			return
		}
		key := [2]int{ep.Season, ep.Episode}
		if _, duplicate := seen[key]; duplicate {
			continue
		}
		seen[key] = struct{}{}
		episodes = append(episodes, episodeInput{
			season: ep.Season, episode: ep.Episode, duration: ep.DurationSeconds,
		})
	}
	if body.MediaType == "tv" && body.Completed && len(episodes) == 0 {
		http.Error(w, "at least one aired episode is required", http.StatusBadRequest)
		return
	}

	now := time.Now()
	l.mu.Lock()
	entryKeyValue := entryKey(body.TmdbID, body.MediaType)
	entry := l.db.Entries[entryKeyValue]
	if entry == nil && body.Completed {
		entry = &LibraryEntry{
			ID:        utils.NewUUID(),
			TmdbID:    body.TmdbID,
			MediaType: body.MediaType,
			AddedAt:   now,
			Status:    StatusWatching,
		}
		l.db.Entries[entryKeyValue] = entry
		delete(l.db.Removed, entryKeyValue)
		l.relinkProgress(entry)
	}
	if entry != nil {
		if body.Title != "" {
			entry.Title = body.Title
		}
		if body.PosterPath != "" {
			entry.PosterPath = body.PosterPath
		}
		entry.VoteAverage = body.VoteAverage
		if body.Status != "" {
			entry.Status = body.Status
		}
		entry.UpdatedAt = now
	}

	upsert := func(season, episode *int, duration float64, completed bool) {
		key := progressKey(body.TmdbID, body.MediaType, season, episode)
		progress := l.db.Progress[key]
		if progress == nil {
			progress = &WatchProgress{
				ID:             utils.NewUUID(),
				LibraryEntryID: entry.ID,
				TmdbID:         body.TmdbID,
				MediaType:      body.MediaType,
				Season:         clonePtr(season),
				Episode:        clonePtr(episode),
			}
			l.db.Progress[key] = progress
		}
		progress.LibraryEntryID = entry.ID
		progress.PositionSeconds = 0
		progress.DurationSeconds = 0
		progress.Completed = completed
		progress.WatchedAt = now
		if completed {
			if duration <= 0 {
				duration = 1
			}
			progress.PositionSeconds = duration
			progress.DurationSeconds = duration
		}
	}

	if body.Completed {
		entry.LastWatchedAt = &now
		if body.MediaType == "movie" {
			upsert(nil, nil, body.DurationSeconds, true)
		} else {
			lastSeason, lastEpisode := 0, 0
			for _, ep := range episodes {
				season, episode := ep.season, ep.episode
				upsert(&season, &episode, ep.duration, true)
				if season > lastSeason || (season == lastSeason && episode > lastEpisode) {
					lastSeason, lastEpisode = season, episode
				}
			}
			entry.LastWatchedSeason = clonePtr(&lastSeason)
			entry.LastWatchedEpisode = clonePtr(&lastEpisode)
		}
	} else {
		for _, progress := range l.db.Progress {
			if progress.TmdbID == body.TmdbID && progress.MediaType == body.MediaType {
				progress.PositionSeconds = 0
				progress.DurationSeconds = 0
				progress.Completed = false
				progress.WatchedAt = now
				if entry != nil {
					progress.LibraryEntryID = entry.ID
				}
			}
		}
		if entry != nil {
			entry.LastWatchedAt = nil
			entry.LastWatchedSeason = nil
			entry.LastWatchedEpisode = nil
		}
	}

	progressOut := make([]*WatchProgress, 0)
	for _, progress := range l.db.Progress {
		if progress.TmdbID == body.TmdbID && progress.MediaType == body.MediaType {
			progressOut = append(progressOut, cloneProgress(progress))
		}
	}
	entryOut := cloneEntry(entry)
	l.gen.Add(1)
	l.markDirty()
	l.mu.Unlock()
	l.tasteGen.Add(1)

	if entryOut != nil {
		entryOut.PosterPath = utils.RewriteTMDBImageURL(entryOut.PosterPath)
	}
	utils.WriteJSON(w, map[string]any{"entry": entryOut, "progress": progressOut})
}

// ── Handler: /api/library/progress ────────────────────────────────────────────
//
// GET  — fetch one progress record by query params
// POST — upsert a progress record; auto-creates library entry as "watching"

func (l *Library) handleProgress(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		tmdbID, err := strconv.Atoi(r.URL.Query().Get("tmdb_id"))
		if err != nil {
			http.Error(w, "invalid tmdb_id", http.StatusBadRequest)
			return
		}
		mediaType := r.URL.Query().Get("media_type")
		if !utils.ValidMediaType(mediaType) {
			http.Error(w, "media_type must be movie or tv", http.StatusBadRequest)
			return
		}

		var season, episode *int
		if s := r.URL.Query().Get("season"); s != "" {
			v, err := strconv.Atoi(s)
			if err != nil {
				http.Error(w, "invalid season", http.StatusBadRequest)
				return
			}
			season = &v
		}
		if e := r.URL.Query().Get("episode"); e != "" {
			v, err := strconv.Atoi(e)
			if err != nil {
				http.Error(w, "invalid episode", http.StatusBadRequest)
				return
			}
			episode = &v
		}

		pKey := progressKey(tmdbID, mediaType, season, episode)
		l.mu.RLock()
		p := l.db.Progress[pKey]
		// Copy under the lock before unlocking — concurrent POSTs can mutate the
		// pointed-to struct while json.Encode runs, causing a data race.
		var pOut *WatchProgress
		if p != nil {
			cp := *p
			pOut = &cp
		}
		l.mu.RUnlock()
		// Return null JSON if not found (not a 404 — absence is normal)
		utils.WriteJSON(w, pOut)

	case http.MethodPost:
		var body struct {
			TmdbID           int     `json:"tmdb_id"`
			MediaType        string  `json:"media_type"`
			Title            string  `json:"title"`
			PosterPath       string  `json:"poster_path"`
			VoteAverage      float64 `json:"vote_average"`
			LastAirDate      string  `json:"last_air_date"`
			LastAiredSeason  *int    `json:"last_aired_season"`
			LastAiredEpisode *int    `json:"last_aired_episode"`
			Season           *int    `json:"season"`
			Episode          *int    `json:"episode"`
			PositionSeconds  float64 `json:"position_seconds"`
			DurationSeconds  float64 `json:"duration_seconds"`
			Completed        bool    `json:"completed"`
		}
		r.Body = http.MaxBytesReader(w, r.Body, utils.SmallBodyLimit)
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, "invalid body: "+err.Error(), http.StatusBadRequest)
			return
		}
		if body.TmdbID <= 0 || !utils.ValidMediaType(body.MediaType) {
			http.Error(w, "positive tmdb_id and media_type movie or tv required", http.StatusBadRequest)
			return
		}
		if body.PositionSeconds < 0 || body.DurationSeconds < 0 {
			http.Error(w, "position_seconds and duration_seconds must be non-negative", http.StatusBadRequest)
			return
		}

		l.mu.Lock()
		now := time.Now()

		// Auto-create library entry as "watching" if it doesn't exist yet.
		eKey := entryKey(body.TmdbID, body.MediaType)
		entry, exists := l.db.Entries[eKey]
		entryCreated := !exists
		if !exists {
			entry = &LibraryEntry{
				ID:          utils.NewUUID(),
				TmdbID:      body.TmdbID,
				MediaType:   body.MediaType,
				Title:       body.Title,
				PosterPath:  body.PosterPath,
				VoteAverage: body.VoteAverage,
				LastAirDate: body.LastAirDate,
				Status:      StatusWatching,
				AddedAt:     now,
				UpdatedAt:   now,
			}
			l.db.Entries[eKey] = entry
			delete(l.db.Removed, eKey)
			l.relinkProgress(entry)
		}

		// Always update last-watched metadata so resume labels stay current.
		entry.LastWatchedAt = &now
		if body.Season != nil {
			entry.LastWatchedSeason = body.Season
		}
		if body.Episode != nil {
			entry.LastWatchedEpisode = body.Episode
		}
		if body.LastAirDate != "" {
			entry.LastAirDate = body.LastAirDate
		}
		if body.LastAiredSeason != nil {
			entry.LastAiredSeason = body.LastAiredSeason
		}
		if body.LastAiredEpisode != nil {
			entry.LastAiredEpisode = body.LastAiredEpisode
		}

		pKey := progressKey(body.TmdbID, body.MediaType, body.Season, body.Episode)
		prog, progExists := l.db.Progress[pKey]
		if !progExists {
			prog = &WatchProgress{
				ID:             utils.NewUUID(),
				LibraryEntryID: entry.ID,
				TmdbID:         body.TmdbID,
				MediaType:      body.MediaType,
				Season:         body.Season,
				Episode:        body.Episode,
			}
			l.db.Progress[pKey] = prog
		}
		wasCompleted := progExists && prog.Completed
		prog.PositionSeconds = body.PositionSeconds
		prog.DurationSeconds = body.DurationSeconds
		prog.Completed = body.Completed
		prog.WatchedAt = now
		completedTransition := !wasCompleted && prog.Completed

		// If the episode/movie was just completed and the entry was "watching",
		// don't auto-flip to "finished" — user controls that manually.

		l.gen.Add(1)
		l.markDirty()
		result := *prog // copy before unlock
		hook := l.onNearComplete
		progressSaveHook := l.onProgressSave
		l.mu.Unlock()

		// A progress write happens on every ~10s tick during playback. Creating
		// the entry is taste-relevant, as is a genuine Completed transition;
		// ordinary position ticks must not bump tasteGen or discover's profile
		// cache would thrash.
		if entryCreated || completedTransition {
			l.tasteGen.Add(1)
		}

		// Best-effort, outside the lock: activity log credits the delta since
		// the last tick into today's date/hour bucket (internal/activity
		// discards seeks and large jumps via its own 0 < delta ≤ 90s clamp).
		if progressSaveHook != nil {
			progressSaveHook(ProgressSaveEvent{
				ProgressKey: pKey,
				TmdbID:      body.TmdbID,
				MediaType:   body.MediaType,
				Position:    body.PositionSeconds,
				Duration:    body.DurationSeconds,
				Completed:   body.Completed,
				At:          now,
			})
		}

		// Best-effort, outside the lock: this is the moment "next episode"
		// becomes the likely next watch (internal/prefetch debounces this
		// itself, so firing it isn't a concern under rapid progress ticks).
		nearComplete := body.Completed ||
			(body.DurationSeconds > 0 && body.PositionSeconds/body.DurationSeconds >= 0.9)
		if hook != nil && nearComplete {
			hook()
		}

		utils.WriteJSON(w, &result)

	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

// ── Handler: /api/library/{tmdbId}/{mediaType}[/{sub}] ────────────────────────
//
// GET    /api/library/{id}/{type}         — entry + all its progress records
// DELETE /api/library/{id}/{type}         — remove entry + all its progress
// PATCH  /api/library/{id}/{type}/status  — update status field
// PATCH  /api/library/{id}/{type}/rating  — update rating field (null to clear)

func (l *Library) handleItem(w http.ResponseWriter, r *http.Request) {
	// "/api/library/12345/movie" → trimmed = "12345/movie"
	// "/api/library/12345/movie/rating" → trimmed = "12345/movie/rating"
	trimmed := strings.TrimPrefix(r.URL.Path, "/api/library/")
	parts := strings.SplitN(trimmed, "/", 3)
	if len(parts) < 2 {
		http.Error(w, "invalid path", http.StatusBadRequest)
		return
	}

	tmdbID, err := strconv.Atoi(parts[0])
	if err != nil {
		http.Error(w, "invalid tmdb_id", http.StatusBadRequest)
		return
	}
	mediaType := parts[1]
	if !utils.ValidMediaType(mediaType) {
		http.Error(w, "media_type must be movie or tv", http.StatusBadRequest)
		return
	}
	sub := ""
	if len(parts) == 3 {
		sub = parts[2]
	}
	key := entryKey(tmdbID, mediaType)

	switch {
	// ── GET /api/library/{id}/{type} ──────────────────────────────────────────
	case sub == "" && r.Method == http.MethodGet:
		l.mu.RLock()
		entry := l.db.Entries[key]
		_, dismissed := l.db.Dismissed[key]
		var progList []*WatchProgress
		for _, p := range l.db.Progress {
			if p.TmdbID == tmdbID && p.MediaType == mediaType {
				// Copy under the lock — concurrent POSTs can mutate shared structs
				// while json.Encode runs outside the lock (same pattern as AllProgress).
				cp := *p
				progList = append(progList, &cp)
			}
		}
		l.mu.RUnlock()
		// Always respond 200, even when the title isn't in the library. Every
		// MediaCard probes this endpoint to check whether its title is saved, so
		// a 404 here floods the devtools console with "failed to load resource"
		// for every not-yet-saved title. A null `entry` is the not-found signal
		// instead (the frontend's requestOrNull / `result?.entry` handles it).
		if progList == nil {
			progList = []*WatchProgress{}
		}
		// Value copy — entry is the actual pointer stored under l.mu (see
		// rewritePosterURL's doc comment for why this must not be mutated
		// in place).
		var entryOut *LibraryEntry
		if entry != nil {
			c := *entry
			c.PosterPath = utils.RewriteTMDBImageURL(c.PosterPath)
			entryOut = &c
		}
		utils.WriteJSON(w, map[string]any{"entry": entryOut, "progress": progList, "dismissed": dismissed})

	// ── DELETE /api/library/{id}/{type} ───────────────────────────────────────
	case sub == "" && r.Method == http.MethodDelete:
		l.mu.Lock()
		delete(l.db.Entries, key)
		// Record a removal tombstone so a sync pull cannot resurrect this entry.
		l.db.Removed[key] = &Removal{TmdbID: tmdbID, MediaType: mediaType, RemovedAt: time.Now()}
		// Deliberately do NOT delete WatchProgress records. The user is only
		// removing the title from their list, not erasing their watch history.
		// Progress records are keyed by (tmdb_id, media_type, season, episode)
		// and will be re-linked to a new entry if the title is added back later.
		l.gen.Add(1)
		l.markDirty()
		l.mu.Unlock()
		l.tasteGen.Add(1) // entry removal — taste-relevant
		w.WriteHeader(http.StatusNoContent)

	// ── PATCH /api/library/{id}/{type}/status ─────────────────────────────────
	case sub == "status" && r.Method == http.MethodPatch:
		var body struct {
			Status Status `json:"status"`
		}
		r.Body = http.MaxBytesReader(w, r.Body, utils.SmallBodyLimit)
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, "invalid body", http.StatusBadRequest)
			return
		}
		if !validStatus(body.Status) {
			http.Error(w, "invalid status", http.StatusBadRequest)
			return
		}
		l.mu.Lock()
		entry := l.db.Entries[key]
		if entry == nil {
			l.mu.Unlock()
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		entry.Status = body.Status
		entry.UpdatedAt = time.Now()
		l.gen.Add(1)
		l.markDirty()
		result := *entry
		l.mu.Unlock()
		l.tasteGen.Add(1) // status change — taste-relevant
		result.PosterPath = utils.RewriteTMDBImageURL(result.PosterPath)
		utils.WriteJSON(w, &result)

	// ── PATCH /api/library/{id}/{type}/rating ─────────────────────────────────
	case sub == "rating" && r.Method == http.MethodPatch:
		var body struct {
			Rating *float64 `json:"rating"`
		}
		r.Body = http.MaxBytesReader(w, r.Body, utils.SmallBodyLimit)
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, "invalid body", http.StatusBadRequest)
			return
		}
		if body.Rating != nil && (*body.Rating < 0 || *body.Rating > 5) {
			http.Error(w, "rating must be between 0 and 5", http.StatusBadRequest)
			return
		}
		l.mu.Lock()
		entry := l.db.Entries[key]
		if entry == nil {
			l.mu.Unlock()
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		entry.Rating = body.Rating
		entry.UpdatedAt = time.Now()
		l.gen.Add(1)
		l.markDirty()
		result := *entry
		l.mu.Unlock()
		l.tasteGen.Add(1) // rating change — taste-relevant
		result.PosterPath = utils.RewriteTMDBImageURL(result.PosterPath)
		utils.WriteJSON(w, &result)

	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (l *Library) handleDismiss(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodOptions:
		w.Header().Set("Access-Control-Allow-Methods", "POST, DELETE, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
		w.WriteHeader(http.StatusNoContent)

	case http.MethodPost, http.MethodDelete:
		var body struct {
			TmdbID    int    `json:"tmdb_id"`
			MediaType string `json:"media_type"`
		}
		r.Body = http.MaxBytesReader(w, r.Body, utils.SmallBodyLimit)
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, "invalid body: "+err.Error(), http.StatusBadRequest)
			return
		}
		if body.TmdbID <= 0 || !utils.ValidMediaType(body.MediaType) {
			http.Error(w, "positive tmdb_id and media_type movie or tv required", http.StatusBadRequest)
			return
		}

		key := entryKey(body.TmdbID, body.MediaType)
		l.mu.Lock()
		if r.Method == http.MethodPost {
			l.db.Dismissed[key] = &Dismissal{
				TmdbID:      body.TmdbID,
				MediaType:   body.MediaType,
				DismissedAt: time.Now(),
			}
		} else {
			delete(l.db.Dismissed, key)
		}
		l.gen.Add(1)
		l.markDirty()
		l.mu.Unlock()
		l.tasteGen.Add(1) // dismissal add/remove — taste-relevant
		w.WriteHeader(http.StatusNoContent)

	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (l *Library) handleStats(w http.ResponseWriter, _ *http.Request) {
	utils.WriteJSON(w, l.Stats())
}

// ── Helpers ───────────────────────────────────────────────────────────────────

// rewritePosterURL rewrites a legacy TMDB-hosted poster URL — one stored
// before internal/imgcache existed, or synced down from Supabase from another
// device on an older version — to route through the local image-cache proxy
// instead. tmdb.go itself only ever produces already-rewritten URLs now, but
// this covers entries written to disk (or Supabase) by a previous version of
// the app, without needing a one-time migration pass over every stored
// LibraryEntry.
//
// Serve-side view only: callers must apply this to a value COPY of the
// entry, never mutate the stored/cached LibraryEntry in place — entries are
// shared pointers under l.mu, and a synced-down URL must stay in its
// original (TMDB-hosted) form so a future Supabase push doesn't propagate a
// rewrite that only makes sense pointed at this machine's own backend.
