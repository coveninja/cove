//go:build supabase

// Copyright (c) 2025 coveninja. All Rights Reserved.
// This file is proprietary and is not covered by the AGPL-3.0 license
// that applies to the rest of the Cove repository.

package supabase

import (
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/coveninja/cove/internal/activity"
	"github.com/coveninja/cove/internal/addons"
	"github.com/coveninja/cove/internal/library"
	"github.com/coveninja/cove/internal/nuvio"
	"github.com/coveninja/cove/internal/settings"
)

// remoteProfile is the `profiles` table row on Supabase.
type remoteProfile struct {
	ID        string    `json:"id"`      // matches local Profile.ID
	UserID    string    `json:"user_id"` // Supabase auth.users.id
	Name      string    `json:"name"`
	IsPrimary bool      `json:"is_primary"`
	UpdatedAt time.Time `json:"updated_at"`
}

// EnsureProfile upserts the profile row in Supabase, preserving the actual
// local name-mutation timestamp for cross-device last-write-wins resolution.
func (c *Config) EnsureProfile(userJWT, localProfileID, supabaseUID, name string, isPrimary bool, updatedAt time.Time) error {
	row := map[string]any{
		"id":         localProfileID,
		"user_id":    supabaseUID,
		"name":       name,
		"is_primary": isPrimary,
		"updated_at": updatedAt.UTC(),
	}
	return c.Upsert(userJWT, "profiles", []any{row})
}

// RemoteProfilesForUser returns all profile rows owned by the given Supabase user.
func (c *Config) RemoteProfilesForUser(userJWT, supabaseUID string) ([]remoteProfile, error) {
	q := "user_id=eq." + url.QueryEscape(supabaseUID)
	rows, err := c.Select(userJWT, "profiles", q)
	if err != nil {
		return nil, err
	}
	out := make([]remoteProfile, 0, len(rows))
	for _, r := range rows {
		var p remoteProfile
		if err := json.Unmarshal(r, &p); err != nil {
			return nil, fmt.Errorf("decode remote profile: %w", err)
		}
		out = append(out, p)
	}
	return out, nil
}

// isRLSError reports whether an error from a PostgREST call is an RLS
// violation (PostgreSQL error code 42501 — "insufficient_privilege").
func isRLSError(err error) bool {
	return err != nil && strings.Contains(err.Error(), "42501")
}

func isUniqueViolation(err error) bool {
	return err != nil && strings.Contains(err.Error(), "23505")
}

func (c *Config) fetchRemoteRowIDs(userJWT, table, profileID string, withSeasonEpisode bool) ([]library.RemoteRowID, error) {
	sel := "id,tmdb_id,media_type"
	if withSeasonEpisode {
		sel += ",season,episode"
	}
	q := "profile_id=eq." + url.QueryEscape(profileID) + "&select=" + sel
	rows, err := c.Select(userJWT, table, q)
	if err != nil {
		return nil, err
	}
	out := make([]library.RemoteRowID, 0, len(rows))
	for _, r := range rows {
		var row struct {
			ID        string `json:"id"`
			TmdbID    int    `json:"tmdb_id"`
			MediaType string `json:"media_type"`
			Season    *int   `json:"season"`
			Episode   *int   `json:"episode"`
		}
		if err := json.Unmarshal(r, &row); err != nil {
			return nil, fmt.Errorf("decode remote %s row ID: %w", table, err)
		}
		if row.ID == "" || row.TmdbID <= 0 || row.MediaType == "" {
			return nil, fmt.Errorf("invalid remote %s row ID: id, tmdb_id, and media_type are required", table)
		}
		out = append(out, library.RemoteRowID{
			ID: row.ID, TmdbID: row.TmdbID, MediaType: row.MediaType,
			Season: row.Season, Episode: row.Episode,
		})
	}
	return out, nil
}

// fetchOwnedIDs returns the set of row IDs owned by profileID in table.
// Uses &select=id to minimise the payload.
func (c *Config) fetchOwnedIDs(userJWT, table, profileID string) (map[string]bool, error) {
	q := "profile_id=eq." + url.QueryEscape(profileID) + "&select=id"
	rows, err := c.Select(userJWT, table, q)
	if err != nil {
		return nil, err
	}
	owned := make(map[string]bool, len(rows))
	for _, r := range rows {
		var row struct {
			ID string `json:"id"`
		}
		if err := json.Unmarshal(r, &row); err != nil {
			return nil, fmt.Errorf("decode owned %s row ID: %w", table, err)
		}
		if row.ID == "" {
			return nil, fmt.Errorf("invalid owned %s row ID: id is required", table)
		}
		owned[row.ID] = true
	}
	return owned, nil
}

