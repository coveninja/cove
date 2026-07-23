import { beforeEach, describe, expect, it, vi } from "vitest";

// Mock the player module to avoid importing the full Qt WebChannel bridge and
// its Svelte rune class-field initialisation.
vi.mock("./player.svelte", () => ({
  ASPECT_MODES: ["fit", "fill", "stretch", "zoom"],
}));

import { loadAspectMode, saveAspectMode } from "./aspectRatio";

beforeEach(() => {
  localStorage.clear();
});

describe("loadAspectMode", () => {
  it('returns "fit" when nothing is stored', () => {
    expect(loadAspectMode(100)).toBe("fit");
  });

  it("returns the stored mode", () => {
    localStorage.setItem("cove-aspect:100", "fill");
    expect(loadAspectMode(100)).toBe("fill");
  });

  it('returns "fit" when the stored value is not a valid AspectMode', () => {
    localStorage.setItem("cove-aspect:100", "widescreen");
    expect(loadAspectMode(100)).toBe("fit");
  });

  it("is keyed per media id so different titles are independent", () => {
    localStorage.setItem("cove-aspect:1", "stretch");
    localStorage.setItem("cove-aspect:2", "zoom");
    expect(loadAspectMode(1)).toBe("stretch");
    expect(loadAspectMode(2)).toBe("zoom");
  });

  it('returns "fit" when localStorage throws', () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("Storage unavailable");
    });
    expect(loadAspectMode(999)).toBe("fit");
  });
});

describe("saveAspectMode", () => {
  it("persists the mode so loadAspectMode reads it back", () => {
    saveAspectMode(200, "zoom");
    expect(loadAspectMode(200)).toBe("zoom");
  });

  it('stores "fit" explicitly so it round-trips like any other mode', () => {
    saveAspectMode(200, "fill");
    saveAspectMode(200, "fit");
    expect(loadAspectMode(200)).toBe("fit");
  });

  it("does not throw when localStorage throws on setItem", () => {
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("QuotaExceededError");
    });
    expect(() => saveAspectMode(300, "fill")).not.toThrow();
  });
});
