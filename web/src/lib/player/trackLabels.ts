// Shared human-readable labels for mpv audio/subtitle tracks, used by the
// desktop player's track menus.
import { languageDisplayName } from "$lib/i18n";
import * as m from "$lib/paraglide/messages.js";

/** Localized display name for an ISO language code, falling back to the code. */
export function langName(code: string): string {
  return languageDisplayName(code);
}

/** Best available label for a track: explicit title, else language name, else a
 *  numbered fallback (some files ship untagged tracks — nothing to name them by). */
export function trackLabel(
  t: { id: number; title: string; lang: string },
  kind: "Audio" | "Subtitle",
): string {
  if (t.title) return t.title;
  if (t.lang) return langName(t.lang);
  return `${kind === "Audio" ? m.player_audio() : m.player_subtitle()} ${t.id}`;
}
