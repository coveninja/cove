import { afterEach, describe, expect, it } from "vitest";
import english from "../../messages/en.json";
import turkish from "../../messages/tr.json";
import { activateLocale, languageDisplayName, regionDisplayName } from "$lib/i18n";
import * as m from "$lib/paraglide/messages.js";

function messageKeys(catalog: Record<string, string>): string[] {
  return Object.keys(catalog)
    .filter((key) => key !== "$schema")
    .sort();
}

describe("message catalogs", () => {
  afterEach(() => activateLocale("en"));

  it("keeps English and Turkish catalogs in parity with no empty messages", () => {
    expect(messageKeys(turkish)).toEqual(messageKeys(english));
    for (const [key, value] of Object.entries(turkish)) {
      if (key !== "$schema") expect(value.trim(), key).not.toBe("");
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
});
