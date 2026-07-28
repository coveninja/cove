// Pure helpers shared by the three search pages (desktop QueryPage, mobile
// MobileSearchPage, TV TvSearchPage). Each shell keeps its own markup, its own
// debounced `$effect`, and its own result-list state; only the query-shaping
// rules live here.
//
// Deliberately rune-free: these are plain functions so they can be unit-tested
// without a component host, matching how $lib/searchTopResults.ts is written.

import type { SearchResults } from "$lib/api";
import type { Media } from "$lib/types/tmdb";

/** The four result sections a search can fill. */
export const SEARCH_TYPES = ["movie", "tv", "person", "provider"] as const;
export type SearchType = (typeof SEARCH_TYPES)[number];

/** A blank result set — used as the initial value and to clear on empty query. */
export function emptyResults(): SearchResults {
  return { movies: [], tv: [], people: [], providers: [], title_order: [] };
}

// Appends the "known for" credits of matched people to a title list, so
// searching an actor's name surfaces their films even when TMDB's title search
// didn't match them directly. Existing ids win — a direct title hit keeps its
// original position and richer payload.
export function withKnownFor(
  list: Media[],
  type: "movie" | "tv",
  people: SearchResults["people"],
): Media[] {
  const seen = new Set(list.map((m) => m.id));
  const out = [...list];
  for (const person of people) {
    for (const credit of person.known_for ?? []) {
      if (credit.media_type === type && !seen.has(credit.id)) {
        seen.add(credit.id);
        out.push(credit);
      }
    }
  }
  return out;
}

// Toggles one type chip, refusing to clear the last one — an empty filter set
// would render a blank page with no way back other than re-selecting a chip.
export function toggleSearchType(selected: string[], type: string): string[] {
  if (!selected.includes(type)) return [...selected, type];
  if (selected.length === 1) return selected;
  return selected.filter((t) => t !== type);
}

/** Ids to request quality badges for, in the shape the batch API expects. */
export function qualityTargets(
  data: Pick<SearchResults, "movies" | "tv">,
): { id: number; type: "movie" | "tv" }[] {
  return [
    ...data.movies.map((m) => ({ id: m.id, type: "movie" as const })),
    ...data.tv.map((m) => ({ id: m.id, type: "tv" as const })),
  ];
}

// Normalises a /api/search/multi payload, which serialises empty sections as
// null rather than []. Every shell did this inline before calling it `data`.
export function normalizeResults(res: Partial<SearchResults>): SearchResults {
  return {
    movies: res.movies ?? [],
    tv: res.tv ?? [],
    people: res.people ?? [],
    providers: res.providers ?? [],
    title_order: res.title_order ?? [],
  };
}
