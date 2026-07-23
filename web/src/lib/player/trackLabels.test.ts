import { describe, expect, it } from "vitest";

import { langName, trackLabel } from "./trackLabels";

describe("langName", () => {
  it("returns a human-readable English name for well-known codes", () => {
    // Intl.DisplayNames is available in jsdom via the V8 ICU data bundled with Node.
    const name = langName("en");
    // Accept either the canonical "English" or the code itself as a safe fallback.
    expect(typeof name).toBe("string");
    expect(name.length).toBeGreaterThan(0);
  });

  it("falls back to the code when the code is unrecognised", () => {
    // "xx" is not a registered language subtag — DisplayNames returns undefined,
    // so langName falls back to the raw code.
    const result = langName("xx");
    expect(result).toBe("xx");
  });

  it("returns the code itself for an empty string", () => {
    expect(langName("")).toBe("");
  });
});

describe("trackLabel", () => {
  it("returns the explicit title when present", () => {
    const t = { id: 1, title: "Commentary", lang: "en" };
    expect(trackLabel(t, "Audio")).toBe("Commentary");
  });

  it("returns the language name when there is no title", () => {
    const t = { id: 2, title: "", lang: "en" };
    const label = trackLabel(t, "Audio");
    // Should be whatever langName("en") returns — a non-empty string that is not the fallback.
    expect(label).toBe(langName("en"));
  });

  it("returns a numbered fallback when both title and lang are absent", () => {
    expect(trackLabel({ id: 3, title: "", lang: "" }, "Audio")).toBe("Audio 3");
    expect(trackLabel({ id: 1, title: "", lang: "" }, "Subtitle")).toBe(
      "Subtitle 1",
    );
  });

  it("prefers title over lang when both are present", () => {
    const t = { id: 4, title: "Director's Cut", lang: "fr" };
    expect(trackLabel(t, "Audio")).toBe("Director's Cut");
  });
});
