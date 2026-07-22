// Package activity maintains a per-profile watch-time log, crediting playback
// seconds from /api/library/progress ticks into date/hour buckets without any
// player changes. The on-disk store is a single JSON file
// (activity-{profileID}.json) following the same debounced-persist pattern as
// internal/library: markDirty coalesces rapid ticks into a single write ~1s
// out; Flush() forces an immediate write on shutdown.
package activity

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/coveninja/cove/internal/library"
	"github.com/coveninja/cove/internal/utils"
)

// DayEntry holds per-day watch-time broken down by hour and by title.
type DayEntry struct {
	// ByHour accumulates seconds of playback credit for each clock-hour (0–23)
	// of the day in local time. Lets the activity page render an
	// "hours by time-of-day" histogram without scanning every day entry.
	ByHour [24]int64 `json:"by_hour"`
	// ByTitle maps "tmdbID:mediaType" → cumulative seconds for this day.
	// Key format matches the entryKey() idiom in internal/library: "12345:movie"
	// for movies, "12345:tv" for shows (title-level, not episode-level).
	ByTitle map[string]int64 `json:"by_title"`
}

// diskStore is the on-disk JSON format for the activity log.
type diskStore struct {
	// Days maps "YYYY-MM-DD" (local time) → per-day bucket. Sparse: missing
	// days never had any recorded activity. Store growth is bounded to roughly
	// 1 MB for ten years of daily use.
	Days map[string]*DayEntry `json:"days"`
	// LastPos records the last known playback position (seconds) per progress
	// key so OnProgressSave can compute the delta since the previous tick
	// rather than crediting the absolute position on every write.
	LastPos map[string]float64 `json:"last_pos"`
	// Backfilled is set after a successful Backfill() run so re-runs (e.g.
	// on restart) are no-ops — progress rows don't change once written, so a
	// second backfill would just double-count historical time.
	Backfilled bool `json:"backfilled"`
}

// LibraryLookup is the slice of *library.Library the activity handler needs
// to enrich TitlesWatchedThisYear with display metadata. *library.Library
// satisfies it. Keeping it as an interface lets tests inject a stub without
// a real on-disk library.
type LibraryLookup interface {
	AllEntries() []*library.LibraryEntry
}

// Store is the per-profile activity log. All exported methods are safe for
// concurrent use.
type Store struct {
	mu sync.RWMutex
	db diskStore

	// path is the file the store is currently bound to (profile-scoped,
	// swapped on SetProfile). Empty string means ConfigPath failed at
	// construction; markDirty/writeNow will log the error and no-op.
	path string

	// lib, if set, is used by the /api/library/activity handler to enrich
	// TitlesWatchedThisYear with title and poster metadata. Set via
	// SetLibrary after construction; a nil library renders those fields as
	// empty strings rather than crashing.
	lib LibraryLookup

	// Debounced persistence (mirrors library's D3 pattern exactly):
	// markDirty() marshals + schedules; flushPending() writes; Flush() forces.
	dirtyMu     sync.Mutex
	dirtyTimer  *time.Timer
	pendingPath string
	pendingData []byte
}

// persistDebounce matches the library's debounce window: long enough to
// coalesce a burst of playback ticks, short enough that a crash right after a
// mutation loses at most this much activity data.
const persistDebounce = 1 * time.Second

// New loads activity-{profileID}.json from the per-user config directory,
// creating it with empty maps if it doesn't exist yet. Always returns a
// usable (non-nil) *Store even on error so the caller can still register
// handlers against an empty store rather than crashing — matching library's
// best-effort pattern.
func New(profileID string) (*Store, error) {
	s := &Store{
		db: diskStore{
			Days:    make(map[string]*DayEntry),
			LastPos: make(map[string]float64),
		},
	}
	path, err := utils.ConfigPath(fmt.Sprintf("activity-%s.json", profileID))
	if err != nil {
		return s, err
	}
	s.path = path

	raw, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		// First run — write the empty store synchronously so the file exists
		// immediately (the same "synchronous exception" as library's New).
		return s, s.writeNow()
	}
	if err != nil {
		return s, err
	}
	if err := json.Unmarshal(raw, &s.db); err != nil {
		return s, err
	}
	// Guard against a store written by an older version that omitted these maps.
	if s.db.Days == nil {
		s.db.Days = make(map[string]*DayEntry)
	}
	if s.db.LastPos == nil {
		s.db.LastPos = make(map[string]float64)
	}
	return s, nil
}

