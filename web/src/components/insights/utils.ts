/**
 * Format a seconds value as a compact human-readable string.
 * < 1 min  → "<1m"
 * < 1 hour → "Xm"
 * ≥ 1 hour, 0 min → "Xh"
 * ≥ 1 hour, >0 min → "Xh Ym"
 */
export function fmtHours(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 60) return m.common_less_minute();
  const totalMinutes = Math.floor(seconds / 60);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours === 0) return m.common_minutes_short({ minutes: totalMinutes });
  if (minutes === 0) return m.common_hours_short({ hours });
  return m.common_hours_minutes({ hours, minutes });
}
import * as m from "$lib/paraglide/messages.js";
