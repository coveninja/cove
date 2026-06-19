// $lib/player/progressSaver.svelte.ts
//
// Owns watch-progress: loading the saved position from the server, seeking to
// it once when playback is ready, and saving the current position (throttled)
// while playing plus on completion. Extracted from Player.svelte.
//
// The save payload needs live media metadata (title, season, …) which changes
// as the user navigates, so track() takes a context *getter* that is called at
// save time rather than a snapshot captured up front.

import { api } from "$lib/api";

export interface ProgressContext {
  tmdbId: number;
  mediaType: string;
  title: string;
  posterPath: string;
  voteAverage: number;
  lastAirDate: string;
  season: number | null;
  episode: number | null;
  /** Fallback when the media element doesn't yet report a duration. */
  probedDuration: number | null;
}

export class ProgressSaver {
  // Position loaded from the server; null = nothing saved or already completed.
  savedPosition = $state<number | null>(null);
  // Prevents seeking twice if canPlay fires more than once.
  hasSeekedToSaved = $state(false);

  /** Clear state — call when the source changes. */
  reset(): void {
    this.savedPosition = null;
    this.hasSeekedToSaved = false;
  }

  /** Fetch the saved position; only restores a meaningful, not-yet-finished one. */
  async load(
    tmdbId: number,
    mediaType: string,
    season: number | null,
    episode: number | null,
  ): Promise<void> {
    try {
      const prog = await api.progressGet(tmdbId, mediaType, season, episode);
      if (prog && !prog.completed && prog.position_seconds > 10) {
        this.savedPosition = prog.position_seconds;
      }
    } catch (e) {
      console.error(e);
    }
  }

  /** Seek to the saved position the first time playback becomes ready. */
  resume(video: HTMLVideoElement | null | undefined): void {
    if (video && this.savedPosition !== null && !this.hasSeekedToSaved) {
      this.hasSeekedToSaved = true;
      video.currentTime = this.savedPosition;
    }
  }

  /**
   * Attach progress-saving listeners to a video element. Saves at most every
   * 10s during playback, and once on "ended". Returns a cleanup function.
   */
  track(video: HTMLVideoElement, getCtx: () => ProgressContext): () => void {
    let lastSaveMs = 0;

    const save = (completed: boolean): void => {
      const c = getCtx();
      const pos = video.currentTime;
      const dur = video.duration || c.probedDuration || 0;
      if (!dur || pos < 5) return; // skip the very start

      api
        .progressSave({
          tmdb_id: c.tmdbId,
          media_type: c.mediaType,
          title: c.title,
          poster_path: c.posterPath,
          vote_average: c.voteAverage,
          last_air_date: c.lastAirDate,
          season: c.season,
          episode: c.episode,
          position_seconds: completed ? dur : pos,
          duration_seconds: dur,
          completed,
        })
        .catch(console.error);
    };

    const onTimeUpdate = (): void => {
      const now = Date.now();
      if (now - lastSaveMs < 10_000) return; // throttle: at most every 10s
      lastSaveMs = now;
      // Auto-detect completion: >90% through
      const dur = video.duration || getCtx().probedDuration || 0;
      save(dur > 0 && video.currentTime / dur >= 0.9);
    };

    const onEnded = (): void => save(true);

    video.addEventListener("timeupdate", onTimeUpdate);
    video.addEventListener("ended", onEnded);

    return () => {
      video.removeEventListener("timeupdate", onTimeUpdate);
      video.removeEventListener("ended", onEnded);
    };
  }
}
