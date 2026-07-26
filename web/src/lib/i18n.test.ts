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
  localeDefinition,
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
    ["pt-BR", "pt"],
    ["PT_pt", "pt"],
    ["es-MX", "es"],
    ["it-IT", "it"],
    ["DE_de", "de"],
    ["ja-JP", "ja"],
    ["en-US", "en"],
    ["fr-FR", null],
    ["", null],
    [undefined, null],
  ])("normalizes %j to %j", (input, expected) => {
    expect(normalizeAppLocale(input)).toBe(expected);
  });

  it("uses a saved locale before browser preferences", () => {
    expect(resolveInitialLocale("en", ["tr-TR"])).toBe("en");
  });

  it("uses the first supported browser language and falls back to English", () => {
    expect(resolveInitialLocale("", ["fr-FR", "pt-BR", "tr-TR"])).toBe("pt");
    expect(resolveInitialLocale("", ["fr-FR"])).toBe("en");
  });

  it("uses Brazilian Portuguese for localized metadata and Intl formatting", () => {
    activateLocale("pt");

    expect(localeDefinition()).toMatchObject({
      appLocale: "pt",
      tmdbLocale: "pt-BR",
      nativeName: "Português",
    });
    expect(intlLocale()).toBe("pt-BR");
    expect(document.documentElement.lang).toBe("pt");
  });

  it.each([
    ["es", "es-ES", "Español"],
    ["it", "it-IT", "Italiano"],
    ["de", "de-DE", "Deutsch"],
    ["ja", "ja-JP", "日本語"],
  ] as const)(
    "uses the canonical metadata and Intl locale for %s",
    (appLocale, tmdbLocale, nativeName) => {
      activateLocale(appLocale);

      expect(localeDefinition()).toMatchObject({
        appLocale,
        tmdbLocale,
        nativeName,
      });
      expect(intlLocale()).toBe(tmdbLocale);
      expect(document.documentElement.lang).toBe(appLocale);
    },
  );

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
