// $lib/nextEpisode.ts
//
// Shared "what's the next aired episode after (season, episode)" logic.
// Extracted from ContinueWatching.svelte, which originally owned this for its
// own roll-forward card — now also used by Player.svelte's up-next overlay
// (F6) so autoplay/roll-forward agree on exactly the same episode.

import { api } from "$lib/api";
import type { TVEpisode } from "$lib/types/tmdb";

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
  fetchSeason: (
    id: number,
    season: number,
  ) => Promise<TVEpisode[]> = defaultFetchSeason,
): Promise<{ season: number; episode: TVEpisode } | null> {
  const same = await fetchSeason(id, season);
  const inSeason = same.find((e) => e.episode_number === episode + 1);
  if (inSeason) return hasAired(inSeason) ? { season, episode: inSeason } : null;

  const next = await fetchSeason(id, season + 1);
  const first = next
    .filter((e) => e.episode_number >= 1)
    .toSorted((a, b) => a.episode_number - b.episode_number)[0];
  if (first) return hasAired(first) ? { season: season + 1, episode: first } : null;
  return null;
}