// PushLibrary uploads all local library entries, progress records, and dismissals
// for a profile to Supabase. Existing remote rows are merged (last-write-wins).
//
// On an RLS 42501 rejection from entries or progress (cross-user row-ID
// contamination from the pre-RLS era): fetches the profile's owned remote IDs,
// calls lib.RegenerateIDsNotIn to assign fresh UUIDs to the conflicting rows,
// then retries the upsert once. Errors are collected across all three tables so
// a progress failure does not prevent dismissals from being pushed.
func (c *Config) PushLibrary(userJWT, profileID string, lib *library.Library) error {
	var errs []error

	if err := c.pushEntries(userJWT, profileID, lib); err != nil {
		errs = append(errs, fmt.Errorf("push library entries: %w", err))
	}
	if err := c.pushProgress(userJWT, profileID, lib); err != nil {
		errs = append(errs, fmt.Errorf("push watch progress: %w", err))
	}

	dismissals := lib.AllDismissals()
	if len(dismissals) > 0 {
		rows := make([]map[string]any, 0, len(dismissals))
		for _, d := range dismissals {
			rows = append(rows, map[string]any{
				"profile_id":   profileID,
				"tmdb_id":      d.TmdbID,
				"media_type":   d.MediaType,
				"dismissed_at": d.DismissedAt,
			})
		}
		if err := c.Upsert(userJWT, "dismissals", rows); err != nil {
			errs = append(errs, fmt.Errorf("push dismissals: %w", err))
		}
	}
	if err := c.pushRemovals(userJWT, profileID, lib); err != nil {
		errs = append(errs, fmt.Errorf("push removals: %w", err))
	}
	return errors.Join(errs...)
}

func (c *Config) pushRemovals(userJWT, profileID string, lib *library.Library) error {
	removals := lib.AllRemovals()
	if len(removals) == 0 {
		return nil
	}
	rows := make([]map[string]any, 0, len(removals))
	for _, r := range removals {
		rows = append(rows, map[string]any{
			"profile_id": profileID, "tmdb_id": r.TmdbID,
			"media_type": r.MediaType, "removed_at": r.RemovedAt,
		})
	}
	if err := c.Upsert(userJWT, "library_removals", rows); err != nil {
		return err
	}
	var errs []error
	for _, r := range removals {
		q := "profile_id=eq." + url.QueryEscape(profileID) +
			"&tmdb_id=eq." + strconv.Itoa(r.TmdbID) +
			"&media_type=eq." + url.QueryEscape(r.MediaType)
		if err := c.Delete(userJWT, "library_entries", q); err != nil {
			errs = append(errs, err)
		}
	}
	return errors.Join(errs...)
}

// pushEntries upserts library_entries; on RLS 42501 regenerates IDs and on
// unique-violation 23505 adopts the existing remote IDs before retrying once.
func (c *Config) pushEntries(userJWT, profileID string, lib *library.Library) error {
	entries := lib.AllEntries()
	if len(entries) == 0 {
		return nil
	}
	rows := buildEntryRows(entries, profileID)
	err := c.Upsert(userJWT, "library_entries", rows)
	if err == nil {
		return nil
	}
	if isRLSError(err) {
		log.Printf("supabase: RLS error pushing library_entries for profile %s — regenerating IDs", profileID)
		ownedEntry, errFetch := c.fetchOwnedIDs(userJWT, "library_entries", profileID)
		if errFetch != nil {
			return fmt.Errorf("fetch owned entry IDs: %w; original: %w", errFetch, err)
		}
		ownedProgress, errFetch := c.fetchOwnedIDs(userJWT, "watch_progress", profileID)
		if errFetch != nil {
			return fmt.Errorf("fetch owned progress IDs: %w; original: %w", errFetch, err)
		}
		lib.RegenerateIDsNotIn(ownedEntry, ownedProgress)
		rows = buildEntryRows(lib.AllEntries(), profileID)
		if retryErr := c.Upsert(userJWT, "library_entries", rows); retryErr != nil {
			return fmt.Errorf("retry after ID regen: %w", retryErr)
		}
		return nil
	}
	if isUniqueViolation(err) {
		log.Printf("supabase: unique-violation pushing library_entries for profile %s — adopting remote row IDs", profileID)
		remoteEntries, errFetch := c.fetchRemoteRowIDs(userJWT, "library_entries", profileID, false)
		if errFetch != nil {
			return fmt.Errorf("fetch remote entry IDs: %w; original: %w", errFetch, err)
		}
		remoteProgress, errFetch := c.fetchRemoteRowIDs(userJWT, "watch_progress", profileID, true)
		if errFetch != nil {
			return fmt.Errorf("fetch remote progress IDs: %w; original: %w", errFetch, err)
		}
		lib.AdoptRemoteIDs(remoteEntries, remoteProgress)
		if retryErr := c.Upsert(userJWT, "library_entries", buildEntryRows(lib.AllEntries(), profileID)); retryErr != nil {
			return fmt.Errorf("retry after ID adoption: %w", retryErr)
		}
		return nil
	}
	return err
}