// SnapshotJSON marshals the current activity store for cross-device sync.
// Safe for concurrent use (holds RLock across marshal).
func (s *Store) SnapshotJSON() ([]byte, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return json.Marshal(s.db)
}

// MergeFromJSON merges a remote activity store into the local one using
// per-bucket max semantics. No timestamp gate — max-merge is idempotent and
// commutative so always merging converges without double-counting:
//   - for each remote day: element-wise max into local ByHour, per-key max into ByTitle
//   - per-key max for LastPos
//   - Backfilled = local || remote
//
// Undercount tradeoff: two devices watching in the same clock-hour of the same
// day yield max(a,b) not a+b — the larger value wins. This mirrors Backfill's
// accepted-approximation stance and avoids double-counting on the common
// single-device case.
//
// Writes synchronously (same "synchronous exception" as Backfill) so the
// merged data reaches disk immediately rather than waiting out the debounce.
func (s *Store) MergeFromJSON(data []byte) error {
	var remote diskStore
	if err := json.Unmarshal(data, &remote); err != nil {
		return err
	}
	if remote.Days == nil {
		remote.Days = make(map[string]*DayEntry)
	}
	if remote.LastPos == nil {
		remote.LastPos = make(map[string]float64)
	}

	s.mu.Lock()
	for dateStr, rd := range remote.Days {
		if rd == nil {
			continue
		}
		ld := s.db.Days[dateStr]
		if ld == nil {
			ld = &DayEntry{ByTitle: make(map[string]int64)}
			s.db.Days[dateStr] = ld
		}
		if ld.ByTitle == nil {
			ld.ByTitle = make(map[string]int64)
		}
		// Element-wise max for ByHour.
		for h, rv := range rd.ByHour {
			if rv > ld.ByHour[h] {
				ld.ByHour[h] = rv
			}
		}
		// Per-key max for ByTitle.
		for titleKey, rv := range rd.ByTitle {
			if rv > ld.ByTitle[titleKey] {
				ld.ByTitle[titleKey] = rv
			}
		}
	}
	// Per-key max for LastPos.
	for key, rv := range remote.LastPos {
		if rv > s.db.LastPos[key] {
			s.db.LastPos[key] = rv
		}
	}
	s.db.Backfilled = s.db.Backfilled || remote.Backfilled

	err := s.writeNow()
	s.mu.Unlock()
	return err
}

// SetLibrary wires the library lookup used by the stats handler to enrich
// title/poster data for TitlesWatchedThisYear. Called from main.go after New,
// before the server starts serving.
func (s *Store) SetLibrary(lib LibraryLookup) {
	s.mu.Lock()
	s.lib = lib
	s.mu.Unlock()
}

// SetProfile reloads the store from the given profile's data file. Safe to
// call while handlers are live: flushes any pending debounced write for the
// old profile before swapping, then takes the write lock to swap db/path.
func (s *Store) SetProfile(profileID string) error {
	path, err := utils.ConfigPath(fmt.Sprintf("activity-%s.json", profileID))
	if err != nil {
		return err
	}
	newDB := diskStore{
		Days:    make(map[string]*DayEntry),
		LastPos: make(map[string]float64),
	}
	raw, err := os.ReadFile(path)
	if err != nil && !os.IsNotExist(err) {
		return err
	}
	if err == nil {
		if err := json.Unmarshal(raw, &newDB); err != nil {
			return err
		}
		if newDB.Days == nil {
			newDB.Days = make(map[string]*DayEntry)
		}
		if newDB.LastPos == nil {
			newDB.LastPos = make(map[string]float64)
		}
	}

	// Synchronous exception (D3): flush the old profile's pending write before
	// swapping db/path — otherwise a mutation just before the switch could be
	// written to the new profile's file once the debounce fires.
	s.Flush()

	s.mu.Lock()
	s.db = newDB
	s.path = path
	s.mu.Unlock()
	return nil
}

