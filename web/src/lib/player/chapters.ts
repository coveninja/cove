// Seek-bar chapter segmentation from IntroDB timestamps, shared by the desktop
// Player (which computes it) and its SeekBar (which colours it).

import type { TimestampData, TimestampSegment } from "$lib/types/addons";

export type ChapterBar = {
  startFrac: number;
  endFrac: number;
  type: "content" | "intro" | "recap" | "credits" | "preview";
};

// Splits the timeline into content + named segment chapters whenever we have
// both timestamp data and a known duration. Returns null when a unified bar is
// needed (no data, or all segments collapse to a single chapter).
export function computeChapterBars(
  timestamps: TimestampData | null,
  durationSec: number,
): ChapterBar[] | null {
  if (!timestamps) return null;
  if (!durationSec) return null;
  const durMs = durationSec * 1000;

  const named: { startMs: number; endMs: number; type: string }[] = [];
  const addAll = (arr: TimestampSegment[] | undefined, type: string) =>
    arr?.forEach((s) =>
      named.push({ startMs: s.start_ms ?? 0, endMs: s.end_ms ?? durMs, type }),
    );
  addAll(timestamps.intro, "intro");
  addAll(timestamps.recap, "recap");
  addAll(timestamps.credits, "credits");
  addAll(timestamps.preview, "preview");
  if (named.length === 0) return null;

  named.sort((a, b) => a.startMs - b.startMs);

  const bars: ChapterBar[] = [];
  let pos = 0;
  for (const seg of named) {
    if (seg.startMs > pos)
      bars.push({ startFrac: pos / durMs, endFrac: seg.startMs / durMs, type: "content" });
    bars.push({
      startFrac: seg.startMs / durMs,
      endFrac: Math.min(seg.endMs / durMs, 1),
      type: seg.type as ChapterBar["type"],
    });
    pos = seg.endMs;
  }
  if (pos < durMs) bars.push({ startFrac: pos / durMs, endFrac: 1, type: "content" });

  return bars.length > 1 ? bars : null;
}

export function segmentBgClass(type: ChapterBar["type"]): string {
  switch (type) {
    case "intro":
      return "bg-amber-400/50";
    case "recap":
      return "bg-blue-400/50";
    case "credits":
      return "bg-purple-400/50";
    case "preview":
      return "bg-green-400/50";
    default:
      return "";
  }
}
