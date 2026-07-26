import { afterEach, describe, expect, it } from "vitest";
import german from "../../messages/de.json";
import english from "../../messages/en.json";
import spanish from "../../messages/es.json";
import italian from "../../messages/it.json";
import japanese from "../../messages/ja.json";
import portuguese from "../../messages/pt.json";
import turkish from "../../messages/tr.json";
import {
  activateLocale,
  languageDisplayName,
  regionDisplayName,
  type AppLocale,
} from "$lib/i18n";
import * as m from "$lib/paraglide/messages.js";

function messageKeys(catalog: Record<string, string>): string[] {
  return Object.keys(catalog)
    .filter((key) => key !== "$schema")
    .sort();
}

describe("message catalogs", () => {
  afterEach(() => activateLocale("en"));

  it("keeps every translated catalog in parity with no empty messages", () => {
    for (const catalog of [
      turkish,
      portuguese,
      spanish,
      italian,
      german,
      japanese,
    ]) {
      expect(messageKeys(catalog)).toEqual(messageKeys(english));
      for (const [key, value] of Object.entries(catalog)) {
        if (key !== "$schema") expect(value.trim(), key).not.toBe("");
      }
    }
  });

  it("renders Turkish messages and Intl display names after activation", () => {
    activateLocale("tr");

    expect(m.settings_title()).toBe("Ayarlar");
    expect(m.onboarding_language()).toBe("Dilinizi Seçin");
    expect(m.onboarding_selected_count({ count: 3 })).toBe("3 seçildi");
    expect(m.explore_genre_movies({ genre: "Aksiyon" })).toBe(
      "Aksiyon filmleri",
    );
    expect(m.explore_genre_shows({ genre: "Dram" })).toBe("Dram dizileri");
    expect(m.common_season_episode({ season: 2, episode: 4 })).toBe("S2B4");
    expect(languageDisplayName("de")).toMatch(/Almanca/i);
    expect(regionDisplayName("US")).toMatch(/Amerika Birleşik Devletleri/i);
  });

  it("renders Portuguese messages and Intl display names after activation", () => {
    activateLocale("pt");

    expect(m.settings_title()).toBe("Configurações");
    expect(m.onboarding_language()).toBe("Escolha seu idioma");
    expect(m.onboarding_selected_count({ count: 3 })).toBe("3 selecionados");
    expect(m.explore_genre_movies({ genre: "Ação" })).toBe("Filmes de Ação");
    expect(m.explore_genre_shows({ genre: "Drama" })).toBe("Séries de Drama");
    expect(m.common_season_episode({ season: 2, episode: 4 })).toBe("T2E4");
    expect(languageDisplayName("de")).toMatch(/alemão/i);
    expect(regionDisplayName("US")).toMatch(/Estados Unidos/i);
  });

  it.each([
    {
      locale: "es",
      settings: "Ajustes",
      onboarding: "Elige tu idioma",
      seasonEpisode: "T2E4",
      englishName: /inglés/i,
      usName: /Estados Unidos/i,
    },
    {
      locale: "it",
      settings: "Impostazioni",
      onboarding: "Scegli la tua lingua",
      seasonEpisode: "S2E4",
      englishName: /inglese/i,
      usName: /Stati Uniti/i,
    },
    {
      locale: "de",
      settings: "Einstellungen",
      onboarding: "Wähle deine Sprache",
      seasonEpisode: "S2E4",
      englishName: /Englisch/i,
      usName: /Vereinigte Staaten/i,
    },
    {
      locale: "ja",
      settings: "設定",
      onboarding: "言語を選択",
      seasonEpisode: "S2E4",
      englishName: /英語/,
      usName: /アメリカ合衆国/,
    },
  ] as const)(
    "renders representative $locale messages and Intl display names",
    ({ locale, settings, onboarding, seasonEpisode, englishName, usName }) => {
      activateLocale(locale as AppLocale);

      expect(m.settings_title()).toBe(settings);
      expect(m.onboarding_language()).toBe(onboarding);
      expect(m.common_season_episode({ season: 2, episode: 4 })).toBe(
        seasonEpisode,
      );
      expect(languageDisplayName("en")).toMatch(englishName);
      expect(regionDisplayName("US")).toMatch(usName);
    },
  );
});
