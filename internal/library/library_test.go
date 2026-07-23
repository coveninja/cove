package library

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func newLib(t *testing.T) *Library {
	t.Helper()
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	l, err := New("test")
	require.NoError(t, err)
	return l
}

func TestNew_Empty(t *testing.T) {
	l := newLib(t)
	assert.Empty(t, l.AllEntries())
	assert.Empty(t, l.AllProgress())
	assert.Empty(t, l.AllDismissals())
}

func TestGeneration_Bumps(t *testing.T) {
	l := newLib(t)
	g0 := l.Generation()
	l.MergeFrom([]*LibraryEntry{{
		ID: "id1", TmdbID: 1, MediaType: "movie", Title: "Test",
		Status: StatusWatchLater, AddedAt: time.Now(), UpdatedAt: time.Now(),
	}}, nil, nil, nil)
	assert.Greater(t, l.Generation(), g0)
}

func TestMergeFrom_IdenticalDataDoesNotBumpGeneration(t *testing.T) {
	l := newLib(t)
	now := time.Now().UTC()
	e := &LibraryEntry{
		ID: "id1", TmdbID: 1, MediaType: "movie", Title: "Test",
		Status: StatusWatchLater, AddedAt: now, UpdatedAt: now,
	}
	p := &WatchProgress{
		ID: "progress1", TmdbID: 1, MediaType: "movie",
		PositionSeconds: 10, WatchedAt: now,
	}
	d := &Dismissal{TmdbID: 2, MediaType: "movie", DismissedAt: now}
	r := &Removal{TmdbID: 3, MediaType: "movie", RemovedAt: now}

	l.MergeFrom([]*LibraryEntry{e}, []*WatchProgress{p}, []*Dismissal{d}, []*Removal{r})
	gen := l.Generation()
	tasteGen := l.TasteGeneration()

	l.MergeFrom([]*LibraryEntry{e}, []*WatchProgress{p}, []*Dismissal{d}, []*Removal{r})

	assert.Equal(t, gen, l.Generation())
	assert.Equal(t, tasteGen, l.TasteGeneration())
}

func TestMergeFrom_AddEntry(t *testing.T) {
	l := newLib(t)
	now := time.Now()
	e := &LibraryEntry{ID: "abc", TmdbID: 42, MediaType: "movie", Title: "Foo", Status: StatusWatching, AddedAt: now, UpdatedAt: now}
	l.MergeFrom([]*LibraryEntry{e}, nil, nil, nil)
	entries := l.AllEntries()
	require.Len(t, entries, 1)
	assert.Equal(t, 42, entries[0].TmdbID)
}

func TestMergeFrom_LastWriteWins(t *testing.T) {
	l := newLib(t)
	old := time.Now().Add(-time.Hour)
	recent := time.Now()
	e1 := &LibraryEntry{ID: "e1", TmdbID: 7, MediaType: "tv", Title: "Old", Status: StatusWatchLater, AddedAt: old, UpdatedAt: old}
	l.MergeFrom([]*LibraryEntry{e1}, nil, nil, nil)

	e2 := &LibraryEntry{ID: "e1", TmdbID: 7, MediaType: "tv", Title: "New", Status: StatusFinished, AddedAt: old, UpdatedAt: recent}
	l.MergeFrom([]*LibraryEntry{e2}, nil, nil, nil)
	entries := l.AllEntries()
	require.Len(t, entries, 1)
	assert.Equal(t, StatusFinished, entries[0].Status)
}

func TestMergeFrom_MostRecentProgressWins(t *testing.T) {
	l := newLib(t)
	base := time.Now()
	p1 := &WatchProgress{ID: "p1", TmdbID: 1, MediaType: "movie", PositionSeconds: 100, WatchedAt: base}
	l.MergeFrom(nil, []*WatchProgress{p1}, nil, nil)

	// A more recent write wins, even with a LOWER position — this is what
	// makes "mark as unwatched" (position 0) and rewatch-from-start
	// syncable. The old max-position rule reverted both.
	p2 := &WatchProgress{ID: "p2", TmdbID: 1, MediaType: "movie", PositionSeconds: 0, Completed: false, WatchedAt: base.Add(time.Minute)}
	l.MergeFrom(nil, []*WatchProgress{p2}, nil, nil)
	progs := l.AllProgress()
	require.Len(t, progs, 1)
	assert.Equal(t, float64(0), progs[0].PositionSeconds)

	// An older write never overwrites a newer one, regardless of position.
	p3 := &WatchProgress{ID: "p3", TmdbID: 1, MediaType: "movie", PositionSeconds: 5000, WatchedAt: base.Add(-time.Hour)}
	l.MergeFrom(nil, []*WatchProgress{p3}, nil, nil)
	progs = l.AllProgress()
	require.Len(t, progs, 1)
	assert.Equal(t, float64(0), progs[0].PositionSeconds)
	assert.Equal(t, "p2", progs[0].ID)
}

