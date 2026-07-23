import { beforeEach, describe, expect, it } from "vitest";

import { loadShowTrackPrefs, saveShowTrackPrefs } from "./trackPrefs";

const MEDIA_ID = 12345;
const KEY = `cove-trackprefs:${MEDIA_ID}`;

beforeEach(() => {
  localStorage.clear();
});

describe("loadShowTrackPrefs", () => {
  it("returns an empty object when no prefs are stored", () => {
    expect(loadShowTrackPrefs(MEDIA_ID)).toEqual({});
  });

  it("parses stored prefs correctly", () => {
    const prefs = {
      speed: 1.5,
      audioLang: "en",
      sub: { kind: "lang", lang: "fr" },
    };
    localStorage.setItem(KEY, JSON.stringify(prefs));
    expect(loadShowTrackPrefs(MEDIA_ID)).toEqual(prefs);
  });

  it("returns empty object for corrupted JSON", () => {
    localStorage.setItem(KEY, "not-json{{{");
    expect(loadShowTrackPrefs(MEDIA_ID)).toEqual({});
  });

  it("returns empty object when stored value is a primitive", () => {
    localStorage.setItem(KEY, "42");
    expect(loadShowTrackPrefs(MEDIA_ID)).toEqual({});
  });
});

describe("saveShowTrackPrefs", () => {
  it("persists prefs that can be read back", () => {
    saveShowTrackPrefs(MEDIA_ID, { speed: 2 });
    expect(loadShowTrackPrefs(MEDIA_ID)).toEqual({ speed: 2 });
  });

  it("merges a partial patch onto the existing record", () => {
    saveShowTrackPrefs(MEDIA_ID, { speed: 1.5, audioLang: "en" });
    saveShowTrackPrefs(MEDIA_ID, { audioLang: "ja" });
    expect(loadShowTrackPrefs(MEDIA_ID)).toEqual({
      speed: 1.5,
      audioLang: "ja",
    });
  });

  it("stores sub: off correctly", () => {
    saveShowTrackPrefs(MEDIA_ID, { sub: { kind: "off" } });
    expect(loadShowTrackPrefs(MEDIA_ID)).toEqual({ sub: { kind: "off" } });
  });

  it("uses a per-media key so different titles don't collide", () => {
    saveShowTrackPrefs(1, { speed: 1.25 });
    saveShowTrackPrefs(2, { speed: 2 });
    expect(loadShowTrackPrefs(1).speed).toBe(1.25);
    expect(loadShowTrackPrefs(2).speed).toBe(2);
  });
});
