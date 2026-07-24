import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  calendarSummary,
  dayLabel,
  groupByDay,
  nextUp,
  shortDateLabel,
  summaryLabel,
} from "$lib/calendar";
import type { CalendarItem } from "$lib/types/calendar";

function item(
  id: number,
  kind: "available" | "episode" | "movie",
  date: string,
): CalendarItem {
  return {
    date,
    kind,
    tmdb_id: id,
    media_type: kind === "movie" ? "movie" : "tv",
    title: `Title ${id}`,
    poster_path: "",
    episode_name: "",
    still_path: "",
    waiting_count: 0,
  };
}

describe("calendar helpers", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 6, 20, 12));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("summarizes available, today, seven-day, and all upcoming items", () => {
    const items = [
      item(1, "available", "2026-07-18"),
      item(2, "available", "2026-07-19"),
      item(3, "episode", "2026-07-20"),
      item(4, "episode", "2026-07-26"),
      item(5, "movie", "2026-07-27"),
      item(6, "movie", "2026-08-20"),
    ];

    expect(calendarSummary(items)).toEqual({
      available: 2,
      today: 1,
      thisWeek: 2,
      upcoming: 4,
    });
  });

  it.each([
    [
      { available: 2, today: 1, thisWeek: 4, upcoming: 8 },
      "2 ready to watch · 1 today · 4 this week",
    ],
    [{ available: 0, today: 0, thisWeek: 0, upcoming: 12 }, "12 upcoming"],
    [{ available: 0, today: 0, thisWeek: 0, upcoming: 0 }, "Nothing scheduled"],
  ])("formats summary omission rules", (summary, expected) => {
    expect(summaryLabel(summary)).toBe(expected);
  });

  it("prioritizes available items, preserves source order, and clamps limits", () => {
    const futureOne = item(1, "episode", "2026-07-21");
    const availableOne = item(2, "available", "2026-07-19");
    const futureTwo = item(3, "movie", "2026-07-22");
    const availableTwo = item(4, "available", "2026-07-18");
    const items = [futureOne, availableOne, futureTwo, availableTwo];

    expect(nextUp(items, 3)).toEqual([availableOne, availableTwo, futureOne]);
    expect(nextUp(items, 0)).toEqual([]);
    expect(nextUp(items, -1)).toEqual([]);
    expect(items).toEqual([futureOne, availableOne, futureTwo, availableTwo]);
  });

  it("groups interleaved items with a single leading available section", () => {
    const firstFuture = item(1, "episode", "2026-07-21");
    const firstAvailable = item(2, "available", "2026-07-19");
    const sameDay = item(3, "movie", "2026-07-21");
    const secondAvailable = item(4, "available", "2026-07-18");
    const later = item(5, "episode", "2026-07-23");

    const groups = groupByDay([
      firstFuture,
      firstAvailable,
      sameDay,
      secondAvailable,
      later,
    ]);

    expect(groups.map((group) => group.key)).toEqual([
      "available",
      "2026-07-21",
      "2026-07-23",
    ]);
    expect(groups[0]).toMatchObject({
      label: "Available Now",
      items: [firstAvailable, secondAvailable],
    });
    expect(groups[1].items).toEqual([firstFuture, sameDay]);
    expect(groups[2].items).toEqual([later]);
  });

  it("labels today, tomorrow, nearby weekdays, and distant dates", () => {
    expect(shortDateLabel("2026-07-20")).toBe("Today");
    expect(shortDateLabel("2026-07-21")).toBe("Tomorrow");
    expect(shortDateLabel("2026-07-23")).toBe(
      new Date("2026-07-23T00:00:00").toLocaleDateString(undefined, {
        weekday: "short",
      }),
    );
    expect(shortDateLabel("2026-08-20")).toBe(
      new Date("2026-08-20T00:00:00").toLocaleDateString(undefined, {
        month: "short",
        day: "numeric",
      }),
    );

    const todayMonthDay = new Date("2026-07-20T00:00:00").toLocaleDateString(
      undefined,
      {
        month: "short",
        day: "numeric",
      },
    );
    expect(dayLabel("2026-07-20")).toBe(`Today · ${todayMonthDay}`);
    expect(dayLabel("2026-07-21")).toBe("Tomorrow");
    expect(dayLabel("2026-07-23")).toContain(" · ");
  });
});