func TestDismissal(t *testing.T) {
	l := newLib(t)
	d := &Dismissal{TmdbID: 99, MediaType: "movie", DismissedAt: time.Now()}
	l.MergeFrom(nil, nil, []*Dismissal{d}, nil)
	ds := l.AllDismissals()
	require.Len(t, ds, 1)
	assert.Equal(t, 99, ds[0].TmdbID)
}

func TestStats(t *testing.T) {
	l := newLib(t)
	rating := 4.0
	l.MergeFrom([]*LibraryEntry{
		{ID: "a", TmdbID: 1, MediaType: "movie", Status: StatusFinished, Rating: &rating, AddedAt: time.Now(), UpdatedAt: time.Now()},
		{ID: "b", TmdbID: 2, MediaType: "tv", Status: StatusWatching, AddedAt: time.Now(), UpdatedAt: time.Now()},
	}, nil, []*Dismissal{{TmdbID: 3, MediaType: "movie", DismissedAt: time.Now()}}, nil)

	st := l.Stats()
	assert.Equal(t, 2, st.Total)
	assert.Equal(t, 1, st.ByType["movie"])
	assert.Equal(t, 1, st.ByType["tv"])
	assert.Equal(t, 1, st.Dismissed)
	assert.Equal(t, 1, st.Rated)
	assert.Equal(t, 4.0, st.AvgRating)
}

func TestTasteSignals(t *testing.T) {
	l := newLib(t)
	l.MergeFrom([]*LibraryEntry{
		{ID: "a", TmdbID: 1, MediaType: "movie", Status: StatusFinished, AddedAt: time.Now(), UpdatedAt: time.Now()},
	}, nil, []*Dismissal{{TmdbID: 2, MediaType: "tv", DismissedAt: time.Now()}}, nil)

	signals := l.TasteSignals()
	require.Len(t, signals, 2)
	byID := make(map[int]TasteSignal)
	for _, s := range signals {
		byID[s.TmdbID] = s
	}
	assert.Equal(t, StatusFinished, byID[1].Status)
	assert.False(t, byID[1].Dismissed)
	assert.True(t, byID[2].Dismissed)
}

func TestHandlers_GetLibrary(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	req := httptest.NewRequest(http.MethodGet, "/api/library", nil)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)

	assert.Equal(t, http.StatusOK, rr.Code)
	var entries []*LibraryEntry
	require.NoError(t, json.NewDecoder(rr.Body).Decode(&entries))
	assert.Empty(t, entries)
}

func TestHandlers_PostLibrary(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	body := `{"tmdb_id":123,"media_type":"movie","title":"Test Movie","status":"watch_later"}`
	req := httptest.NewRequest(http.MethodPost, "/api/library", bytes.NewBufferString(body))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)

	assert.Equal(t, http.StatusOK, rr.Code)
	var e LibraryEntry
	require.NoError(t, json.NewDecoder(rr.Body).Decode(&e))
	assert.Equal(t, 123, e.TmdbID)
	assert.Equal(t, "watch_later", e.Status)
}

func TestHandlers_GetStats(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	req := httptest.NewRequest(http.MethodGet, "/api/library/stats", nil)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)

	assert.Equal(t, http.StatusOK, rr.Code)
	var st Stats
	require.NoError(t, json.NewDecoder(rr.Body).Decode(&st))
	assert.Equal(t, 0, st.Total)
}

// ── D2: TasteGeneration() bumps only on taste-relevant mutations ────────────

func TestTasteGeneration_BumpsOnEntryUpsert(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	g0 := l.TasteGeneration()
	body := `{"tmdb_id":123,"media_type":"movie","title":"Test Movie","status":"watch_later"}`
	req := httptest.NewRequest(http.MethodPost, "/api/library", bytes.NewBufferString(body))
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)

	assert.Equal(t, http.StatusOK, rr.Code)
	assert.Greater(t, l.TasteGeneration(), g0)
}

func TestTasteGeneration_DoesNotBumpOnProgressPositionTick(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	// First write creates the entry — taste-relevant (auto-created "watching").
	// Isolate the *second* write (a plain position tick, not completing
	// anything) to verify it alone doesn't bump tasteGen.
	post := func(pos float64, completed bool) {
		body := bytes.NewBufferString(`{"tmdb_id":55,"media_type":"movie","position_seconds":` +
			jsonFloat(pos) + `,"duration_seconds":6000,"completed":` + jsonBool(completed) + `}`)
		req := httptest.NewRequest(http.MethodPost, "/api/library/progress", body)
		rr := httptest.NewRecorder()
		mux.ServeHTTP(rr, req)
		require.Equal(t, http.StatusOK, rr.Code)
	}

	post(10, false)
	g0 := l.TasteGeneration()
	post(20, false) // plain position tick — not near/at completion
	assert.Equal(t, g0, l.TasteGeneration(), "a progress tick that doesn't complete must not bump tasteGen")
}

