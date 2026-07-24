// Shared human-readable labels for mpv audio/subtitle tracks, used by the
// desktop player's track menus.

/** English display name for an ISO language code, falling back to the code. */
export function langName(code: string): string {
  if (!code) return code;
  try {
    return new Intl.DisplayNames(["en"], { type: "language" }).of(code) ?? code;
  } catch {
    return code;
  }
}

/** Best available label for a track: explicit title, else language name, else a
 *  numbered fallback (some files ship untagged tracks — nothing to name them by). */
export function trackLabel(
  t: { id: number; title: string; lang: string },
  kind: "Audio" | "Subtitle",
): string {
  if (t.title) return t.title;
  if (t.lang) return langName(t.lang);
  return `${kind} ${t.id}`;
}
