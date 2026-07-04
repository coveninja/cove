package activity

import (
	"testing"
	"time"

	"github.com/coveninja/cove/internal/library"
)

// newTestStore creates a Store backed by an isolated temp config directory
// so tests don't touch the real user config. The XDG_CONFIG_HOME env override
// is the same pattern used in internal/profiles/profiles_test.go.
func newTestStore(t *testing.T) *Store {
	t.Helper()
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	s, err := New("test")
	if err != nil {
		t.Fatal("activity.New:", err)
	}
	return s
}

func intPtr(v int) *int { return &v }

// progressEvent constructs a minimal ProgressSaveEvent for testing OnProgressSave.
func progressEvent(pKey string, pos float64, at time.Time) library.ProgressSaveEvent {
	return library.ProgressSaveEvent{
		ProgressKey: pKey,
		TmdbID:      1,
		MediaType:   "movie",
		Position:    pos,
		At:          at,
	}
}

// dayTotal returns the sum of all hours in a day entry.
func dayTotal(day *DayEntry) int64 {
	if day == nil {
		return 0
	}
	var total int64
	for _, h := range day.ByHour {
		total += h
	}
	return total
}

// ── Delta-clamp boundary tests ────────────────────────────────────────────────

// TestDeltaZeroNotCredited confirms a delta of exactly 0 earns no credit.
func TestDeltaZeroNotCredited(t *testing.T) {
	s := newTestStore(t)
	base := time.Now()

	// Seed LastPos to 10.
	s.OnProgressSave(progressEvent("1:movie", 10, base))
	// Same position again: delta = 0 — should not credit.
	s.OnProgressSave(progressEvent("1:movie", 10, base))

	date := base.Format("2006-01-02")
	s.mu.RLock()
	total := dayTotal(s.db.Days[date])
	s.mu.RUnlock()

	// First event: delta = 10 (credited). Second: delta = 0 (not credited).
	if total != 10 {
		t.Errorf("delta=0: expected 10 seconds credited, got %d", total)
	}
}

// TestDeltaNegativeNotCredited confirms a negative delta (rewatch-from-start)
// earns no credit.
func TestDeltaNegativeNotCredited(t *testing.T) {
	s := newTestStore(t)
	base := time.Now()

	s.OnProgressSave(progressEvent("1:movie", 100, base)) // delta 100 — credited (≤90? no, 100>90)
	s.OnProgressSave(progressEvent("1:movie", 0, base))   // delta -100 — not credited

	date := base.Format("2006-01-02")
	s.mu.RLock()
	total := dayTotal(s.db.Days[date])
	s.mu.RUnlock()

	// First tick: delta = 100 > 90 — not credited. Second: delta = -100 — not credited.
	if total != 0 {
		t.Errorf("negative delta: expected 0 seconds credited, got %d", total)
	}
}

// TestDeltaOneCredited confirms a delta of 1 (the minimum positive) is credited.
func TestDeltaOneCredited(t *testing.T) {
	s := newTestStore(t)
	base := time.Now()

	s.OnProgressSave(progressEvent("1:movie", 0, base)) // seed LastPos
	s.OnProgressSave(progressEvent("1:movie", 1, base)) // delta = 1

	date := base.Format("2006-01-02")
	s.mu.RLock()
	total := dayTotal(s.db.Days[date])
	s.mu.RUnlock()

	if total != 1 {
		t.Errorf("delta=1: expected 1 second credited, got %d", total)
	}
}

// TestDeltaNinetyCredited confirms a delta of exactly 90 (the maximum) is credited.
func TestDeltaNinetyCredited(t *testing.T) {
	s := newTestStore(t)
	base := time.Now()

	s.OnProgressSave(progressEvent("1:movie", 0, base))  // seed
	s.OnProgressSave(progressEvent("1:movie", 90, base)) // delta = 90 ≤ 90 — credited

	date := base.Format("2006-01-02")
	s.mu.RLock()
	total := dayTotal(s.db.Days[date])
	s.mu.RUnlock()

	if total != 90 {
		t.Errorf("delta=90: expected 90 seconds credited, got %d", total)
	}
}

// TestDeltaNinetyOneNotCredited confirms a delta of 91 (one over the ceiling)
// is discarded.
func TestDeltaNinetyOneNotCredited(t *testing.T) {
	s := newTestStore(t)
	base := time.Now()

	s.OnProgressSave(progressEvent("1:movie", 0, base))  // seed
	s.OnProgressSave(progressEvent("1:movie", 91, base)) // delta = 91 > 90 — not credited

	date := base.Format("2006-01-02")
	s.mu.RLock()
	total := dayTotal(s.db.Days[date])
	s.mu.RUnlock()

	if total != 0 {
		t.Errorf("delta=91: expected 0 seconds credited, got %d", total)
	}
}

// ── Streak tests ──────────────────────────────────────────────────────────────

// TestStreakConsecutive checks that three consecutive days yield streak = 3.
func TestStreakConsecutive(t *testing.T) {
	days := []string{"2026-01-01", "2026-01-02", "2026-01-03"}
	today, yesterday := "2026-01-03", "2026-01-02"
	if got := currentStreak(days, today, yesterday); got != 3 {
		t.Errorf("consecutive current streak: expected 3, got %d", got)
	}
	if got := longestStreak(days); got != 3 {
		t.Errorf("consecutive longest streak: expected 3, got %d", got)
	}
}

