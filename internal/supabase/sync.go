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

// EnsureProfile upserts the profile row in Supabase, creating it if absent.
func (c *Config) EnsureProfile(userJWT, localProfileID, supabaseUID, name string, isPrimary bool) error {
	row := map[string]any{
		"id":         localProfileID,
		"user_id":    supabaseUID,
		"name":       name,
		"is_primary": isPrimary,
		"updated_at": time.Now().UTC(),
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
			continue
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

// isUniqueViolation reports whether an error from a PostgREST call is a unique
// constraint violation (PostgreSQL error code 23505 — "unique_violation").
func isUniqueViolation(err error) bool {
	return err != nil && strings.Contains(err.Error(), "23505")
}

// fetchRemoteRowIDs returns the remote row IDs for all rows belonging to
// profileID in table, decoded into RemoteRowID values. When withSeasonEpisode
// is true, season and episode columns are included in the select (needed for
// watch_progress rows).
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
			continue
		}
		out = append(out, library.RemoteRowID{
			ID:        row.ID,
			TmdbID:    row.TmdbID,
			MediaType: row.MediaType,
			Season:    row.Season,
			Episode:   row.Episode,
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
		if err := json.Unmarshal(r, &row); err == nil && row.ID != "" {
			owned[row.ID] = true
		}
	}
	return owned, nil
}

// PushLibrary uploads all local library entries, progress records, and dismissals
// for a profile to Supabase. Existing remote rows are merged (last-write-wins).
//
// On an RLS 42501 rejection from entries or progress (cross-user row-ID
// contamination from the pre-RLS era): fetches the profile's owned remote IDs,
// calls lib.RegenerateIDsNotIn to assign fresh UUIDs to the conflicting rows,
// then retries the upsert once.
//
// On a unique-violation 23505 (same media title on two devices under different
// UUIDs): fetches remote natural-key → UUID mappings, calls lib.AdoptRemoteIDs
// to align local IDs with the remote ones, then retries the upsert once.
//
// Errors are collected across all three tables so a progress failure does not
// prevent dismissals from being pushed.
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
	return errors.Join(errs...)
}

// pushEntries upserts library_entries; on RLS 42501 regenerates IDs and retries
// once; on unique-violation 23505 adopts remote IDs and retries once.
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
		// RLS rejection: repair cross-user ID contamination and retry once.
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
	} else if isUniqueViolation(err) {
		// Unique-violation: same title exists locally and remotely under different
		// UUIDs — adopt the remote UUIDs so the PK-resolved upsert updates in place.
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
		rows = buildEntryRows(lib.AllEntries(), profileID)
		if retryErr := c.Upsert(userJWT, "library_entries", rows); retryErr != nil {
			return fmt.Errorf("retry after ID adoption: %w", retryErr)
		}
		return nil
	}
	return err
}

// pushProgress upserts watch_progress; on RLS 42501 regenerates IDs and retries
// once; on unique-violation 23505 adopts remote IDs and retries once.
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
		rows = buildProgressRows(lib.AllProgress(), profileID)
		if retryErr := c.Upsert(userJWT, "watch_progress", rows); retryErr != nil {
			return fmt.Errorf("retry after ID regen: %w", retryErr)
		}
		return nil
	} else if isUniqueViolation(err) {
		// Unique-violation: same progress row exists remotely under a different
		// UUID — adopt the remote UUIDs so the PK-resolved upsert updates in place.
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
		rows = buildProgressRows(lib.AllProgress(), profileID)
		if retryErr := c.Upsert(userJWT, "watch_progress", rows); retryErr != nil {
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

// PushSettings uploads current settings for the profile.
func (c *Config) PushSettings(userJWT, profileID string, st *settings.Store) error {
	data, err := json.Marshal(st.Get())
	if err != nil {
		return err
	}
	return c.Upsert(userJWT, "profile_settings", []any{map[string]any{
		"profile_id": profileID,
		"data":       json.RawMessage(data),
		"updated_at": time.Now().UTC(),
	}})
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
			log.Println("supabase pull: decode library_entry:", err)
			continue
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
			log.Println("supabase pull: decode watch_progress:", err)
			continue
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
			log.Println("supabase pull: decode dismissal:", err)
			continue
		}
		out.Dismissals = append(out.Dismissals, &d)
	}

	// Settings
	settingsRows, err := c.Select(userJWT, "profile_settings", "profile_id=eq."+url.QueryEscape(profileID))
	if err == nil && len(settingsRows) > 0 {
		var row struct {
			Data json.RawMessage `json:"data"`
		}
		if err := json.Unmarshal(settingsRows[0], &row); err == nil {
			var s settings.Settings
			if err := json.Unmarshal(row.Data, &s); err == nil {
				out.Settings = &s
			}
		}
	}

	// Addons
	addonRows, err := c.Select(userJWT, "profile_addons", "profile_id=eq."+url.QueryEscape(profileID))
	if err == nil && len(addonRows) > 0 {
		var row struct {
			Data      json.RawMessage `json:"data"`
			UpdatedAt time.Time       `json:"updated_at"`
		}
		if err := json.Unmarshal(addonRows[0], &row); err == nil {
			var entries []addons.AddonEntry
			if err := json.Unmarshal(row.Data, &entries); err == nil {
				out.Addons = entries
				out.AddonsUpdatedAt = row.UpdatedAt
				out.AddonsPresent = true
			}
		}
	}

	// Nuvio
	nuvioRows, err := c.Select(userJWT, "profile_nuvio", "profile_id=eq."+url.QueryEscape(profileID))
	if err == nil && len(nuvioRows) > 0 {
		var row struct {
			Data      json.RawMessage `json:"data"`
			UpdatedAt time.Time       `json:"updated_at"`
		}
		if err := json.Unmarshal(nuvioRows[0], &row); err == nil {
			out.NuvioData = row.Data
			out.NuvioUpdatedAt = row.UpdatedAt
			out.NuvioPresent = true
		}
	}

	// Activity
	actRows, err := c.Select(userJWT, "profile_activity", "profile_id=eq."+url.QueryEscape(profileID))
	if err == nil && len(actRows) > 0 {
		var row struct {
			Data json.RawMessage `json:"data"`
		}
		if err := json.Unmarshal(actRows[0], &row); err == nil {
			out.ActivityData = row.Data
			out.ActivityPresent = true
		}
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
		"library_entries", "watch_progress", "dismissals",
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
