import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({ libraryCalendar: vi.fn() }));

vi.mock("$lib/api", () => ({
  api: { libraryCalendar: mocks.libraryCalendar },
}));

import {
  CalendarAgendaController,
  chipSublabel,
  dayOfMonth,
  toMedia,
} from "$lib/calendarAgenda.svelte";
import type { CalendarItem } from "$lib/types/calendar";

function upcoming(date: string, over: Partial<CalendarItem> = {}): CalendarItem {
  return {
    date,
    kind: "episode",
    tmdb_id: 1396,
    media_type: "tv",
    title: "Breaking Bad",
    poster_path: "/bb.jpg",
    episode_name: "Pilot",
    still_path: "",
    waiting_count: 0,
    ...over,
  } as CalendarItem;
}

function available(over: Partial<CalendarItem> = {}): CalendarItem {
  return upcoming("2026-07-01", { kind: "available", ...over });
}

/** N upcoming items on N distinct dates. */
function days(n: number): CalendarItem[] {
  return Array.from({ length: n }, (_, i) =>
    upcoming(`2026-08-${String(i + 1).padStart(2, "0")}`),
  );
}

function make(chipLimit = 12): CalendarAgendaController {
  let ctl!: CalendarAgendaController;
  $effect.root(() => {
    ctl = new CalendarAgendaController({ chipLimit });
  });
  return ctl;
}

beforeEach(() => {
  mocks.libraryCalendar.mockReset().mockResolvedValue([]);
});

describe("toMedia", () => {
  it("maps a TV row onto `name`", () => {
    const media = toMedia(available({ media_type: "tv", title: "Severance" }));
    expect(media.media_type).toBe("tv");
    expect(media.name).toBe("Severance");
  });

  it("maps a movie row onto `title`", () => {
    const media = toMedia(
      available({ media_type: "movie", title: "Dune", tmdb_id: 438631 }),
    );
    expect(media.media_type).toBe("movie");
    expect(media.title).toBe("Dune");
    expect(media.id).toBe(438631);
  });
});

describe("chipSublabel", () => {
  it("shows the episode code for an available episode", () => {
    expect(
      chipSublabel(available({ season_number: 2, episode_number: 7 })),
    ).toBe("S2E7");
  });

  it("falls back to 'Watch' for an available item with no episode numbers", () => {
    expect(chipSublabel(available({ media_type: "movie" }))).toBe("Watch");
  });

  it("shows a short date for an upcoming item", () => {
    const label = chipSublabel(upcoming("2026-08-15"));
    expect(label).not.toBe("Watch");
    expect(label.length).toBeGreaterThan(0);
  });
});

describe("dayOfMonth", () => {
  it("reads the day out of a YYYY-MM-DD key in local time", () => {
    expect(dayOfMonth("2026-08-15")).toBe(15);
    expect(dayOfMonth("2026-01-01")).toBe(1);
  });
});

describe("load", () => {
  it("stores the fetched items and clears loading", async () => {
    mocks.libraryCalendar.mockResolvedValue([upcoming("2026-08-01")]);
    const ctl = make();
    await ctl.load();
    expect(ctl.items).toHaveLength(1);
    expect(ctl.loading).toBe(false);
  });

  it("falls back to an empty timeline when the request fails", async () => {
    mocks.libraryCalendar.mockRejectedValue(new Error("offline"));
    const ctl = make();
    await ctl.load();
    expect(ctl.items).toEqual([]);
    expect(ctl.loading).toBe(false);
  });

  it("reports empty only once loading has finished", async () => {
    const ctl = make();
    expect(ctl.isEmpty).toBe(false); // still loading
    await ctl.load();
    expect(ctl.isEmpty).toBe(true);
  });
});

describe("day windowing", () => {
  it("shows every day when there are seven or fewer", () => {
    const ctl = make();
    ctl.items = days(7);
    expect(ctl.visibleDays).toHaveLength(7);
    expect(ctl.hiddenDayCount).toBe(0);
  });

  it("caps at seven date groups and reports the remainder", () => {
    const ctl = make();
    ctl.items = days(10);
    expect(ctl.visibleDays).toHaveLength(7);
    expect(ctl.hiddenDayCount).toBe(3);
  });

  it("always keeps 'Available Now' outside the cap", () => {
    const ctl = make();
    ctl.items = [available(), ...days(10)];
    expect(ctl.visibleDays).toHaveLength(8);
    expect(ctl.visibleDays[0].key).toBe("available");
    // The available group is not counted against the seven-day window.
    expect(ctl.hiddenDayCount).toBe(3);
  });

  it("expands to the full list once showAllDays is set", () => {
    const ctl = make();
    ctl.items = days(10);
    ctl.showAllDays = true;
    expect(ctl.visibleDays).toHaveLength(10);
  });
});

describe("chips", () => {
  it("honours the shell's chip limit", () => {
    const wide = make(12);
    const narrow = make(10);
    wide.items = days(20);
    narrow.items = days(20);
    expect(wide.chips).toHaveLength(12);
    expect(narrow.chips).toHaveLength(10);
  });
});

describe("toggleGroup", () => {
  it("flips a group closed and open again", () => {
    const ctl = make();
    expect(ctl.collapsedGroups["2026-08-01"]).toBeUndefined();
    ctl.toggleGroup("2026-08-01");
    expect(ctl.collapsedGroups["2026-08-01"]).toBe(true);
    ctl.toggleGroup("2026-08-01");
    expect(ctl.collapsedGroups["2026-08-01"]).toBe(false);
  });

  it("tracks groups independently", () => {
    const ctl = make();
    ctl.toggleGroup("a");
    expect(ctl.collapsedGroups["a"]).toBe(true);
    expect(ctl.collapsedGroups["b"]).toBeUndefined();
  });
});
