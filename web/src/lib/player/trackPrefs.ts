// Per-media, device-local memory of the user's audio / subtitle / speed picks,
// so they persist across a show's episodes and reset to the global defaults for
// a different title. Keyed by TMDB id, stored in localStorage — the same
// device-local rationale as the aspect-mode memory ([[aspectRatio]]): track
// availability and the "right" pick differ per device (a phone's tiny speaker
// might want different audio than a TV), and it needs no backend/sync.
//
// Audio and subtitle picks are stored by *language*, not track id: mpv track
// ids aren't stable across a season's files (episode 2's "track 3" may be a
// different language than episode 1's), so we re-match by language each episode
// exactly like the global auto-select does.

export type SubChoicePref = { kind: "off" } | { kind: "lang"; lang: string };

export interface ShowTrackPrefs {
  speed?: number;
  audioLang?: string; // language tag of the chosen audio track
  sub?: SubChoicePref;
}

const KEY_PREFIX = "cove-trackprefs:";

export function loadShowTrackPrefs(mediaId: number): ShowTrackPrefs {
  try {
    const raw = localStorage.getItem(KEY_PREFIX + mediaId);
    if (!raw) return {};
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === "object" ? (parsed as ShowTrackPrefs) : {};
  } catch {
    return {};
  }
}

/** Merge a partial pref into the stored record for a title. */
export function saveShowTrackPrefs(
  mediaId: number,
  patch: Partial<ShowTrackPrefs>,
): void {
  try {
    const next = { ...loadShowTrackPrefs(mediaId), ...patch };
    localStorage.setItem(KEY_PREFIX + mediaId, JSON.stringify(next));
  } catch {
    // Storage unavailable (private/in-memory) — the pick just won't persist.
  }
}