// pushProgress upserts watch_progress with the same ID-repair behavior as entries.
func (c *Config) pushProgress(userJWT, profileID string, lib *library.Library) error {
	progress := lib.AllProgress()
	if len(progress) == 0 {
		return nil
	}
	rows := buildProgressRows(progress, profileID)
	err := c.Upsert(userJWT, "watch_progress", rows)
	if err == nil {
		return nil
	}
	if isRLSError(err) {
		log.Printf("supabase: RLS error pushing watch_progress for profile %s — regenerating IDs", profileID)
		ownedEntry, errFetch := c.fetchOwnedIDs(userJWT, "library_entries", profileID)
		if errFetch != nil {
			return fmt.Errorf("fetch owned entry IDs: %w; original: %w", errFetch, err)
		}
		ownedProgress, errFetch := c.fetchOwnedIDs(userJWT, "watch_progress", profileID)
		if errFetch != nil {
			return fmt.Errorf("fetch owned progress IDs: %w; original: %w", errFetch, err)
		}
		lib.RegenerateIDsNotIn(ownedEntry, ownedProgress)
		if retryErr := c.Upsert(userJWT, "watch_progress", buildProgressRows(lib.AllProgress(), profileID)); retryErr != nil {
			return fmt.Errorf("retry after ID regen: %w", retryErr)
		}
		return nil
	}
	if isUniqueViolation(err) {
		log.Printf("supabase: unique-violation pushing watch_progress for profile %s — adopting remote row IDs", profileID)
		remoteEntries, errFetch := c.fetchRemoteRowIDs(userJWT, "library_entries", profileID, false)
		if errFetch != nil {
			return fmt.Errorf("fetch remote entry IDs: %w; original: %w", errFetch, err)
		}
		remoteProgress, errFetch := c.fetchRemoteRowIDs(userJWT, "watch_progress", profileID, true)
		if errFetch != nil {
			return fmt.Errorf("fetch remote progress IDs: %w; original: %w", errFetch, err)
		}
		lib.AdoptRemoteIDs(remoteEntries, remoteProgress)
		if retryErr := c.Upsert(userJWT, "watch_progress", buildProgressRows(lib.AllProgress(), profileID)); retryErr != nil {
			return fmt.Errorf("retry after ID adoption: %w", retryErr)
		}
		return nil
	}
	return err
}

func buildEntryRows(entries []*library.LibraryEntry, profileID string) []map[string]any {
	rows := make([]map[string]any, 0, len(entries))
	for _, e := range entries {
		pid := profileID
		e.ProfileID = &pid
		rows = append(rows, entryToMap(e))
	}
	return rows
}

func buildProgressRows(progress []*library.WatchProgress, profileID string) []map[string]any {
	rows := make([]map[string]any, 0, len(progress))
	for _, p := range progress {
		pid := profileID
		p.ProfileID = &pid
		rows = append(rows, progressToMap(p))
	}
	return rows
}

// PushSettings uploads roaming settings for the profile. Network-exposure
// settings are device-local and deliberately redacted before serialization:
// syncing them could open a listener, disclose its bearer token, or enable LAN
// stream proxying on another device without that device's consent.
func (c *Config) PushSettings(userJWT, profileID string, st *settings.Store) error {
	snapshot := st.Get()
	snapshot.RemoteAccessEnabled = false
	snapshot.RemoteAccessToken = ""
	snapshot.AllowLanStreamSources = false
	data, err := json.Marshal(snapshot)
	if err != nil {
		return err
	}
	return c.Upsert(userJWT, "profile_settings", []any{map[string]any{
		"profile_id": profileID,
		"data":       json.RawMessage(data),
		"updated_at": time.Now().UTC(),
	}})
}