// OnProgressSave credits a watch-time delta into the current day/hour bucket.
// Designed to be registered via lib.SetOnProgressSave(act.OnProgressSave) and
// called on every ~10s playback tick from POST /api/library/progress.
//
// Delta logic: computes delta = ev.Position − LastPos[ev.ProgressKey] and
// credits only 0 < delta ≤ 90 seconds as genuine playback time. The 90s
// ceiling discards seeks, large pauses, and the position jump when a completed
// save arrives after prior ticks already covered the content. A negative delta
// (rewatch-from-start) is discarded, losing at most one tick — accepted.
func (s *Store) OnProgressSave(ev library.ProgressSaveEvent) {
	s.mu.Lock()

	delta := ev.Position - s.db.LastPos[ev.ProgressKey]
	s.db.LastPos[ev.ProgressKey] = ev.Position

	if delta > 0 && delta <= 90 {
		date := ev.At.Format("2006-01-02")
		hour := ev.At.Hour()
		day := s.db.Days[date]
		if day == nil {
			day = &DayEntry{ByTitle: make(map[string]int64)}
			s.db.Days[date] = day
		}
		if day.ByTitle == nil {
			day.ByTitle = make(map[string]int64)
		}
		credits := int64(delta)
		day.ByHour[hour] += credits
		titleKey := fmt.Sprintf("%d:%s", ev.TmdbID, ev.MediaType)
		day.ByTitle[titleKey] += credits
		s.markDirty()
	}

	s.mu.Unlock()
}

// Backfill seeds the activity log from existing watch-progress rows. It is
// idempotent: subsequent calls after the first successful run are no-ops
// (guarded by the Backfilled flag persisted to disk). For each row it credits
// DurationSeconds if the row is marked completed, else PositionSeconds,
// bucketed at the row's WatchedAt date/hour in local time (matching live
// ticks, which use time.Now()). This is an approximation —
// all seconds for a title land in one timestamp per row — that the user has
// accepted in exchange for getting historical data with no extra tracking.
//
// Backfill also seeds LastPos from PositionSeconds so the first live tick
// after an upgrade computes a correct delta and doesn't mis-credit the entire
// existing position as fresh watch-time.
func (s *Store) Backfill(progress []*library.WatchProgress) {
	s.mu.Lock()
	if s.db.Backfilled {
		s.mu.Unlock()
		return
	}

	for _, p := range progress {
		var secs float64
		if p.Completed {
			secs = p.DurationSeconds
		} else {
			secs = p.PositionSeconds
		}
		if secs <= 0 {
			continue
		}

		// Seed LastPos so the first live tick produces a correct delta.
		pKey := progressKeyFor(p)
		if s.db.LastPos[pKey] < p.PositionSeconds {
			s.db.LastPos[pKey] = p.PositionSeconds
		}

		local := p.WatchedAt.In(time.Local)
		date := local.Format("2006-01-02")
		hour := local.Hour()
		day := s.db.Days[date]
		if day == nil {
			day = &DayEntry{ByTitle: make(map[string]int64)}
			s.db.Days[date] = day
		}
		if day.ByTitle == nil {
			day.ByTitle = make(map[string]int64)
		}
		credits := int64(secs)
		day.ByHour[hour] += credits
		titleKey := fmt.Sprintf("%d:%s", p.TmdbID, p.MediaType)
		day.ByTitle[titleKey] += credits
	}

	s.db.Backfilled = true
	// Synchronous write inside the lock — this is first-run data (same
	// "synchronous exception" as library.MergeFrom) and must hit disk
	// immediately so a restart doesn't re-run the backfill and double-count.
	if err := s.writeNow(); err != nil {
		log.Println("activity: backfill write failed:", err)
	}
	s.mu.Unlock()
}