func TestTasteGeneration_BumpsOnCompletedTransition(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	post := func(pos float64, completed bool) {
		body := bytes.NewBufferString(`{"tmdb_id":66,"media_type":"movie","position_seconds":` +
			jsonFloat(pos) + `,"duration_seconds":6000,"completed":` + jsonBool(completed) + `}`)
		req := httptest.NewRequest(http.MethodPost, "/api/library/progress", body)
		rr := httptest.NewRecorder()
		mux.ServeHTTP(rr, req)
		require.Equal(t, http.StatusOK, rr.Code)
	}

	post(10, false)
	g0 := l.TasteGeneration()
	post(6000, true) // transitions to Completed
	assert.Greater(t, l.TasteGeneration(), g0, "a Completed transition must bump tasteGen")
}

func TestGeneration_NotDoubleBumpedByMergeFrom(t *testing.T) {
	l := newLib(t)
	g0 := l.Generation()
	l.MergeFrom([]*LibraryEntry{{
		ID: "id1", TmdbID: 1, MediaType: "movie", Title: "Test",
		Status: StatusWatchLater, AddedAt: time.Now(), UpdatedAt: time.Now(),
	}}, nil, nil, nil)
	// persist() bumps gen exactly once; MergeFrom must not add a second bump
	// on top of it.
	assert.Equal(t, g0+1, l.Generation())
}

func jsonFloat(f float64) string {
	return strconv.FormatFloat(f, 'f', -1, 64)
}

func jsonBool(b bool) string {
	if b {
		return "true"
	}
	return "false"
}

// ── RegenerateIDsNotIn tests ──────────────────────────────────────────────────

func TestRegenerateIDsNotIn_RegeneratesNonOwned(t *testing.T) {
	l := newLib(t)
	now := time.Now()

	// Seed a library entry and a progress row with IDs that are NOT in the owned set.
	e := &LibraryEntry{ID: "foreign-entry-id", TmdbID: 1, MediaType: "movie", Title: "T",
		Status: StatusWatchLater, AddedAt: now, UpdatedAt: now}
	p := &WatchProgress{ID: "foreign-progress-id", TmdbID: 1, MediaType: "movie",
		LibraryEntryID: "foreign-entry-id", WatchedAt: now}
	l.MergeFrom([]*LibraryEntry{e}, []*WatchProgress{p}, nil, nil)

	gen0 := l.Generation()

	// Both IDs are absent from the owned sets → should be regenerated.
	l.RegenerateIDsNotIn(map[string]bool{}, map[string]bool{})

	entries := l.AllEntries()
	require.Len(t, entries, 1)
	assert.NotEqual(t, "foreign-entry-id", entries[0].ID, "entry ID should be regenerated")
	assert.NotEmpty(t, entries[0].ID)

	progress := l.AllProgress()
	require.Len(t, progress, 1)
	assert.NotEqual(t, "foreign-progress-id", progress[0].ID, "progress ID should be regenerated")
	// LibraryEntryID must follow the entry's new ID.
	assert.Equal(t, entries[0].ID, progress[0].LibraryEntryID, "LibraryEntryID should be remapped to the new entry ID")

	assert.Greater(t, l.Generation(), gen0, "generation must bump after regen")
}

func TestRegenerateIDsNotIn_OwnershipPreservesIDs(t *testing.T) {
	l := newLib(t)
	now := time.Now()

	e := &LibraryEntry{ID: "owned-entry", TmdbID: 2, MediaType: "tv", Title: "T",
		Status: StatusWatching, AddedAt: now, UpdatedAt: now}
	p := &WatchProgress{ID: "owned-progress", TmdbID: 2, MediaType: "tv",
		LibraryEntryID: "owned-entry", WatchedAt: now}
	l.MergeFrom([]*LibraryEntry{e}, []*WatchProgress{p}, nil, nil)

	// Both IDs ARE in the owned sets → must not change.
	l.RegenerateIDsNotIn(
		map[string]bool{"owned-entry": true},
		map[string]bool{"owned-progress": true},
	)

	entries := l.AllEntries()
	require.Len(t, entries, 1)
	assert.Equal(t, "owned-entry", entries[0].ID, "owned entry ID must not change")

	progress := l.AllProgress()
	require.Len(t, progress, 1)
	assert.Equal(t, "owned-progress", progress[0].ID, "owned progress ID must not change")
}

func TestRegenerateIDsNotIn_MixedOwnership(t *testing.T) {
	l := newLib(t)
	now := time.Now()

	eOwned := &LibraryEntry{ID: "owned-e", TmdbID: 3, MediaType: "movie", Title: "A",
		Status: StatusWatchLater, AddedAt: now, UpdatedAt: now}
	eForeign := &LibraryEntry{ID: "foreign-e", TmdbID: 4, MediaType: "movie", Title: "B",
		Status: StatusWatchLater, AddedAt: now, UpdatedAt: now}
	l.MergeFrom([]*LibraryEntry{eOwned, eForeign}, nil, nil, nil)

	l.RegenerateIDsNotIn(
		map[string]bool{"owned-e": true},
		map[string]bool{},
	)

	entries := l.AllEntries()
	require.Len(t, entries, 2)
	ids := make(map[string]bool)
	for _, e := range entries {
		ids[e.ID] = true
	}
	assert.True(t, ids["owned-e"], "owned-e must survive regeneration")
	assert.False(t, ids["foreign-e"], "foreign-e must have been replaced with a new UUID")
}

