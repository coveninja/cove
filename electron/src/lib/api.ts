import type { Details, Media, MediaImages, MediaVideos } from "$lib/types/tmdb";
import type { Stream } from "$lib/types/addons";
import type { Settings } from "$lib/types/settings"; // tygo-generated
import type { LibraryEntry, WatchProgress } from "$lib/types/library"; // tygo-generated

const BASE = "http://localhost:6969/api";

// ── Library: TypeScript-only types ────────────────────────────────────────────
//
// LibraryStatus can't be a Go union type, so we define it here and let tygo
// emit `string` for the Status field in LibraryEntry. Both are correct at
// runtime; the union just gives callers better autocomplete.

export type LibraryStatus = "watch_later" | "watching" | "finished" | "dropped";

export const STATUS_LABELS: Record<LibraryStatus, string> = {
  watch_later: "Watch Later",
  watching:    "Watching",
  finished:    "Finished",
  dropped:     "Dropped",
};

/** "1h 23m" / "4m 12s" / "8s" */
export function formatPosition(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${s}s`;
  return `${s}s`;
}

// ── API ────────────────────────────────────────────────────────────────────────

export const api = {

  // ── TMDB ────────────────────────────────────────────────────────────────────
  search: (q: string): Promise<Media[]> =>
    fetch(`${BASE}/search?q=${encodeURIComponent(q)}`).then((r) => r.json()),

  getKeywords: (q: string): Promise<{ id: number; name: string }[]> =>
    fetch(`${BASE}/keywords?q=${encodeURIComponent(q)}`).then((r) => r.json()),

  getStreams: (tmdbId: number): Promise<Stream[]> =>
    fetch(`${BASE}/streams?id=${tmdbId}`).then((r) => r.json()),

  getSimilar: (media: Media): Promise<Media[]> =>
    fetch(`${BASE}/similar?id=${media.id}&type=${media.media_type}`).then((r) => r.json()),

  // Fetches a genuine, fully-populated Media object by ID — for places that
  // only have a bare tmdb_id (e.g. a LibraryEntry) and would otherwise have
  // to reconstruct a partial Media object by hand.
  getMediaByID: (tmdbId: number, mediaType: string): Promise<Media> =>
    fetch(`${BASE}/media?id=${tmdbId}&type=${mediaType}`).then((r) => r.json()),

  getDetails: (media: Media): Promise<Details> =>
    fetch(`${BASE}/details?id=${media.id}&type=${media.media_type}`).then((r) => r.json()),

  getImages: (media: Media): Promise<MediaImages> =>
    fetch(`${BASE}/images?id=${media.id}&type=${media.media_type}`).then((r) => r.json()),

  getVideos: (media: Media): Promise<MediaVideos> =>
    fetch(`${BASE}/videos?id=${media.id}&type=${media.media_type}`).then((r) => r.json()),

  // ── Settings ─────────────────────────────────────────────────────────────────
  getSettings: (): Promise<Settings> =>
    fetch(`${BASE}/settings`).then((r) => r.json()),

  updateSettings: (s: Settings): Promise<Settings> =>
    fetch(`${BASE}/settings`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(s),
    }).then((r) => r.json()),

  // ── Library ──────────────────────────────────────────────────────────────────
  libraryList: (status?: LibraryStatus): Promise<LibraryEntry[]> =>
    fetch(`${BASE}/library${status ? `?status=${status}` : ""}`).then((r) => r.json()),

  libraryUpsert: (p: {
    tmdb_id: number;
    media_type: string;
    title: string;
    poster_path: string;
    vote_average?: number;
    last_air_date?: string;
    last_aired_season?: number | null;
    last_aired_episode?: number | null;
    status?: LibraryStatus;
  }): Promise<LibraryEntry> =>
    fetch(`${BASE}/library`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(p),
    }).then((r) => r.json()),

  // Returns null only when the title has no entry AND no progress history.
  // entry can be null if the user removed the title from their list but
  // progress records (watch history) still exist.
  libraryGet: (
    tmdbId: number,
    mediaType: string,
  ): Promise<{ entry: LibraryEntry | null; progress: WatchProgress[] } | null> =>
    fetch(`${BASE}/library/${tmdbId}/${mediaType}`).then((r) =>
      r.status === 404 ? null : r.json(),
    ),

  libraryRemove: (tmdbId: number, mediaType: string): Promise<void> =>
    fetch(`${BASE}/library/${tmdbId}/${mediaType}`, { method: "DELETE" }).then(() => {}),

  librarySetStatus: (
    tmdbId: number,
    mediaType: string,
    status: LibraryStatus,
  ): Promise<LibraryEntry> =>
    fetch(`${BASE}/library/${tmdbId}/${mediaType}/status`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ status }),
    }).then((r) => r.json()),

  librarySetRating: (
    tmdbId: number,
    mediaType: string,
    rating: number | null,
  ): Promise<LibraryEntry> =>
    fetch(`${BASE}/library/${tmdbId}/${mediaType}/rating`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ rating }),
    }).then((r) => r.json()),

  // ── Watch progress ────────────────────────────────────────────────────────────
  // Returns null when no progress has been saved yet (not an error).
  progressGet: (
    tmdbId: number,
    mediaType: string,
    season?: number | null,
    episode?: number | null,
  ): Promise<WatchProgress | null> => {
    const p = new URLSearchParams({ tmdb_id: String(tmdbId), media_type: mediaType });
    if (season != null) p.set("season", String(season));
    if (episode != null) p.set("episode", String(episode));
    return fetch(`${BASE}/library/progress?${p}`).then((r) => (r.ok ? r.json() : null));
  },

  // Upserts a progress record. Also auto-creates a "watching" library entry
  // if one doesn't already exist (handled server-side).
  progressSave: (p: {
    tmdb_id: number;
    media_type: string;
    title?: string;
    poster_path?: string;
    vote_average?: number;
    last_air_date?: string;
    last_aired_season?: number | null;
    last_aired_episode?: number | null;
    season?: number | null;
    episode?: number | null;
    position_seconds: number;
    duration_seconds: number;
    completed: boolean;
  }): Promise<WatchProgress> =>
    fetch(`${BASE}/library/progress`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(p),
    }).then((r) => r.json()),
};