// AccountOnboardingDone reports whether any profile settings row owned by the
// signed-in account has completed onboarding. Completion is account-wide for
// first-run purposes: profile reconciliation can adopt a different remote
// profile on a fresh device, but that must not make an established user repeat
// the application-level onboarding flow.
func (c *Config) AccountOnboardingDone(userJWT string) (bool, error) {
	rows, err := c.Select(userJWT, "profile_settings", "select=data&order=updated_at.desc")
	if err != nil {
		return false, fmt.Errorf("pull account onboarding state: %w", err)
	}
	for _, raw := range rows {
		var row struct {
			Data struct {
				OnboardingDone bool `json:"onboardingDone"`
			} `json:"data"`
		}
		if err := json.Unmarshal(raw, &row); err != nil {
			return false, fmt.Errorf("decode account onboarding state: %w", err)
		}
		if row.Data.OnboardingDone {
			return true, nil
		}
	}
	return false, nil
}

// PushAddons uploads current addon configuration for the profile.
// The row's updated_at reflects mgr.UpdatedAt() (the last local mutation time)
// so the remote timestamp stays stable when content hasn't changed — avoiding
// spurious LWW wins that would overwrite a newer remote with an older local
// snapshot just because push ran after the remote was written from another device.
func (c *Config) PushAddons(userJWT, profileID string, mgr *addons.Manager) error {
	entries := mgr.GetEntries()
	data, err := json.Marshal(entries)
	if err != nil {
		return err
	}
	return c.Upsert(userJWT, "profile_addons", []any{map[string]any{
		"profile_id": profileID,
		"data":       json.RawMessage(data),
		"updated_at": mgr.UpdatedAt(),
	}})
}

// PushNuvio uploads the current nuvio repo/scraper configuration for the profile.
// The row's updated_at reflects the store's last local mutation time (from
// SnapshotJSON) so cross-device LWW converges correctly.
func (c *Config) PushNuvio(userJWT, profileID string, mgr *nuvio.Manager) error {
	data, updatedAt := mgr.SnapshotJSON()
	return c.Upsert(userJWT, "profile_nuvio", []any{map[string]any{
		"profile_id": profileID,
		"data":       json.RawMessage(data),
		"updated_at": updatedAt,
	}})
}

// PushActivity uploads the current activity (insights) store for the profile.
// Activity uses per-bucket max merge semantics (no timestamp gate), so push
// time is used for updated_at — the remote timestamp is informational only.
func (c *Config) PushActivity(userJWT, profileID string, act *activity.Store) error {
	data, err := act.SnapshotJSON()
	if err != nil {
		return err
	}
	return c.Upsert(userJWT, "profile_activity", []any{map[string]any{
		"profile_id": profileID,
		"data":       json.RawMessage(data),
		"updated_at": time.Now().UTC(),
	}})
}

// PulledData is returned by PullAll containing all remote data for a profile.
type PulledData struct {
	Entries    []*library.LibraryEntry
	Progress   []*library.WatchProgress
	Dismissals []*library.Dismissal
	Removals   []*library.Removal
	Settings   *settings.Settings

	// Addons — AddonsPresent distinguishes "no remote row" (account that never
	// pushed addons) from "remote row with empty list" so callers can avoid
	// wiping local addons when the remote has no data.
	Addons          []addons.AddonEntry
	AddonsUpdatedAt time.Time
	AddonsPresent   bool

	// Nuvio — NuvioPresent distinguishes "no remote row" from empty.
	NuvioData      json.RawMessage
	NuvioUpdatedAt time.Time
	NuvioPresent   bool

	// Activity — ActivityPresent distinguishes "no remote row" from empty.
	ActivityData    json.RawMessage
	ActivityPresent bool
}