// progressKeyFor reconstructs the progress key from a WatchProgress row,
// matching internal/library's progressKey() format exactly:
//
//	movies:   "12345:movie"
//	episodes: "12345:tv:1:3"
func progressKeyFor(p *library.WatchProgress) string {
	if p.Season != nil && p.Episode != nil {
		return fmt.Sprintf("%d:%s:%d:%d", p.TmdbID, p.MediaType, *p.Season, *p.Episode)
	}
	return fmt.Sprintf("%d:%s", p.TmdbID, p.MediaType)
}

// ── Stats ─────────────────────────────────────────────────────────────────────

// TitleSeconds holds per-title watch-time for the current year, enriched with
// display metadata when a LibraryLookup is wired in.
type TitleSeconds struct {
	TmdbID     int    `json:"tmdb_id"`
	MediaType  string `json:"media_type"`
	Seconds    int64  `json:"seconds"`
	Title      string `json:"title,omitempty"`
	PosterPath string `json:"poster_path,omitempty"`
}

// Stats is the response shape for GET /api/library/activity.
type Stats struct {
	TotalSeconds           int64            `json:"total_seconds"`
	TotalTitles            int              `json:"total_titles"`
	CurrentStreak          int              `json:"current_streak"`
	LongestStreak          int              `json:"longest_streak"`
	AvgSecondsPerActiveDay int64            `json:"avg_seconds_per_active_day"`
	TitlesThisYear         int              `json:"titles_this_year"`
	ThisYearSeconds        int64            `json:"this_year_seconds"`
	LastYearSeconds        int64            `json:"last_year_seconds"`
	ByYear                 map[string]int64 `json:"by_year"`
	ByMonthThisYear        [12]int64        `json:"by_month_this_year"` // 0 = Jan
	ByMonthLastYear        [12]int64        `json:"by_month_last_year"`
	ByDayOfWeek            [7]int64         `json:"by_day_of_week"` // 0 = Sun
	ByHourOfDay            [24]int64        `json:"by_hour_of_day"`
	Calendar               map[string]int64 `json:"calendar"`                 // non-zero days only
	TitlesWatchedThisYear  []TitleSeconds   `json:"titles_watched_this_year"` // top ~20 by seconds
}