// ── AdoptRemoteIDs tests ──────────────────────────────────────────────────────

func intPtr(v int) *int { return &v }

// TestAdoptRemoteIDs_EntryIDAdopted verifies that a local entry whose natural
// key (tmdb_id, media_type) matches a remote row but whose UUID differs has its
// ID replaced with the remote UUID.
func TestAdoptRemoteIDs_EntryIDAdopted(t *testing.T) {
	l := newLib(t)
	now := time.Now()

	e := &LibraryEntry{ID: "local-entry-uuid", TmdbID: 10, MediaType: "movie", Title: "T",
		Status: StatusWatchLater, AddedAt: now, UpdatedAt: now}
	l.MergeFrom([]*LibraryEntry{e}, nil, nil, nil)

	gen0 := l.Generation()

	l.AdoptRemoteIDs([]RemoteRowID{
		{ID: "remote-entry-uuid", TmdbID: 10, MediaType: "movie"},
	}, nil)

	entries := l.AllEntries()
	require.Len(t, entries, 1)
	assert.Equal(t, "remote-entry-uuid", entries[0].ID, "entry ID must be adopted from remote")
	assert.Greater(t, l.Generation(), gen0, "generation must bump after adoption")
}

// TestAdoptRemoteIDs_EntryNotInRemote verifies that a local entry with no
// matching natural key in the remote list is left completely untouched.
func TestAdoptRemoteIDs_EntryNotInRemote(t *testing.T) {
	l := newLib(t)
	now := time.Now()

	e := &LibraryEntry{ID: "local-only-uuid", TmdbID: 20, MediaType: "tv", Title: "T",
		Status: StatusWatching, AddedAt: now, UpdatedAt: now}
	l.MergeFrom([]*LibraryEntry{e}, nil, nil, nil)

	// Remote list contains a different title — local entry must not change.
	l.AdoptRemoteIDs([]RemoteRowID{
		{ID: "remote-uuid", TmdbID: 99, MediaType: "movie"},
	}, nil)

	entries := l.AllEntries()
	require.Len(t, entries, 1)
	assert.Equal(t, "local-only-uuid", entries[0].ID, "entry not in remote must keep its local ID")
}

// TestAdoptRemoteIDs_ProgressIDAdopted verifies that a watch_progress row
// matched by (tmdb_id, media_type, season, episode) has its UUID adopted.
func TestAdoptRemoteIDs_ProgressIDAdopted(t *testing.T) {
	l := newLib(t)
	now := time.Now()

	e := &LibraryEntry{ID: "entry-uuid", TmdbID: 30, MediaType: "tv", Title: "S",
		Status: StatusWatching, AddedAt: now, UpdatedAt: now}
	p := &WatchProgress{ID: "local-prog-uuid", TmdbID: 30, MediaType: "tv",
		LibraryEntryID: "entry-uuid", Season: intPtr(1), Episode: intPtr(2), WatchedAt: now}
	l.MergeFrom([]*LibraryEntry{e}, []*WatchProgress{p}, nil, nil)

	l.AdoptRemoteIDs(nil, []RemoteRowID{
		{ID: "remote-prog-uuid", TmdbID: 30, MediaType: "tv", Season: intPtr(1), Episode: intPtr(2)},
	})

	progress := l.AllProgress()
	require.Len(t, progress, 1)
	assert.Equal(t, "remote-prog-uuid", progress[0].ID, "progress ID must be adopted from remote")
}

// TestAdoptRemoteIDs_LibraryEntryIDRemapped verifies that when a parent entry's
// ID is adopted, the LibraryEntryID on its progress rows is updated to match.
func TestAdoptRemoteIDs_LibraryEntryIDRemapped(t *testing.T) {
	l := newLib(t)
	now := time.Now()

	e := &LibraryEntry{ID: "old-entry-uuid", TmdbID: 40, MediaType: "movie", Title: "M",
		Status: StatusWatchLater, AddedAt: now, UpdatedAt: now}
	p := &WatchProgress{ID: "prog-uuid", TmdbID: 40, MediaType: "movie",
		LibraryEntryID: "old-entry-uuid", WatchedAt: now}
	l.MergeFrom([]*LibraryEntry{e}, []*WatchProgress{p}, nil, nil)

	l.AdoptRemoteIDs([]RemoteRowID{
		{ID: "new-entry-uuid", TmdbID: 40, MediaType: "movie"},
	}, nil)

	entries := l.AllEntries()
	require.Len(t, entries, 1)
	assert.Equal(t, "new-entry-uuid", entries[0].ID, "entry ID must be adopted")

	progress := l.AllProgress()
	require.Len(t, progress, 1)
	assert.Equal(t, "new-entry-uuid", progress[0].LibraryEntryID,
		"LibraryEntryID must follow the adopted entry ID")
}

// ── AllRemovals ───────────────────────────────────────────────────────────────

func TestAllRemovals_EmptyInitially(t *testing.T) {
	l := newLib(t)
	assert.Empty(t, l.AllRemovals())
}

