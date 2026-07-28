/** Format a playback position as M:SS or H:MM:SS. */
export function formatPlaybackTime(seconds: number): string {
  const safe = Number.isFinite(seconds) && seconds >= 0 ? seconds : 0;
  const hours = Math.floor(safe / 3600);
  const minutes = Math.floor((safe % 3600) / 60);
  const remainingSeconds = Math.floor(safe % 60);
  const minuteLabel = hours
    ? String(minutes).padStart(2, "0")
    : String(minutes);
  return `${hours ? `${hours}:` : ""}${minuteLabel}:${String(
    remainingSeconds,
  ).padStart(2, "0")}`;
}
