import { intlLocale } from "$lib/i18n";
import * as m from "$lib/paraglide/messages.js";

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

export type ActivityCalendarCell = {
  dateStr: string;
  label: string;
  seconds: number;
  level: number;
  weekIdx: number;
  dayIdx: number;
  inRange: boolean;
};

export type ActivityMonthLabel = { weekIdx: number; name: string };

export type ActivityCalendarData = {
  cells: ActivityCalendarCell[];
  monthLabels: ActivityMonthLabel[];
  totalWeeks: number;
};

export function buildActivityCalendar(
  calendar: Record<string, number>,
  endDate: Date = new Date(),
  locale: string = intlLocale(),
): ActivityCalendarData {
  let maxVal = 1;
  for (const value of Object.values(calendar)) {
    if (value > maxVal) maxVal = value;
  }

  const today = new Date(endDate);
  today.setHours(0, 0, 0, 0);

  const rangeStart = new Date(today);
  rangeStart.setFullYear(rangeStart.getFullYear() - 1);
  rangeStart.setDate(rangeStart.getDate() + 1);

  const gridStart = new Date(rangeStart);
  gridStart.setDate(gridStart.getDate() - gridStart.getDay());

  const monthFormatter = new Intl.DateTimeFormat(locale, { month: "short" });
  const dateFormatter = new Intl.DateTimeFormat(locale, {
    month: "short",
    day: "numeric",
  });
  const cells: ActivityCalendarCell[] = [];
  const monthLabels: ActivityMonthLabel[] = [];
  let seenMonth = -1;
  let weekIdx = 0;
  const cur = new Date(gridStart);

  while (cur <= today) {
    const dayIdx = cur.getDay();
    if (dayIdx === 0 && cells.length > 0) weekIdx++;

    const inRange = cur >= rangeStart;
    const year = cur.getFullYear();
    const month = cur.getMonth();
    const day = cur.getDate();
    const dateStr = `${year}-${String(month + 1).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
    const seconds = inRange ? (calendar[dateStr] ?? 0) : 0;

    let level = 0;
    if (inRange && seconds > 0) {
      const fraction = seconds / maxVal;
      if (fraction <= 0.25) level = 1;
      else if (fraction <= 0.5) level = 2;
      else if (fraction <= 0.75) level = 3;
      else level = 4;
    }

    if (inRange && month !== seenMonth) {
      seenMonth = month;
      const label = { weekIdx, name: monthFormatter.format(cur) };
      const previous = monthLabels.at(-1);
      // A partial first month and the following month can begin in the same
      // calendar week. Keep the newer month so keyed rendering stays unique
      // and the two labels do not overlap at the same horizontal position.
      if (previous?.weekIdx === weekIdx) {
        monthLabels[monthLabels.length - 1] = label;
      } else {
        monthLabels.push(label);
      }
    }

    cells.push({
      dateStr,
      label: dateFormatter.format(cur),
      seconds,
      level,
      weekIdx,
      dayIdx,
      inRange,
    });

    cur.setDate(cur.getDate() + 1);
  }

  return { cells, monthLabels, totalWeeks: weekIdx + 1 };
}

export function activityWeekdayLabels(
  locale: string = intlLocale(),
): string[] {
  const formatter = new Intl.DateTimeFormat(locale, { weekday: "narrow" });
  return Array.from({ length: 7 }, (_, dayIdx) =>
    dayIdx === 1 || dayIdx === 3 || dayIdx === 5
      ? formatter.format(new Date(2024, 0, 7 + dayIdx))
      : "",
  );
}