func TestAllRemovals_ViaDeleteHandler(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	body := `{"tmdb_id":77,"media_type":"movie","title":"Tombstone Test","status":"watch_later"}`
	req := httptest.NewRequest(http.MethodPost, "/api/library", bytes.NewBufferString(body))
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	require.Equal(t, http.StatusOK, rr.Code)

	req = httptest.NewRequest(http.MethodDelete, "/api/library/77/movie", nil)
	rr = httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	require.Equal(t, http.StatusNoContent, rr.Code)

	// The tombstone is what supabase/sync.go's pushRemovals consumes via AllRemovals().
	removals := l.AllRemovals()
	require.Len(t, removals, 1, "AllRemovals must return the tombstone that Supabase sync pushes")
	assert.Equal(t, 77, removals[0].TmdbID)
	assert.Equal(t, "movie", removals[0].MediaType)
	assert.False(t, removals[0].RemovedAt.IsZero())
}

func TestAllRemovals_ViaMergeFrom(t *testing.T) {
	l := newLib(t)
	now := time.Now()
	r := &Removal{TmdbID: 88, MediaType: "tv", RemovedAt: now}
	l.MergeFrom(nil, nil, nil, []*Removal{r})
	removals := l.AllRemovals()
	require.Len(t, removals, 1)
	assert.Equal(t, 88, removals[0].TmdbID)
	assert.Equal(t, "tv", removals[0].MediaType)
}

// ── MarkExternallyWatched ─────────────────────────────────────────────────────

func TestMarkExternallyWatched_CreatesEntryAndProgress(t *testing.T) {
	l := newLib(t)
	watchedAt := time.Now().Add(-time.Hour)
	l.MarkExternallyWatched(100, "movie", nil, nil, "Inception", "/path.jpg", watchedAt)

	entries := l.AllEntries()
	require.Len(t, entries, 1)
	assert.Equal(t, 100, entries[0].TmdbID)
	assert.Equal(t, StatusWatching, entries[0].Status)
	assert.Equal(t, "Inception", entries[0].Title)

	progs := l.AllProgress()
	require.Len(t, progs, 1)
	assert.True(t, progs[0].Completed)
	assert.Equal(t, watchedAt.UTC(), progs[0].WatchedAt.UTC())
}

func TestMarkExternallyWatched_NoopIfAlreadyCompletedAtSameOrLaterTime(t *testing.T) {
	l := newLib(t)
	watchedAt := time.Now()
	l.MarkExternallyWatched(101, "movie", nil, nil, "Foo", "", watchedAt)

	// Same time — no-op.
	l.MarkExternallyWatched(101, "movie", nil, nil, "Foo", "", watchedAt)
	// Earlier time — also no-op.
	l.MarkExternallyWatched(101, "movie", nil, nil, "Foo", "", watchedAt.Add(-time.Minute))

	progs := l.AllProgress()
	require.Len(t, progs, 1)
	assert.Equal(t, watchedAt.UTC(), progs[0].WatchedAt.UTC())
}

func TestMarkExternallyWatched_UpdatesIfNewer(t *testing.T) {
	l := newLib(t)
	first := time.Now().Add(-2 * time.Hour)
	l.MarkExternallyWatched(102, "movie", nil, nil, "Bar", "", first)

	second := first.Add(time.Hour)
	l.MarkExternallyWatched(102, "movie", nil, nil, "Bar", "", second)

	progs := l.AllProgress()
	require.Len(t, progs, 1)
	assert.Equal(t, second.UTC(), progs[0].WatchedAt.UTC())
}

func TestMarkExternallyWatched_DoesNotOverwriteExistingEntryStatus(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	// Add entry manually with StatusWatchLater.
	body := `{"tmdb_id":103,"media_type":"movie","title":"Existing","status":"watch_later"}`
	req := httptest.NewRequest(http.MethodPost, "/api/library", bytes.NewBufferString(body))
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	require.Equal(t, http.StatusOK, rr.Code)

	l.MarkExternallyWatched(103, "movie", nil, nil, "Existing", "", time.Now())

	entries := l.AllEntries()
	require.Len(t, entries, 1)
	assert.Equal(t, StatusWatchLater, entries[0].Status, "existing entry status must not be overwritten by MarkExternallyWatched")
}

func TestMarkExternallyWatched_TVEpisode(t *testing.T) {
	l := newLib(t)
	s, ep := 2, 3
	l.MarkExternallyWatched(104, "tv", &s, &ep, "Show", "", time.Now())

	progs := l.AllProgress()
	require.Len(t, progs, 1)
	require.NotNil(t, progs[0].Season)
	assert.Equal(t, 2, *progs[0].Season)
	require.NotNil(t, progs[0].Episode)
	assert.Equal(t, 3, *progs[0].Episode)

	entries := l.AllEntries()
	require.Len(t, entries, 1)
	require.NotNil(t, entries[0].LastWatchedSeason)
	assert.Equal(t, 2, *entries[0].LastWatchedSeason)
	require.NotNil(t, entries[0].LastWatchedEpisode)
	assert.Equal(t, 3, *entries[0].LastWatchedEpisode)
}

