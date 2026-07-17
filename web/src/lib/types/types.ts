import type { Media } from "$lib/types/tmdb";
import type { Stream } from "$lib/types/addons";

// Describes an in-progress or paused playback session. Lifted here so both
// the desktop App.svelte shell and the Android mobile shell can share the
// type without duplicating it.
export type PlayerSession = {
  media: Media;
  stream: Stream;
  season?: number;
  episode?: number;
  episodeName?: string;
  subtitles: { id: string; url: string; lang: string }[];
  // B2: runner-up streams (ranked, best-first) to fall back to if `stream`
  // never actually starts — see handlePlaybackFailed. Session-local only;
  // no Go/tygo type carries this. Manual stream-list picks carry no
  // candidates (undefined) — an explicit user pick is never silently
  // swapped for another stream, it just gets the watchdog UI.
  candidates?: Stream[];
  // How many candidates have already failed for this playback attempt —
  // caps the auto-advance chain (see handlePlaybackFailed).
  attempt?: number;
};

export type Page =
  | { type: "home" }
  | { type: "myList" }
  | { type: "explore" }
  | { type: "settings" }
  | { type: "account" }
  | { type: "query"; query: string }
  | { type: "mediaView"; media: Media }
  | { type: "catalog"; addonId: string; catalogType: string; catalogId: string; name: string; addonUrl?: string };

export interface UpcomingItem {
  tmdbId: number;
  title: string;
  posterPath: string;
  season: number;
  episode: number;
  episodeName: string;
  airDate: string; // YYYY-MM-DD from TMDB
  stillPath: string;
}
