import { describe, expect, it } from "vitest";

import { computeChapterBars, segmentBgClass } from "./chapters";
import type { TimestampData } from "$lib/types/addons";

const DURATION = 1200; // 20 minutes in seconds

describe("computeChapterBars", () => {
  it("returns null when timestamps is null", () => {
    expect(computeChapterBars(null, DURATION)).toBeNull();
  });

  it("returns null when duration is zero", () => {
    const ts: TimestampData = { intro: [{ start_ms: 0, end_ms: 30_000 }] };
    expect(computeChapterBars(ts, 0)).toBeNull();
  });

  it("returns null when timestamps has no named segments", () => {
    const ts: TimestampData = {};
    expect(computeChapterBars(ts, DURATION)).toBeNull();
  });

  it("produces content bars around a single intro segment", () => {
    // 20 min episode, intro from 0:30 to 1:30 (30_000–90_000 ms)
    const ts: TimestampData = {
      intro: [{ start_ms: 30_000, end_ms: 90_000 }],
    };
    const durMs = DURATION * 1000;
    const bars = computeChapterBars(ts, DURATION);
    expect(bars).not.toBeNull();
    expect(bars!.length).toBe(3);

    const [pre, intro, post] = bars!;
    expect(pre.type).toBe("content");
    expect(pre.startFrac).toBeCloseTo(0);
    expect(pre.endFrac).toBeCloseTo(30_000 / durMs);

    expect(intro.type).toBe("intro");
    expect(intro.startFrac).toBeCloseTo(30_000 / durMs);
    expect(intro.endFrac).toBeCloseTo(90_000 / durMs);

    expect(post.type).toBe("content");
    expect(post.startFrac).toBeCloseTo(90_000 / durMs);
    expect(post.endFrac).toBeCloseTo(1);
  });

  it("handles multiple named segment types sorted by start time", () => {
    const ts: TimestampData = {
      credits: [{ start_ms: 1_100_000, end_ms: 1_200_000 }],
      recap: [{ start_ms: 0, end_ms: 30_000 }],
    };
    const bars = computeChapterBars(ts, DURATION);
    expect(bars).not.toBeNull();
    // Expected: recap | content | credits
    expect(bars!.map((b) => b.type)).toEqual(["recap", "content", "credits"]);
  });

  it("returns null when the result would be a single-bar timeline", () => {
    // One intro that spans the entire duration produces exactly one bar — useless.
    const durMs = DURATION * 1000;
    const ts: TimestampData = {
      intro: [{ start_ms: 0, end_ms: durMs }],
    };
    expect(computeChapterBars(ts, DURATION)).toBeNull();
  });

  it("clamps segment end fraction to 1 when end_ms exceeds duration", () => {
    const durMs = DURATION * 1000;
    const ts: TimestampData = {
      credits: [{ start_ms: durMs - 10_000, end_ms: durMs + 50_000 }],
    };
    const bars = computeChapterBars(ts, DURATION);
    expect(bars).not.toBeNull();
    const credits = bars!.find((b) => b.type === "credits");
    expect(credits!.endFrac).toBeLessThanOrEqual(1);
  });

  it("handles a segment starting at time zero with no leading content bar", () => {
    const ts: TimestampData = {
      recap: [{ start_ms: 0, end_ms: 30_000 }],
    };
    const bars = computeChapterBars(ts, DURATION);
    expect(bars).not.toBeNull();
    expect(bars![0].type).toBe("recap");
    expect(bars![0].startFrac).toBe(0);
  });
});

describe("segmentBgClass", () => {
  it("returns the intro colour class", () => {
    expect(segmentBgClass("intro")).toBe("bg-amber-400/50");
  });

  it("returns the recap colour class", () => {
    expect(segmentBgClass("recap")).toBe("bg-blue-400/50");
  });

  it("returns the credits colour class", () => {
    expect(segmentBgClass("credits")).toBe("bg-purple-400/50");
  });

  it("returns the preview colour class", () => {
    expect(segmentBgClass("preview")).toBe("bg-green-400/50");
  });

  it("returns an empty string for content", () => {
    expect(segmentBgClass("content")).toBe("");
  });
});
