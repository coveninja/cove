import type { CalendarItem } from "$lib/types/calendar";
import { intlLocale } from "$lib/i18n";
import * as m from "$lib/paraglide/messages.js";

export interface CalendarDay {
  key: string; // "available" or YYYY-MM-DD
  label: string; // "Available Now" / "Today · Jul 20" / "Tomorrow" / "Fri · Jul 25"
  items: CalendarItem[];
}

export interface CalendarSummary {
  available: number;
  today: number;
  thisWeek: number;
  upcoming: number;
}

/**
 * Returns a count object used to build the summary label.
 * - available: items with kind="available"
 * - today: future items whose date is today
 * - thisWeek: future items within the next 7 days (inclusive of today)
 * - upcoming: all future items
 */
export function calendarSummary(items: CalendarItem[]): CalendarSummary {
  const now = new Date();
  now.setHours(0, 0, 0, 0);
  const weekEnd = new Date(now.getTime() + 7 * 86_400_000);

  let available = 0;
  let today = 0;
  let thisWeek = 0;
  let upcoming = 0;

  for (const item of items) {
    if (item.kind === "available") {
      available++;
    } else {
      upcoming++;
      const target = new Date(item.date + "T00:00:00");
      const delta = Math.round(
        (target.getTime() - now.getTime()) / 86_400_000,
      );
      if (delta === 0) today++;
      if (target < weekEnd) thisWeek++;
    }
  }

  return { available, today, thisWeek, upcoming };
}

/**
 * Builds "4 ready to watch · 2 today · 9 this week" with omission rules:
 * - Zero segments are omitted
 * - If nothing is ready/today/this week but there are future items → "12 upcoming"
 * - If everything is zero → "Nothing scheduled"
 */
export function summaryLabel(s: CalendarSummary): string {
  const parts: string[] = [];
  if (s.available > 0)
    parts.push(m.common_ready_to_watch({ count: s.available }));
  if (s.today > 0)
    parts.push(m.common_today_count({ count: s.today }));
  if (s.thisWeek > 0)
    parts.push(m.common_this_week_count({ count: s.thisWeek }));
  if (parts.length > 0) return parts.join(" · ");
  if (s.upcoming > 0) return m.common_upcoming_count({ count: s.upcoming });
  return m.common_nothing_scheduled();
}

/**
 * Returns up to `limit` items for the next-up chip strip:
 * available items first (freshest-first as returned by the backend),
 * then future items (date-asc as returned by the backend).
 */
export function nextUp(items: CalendarItem[], limit: number): CalendarItem[] {
  if (limit <= 0) return [];
  const available = items.filter((i) => i.kind === "available");
  const future = items.filter((i) => i.kind !== "available");
  return [...available, ...future].slice(0, limit);
}

/**
 * Returns a short human-readable label for a YYYY-MM-DD date string:
 *   delta 0 → "Today"
 *   delta 1 → "Tomorrow"
 *   delta 2–6 → "Thu" (weekday short, reuses dayLabel parsing logic)
 *   else → "Jul 25"
 */
export function shortDateLabel(dateStr: string): string {
  const today = new Date();
  today.setHours(0, 0, 0, 0);

  const target = new Date(dateStr + "T00:00:00");
  const delta = Math.round(
    (target.getTime() - today.getTime()) / 86_400_000,
  );

  if (delta === 0) return m.common_today();
  if (delta === 1) return m.common_tomorrow();
  if (delta >= 2 && delta <= 6) {
    return target.toLocaleDateString(intlLocale(), { weekday: "short" });
  }
  return target.toLocaleDateString(intlLocale(), {
    month: "short",
    day: "numeric",
  });
}

/**
 * Groups a flat sorted CalendarItem array (as returned by GET /api/library/calendar)
 * into day sections. All kind==="available" items collapse into one leading section;
 * the rest are grouped by their date string, preserving backend order.
 */
export function groupByDay(items: CalendarItem[]): CalendarDay[] {
  const days: CalendarDay[] = [];
  const dateMap = new Map<string, CalendarDay>();

  for (const item of items) {
    if (item.kind === "available") {
      let avail = days[0];
      if (!avail || avail.key !== "available") {
        avail = { key: "available", label: m.common_available_now(), items: [] };
        days.unshift(avail);
      }
      avail.items.push(item);
    } else {
      const key = item.date;
      let day = dateMap.get(key);
      if (!day) {
        day = { key, label: dayLabel(key), items: [] };
        dateMap.set(key, day);
        days.push(day);
      }
      day.items.push(item);
    }
  }

  return days;
}

/**
 * Returns a human-readable label for a YYYY-MM-DD date string:
 *   delta 0 → "Today · Jul 20"
 *   delta 1 → "Tomorrow"
 *   else    → "Fri · Jul 25"
 */
export function dayLabel(dateStr: string): string {
  const today = new Date();
  today.setHours(0, 0, 0, 0);

  const target = new Date(dateStr + "T00:00:00");
  const delta = Math.round(
    (target.getTime() - today.getTime()) / 86_400_000,
  );

  const monthDay = target.toLocaleDateString(intlLocale(), {
    month: "short",
    day: "numeric",
  });

  if (delta === 0) return `${m.common_today()} · ${monthDay}`;
  if (delta === 1) return m.common_tomorrow();

  const weekday = target.toLocaleDateString(intlLocale(), { weekday: "short" });
  return `${weekday} · ${monthDay}`;
}
