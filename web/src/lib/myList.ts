// Sorting and filtering for the My List page, shared by the desktop, mobile,
// and TV shells. Each shell keeps its own markup and its own filter/sort UI;
// only the ordering rules live here so a change to "recently watched" (say)
// doesn't have to be made three times.
//
// The accessors that need loaded TMDB metadata (tmdbRating, releaseDate,
// genresFor) take the shell's mediaByKey map as an explicit parameter rather
// than closing over component state — that is what makes them testable and
// keeps this module free of runes.

import type { LibraryEntry } from "$lib/types/library";
import type { Media } from "$lib/types/tmdb";
import * as m from "$lib/paraglide/messages.js";

export type SortKey =
  | "default"
  | "watched_desc"
  | "added_desc"
  | "added_asc"
  | "release_desc"
  | "tmdb_desc"
  | "personal_desc"
  | "title_asc";

/** Media the shell has fetched, keyed by {@link toMediaKey}. */
export type MediaByKey = Record<string, Media | undefined>;

/** TMDB genre id → name, per media type, as the shells cache it. */
export type GenreNames = {
  movie?: Record<number, string>;
  tv?: Record<number, string>;
};

// A function rather than a const: the labels come from paraglide message
// getters, and building the array on call keeps evaluation at component-init
// time (where it was when each shell declared this inline) instead of at
// module-load time, so a locale change is picked up on re-init.
export function sortOptions(): { value: SortKey; label: string }[] {
  return [
    { value: "default", label: m.my_list_sort_recommended() },
    { value: "watched_desc", label: m.my_list_sort_recently_watched() },
    { value: "added_desc", label: m.my_list_sort_recently_added() },
    { value: "added_asc", label: m.my_list_sort_oldest_added() },
    { value: "release_desc", label: m.my_list_sort_release_date() },
    { value: "tmdb_desc", label: m.my_list_sort_tmdb_rating() },
    { value: "personal_desc", label: m.my_list_sort_your_rating() },
    { value: "title_asc", label: m.my_list_sort_title() },
  ];
}

export function toMediaKey(entry: LibraryEntry): string {
  return `${entry.tmdb_id}-${entry.media_type}`;
}

/** Parses a date string to epoch ms; 0 for missing or unparseable input. */
export function ts(d?: string | null): number {
  if (!d) return 0;
  const t = new Date(d).getTime();
  return Number.isNaN(t) ? 0 : t;
}

// A show has unwatched new episodes when the latest aired episode is
// numerically ahead of the user's last-watched episode. Comparing season and
// episode numbers (not dates) avoids the bug where a recently-watched older
// episode looks "newer" than an unwatched episode that aired weeks ago —
// timestamps don't reflect watch order, episode numbers do.
export function hasNewEpisodes(entry: LibraryEntry): boolean {
  if (entry.media_type !== "tv" || entry.status !== "watching") return false;

  const airedS = entry.last_aired_season;
  const airedE = entry.last_aired_episode;
  if (airedS == null || airedE == null) return false;

  const watchedS = entry.last_watched_season ?? 0;
  const watchedE = entry.last_watched_episode ?? 0;

  if (airedS > watchedS) return true;
  return airedS === watchedS && airedE > watchedE;
}

// TMDB community score. Stored on the entry in newer libraries (libraryUpsert
// persists vote_average); otherwise read off the loaded Media.
export function tmdbRating(entry: LibraryEntry, mediaByKey: MediaByKey): number {
  const onEntry = (entry as LibraryEntry & { vote_average?: number })
    .vote_average;
  if (typeof onEntry === "number" && onEntry > 0) return onEntry;
  const media = mediaByKey[toMediaKey(entry)] as
    | (Media & { vote_average?: number })
    | undefined;
  return media?.vote_average ?? 0;
}

/** Unrated entries sink to the bottom of a descending sort. */
export function personalRating(entry: LibraryEntry): number {
  return entry.rating ?? -1;
}

export function lastWatchedAt(entry: LibraryEntry): number {
  const e = entry as LibraryEntry & {
    last_watched_at?: string;
    watched_at?: string;
    updated_at?: string;
  };
  return ts(
    e.last_watched_at ?? e.watched_at ?? e.updated_at ?? entry.added_at,
  );
}

export function releaseDate(
  entry: LibraryEntry,
  mediaByKey: MediaByKey,
): number {
  const media = mediaByKey[toMediaKey(entry)] as
    | (Media & {
        release_date?: string;
        first_air_date?: string;
        last_air_date?: string;
      })
    | undefined;
  return ts(
    entry.last_air_date ??
      media?.release_date ??
      media?.first_air_date ??
      media?.last_air_date,
  );
}

export function titleOf(entry: LibraryEntry): string {
  return (entry.title ?? "").toLowerCase();
}

// Genre names for an entry. Prefers names already on the fetched Media; falls
// back to mapping numeric genre_ids through the per-type TMDB genre list.
// Empty until the Media for this entry has loaded.
export function genresFor(
  entry: LibraryEntry,
  mediaByKey: MediaByKey,
  genreNames: GenreNames,
): string[] {
  const media = mediaByKey[toMediaKey(entry)] as
    | (Media & {
        genres?: { id: number; name: string }[];
        genre_ids?: number[];
      })
    | undefined;
  if (!media) return [];
  if (Array.isArray(media.genres) && media.genres.length) {
    return media.genres.map((g) => g.name).filter(Boolean);
  }
  const ids = media.genre_ids ?? [];
  const map = genreNames[entry.media_type as "movie" | "tv"] ?? {};
  return ids.map((id) => map[id]).filter(Boolean);
}

// The original "smart" ordering — new episodes first, then most recent —
// preserved as the default sort.
export function defaultCompare(a: LibraryEntry, b: LibraryEntry): number {
  const aNew = hasNewEpisodes(a) ? 1 : 0;
  const bNew = hasNewEpisodes(b) ? 1 : 0;
  if (bNew !== aNew) return bNew - aNew;
  return ts(b.last_air_date || b.added_at) - ts(a.last_air_date || a.added_at);
}

export function compareEntries(
  a: LibraryEntry,
  b: LibraryEntry,
  sortKey: SortKey,
  mediaByKey: MediaByKey,
): number {
  switch (sortKey) {
    case "added_desc":
      return ts(b.added_at) - ts(a.added_at);
    case "added_asc":
      return ts(a.added_at) - ts(b.added_at);
    case "title_asc":
      return titleOf(a).localeCompare(titleOf(b));
    case "tmdb_desc":
      return tmdbRating(b, mediaByKey) - tmdbRating(a, mediaByKey);
    case "personal_desc":
      return personalRating(b) - personalRating(a);
    case "watched_desc":
      return lastWatchedAt(b) - lastWatchedAt(a);
    case "release_desc":
      return releaseDate(b, mediaByKey) - releaseDate(a, mediaByKey);
    default:
      return defaultCompare(a, b);
  }
}
