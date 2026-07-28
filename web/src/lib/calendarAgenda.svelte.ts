// $lib/calendarAgenda.svelte.ts
//
// Shared data layer for the three calendar agenda strips (desktop, mobile,
// TV): the calendar fetch, its reload-on-library-change, the day grouping and
// the "show more days" window.
//
// The pure item helpers below (toMedia, chipSublabel, dayOfMonth) were
// byte-identical in all three components. The grouping/summary primitives they
// build on live in $lib/calendar and are unchanged.
//
// No $effect here — each component wires reload() from its own $effect, same
// rule as the rest of $lib.

import { api } from "$lib/api";
import { calendarSummary, groupByDay, nextUp, shortDateLabel, summaryLabel } from "$lib/calendar";
import { mediaFromEntry } from "$lib/mediaFromEntry";
import type { CalendarItem } from "$lib/types/calendar";
import type { Media } from "$lib/types/tmdb";

// Show "Available Now" + this many date groups; the rest hide behind
// "show more".
const MAX_DAYS_INITIAL = 7;

/** The Media object a calendar row opens as a detail overlay. */
export function toMedia(item: CalendarItem): Media {
  if (item.media_type === "tv") {
    return mediaFromEntry({
      id: item.tmdb_id,
      media_type: "tv",
      name: item.title,
      poster_path: item.poster_path,
    });
  }
  return mediaFromEntry({
    id: item.tmdb_id,
    media_type: "movie",
    title: item.title,
    poster_path: item.poster_path,
  });
}

/** Short label shown below the chip thumbnail. */
export function chipSublabel(item: CalendarItem): string {
  if (item.kind === "available") {
    if (
      item.media_type === "tv" &&
      item.season_number != null &&
      item.episode_number != null
    ) {
      return `S${item.season_number}E${item.episode_number}`;
    }
    return "Watch";
  }
  return shortDateLabel(item.date);
}

/** Day-of-month number for the timeline rail bubble. */
export function dayOfMonth(dateKey: string): number {
  // Transient parse — the Date is read once and discarded.
  // eslint-disable-next-line svelte/prefer-svelte-reactivity
  return new Date(dateKey + "T00:00:00").getDate();
}

export interface CalendarAgendaOptions {
  /** How many "next up" chips the strip shows. Desktop uses 12; the mobile
   *  and TV strips are narrower and use 10. */
  chipLimit: number;
}

export class CalendarAgendaController {
  items = $state<CalendarItem[]>([]);
  loading = $state(true);
  expanded = $state(false);
  showAllDays = $state(false);
  collapsedGroups = $state<Record<string, boolean>>({});

  #opts: CalendarAgendaOptions;

  constructor(opts: CalendarAgendaOptions) {
    this.#opts = opts;
  }

  /** Fetch the calendar. Call from a component $effect that also reads
   *  `$libraryChanged`, so it reloads when the library does. */
  async load(): Promise<void> {
    this.loading = true;
    try {
      this.items = await api.libraryCalendar();
    } catch {
      this.items = [];
    } finally {
      this.loading = false;
    }
  }

  days = $derived(groupByDay(this.items));
  summary = $derived(calendarSummary(this.items));
  label = $derived(summaryLabel(this.summary));
  chips = $derived.by(() => nextUp(this.items, this.#opts.chipLimit));
  isEmpty = $derived(!this.loading && this.items.length === 0);

  visibleDays = $derived.by(() => {
    const availDays = this.days.filter((d) => d.key === "available");
    const dateDays = this.days.filter((d) => d.key !== "available");
    if (this.showAllDays || dateDays.length <= MAX_DAYS_INITIAL) {
      return [...availDays, ...dateDays];
    }
    return [...availDays, ...dateDays.slice(0, MAX_DAYS_INITIAL)];
  });

  hiddenDayCount = $derived.by(() => {
    const dateDays = this.days.filter((d) => d.key !== "available");
    return Math.max(0, dateDays.length - MAX_DAYS_INITIAL);
  });

  /** Collapse/expand one day group. The TV shell wraps this to also restore
   *  D-pad focus to the group header it just collapsed. */
  toggleGroup(key: string): void {
    this.collapsedGroups[key] = !this.collapsedGroups[key];
  }
}
