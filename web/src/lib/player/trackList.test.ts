import { describe, expect, it } from "vitest";

import {
  groupByLang,
  sortAudioTracks,
  subtitleItems,
  subtitleRows,
  type ExternalSubtitle,
} from "$lib/player/trackList";
import type { MpvTrack } from "$lib/player/player.svelte";

function track(
  id: number,
  over: Partial<MpvTrack> = {},
): MpvTrack {
  return { id, type: "sub", title: "", lang: "", selected: false, ...over };
}

function ext(id: string, lang: string): ExternalSubtitle {
  return { id, lang, url: `https://subs.test/${id}.srt` };
}

describe("sortAudioTracks", () => {
  it("orders by display label, not by track id", () => {
    const sorted = sortAudioTracks([
      track(3, { type: "audio", title: "Zulu" }),
      track(1, { type: "audio", title: "Alpha" }),
    ]);
    expect(sorted.map((t) => t.title)).toEqual(["Alpha", "Zulu"]);
  });

  it("leaves the input array untouched", () => {
    const input = [
      track(3, { type: "audio", title: "Zulu" }),
      track(1, { type: "audio", title: "Alpha" }),
    ];
    sortAudioTracks(input);
    expect(input.map((t) => t.id)).toEqual([3, 1]);
  });
});

describe("subtitleItems", () => {
  it("always leads with an Off entry", () => {
    const items = subtitleItems([], []);
    expect(items).toHaveLength(1);
    expect(items[0].kind).toBe("off");
  });

  it("lists embedded tracks before external ones", () => {
    const items = subtitleItems(
      [track(1, { title: "Forced" })],
      [ext("os-1", "en")],
    );
    expect(items.map((i) => i.kind)).toEqual(["off", "embedded", "external"]);
  });

  it("labels an embedded track by title, then language, then id", () => {
    const items = subtitleItems(
      [track(1, { title: "Forced" }), track(2, { lang: "de" }), track(3)],
      [],
    );
    const labels = items.slice(1).map((i) => i.label);
    expect(labels[0]).toBe("Forced");
    expect(labels[1]).not.toBe("");
    expect(labels[2]).toContain("3");
  });

  it("marks external subtitles as coming from OpenSubtitles", () => {
    const items = subtitleItems([], [ext("os-1", "en")]);
    expect(items[1].label).toContain("OpenSubtitles");
  });
});

describe("groupByLang", () => {
  it("groups entries sharing a language", () => {
    const groups = groupByLang([
      { lang: "English", item: "a" },
      { lang: "German", item: "b" },
      { lang: "English", item: "c" },
    ]);
    expect(groups).toHaveLength(2);
    expect(groups.find((g) => g.label === "English")?.items).toEqual(["a", "c"]);
  });

  it("sorts languages alphabetically", () => {
    const groups = groupByLang([
      { lang: "German", item: 1 },
      { lang: "Arabic", item: 2 },
    ]);
    expect(groups.map((g) => g.label)).toEqual(["Arabic", "German"]);
  });

  it("buckets untagged entries together and sorts that bucket last", () => {
    const groups = groupByLang([
      { lang: "", item: 1 },
      { lang: "German", item: 2 },
      { lang: "", item: 3 },
    ]);
    expect(groups).toHaveLength(2);
    expect(groups[0].label).toBe("German");
    expect(groups[1].items).toEqual([1, 3]);
  });

  it("returns nothing for no entries", () => {
    expect(groupByLang([])).toEqual([]);
  });
});

describe("subtitleRows", () => {
  it("is just Off when there are no tracks at all", () => {
    const rows = subtitleRows([], []);
    expect(rows).toHaveLength(1);
    expect(rows[0].id).toBe("off");
  });

  it("adds an Embedded section header only when embedded tracks exist", () => {
    const rows = subtitleRows([track(1, { lang: "en" })], []);
    expect(rows.some((r) => r.id === "hdr-embedded")).toBe(true);
    expect(rows.some((r) => r.id === "hdr-addons")).toBe(false);
  });

  it("adds an Addons section header only when external subs exist", () => {
    const rows = subtitleRows([], [ext("os-1", "en")]);
    expect(rows.some((r) => r.id === "hdr-addons")).toBe(true);
    expect(rows.some((r) => r.id === "hdr-embedded")).toBe(false);
  });

  it("indents a per-language subheader under each source header", () => {
    const rows = subtitleRows([track(1, { lang: "en" })], []);
    const sub = rows.find((r) => r.indent);
    expect(sub).toBeDefined();
    expect(sub?.header).toBe(true);
  });

  it("keeps embedded rows ahead of addon rows", () => {
    const rows = subtitleRows([track(1, { lang: "en" })], [ext("os-1", "de")]);
    const emb = rows.findIndex((r) => r.id === "hdr-embedded");
    const add = rows.findIndex((r) => r.id === "hdr-addons");
    expect(emb).toBeGreaterThan(-1);
    expect(add).toBeGreaterThan(emb);
  });

  it("emits a selectable row per track, keyed by track id", () => {
    const rows = subtitleRows([track(7, { lang: "en", title: "Full" })], []);
    const selectable = rows.filter((r) => !r.header);
    expect(selectable.map((r) => r.id)).toEqual(["off", 7]);
  });
});