// PullAll downloads all Supabase data for the given profile.
func (c *Config) PullAll(userJWT, profileID string) (*PulledData, error) {
	q := "profile_id=eq." + url.QueryEscape(profileID)
	out := &PulledData{}

	// Library entries
	rows, err := c.Select(userJWT, "library_entries", q)
	if err != nil {
		return nil, fmt.Errorf("pull library_entries: %w", err)
	}
	for _, r := range rows {
		var e library.LibraryEntry
		if err := json.Unmarshal(r, &e); err != nil {
			return nil, fmt.Errorf("decode library_entry: %w", err)
		}
		if e.ID == "" || e.TmdbID <= 0 || e.MediaType == "" {
			return nil, fmt.Errorf("invalid library_entry: id, tmdb_id, and media_type are required")
		}
		out.Entries = append(out.Entries, &e)
	}

	// Watch progress
	rows, err = c.Select(userJWT, "watch_progress", q)
	if err != nil {
		return nil, fmt.Errorf("pull watch_progress: %w", err)
	}
	for _, r := range rows {
		var p library.WatchProgress
		if err := json.Unmarshal(r, &p); err != nil {
			return nil, fmt.Errorf("decode watch_progress: %w", err)
		}
		if p.ID == "" || p.TmdbID <= 0 || p.MediaType == "" {
			return nil, fmt.Errorf("invalid watch_progress: id, tmdb_id, and media_type are required")
		}
		out.Progress = append(out.Progress, &p)
	}

	// Dismissals
	rows, err = c.Select(userJWT, "dismissals", q)
	if err != nil {
		return nil, fmt.Errorf("pull dismissals: %w", err)
	}
	for _, r := range rows {
		var d library.Dismissal
		if err := json.Unmarshal(r, &d); err != nil {
			return nil, fmt.Errorf("decode dismissal: %w", err)
		}
		if d.TmdbID <= 0 || d.MediaType == "" {
			return nil, fmt.Errorf("invalid dismissal: tmdb_id and media_type are required")
		}
		out.Dismissals = append(out.Dismissals, &d)
	}

	rows, err = c.Select(userJWT, "library_removals", q)
	if err != nil {
		return nil, fmt.Errorf("pull library_removals: %w", err)
	}
	for _, r := range rows {
		var removal library.Removal
		if err := json.Unmarshal(r, &removal); err != nil {
			return nil, fmt.Errorf("decode library_removal: %w", err)
		}
		if removal.TmdbID <= 0 || removal.MediaType == "" {
			return nil, fmt.Errorf("invalid library_removal: tmdb_id and media_type are required")
		}
		out.Removals = append(out.Removals, &removal)
	}

	// Settings
	settingsRows, err := c.Select(
		userJWT,
		"profile_settings",
		"profile_id=eq."+url.QueryEscape(profileID)+"&order=updated_at.desc&limit=1",
	)
	if err != nil {
		return nil, fmt.Errorf("pull profile_settings: %w", err)
	}
	if len(settingsRows) > 0 {
		var row struct {
			Data json.RawMessage `json:"data"`
		}
		if err := json.Unmarshal(settingsRows[0], &row); err != nil {
			return nil, fmt.Errorf("decode profile_settings row: %w", err)
		}
		var s settings.Settings
		if err := json.Unmarshal(row.Data, &s); err != nil {
			return nil, fmt.Errorf("decode profile_settings data: %w", err)
		}
		out.Settings = &s
	}

	// Addons
	addonRows, err := c.Select(userJWT, "profile_addons", "profile_id=eq."+url.QueryEscape(profileID))
	if err != nil {
		return nil, fmt.Errorf("pull profile_addons: %w", err)
	}
	if len(addonRows) > 0 {
		var row struct {
			Data      json.RawMessage `json:"data"`
			UpdatedAt time.Time       `json:"updated_at"`
		}
		if err := json.Unmarshal(addonRows[0], &row); err != nil {
			return nil, fmt.Errorf("decode profile_addons row: %w", err)
		}
		var entries []addons.AddonEntry
		if err := json.Unmarshal(row.Data, &entries); err != nil {
			return nil, fmt.Errorf("decode profile_addons data: %w", err)
		}
		out.Addons, out.AddonsUpdatedAt, out.AddonsPresent = entries, row.UpdatedAt, true
	}

	// Nuvio
	nuvioRows, err := c.Select(userJWT, "profile_nuvio", "profile_id=eq."+url.QueryEscape(profileID))
	if err != nil {
		return nil, fmt.Errorf("pull profile_nuvio: %w", err)
	}
	if len(nuvioRows) > 0 {
		var row struct {
			Data      json.RawMessage `json:"data"`
			UpdatedAt time.Time       `json:"updated_at"`
		}
		if err := json.Unmarshal(nuvioRows[0], &row); err != nil {
			return nil, fmt.Errorf("decode profile_nuvio row: %w", err)
		}
		var payload *struct {
			Repos     []nuvio.Repo `json:"repos"`
			UpdatedAt time.Time    `json:"updatedAt"`
		}
		if err := json.Unmarshal(row.Data, &payload); err != nil {
			return nil, fmt.Errorf("decode profile_nuvio data: %w", err)
		}
		if payload == nil {
			return nil, fmt.Errorf("decode profile_nuvio data: object is required")
		}
		out.NuvioData, out.NuvioUpdatedAt, out.NuvioPresent = row.Data, row.UpdatedAt, true
	}

	// Activity
	actRows, err := c.Select(userJWT, "profile_activity", "profile_id=eq."+url.QueryEscape(profileID))
	if err != nil {
		return nil, fmt.Errorf("pull profile_activity: %w", err)
	}
	if len(actRows) > 0 {
		var row struct {
			Data json.RawMessage `json:"data"`
		}
		if err := json.Unmarshal(actRows[0], &row); err != nil {
			return nil, fmt.Errorf("decode profile_activity row: %w", err)
		}
		var payload *struct {
			Days       map[string]*activity.DayEntry `json:"days"`
			LastPos    map[string]float64            `json:"last_pos"`
			Backfilled bool                          `json:"backfilled"`
		}
		if err := json.Unmarshal(row.Data, &payload); err != nil {
			return nil, fmt.Errorf("decode profile_activity data: %w", err)
		}
		if payload == nil {
			return nil, fmt.Errorf("decode profile_activity data: object is required")
		}
		out.ActivityData, out.ActivityPresent = row.Data, true
	}

	return out, nil
}