// ── AddWatchLater ─────────────────────────────────────────────────────────────

func TestAddWatchLater_AddsIfNotExists(t *testing.T) {
	l := newLib(t)
	addedAt := time.Now().Add(-24 * time.Hour)
	l.AddWatchLater(110, "movie", "Watchlist Film", "/poster.jpg", addedAt)

	entries := l.AllEntries()
	require.Len(t, entries, 1)
	assert.Equal(t, StatusWatchLater, entries[0].Status)
	assert.Equal(t, "Watchlist Film", entries[0].Title)
	assert.Equal(t, addedAt.UTC(), entries[0].AddedAt.UTC())
}

func TestAddWatchLater_NoopIfEntryAlreadyExists(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	// Add entry with a different status first.
	body := `{"tmdb_id":111,"media_type":"movie","title":"Already There","status":"watching"}`
	req := httptest.NewRequest(http.MethodPost, "/api/library", bytes.NewBufferString(body))
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	require.Equal(t, http.StatusOK, rr.Code)

	l.AddWatchLater(111, "movie", "Already There", "", time.Now())

	entries := l.AllEntries()
	require.Len(t, entries, 1)
	assert.Equal(t, StatusWatching, entries[0].Status, "AddWatchLater must not overwrite existing entry")
}

// ── SetOnNearComplete / SetOnProgressSave ─────────────────────────────────────

func TestSetOnNearComplete_FiresWhenCompleted(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	fired := make(chan struct{}, 1)
	l.SetOnNearComplete(func() { fired <- struct{}{} })

	body := bytes.NewBufferString(`{"tmdb_id":120,"media_type":"movie","position_seconds":6000,"duration_seconds":6000,"completed":true}`)
	req := httptest.NewRequest(http.MethodPost, "/api/library/progress", body)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	require.Equal(t, http.StatusOK, rr.Code)

	select {
	case <-fired:
	case <-time.After(time.Second):
		t.Fatal("onNearComplete was not called on completion")
	}
}

func TestSetOnNearComplete_FiresAt90Percent(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	fired := make(chan struct{}, 1)
	l.SetOnNearComplete(func() { fired <- struct{}{} })

	body := bytes.NewBufferString(`{"tmdb_id":121,"media_type":"movie","position_seconds":5400,"duration_seconds":6000,"completed":false}`)
	req := httptest.NewRequest(http.MethodPost, "/api/library/progress", body)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	require.Equal(t, http.StatusOK, rr.Code)

	select {
	case <-fired:
	case <-time.After(time.Second):
		t.Fatal("onNearComplete was not called at ≥90%")
	}
}

func TestSetOnNearComplete_DoesNotFireForEarlyProgress(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	fired := make(chan struct{}, 1)
	l.SetOnNearComplete(func() { fired <- struct{}{} })

	body := bytes.NewBufferString(`{"tmdb_id":122,"media_type":"movie","position_seconds":100,"duration_seconds":6000,"completed":false}`)
	req := httptest.NewRequest(http.MethodPost, "/api/library/progress", body)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	require.Equal(t, http.StatusOK, rr.Code)

	select {
	case <-fired:
		t.Fatal("onNearComplete must not fire for early progress (<90%)")
	case <-time.After(50 * time.Millisecond):
		// correct — nothing fired
	}
}

func TestSetOnProgressSave_Fires(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	events := make(chan ProgressSaveEvent, 1)
	l.SetOnProgressSave(func(ev ProgressSaveEvent) { events <- ev })

	body := bytes.NewBufferString(`{"tmdb_id":130,"media_type":"movie","position_seconds":120,"duration_seconds":6000,"completed":false}`)
	req := httptest.NewRequest(http.MethodPost, "/api/library/progress", body)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	require.Equal(t, http.StatusOK, rr.Code)

	select {
	case ev := <-events:
		assert.Equal(t, 130, ev.TmdbID)
		assert.Equal(t, "movie", ev.MediaType)
		assert.Equal(t, float64(120), ev.Position)
		assert.Equal(t, float64(6000), ev.Duration)
		assert.False(t, ev.Completed)
	case <-time.After(time.Second):
		t.Fatal("onProgressSave was not called")
	}
}

func TestSetOnProgressSave_FiredOnEveryTick(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	var count int
	events := make(chan struct{}, 5)
	l.SetOnProgressSave(func(_ ProgressSaveEvent) { events <- struct{}{} })

	for i := 0; i < 3; i++ {
		body := bytes.NewBufferString(`{"tmdb_id":131,"media_type":"movie","position_seconds":10,"duration_seconds":6000,"completed":false}`)
		req := httptest.NewRequest(http.MethodPost, "/api/library/progress", body)
		rr := httptest.NewRecorder()
		mux.ServeHTTP(rr, req)
		require.Equal(t, http.StatusOK, rr.Code)
	}

	timeout := time.After(time.Second)
	for count < 3 {
		select {
		case <-events:
			count++
		case <-timeout:
			t.Fatalf("onProgressSave only fired %d/3 times", count)
		}
	}
}