// computeStats builds the Stats response from s.db. Must be called with
// s.mu held in any mode (it reads s.db directly without re-locking).
func (s *Store) computeStats(lib LibraryLookup) Stats {
	now := time.Now()
	thisYear := now.Year()
	lastYear := thisYear - 1
	today := now.Format("2006-01-02")
	yesterday := now.AddDate(0, 0, -1).Format("2006-01-02")

	stats := Stats{
		ByYear:   make(map[string]int64),
		Calendar: make(map[string]int64),
	}

	// allTitles collects every unique "tmdbID:mediaType" key ever seen, for
	// TotalTitles. titlesThisYear accumulates seconds per title in this year,
	// for the top-N poster strip.
	allTitles := make(map[string]struct{})
	titlesThisYear := make(map[string]int64)

	for dateStr, day := range s.db.Days {
		t, err := time.ParseInLocation("2006-01-02", dateStr, time.Local)
		if err != nil {
			continue
		}
		year := t.Year()
		month := int(t.Month()) - 1 // 0-indexed January
		dow := int(t.Weekday())     // 0 = Sunday

		var dayTotal int64
		for h, secs := range day.ByHour {
			dayTotal += secs
			stats.ByHourOfDay[h] += secs
		}
		for titleKey, secs := range day.ByTitle {
			allTitles[titleKey] = struct{}{}
			if year == thisYear {
				titlesThisYear[titleKey] += secs
			}
		}

		if dayTotal == 0 {
			continue
		}

		yearStr := fmt.Sprintf("%d", year)
		stats.ByYear[yearStr] += dayTotal
		stats.ByDayOfWeek[dow] += dayTotal
		stats.Calendar[dateStr] = dayTotal
		stats.TotalSeconds += dayTotal

		if year == thisYear {
			stats.ThisYearSeconds += dayTotal
			stats.ByMonthThisYear[month] += dayTotal
		} else if year == lastYear {
			stats.LastYearSeconds += dayTotal
			stats.ByMonthLastYear[month] += dayTotal
		}
	}

	stats.TotalTitles = len(allTitles)
	stats.TitlesThisYear = len(titlesThisYear)

	activeDays := int64(len(stats.Calendar))
	if activeDays > 0 {
		stats.AvgSecondsPerActiveDay = stats.TotalSeconds / activeDays
	}

	// Streaks are computed over the sorted set of active-day keys; "YYYY-MM-DD"
	// strings sort lexicographically in chronological order.
	activeDayKeys := make([]string, 0, len(stats.Calendar))
	for d := range stats.Calendar {
		activeDayKeys = append(activeDayKeys, d)
	}
	sort.Strings(activeDayKeys)

	stats.LongestStreak = longestStreak(activeDayKeys)
	stats.CurrentStreak = currentStreak(activeDayKeys, today, yesterday)

	// TitlesWatchedThisYear: sort descending, cap at 20, enrich with metadata.
	type kv struct {
		key  string
		secs int64
	}
	titleList := make([]kv, 0, len(titlesThisYear))
	for k, v := range titlesThisYear {
		titleList = append(titleList, kv{k, v})
	}
	sort.Slice(titleList, func(i, j int) bool { return titleList[i].secs > titleList[j].secs })
	if len(titleList) > 20 {
		titleList = titleList[:20]
	}

	// Build a lookup map from library entries if a library is wired in.
	entryByKey := make(map[string]*library.LibraryEntry)
	if lib != nil {
		for _, e := range lib.AllEntries() {
			entryByKey[fmt.Sprintf("%d:%s", e.TmdbID, e.MediaType)] = e
		}
	}

	stats.TitlesWatchedThisYear = make([]TitleSeconds, 0, len(titleList))
	for _, item := range titleList {
		// Title keys are "tmdbID:mediaType"; split on the first colon only so
		// we don't break if a future media type ever contains a colon.
		sep := strings.Index(item.key, ":")
		if sep < 0 {
			continue
		}
		tmdbID, err := strconv.Atoi(item.key[:sep])
		if err != nil {
			continue
		}
		ts := TitleSeconds{
			TmdbID:    tmdbID,
			MediaType: item.key[sep+1:],
			Seconds:   item.secs,
		}
		if e, ok := entryByKey[item.key]; ok {
			ts.Title = e.Title
			ts.PosterPath = rewritePosterURL(e.PosterPath)
		}
		stats.TitlesWatchedThisYear = append(stats.TitlesWatchedThisYear, ts)
	}

	return stats
}

// rewritePosterURL rewrites a TMDB-hosted poster URL to route through the
// local image-cache proxy — same logic as library's unexported helper,
// duplicated here to avoid a cross-package dependency on an unexported symbol.
func rewritePosterURL(s string) string {
	if rest, ok := strings.CutPrefix(s, "https://image.tmdb.org/t/p/"); ok {
		return "http://127.0.0.1:6969/api/img/" + rest
	}
	return s
}

// longestStreak returns the longest run of consecutive calendar days in a
// sorted (ascending) slice of "YYYY-MM-DD" strings.
func longestStreak(sorted []string) int {
	var longest, run int
	for i, d := range sorted {
		if i == 0 {
			run = 1
		} else {
			prev, _ := time.ParseInLocation("2006-01-02", sorted[i-1], time.Local)
			cur, _ := time.ParseInLocation("2006-01-02", d, time.Local)
			if cur.Sub(prev) == 24*time.Hour {
				run++
			} else {
				run = 1
			}
		}
		if run > longest {
			longest = run
		}
	}
	return longest
}