// TestStreakGap confirms a gap in the middle resets the streak to 1.
func TestStreakGap(t *testing.T) {
	// 2026-01-01 and 2026-01-03 are not consecutive.
	days := []string{"2026-01-01", "2026-01-03"}
	today, yesterday := "2026-01-03", "2026-01-02"
	if got := currentStreak(days, today, yesterday); got != 1 {
		t.Errorf("gap current streak: expected 1, got %d", got)
	}
	if got := longestStreak(days); got != 1 {
		t.Errorf("gap longest streak: expected 1, got %d", got)
	}
}

// TestStreakEmpty confirms an empty day list yields streak = 0.
func TestStreakEmpty(t *testing.T) {
	today, yesterday := "2026-01-03", "2026-01-02"
	if got := currentStreak(nil, today, yesterday); got != 0 {
		t.Errorf("empty current streak: expected 0, got %d", got)
	}
	if got := longestStreak(nil); got != 0 {
		t.Errorf("empty longest streak: expected 0, got %d", got)
	}
}

// TestStreakTodayEmptyYesterdayActive confirms that a streak counts back from
// yesterday when today has no recorded activity yet.
func TestStreakTodayEmptyYesterdayActive(t *testing.T) {
	days := []string{"2026-01-01", "2026-01-02", "2026-01-03"}
	// today is 2026-01-04 (no data), yesterday is 2026-01-03 (has data).
	today, yesterday := "2026-01-04", "2026-01-03"
	if got := currentStreak(days, today, yesterday); got != 3 {
		t.Errorf("yesterday-active streak: expected 3, got %d", got)
	}
}

// TestStreakNeither confirms that a streak of 0 is returned when neither today
// nor yesterday appears in the active-day list.
func TestStreakNeither(t *testing.T) {
	days := []string{"2026-01-01", "2026-01-02"}
	// today is 2026-01-05, yesterday 2026-01-04 — neither in the list.
	today, yesterday := "2026-01-05", "2026-01-04"
	if got := currentStreak(days, today, yesterday); got != 0 {
		t.Errorf("neither-today-nor-yesterday streak: expected 0, got %d", got)
	}
}

// ── Backfill idempotency test ─────────────────────────────────────────────────

// TestBackfillIdempotent confirms that calling Backfill twice credits the same
// total seconds as calling it once (Backfilled flag prevents double-counting).
func TestBackfillIdempotent(t *testing.T) {
	s := newTestStore(t)

	progress := []*library.WatchProgress{
		{
			TmdbID: 42, MediaType: "movie",
			PositionSeconds: 3000, DurationSeconds: 6000,
			Completed: false, WatchedAt: time.Now(),
		},
		{
			TmdbID: 99, MediaType: "tv", Season: intPtr(1), Episode: intPtr(2),
			PositionSeconds: 1200, DurationSeconds: 1500,
			Completed: true, WatchedAt: time.Now(),
		},
	}

	s.Backfill(progress)

	s.mu.RLock()
	var total1 int64
	for _, day := range s.db.Days {
		total1 += dayTotal(day)
	}
	s.mu.RUnlock()

	// Second call must be a no-op.
	s.Backfill(progress)

	s.mu.RLock()
	var total2 int64
	for _, day := range s.db.Days {
		total2 += dayTotal(day)
	}
	s.mu.RUnlock()

	if total1 != total2 {
		t.Errorf("backfill idempotency: first pass credited %d, second credited %d (should be equal)", total1, total2)
	}

	// movie: position (not completed) = 3000; tv episode: duration (completed) = 1500.
	const want = 3000 + 1500
	if total1 != want {
		t.Errorf("backfill totals: expected %d seconds, got %d", want, total1)
	}
}

// TestBackfillSeedsLastPos confirms that Backfill seeds LastPos so the first
// live tick after a backfill doesn't mis-credit the full existing position.
func TestBackfillSeedsLastPos(t *testing.T) {
	s := newTestStore(t)

	progress := []*library.WatchProgress{
		{TmdbID: 7, MediaType: "movie", PositionSeconds: 500, DurationSeconds: 6000, Completed: false, WatchedAt: time.Now()},
	}
	s.Backfill(progress)

	s.mu.RLock()
	lastPos := s.db.LastPos["7:movie"]
	s.mu.RUnlock()

	if lastPos != 500 {
		t.Errorf("backfill LastPos seed: expected 500, got %f", lastPos)
	}
}

// TestBackfillBucketsByLocalTime confirms that Backfill places seconds in the
// day/hour computed from WatchedAt converted to local time, not raw UTC. We
// pick 23:30 UTC so the date boundary crosses in most western timezones and
// the test is non-trivial.
func TestBackfillBucketsByLocalTime(t *testing.T) {
	s := newTestStore(t)

	// Build a UTC timestamp at 23:30 on 2026-01-01.
	watchedUTC := time.Date(2026, 1, 1, 23, 30, 0, 0, time.UTC)
	progress := []*library.WatchProgress{
		{
			TmdbID: 55, MediaType: "movie",
			PositionSeconds: 1000, DurationSeconds: 5000,
			Completed: false, WatchedAt: watchedUTC,
		},
	}
	s.Backfill(progress)

	// Compute expected bucket from local time — same conversion the code does.
	local := watchedUTC.In(time.Local)
	wantDate := local.Format("2006-01-02")
	wantHour := local.Hour()

	s.mu.RLock()
	day := s.db.Days[wantDate]
	s.mu.RUnlock()

	if day == nil {
		t.Fatalf("backfill local-time bucket: no entry for local date %s (UTC date was %s)", wantDate, watchedUTC.Format("2006-01-02"))
	}
	if got := day.ByHour[wantHour]; got != 1000 {
		t.Errorf("backfill local-time bucket: expected 1000 seconds in hour %d of %s, got %d", wantHour, wantDate, got)
	}
}