// ── handleItem ────────────────────────────────────────────────────────────────

func TestHandleItem_GET_NotInLibrary(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	req := httptest.NewRequest(http.MethodGet, "/api/library/999/movie", nil)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	assert.Equal(t, http.StatusOK, rr.Code)

	var resp map[string]any
	require.NoError(t, json.NewDecoder(rr.Body).Decode(&resp))
	assert.Nil(t, resp["entry"], "entry should be null for unknown title")
	assert.NotNil(t, resp["progress"])
}

func TestHandleItem_GET_InLibrary(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	body := `{"tmdb_id":140,"media_type":"movie","title":"In Library","status":"watching"}`
	req := httptest.NewRequest(http.MethodPost, "/api/library", bytes.NewBufferString(body))
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	require.Equal(t, http.StatusOK, rr.Code)

	req = httptest.NewRequest(http.MethodGet, "/api/library/140/movie", nil)
	rr = httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	assert.Equal(t, http.StatusOK, rr.Code)

	var resp struct {
		Entry     *LibraryEntry    `json:"entry"`
		Progress  []*WatchProgress `json:"progress"`
		Dismissed bool             `json:"dismissed"`
	}
	require.NoError(t, json.NewDecoder(rr.Body).Decode(&resp))
	require.NotNil(t, resp.Entry)
	assert.Equal(t, 140, resp.Entry.TmdbID)
	assert.Equal(t, StatusWatching, resp.Entry.Status)
	assert.NotNil(t, resp.Progress)
}

func TestHandleItem_DELETE_CreatesTombstone(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	body := `{"tmdb_id":150,"media_type":"tv","title":"To Remove","status":"watch_later"}`
	req := httptest.NewRequest(http.MethodPost, "/api/library", bytes.NewBufferString(body))
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	require.Equal(t, http.StatusOK, rr.Code)

	req = httptest.NewRequest(http.MethodDelete, "/api/library/150/tv", nil)
	rr = httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	assert.Equal(t, http.StatusNoContent, rr.Code)

	// Entry must be gone from the library.
	assert.Empty(t, l.AllEntries())

	// Tombstone must be present — supabase/sync.go pushRemovals reads AllRemovals()
	// to build the rows it upserts into library_removals and then deletes from
	// library_entries on the remote side. Without the tombstone sync cannot evict
	// the remote copy.
	removals := l.AllRemovals()
	require.Len(t, removals, 1, "tombstone must exist in AllRemovals after DELETE")
	assert.Equal(t, 150, removals[0].TmdbID)
	assert.Equal(t, "tv", removals[0].MediaType)
	assert.False(t, removals[0].RemovedAt.IsZero())
}

func TestHandleItem_DELETE_PreservesWatchProgress(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	// Add entry.
	req := httptest.NewRequest(http.MethodPost, "/api/library", bytes.NewBufferString(`{"tmdb_id":151,"media_type":"movie","title":"Prog Test","status":"watching"}`))
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	require.Equal(t, http.StatusOK, rr.Code)

	// Save some progress.
	req = httptest.NewRequest(http.MethodPost, "/api/library/progress", bytes.NewBufferString(`{"tmdb_id":151,"media_type":"movie","position_seconds":300,"duration_seconds":6000,"completed":false}`))
	rr = httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	require.Equal(t, http.StatusOK, rr.Code)

	// Delete entry.
	req = httptest.NewRequest(http.MethodDelete, "/api/library/151/movie", nil)
	rr = httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	require.Equal(t, http.StatusNoContent, rr.Code)

	// Progress must survive the removal (user is removing from list, not erasing history).
	progs := l.AllProgress()
	require.Len(t, progs, 1, "watch progress must be preserved when entry is deleted")
	assert.Equal(t, float64(300), progs[0].PositionSeconds)
}

func TestHandleItem_PATCH_Status(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	body := `{"tmdb_id":160,"media_type":"movie","title":"Status Test","status":"watch_later"}`
	req := httptest.NewRequest(http.MethodPost, "/api/library", bytes.NewBufferString(body))
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	require.Equal(t, http.StatusOK, rr.Code)

	req = httptest.NewRequest(http.MethodPatch, "/api/library/160/movie/status", bytes.NewBufferString(`{"status":"finished"}`))
	rr = httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	assert.Equal(t, http.StatusOK, rr.Code)

	var e LibraryEntry
	require.NoError(t, json.NewDecoder(rr.Body).Decode(&e))
	assert.Equal(t, StatusFinished, e.Status)
}

func TestHandleItem_PATCH_Status_NotFound(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	req := httptest.NewRequest(http.MethodPatch, "/api/library/9999/movie/status", bytes.NewBufferString(`{"status":"finished"}`))
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	assert.Equal(t, http.StatusNotFound, rr.Code)
}

