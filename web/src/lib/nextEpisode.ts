// $lib/nextEpisode.ts
//
// Shared "what's the next aired episode after (season, episode)" logic.
// Extracted from ContinueWatching.svelte, which originally owned this for its
// own roll-forward card — now also used by Player.svelte's up-next overlay
// (F6) so autoplay/roll-forward agree on exactly the same episode.

import { api } from "$lib/api";
import type { WatchProgress } from "$lib/types/library";
import type { TVEpisode } from "$lib/types/tmdb";

type FetchSeason = (id: number, season: number) => Promise<TVEpisode[]>;

/** True if ep has aired as of today (local date, midnight-truncated). Plain
 * Date is fine here — this is a one-shot computation, not template-bound
 * reactive state, so there's no need for SvelteDate's reactivity machinery. */
export function hasAired(ep: TVEpisode): boolean {
  if (!ep.air_date) return false;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return new Date(ep.air_date + "T00:00:00").getTime() <= today.getTime();
}

/** Default fetchSeason: a non-existent season comes back as an empty/null
 * body (resolves to null, not a rejection), so callers can always assume an
 * array back. */
async function defaultFetchSeason(
  id: number,
  season: number,
): Promise<TVEpisode[]> {
  try {
    const eps = await api.tvEpisodes(id, season);
    return eps ?? [];
  } catch {
    return [];
  }
}

/**
 * The next *aired* episode after (season, episode): the following episode in
 * the same season, else the first of the next season. An existing-but-unaired
 * next episode means the user is caught up — returns null (the "New
 * Episodes"/"Ready" rows own that case, not this one).
 *
 * fetchSeason is injectable so callers with their own per-load season cache
 * (e.g. ContinueWatching's SvelteMap-backed cache, reused across a show's
 * resume-point lookup and this roll-forward check) can share it instead of
 * re-fetching; defaults to a plain uncached api.tvEpisodes call.
 */
export async function nextAiredEpisode(
  id: number,
  season: number,
  episode: number,
  fetchSeason: FetchSeason = defaultFetchSeason,
): Promise<{ season: number; episode: TVEpisode } | null> {
  const same = await fetchSeason(id, season);
  // Prefer the lowest numbered later episode instead of requiring an exact
  // +1 match. TMDB metadata can occasionally omit an episode from a season;
  // that gap must not make us jump to the next season while a later episode
  // in the current season is already available.
  const inSeason = same
    .filter((e) => e.episode_number > episode)
    .toSorted((a, b) => a.episode_number - b.episode_number)[0];
  if (inSeason) return hasAired(inSeason) ? { season, episode: inSeason } : null;

  const next = await fetchSeason(id, season + 1);
  const first = next
    .filter((e) => e.episode_number >= 1)
    .toSorted((a, b) => a.episode_number - b.episode_number)[0];
  if (first) return hasAired(first) ? { season: season + 1, episode: first } : null;
  return null;
}

/**
 * Continue Watching's completed-aware roll-forward. Unlike nextAiredEpisode,
 * this skips aired episodes that already have completed progress records.
 * It scans through the furthest completed season plus one so stale or tied
 * watched_at timestamps cannot offer an already-watched episode as "Up Next".
 */
export async function nextUnwatchedAiredEpisode(
  id: number,
  season: number,
  episode: number,
  progress: WatchProgress[],
  fetchSeason: FetchSeason = defaultFetchSeason,
): Promise<{ season: number; episode: TVEpisode } | null> {
  const completed = new Set<string>();
  let lastSeason = season + 1;

  for (const item of progress) {
    if (
      !item.completed ||
      item.tmdb_id !== id ||
      item.media_type !== "tv" ||
      item.season == null ||
      item.episode == null ||
      item.season <= 0 ||
      item.episode <= 0
    ) {
      continue;
    }
    completed.add(`${item.season}:${item.episode}`);
    if (item.season >= season) {
      lastSeason = Math.max(lastSeason, item.season + 1);
    }
  }

  // Defensive cap for malformed synced progress. Real shows stay far below
  // this, while a bogus season number must not trigger hundreds of requests.
  lastSeason = Math.min(lastSeason, season + 99);

  for (
    let candidateSeason = season;
    candidateSeason <= lastSeason;
    candidateSeason++
  ) {
    const afterEpisode = candidateSeason === season ? episode : 0;
    const candidates = (await fetchSeason(id, candidateSeason))
      .filter((item) => item.episode_number > afterEpisode)
      .toSorted((a, b) => a.episode_number - b.episode_number);

    for (const candidate of candidates) {
      // Episode numbers are chronological within a season. If the earliest
      // remaining one has not aired, the show is caught up for this row.
      if (!hasAired(candidate)) return null;
      if (completed.has(`${candidateSeason}:${candidate.episode_number}`)) {
        continue;
      }
      return { season: candidateSeason, episode: candidate };
    }
  }

  return null;
}
