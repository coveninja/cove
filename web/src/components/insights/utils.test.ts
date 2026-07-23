import { describe, expect, it } from "vitest";

import { fmtHours } from "./utils";

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