func TestHandleItem_PATCH_Rating(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	body := `{"tmdb_id":170,"media_type":"movie","title":"Rating Test","status":"watching"}`
	req := httptest.NewRequest(http.MethodPost, "/api/library", bytes.NewBufferString(body))
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	require.Equal(t, http.StatusOK, rr.Code)

	req = httptest.NewRequest(http.MethodPatch, "/api/library/170/movie/rating", bytes.NewBufferString(`{"rating":4.5}`))
	rr = httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	assert.Equal(t, http.StatusOK, rr.Code)

	var e LibraryEntry
	require.NoError(t, json.NewDecoder(rr.Body).Decode(&e))
	require.NotNil(t, e.Rating)
	assert.Equal(t, 4.5, *e.Rating)
}

func TestHandleItem_PATCH_Rating_ClearWithNull(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	body := `{"tmdb_id":171,"media_type":"movie","title":"Clear Rating","status":"watching"}`
	req := httptest.NewRequest(http.MethodPost, "/api/library", bytes.NewBufferString(body))
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	require.Equal(t, http.StatusOK, rr.Code)

	req = httptest.NewRequest(http.MethodPatch, "/api/library/171/movie/rating", bytes.NewBufferString(`{"rating":3.0}`))
	rr = httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	require.Equal(t, http.StatusOK, rr.Code)

	req = httptest.NewRequest(http.MethodPatch, "/api/library/171/movie/rating", bytes.NewBufferString(`{"rating":null}`))
	rr = httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	assert.Equal(t, http.StatusOK, rr.Code)

	var e LibraryEntry
	require.NoError(t, json.NewDecoder(rr.Body).Decode(&e))
	assert.Nil(t, e.Rating)
}

func TestHandleItem_PATCH_Rating_InvalidRange(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	body := `{"tmdb_id":172,"media_type":"movie","title":"Bad Rating","status":"watching"}`
	req := httptest.NewRequest(http.MethodPost, "/api/library", bytes.NewBufferString(body))
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	require.Equal(t, http.StatusOK, rr.Code)

	req = httptest.NewRequest(http.MethodPatch, "/api/library/172/movie/rating", bytes.NewBufferString(`{"rating":6.0}`))
	rr = httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	assert.Equal(t, http.StatusBadRequest, rr.Code)
}

func TestHandleItem_InvalidPath(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	// Trailing-slash route reaches handleItem with an empty trimmed path.
	req := httptest.NewRequest(http.MethodGet, "/api/library/", nil)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	assert.Equal(t, http.StatusBadRequest, rr.Code)
}

func TestHandleItem_InvalidTmdbID(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	req := httptest.NewRequest(http.MethodGet, "/api/library/notanumber/movie", nil)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	assert.Equal(t, http.StatusBadRequest, rr.Code)
}

// ── handleDismiss ─────────────────────────────────────────────────────────────

func TestHandleDismiss_POST_AddsDismissal(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	body := `{"tmdb_id":180,"media_type":"movie"}`
	req := httptest.NewRequest(http.MethodPost, "/api/library/dismiss", bytes.NewBufferString(body))
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	assert.Equal(t, http.StatusNoContent, rr.Code)

	dismissals := l.AllDismissals()
	require.Len(t, dismissals, 1)
	assert.Equal(t, 180, dismissals[0].TmdbID)
	assert.Equal(t, "movie", dismissals[0].MediaType)
	assert.False(t, dismissals[0].DismissedAt.IsZero())
}

func TestHandleDismiss_DELETE_RemovesDismissal(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	req := httptest.NewRequest(http.MethodPost, "/api/library/dismiss", bytes.NewBufferString(`{"tmdb_id":181,"media_type":"tv"}`))
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	require.Equal(t, http.StatusNoContent, rr.Code)
	require.Len(t, l.AllDismissals(), 1)

	req = httptest.NewRequest(http.MethodDelete, "/api/library/dismiss", bytes.NewBufferString(`{"tmdb_id":181,"media_type":"tv"}`))
	rr = httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	assert.Equal(t, http.StatusNoContent, rr.Code)
	assert.Empty(t, l.AllDismissals())
}

func TestHandleDismiss_POST_BumpsTasteGen(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	g0 := l.TasteGeneration()
	req := httptest.NewRequest(http.MethodPost, "/api/library/dismiss", bytes.NewBufferString(`{"tmdb_id":182,"media_type":"movie"}`))
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	require.Equal(t, http.StatusNoContent, rr.Code)
	assert.Greater(t, l.TasteGeneration(), g0, "dismissal must bump tasteGen")
}

func TestHandleDismiss_POST_MissingFields(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	req := httptest.NewRequest(http.MethodPost, "/api/library/dismiss", bytes.NewBufferString(`{"tmdb_id":0,"media_type":""}`))
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	assert.Equal(t, http.StatusBadRequest, rr.Code)
}

func TestHandleDismiss_InvalidMethod(t *testing.T) {
	l := newLib(t)
	mux := http.NewServeMux()
	l.SetupHandlers(mux)

	req := httptest.NewRequest(http.MethodGet, "/api/library/dismiss", nil)
	rr := httptest.NewRecorder()
	mux.ServeHTTP(rr, req)
	assert.Equal(t, http.StatusMethodNotAllowed, rr.Code)
}
