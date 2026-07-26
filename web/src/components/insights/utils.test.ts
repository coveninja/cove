import { describe, expect, it } from "vitest";

import {
  activityDayLabels,
  activityHourLabels,
  activityMonthLabels,
  activityWeekdayLabels,
  buildActivityCalendar,
  fmtHours,
} from "./utils";

describe("fmtHours", () => {
  it.each([
    [Number.NaN, "<1m"],
    [Number.POSITIVE_INFINITY, "<1m"],
    [-1, "<1m"],
    [0, "<1m"],
    [59, "<1m"],
    [60, "1m"],
    [3599, "59m"],
    [3600, "1h"],
    [3660, "1h 1m"],
    [7325, "2h 2m"],
  ])("formats %s seconds as %s", (seconds, expected) => {
    expect(fmtHours(seconds)).toBe(expected);
  });
});

describe("activity calendar geometry", () => {
  it("keeps month-label week keys unique at a range-start boundary", () => {
    // The range begins on 2024-06-30, so June and July both start in week 0.
    const data = buildActivityCalendar({}, new Date(2025, 5, 29), "en-US");
    const weekKeys = data.monthLabels.map((label) => label.weekIdx);

    expect(new Set(weekKeys).size).toBe(weekKeys.length);
    expect(data.monthLabels.filter((label) => label.weekIdx === 0)).toEqual([
      { weekIdx: 0, name: "Jul" },
    ]);
  });

  it("localizes the visible weekday labels", () => {
    expect(activityWeekdayLabels("tr-TR")).toEqual([
      "",
      "P",
      "",
      "Ç",
      "",
      "C",
      "",
    ]);
  });

  it("localizes activity chart axes", () => {
    expect(activityMonthLabels("tr-TR").slice(0, 3)).toEqual([
      "Oca",
      "Şub",
      "Mar",
    ]);
    expect(activityDayLabels("tr-TR")).toEqual([
      "Paz",
      "Pzt",
      "Sal",
      "Çar",
      "Per",
      "Cum",
      "Cmt",
    ]);
    expect(activityHourLabels("tr-TR").slice(0, 2)).toEqual(["00", "01"]);
  });
});
