import { beforeEach, describe, expect, it, vi } from "vitest";

const settingsMock = vi.hoisted(() => ({
  current: { uiLanguage: "" },
  load: vi.fn(),
  save: vi.fn(),
}));

vi.mock("$lib/stores/settings", () => ({
  settings: {
    load: settingsMock.load,
    save: settingsMock.save,
    getCurrent: () => settingsMock.current,
  },
}));

import {
  activeLocale,
  activateLocale,
  initializeLocalization,
  intlLocale,
  normalizeAppLocale,
  resolveInitialLocale,
} from "$lib/i18n";

describe("localization", () => {
  beforeEach(() => {
    settingsMock.current = { uiLanguage: "" };
    settingsMock.load.mockReset().mockResolvedValue(undefined);
    settingsMock.save.mockReset().mockResolvedValue(true);
    activateLocale("en");
  });

  it.each([
    ["tr-TR", "tr"],
    ["TR_tr", "tr"],
    ["en-US", "en"],
    ["de-DE", null],
    ["", null],
    [undefined, null],
  ])("normalizes %j to %j", (input, expected) => {
    expect(normalizeAppLocale(input)).toBe(expected);
  });

  it("uses a saved locale before browser preferences", () => {
    expect(resolveInitialLocale("en", ["tr-TR"])).toBe("en");
  });

  it("uses the first supported browser language and falls back to English", () => {
    expect(resolveInitialLocale("", ["de-DE", "tr-TR", "en-US"])).toBe("tr");
    expect(resolveInitialLocale("", ["de-DE"])).toBe("en");
  });

  it("persists and activates the first-run device locale", async () => {
    Object.defineProperty(navigator, "languages", {
      configurable: true,
      value: ["tr-TR", "en-US"],
    });

    await expect(initializeLocalization()).resolves.toBe("tr");

    expect(settingsMock.save).toHaveBeenCalledWith({ uiLanguage: "tr" });
    expect(activeLocale()).toBe("tr");
    expect(intlLocale()).toBe("tr-TR");
    expect(document.documentElement.lang).toBe("tr");
    expect(document.documentElement.dir).toBe("ltr");
  });

  it("falls back consistently to English when first-run persistence fails", async () => {
    Object.defineProperty(navigator, "languages", {
      configurable: true,
      value: ["tr-TR"],
    });
    settingsMock.save.mockResolvedValue(false);

    await expect(initializeLocalization()).resolves.toBe("en");
    expect(activeLocale()).toBe("en");
  });
});
