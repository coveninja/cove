import { afterEach, describe, expect, it } from "vitest";
import { activateLocale } from "$lib/i18n";
import { dayLabel, shortDateLabel, summaryLabel } from "$lib/calendar";
import { countryName, formatRuntime } from "$lib/utils";
import type { Details } from "$lib/types/tmdb";

describe("localized formatting", () => {
  afterEach(() => activateLocale("en"));

  it("uses Turkish labels for runtime, countries, and calendar summaries", () => {
    activateLocale("tr");

    expect(formatRuntime({ runtime: 125 } as Details)).toBe("2 sa 5 dk");
    expect(countryName("US")).toMatch(/Amerika Birleşik Devletleri/i);
    expect(
      summaryLabel({ available: 2, today: 1, thisWeek: 3, upcoming: 4 }),
    ).toBe("2 izlenmeye hazır · 1 bugün · 3 bu hafta");
  });

  it("uses Portuguese labels for runtime, countries, and calendar summaries", () => {
    activateLocale("pt");

    expect(formatRuntime({ runtime: 125 } as Details)).toBe("2 h 5 min");
    expect(countryName("US")).toMatch(/Estados Unidos/i);
    expect(
      summaryLabel({ available: 2, today: 1, thisWeek: 3, upcoming: 4 }),
    ).toBe("2 prontos para assistir · 1 hoje · 3 esta semana");
  });

  it("uses the active Intl locale for date labels", () => {
    activateLocale("tr");
    const today = new Date();
    const target = new Date(today.getFullYear(), today.getMonth(), today.getDate() + 1);
    const tomorrow = [
      target.getFullYear(),
      String(target.getMonth() + 1).padStart(2, "0"),
      String(target.getDate()).padStart(2, "0"),
    ].join("-");

    expect(shortDateLabel(tomorrow)).toBe("Yarın");
    expect(dayLabel(tomorrow)).toBe("Yarın");
  });
});
