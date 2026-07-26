// Per-media, device-local persistence of the player's aspect-fit mode.
//
// The choice is remembered per title (keyed by TMDB id) so it carries across a
// show's episodes but resets to "fit" for a different title. It's stored in
// localStorage rather than the synced settings store because aspect handling is
// inherently display-dependent — a phone and a TV want independent choices for
// the same show. localStorage in Qt WebEngine can be flaky/in-memory (see the
// note in api.ts), so every access is guarded.

import { ASPECT_MODES, type AspectMode } from "./player.svelte";
import * as m from "$lib/paraglide/messages.js";

const KEY_PREFIX = "cove-aspect:";

/** Human labels for the aspect button + desktop flash. */
export const ASPECT_LABELS: Record<AspectMode, string> = {
  fit: m.player_aspect_fit(),
  fill: m.player_aspect_fill(),
  stretch: m.player_aspect_stretch(),
  zoom: m.player_aspect_zoom(),
};

function isAspectMode(v: string | null): v is AspectMode {
  return v !== null && (ASPECT_MODES as readonly string[]).includes(v);
}

/** Saved mode for a title, or "fit" if none stored / storage unavailable. */
export function loadAspectMode(mediaId: number): AspectMode {
  try {
    const v = localStorage.getItem(KEY_PREFIX + mediaId);
    return isAspectMode(v) ? v : "fit";
  } catch {
    return "fit";
  }
}

/** Persist the mode for a title. "fit" is stored explicitly (not cleared) so it
 *  round-trips the same as any other choice. */
export function saveAspectMode(mediaId: number, mode: AspectMode): void {
  try {
    localStorage.setItem(KEY_PREFIX + mediaId, mode);
  } catch {
    // Storage unavailable (private/in-memory) — the choice simply won't persist.
  }
}
