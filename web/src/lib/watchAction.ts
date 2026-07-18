// $lib/watchAction.ts
//
// What the detail overlay's primary button should say and play for a TV show,
// shared by MediaExpandedModal (desktop), MediaDetailSheet (mobile), and
// TvDetailOverlay (TV) so all three shells agree. Movies keep their local
// Continue/Watch check.

import type { WatchProgress } from "$lib/types/library";
import { nextAiredEpisode } from "$lib/nextEpisode";

export interface TvWatchAction {
  label: string; // "Watch" | "Continue SxEy" | "Rewatch"
  season?: number;
  episode?: number;
}

/**
 * Mid-episode → resume it; finished → the next aired episode; nothing aired
 * left to watch → "Rewatch" from the start. "Latest" is by watched_at, the
 * same rule ContinueWatching's roll-forward uses.
 */
export async function resolveTvWatchAction(
  id: number,
  progress: WatchProgress[],
): Promise<TvWatchAction> {
  const latest = progress.toSorted(
    (a, b) =>
      new Date(b.watched_at).getTime() - new Date(a.watched_at).getTime(),
  )[0];
  if (!latest) return { label: "Watch" };

  const s = latest.season ?? 1;
  const e = latest.episode ?? 1;

  if (!latest.completed)
    return { label: `Continue S${s}E${e}`, season: s, episode: e };

  const next = await nextAiredEpisode(id, s, e);
  if (next)
    return {
      label: `Continue S${next.season}E${next.episode.episode_number}`,
      season: next.season,
      episode: next.episode.episode_number,
    };

  return { label: "Rewatch", season: 1, episode: 1 };
}