// currentStreak counts backward from today (or yesterday if today has no
// activity) until the chain of consecutive days with activity breaks.
func currentStreak(sorted []string, today, yesterday string) int {
	if len(sorted) == 0 {
		return 0
	}
	// The anchor is today if the most-recent active day is today, otherwise
	// yesterday (so a streak doesn't reset just because the user hasn't
	// watched anything yet today).
	last := sorted[len(sorted)-1]
	var anchor string
	switch last {
	case today:
		anchor = today
	case yesterday:
		anchor = yesterday
	default:
		return 0
	}

	anchorTime, err := time.ParseInLocation("2006-01-02", anchor, time.Local)
	if err != nil {
		return 0
	}

	streak := 0
	expected := anchorTime
	for i := len(sorted) - 1; i >= 0; i-- {
		d, err := time.ParseInLocation("2006-01-02", sorted[i], time.Local)
		if err != nil {
			break
		}
		if d.Equal(expected) {
			streak++
			expected = expected.AddDate(0, 0, -1)
		} else {
			break
		}
	}
	return streak
}

// ── Persistence (mirrors library's D3 debounce pattern) ───────────────────────

// markDirty marshals the current store and schedules a debounced write.
// Must be called with s.mu held (any mode — it only reads s.db and s.path).
func (s *Store) markDirty() {
	raw, err := json.Marshal(s.db)
	if err != nil {
		log.Println("activity: marshal failed:", err)
		return
	}
	s.dirtyMu.Lock()
	defer s.dirtyMu.Unlock()
	s.pendingData = raw
	s.pendingPath = s.path
	if s.dirtyTimer == nil {
		s.dirtyTimer = time.AfterFunc(persistDebounce, s.flushPending)
	}
}

// flushPending is dirtyTimer's callback — writes out whatever markDirty last
// staged. Does not touch s.mu: it only reads pendingData/pendingPath, both
// owned by dirtyMu.
func (s *Store) flushPending() {
	s.dirtyMu.Lock()
	data, path := s.pendingData, s.pendingPath
	s.pendingData, s.pendingPath, s.dirtyTimer = nil, "", nil
	s.dirtyMu.Unlock()

	if data == nil {
		return
	}
	if err := utils.AtomicWriteFile(path, data, 0o644); err != nil {
		log.Println("activity: debounced write failed:", err)
	}
}

// Flush forces any pending debounced write to happen immediately — call on
// shutdown so the last credited seconds before exit aren't lost to the
// persistDebounce window the process didn't live to see.
func (s *Store) Flush() {
	s.dirtyMu.Lock()
	if s.dirtyTimer != nil {
		s.dirtyTimer.Stop()
	}
	data, path := s.pendingData, s.pendingPath
	s.pendingData, s.pendingPath, s.dirtyTimer = nil, "", nil
	s.dirtyMu.Unlock()

	if data == nil {
		return
	}
	if err := utils.AtomicWriteFile(path, data, 0o644); err != nil {
		log.Println("activity: flush failed:", err)
	}
}

// writeNow synchronously marshals and writes the store to disk, bypassing the
// debounce. Must be called with s.mu held (or before s is accessible to other
// goroutines, as in New's first-run write).
func (s *Store) writeNow() error {
	if s.path == "" {
		return nil // ConfigPath failed at construction; silently no-op
	}
	raw, err := json.Marshal(s.db)
	if err != nil {
		return err
	}
	return utils.AtomicWriteFile(s.path, raw, 0o644)
}

// ── HTTP handler ──────────────────────────────────────────────────────────────

// SetupHandlers registers GET /api/library/activity on mux, wrapped in the
// same CORS middleware every other library handler uses.
func (s *Store) SetupHandlers(mux *http.ServeMux) {
	mux.HandleFunc("/api/library/activity", utils.CorsMiddleware(s.handleActivity))
}

func (s *Store) handleActivity(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	// Hold the read lock across computeStats (which reads s.db directly) and
	// the lib.AllEntries() call inside it. The library never acquires s.mu, so
	// the lock order s.mu → lib.mu has no reverse and is deadlock-free.
	s.mu.RLock()
	lib := s.lib
	stats := s.computeStats(lib)
	s.mu.RUnlock()

	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(stats); err != nil {
		log.Println("activity: json encode:", err)
	}
}
