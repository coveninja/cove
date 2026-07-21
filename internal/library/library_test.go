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