// ── mapping helpers ───────────────────────────────────────────────────────────

func entryToMap(e *library.LibraryEntry) map[string]any {
	return map[string]any{
		"id":                   e.ID,
		"profile_id":           e.ProfileID,
		"tmdb_id":              e.TmdbID,
		"media_type":           e.MediaType,
		"title":                e.Title,
		"poster_path":          e.PosterPath,
		"status":               e.Status,
		"rating":               e.Rating,
		"vote_average":         e.VoteAverage,
		"last_air_date":        e.LastAirDate,
		"last_watched_at":      e.LastWatchedAt,
		"last_watched_season":  e.LastWatchedSeason,
		"last_watched_episode": e.LastWatchedEpisode,
		"last_aired_season":    e.LastAiredSeason,
		"last_aired_episode":   e.LastAiredEpisode,
		"added_at":             e.AddedAt,
		"updated_at":           e.UpdatedAt,
	}
}

func progressToMap(p *library.WatchProgress) map[string]any {
	return map[string]any{
		"id":               p.ID,
		"profile_id":       p.ProfileID,
		"tmdb_id":          p.TmdbID,
		"media_type":       p.MediaType,
		"season":           p.Season,
		"episode":          p.Episode,
		"position_seconds": p.PositionSeconds,
		"duration_seconds": p.DurationSeconds,
		"completed":        p.Completed,
		"watched_at":       p.WatchedAt,
	}
}

// DeleteProfileData removes all Supabase rows for the given profile. Child
// table rows must go first: their RLS policies prove ownership through the
// profiles row (profile_id IN (SELECT id FROM profiles WHERE user_id =
// auth.uid())), so once the parent row is gone a child DELETE silently
// matches nothing and the rows are orphaned forever. If ANY child-table
// delete fails, the function returns immediately without deleting the parent
// profiles row — a partial cleanup is recoverable on retry; an orphaned
// parent (with all its inaccessible child rows) is not.
func (c *Config) DeleteProfileData(userJWT, profileID string) error {
	for _, table := range []string{
		"library_entries", "watch_progress", "dismissals", "library_removals",
		"profile_settings", "profile_addons", "profile_nuvio", "profile_activity",
	} {
		if err := c.Delete(userJWT, table, "profile_id=eq."+url.QueryEscape(profileID)); err != nil {
			return fmt.Errorf("delete %s (aborting to avoid orphaned profile row): %w", table, err)
		}
	}
	if err := c.Delete(userJWT, "profiles", "id=eq."+url.QueryEscape(profileID)); err != nil {
		return fmt.Errorf("delete profile row: %w", err)
	}
	return nil
}
