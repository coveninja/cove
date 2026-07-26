import type { Media } from "$lib/types/tmdb";

type TitleType = "movie" | "tv";

interface RankedTitle {
  key: string;
  type: TitleType;
  media: Media;
}

export interface TopSearchResultsOptions {
  includeMovies: boolean;
  includeTV: boolean;
  limit?: number;
  compare?: (a: Media, b: Media) => number;
}

/**
 * Reconstruct the backend's unified movie/TV ranking from its sectioned
 * payload. Unknown order keys are ignored, duplicate typed IDs keep their
 * first occurrence, and unlisted titles are appended as a compatibility
 * fallback for older or partially stale responses.
 */
export function getTopSearchResults(
  movies: readonly Media[],
  tv: readonly Media[],
  titleOrder: readonly string[] | null | undefined,
  options: TopSearchResultsOptions,
): Media[] {
  const byKey = new Map<string, RankedTitle>();
  const fallbackOrder: RankedTitle[] = [];

  function addTitles(items: readonly Media[], type: TitleType): void {
    for (const media of items) {
      const key = `${type}:${media.id}`;
      if (byKey.has(key)) continue;
      const title = { key, type, media };
      byKey.set(key, title);
      fallbackOrder.push(title);
    }
  }

  addTitles(movies, "movie");
  addTitles(tv, "tv");

  const ordered: RankedTitle[] = [];
  const seen = new Set<string>();
  for (const key of titleOrder ?? []) {
    const title = byKey.get(key);
    if (!title || seen.has(key)) continue;
    seen.add(key);
    ordered.push(title);
  }
  for (const title of fallbackOrder) {
    if (seen.has(title.key)) continue;
    seen.add(title.key);
    ordered.push(title);
  }

  let eligible = ordered.filter(
    ({ type }) =>
      (type === "movie" && options.includeMovies) ||
      (type === "tv" && options.includeTV),
  );

  if (options.compare) {
    const compare = options.compare;
    eligible = eligible
      .map((title, index) => ({ title, index }))
      .sort((a, b) => {
        const result = compare(a.title.media, b.title.media);
        return Number.isFinite(result) && result !== 0
          ? result
          : a.index - b.index;
      })
      .map(({ title }) => title);
  }

  const limit = Math.max(0, Math.floor(options.limit ?? 6));
  return eligible.slice(0, limit).map(({ media }) => media);
}
