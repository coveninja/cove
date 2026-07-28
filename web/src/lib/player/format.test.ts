import { describe, expect, it } from "vitest";
import { formatPlaybackTime } from "$lib/player/format";

describe("formatPlaybackTime", () => {
  it.each([
    [0, "0:00"],
    [65.9, "1:05"],
    [3_661, "1:01:01"],
    [Number.NaN, "0:00"],
    [-1, "0:00"],
  ])("formats %s as %s", (seconds, expected) => {
    expect(formatPlaybackTime(seconds)).toBe(expected);
  });
});
