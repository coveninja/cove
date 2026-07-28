import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  genreList: vi.fn(),
  search: vi.fn(),
  discover: vi.fn(),
  discoverByGenre: vi.fn(),
  libraryUpsert: vi.fn(),
  save: vi.fn(),
  getCurrent: vi.fn(),
  activeLocale: vi.fn(() => "en"),
  activateLocale: vi.fn(),
}));

vi.mock("$lib/api", () => ({ api: mocks }));
vi.mock("$lib/stores/settings", () => ({
  settings: { save: mocks.save, getCurrent: mocks.getCurrent },
}));
vi.mock("$lib/i18n", () => ({
  activeLocale: mocks.activeLocale,
  activateLocale: mocks.activateLocale,
  languageDisplayName: (v: string) => v.toUpperCase(),
}));

import {
  audioLangLabel,
  langLabel,
  OnboardingController,
  STEPS,
} from "$lib/onboarding.svelte";
import type { Media } from "$lib/types/tmdb";

function movie(id: number): Media {
  return { id, media_type: "movie", title: `M${id}` } as Media;
}

function show(id: number): Media {
  return { id, media_type: "tv", name: `T${id}` } as Media;
}

function make(onClose = vi.fn()) {
  let ctl!: OnboardingController;
  $effect.root(() => {
    ctl = new OnboardingController({ onClose });
  });
  return { ctl, onClose };
}

/** Move the wizard to the named step without running any side effects. */
function at(ctl: OnboardingController, id: string): void {
  ctl.stepIndex = STEPS.findIndex((s) => s.id === id);
}

beforeEach(() => {
  vi.useFakeTimers();
  mocks.genreList.mockReset().mockResolvedValue([]);
  mocks.search.mockReset().mockResolvedValue([]);
  mocks.discover.mockReset().mockResolvedValue([]);
  mocks.discoverByGenre.mockReset().mockResolvedValue([]);
  mocks.libraryUpsert.mockReset().mockImplementation(async (e) => e);
  mocks.save.mockReset().mockResolvedValue(true);
  mocks.getCurrent.mockReset().mockReturnValue({
    defaultSubtitleLang: "fr",
    defaultAudioLang: "ja",
    autoPlay: true,
    rememberPosition: false,
    autoSkipIntro: true,
    autoSkipRecap: false,
    autoSkipCredits: true,
    autoSkipPreview: false,
  });
  mocks.activeLocale.mockReturnValue("en");
  mocks.activateLocale.mockReset();
});

describe("labels", () => {
  it("langLabel is the plain display name", () => {
    expect(langLabel("de")).toBe("DE");
  });

  it("audioLangLabel resolves the 'original' pseudo-language", () => {
    expect(audioLangLabel("de")).toBe("DE");
    expect(audioLangLabel("original")).not.toBe("ORIGINAL");
  });
});

describe("preference seeding", () => {
  it("starts from the store's current values", () => {
    const { ctl } = make();
    expect(ctl.subtitleLang).toBe("fr");
    expect(ctl.audioLang).toBe("ja");
    expect(ctl.autoPlay).toBe(true);
    expect(ctl.rememberPosition).toBe(false);
    expect(ctl.autoSkipIntro).toBe(true);
    expect(ctl.autoSkipCredits).toBe(true);
  });
});

describe("step derivations", () => {
  it("starts on welcome and knows it is first", () => {
    const { ctl } = make();
    expect(ctl.step.id).toBe("welcome");
    expect(ctl.isFirst).toBe(true);
    expect(ctl.isLast).toBe(false);
  });

  it("knows the last step", () => {
    const { ctl } = make();
    at(ctl, "done");
    expect(ctl.isLast).toBe(true);
  });

  it("blocks the genres step until at least one genre is picked", () => {
    const { ctl } = make();
    at(ctl, "genres");
    expect(ctl.canProceed).toBe(false);
    ctl.toggleMovieGenre(28);
    expect(ctl.canProceed).toBe(true);
  });

  it("never blocks any other step", () => {
    const { ctl } = make();
    at(ctl, "seen");
    expect(ctl.canProceed).toBe(true);
  });
});

describe("language", () => {
  it("activates the locale and clears any previous save error", () => {
    const { ctl } = make();
    ctl.languageSaveError = true;
    ctl.selectUiLanguage("de");
    expect(ctl.selectedUiLanguage).toBe("de");
    expect(ctl.languageSaveError).toBe(false);
    expect(mocks.activateLocale).toHaveBeenCalledWith("de");
  });

  it("stays on the language step when the save fails", async () => {
    const { ctl } = make();
    at(ctl, "language");
    mocks.save.mockResolvedValue(false);
    await ctl.next();
    expect(ctl.languageSaveError).toBe(true);
    expect(ctl.step.id).toBe("language");
  });
});

describe("genres", () => {
  it("toggles selections on and off", () => {
    const { ctl } = make();
    ctl.toggleMovieGenre(28);
    ctl.toggleTvGenre(18);
    expect([...ctl.selectedMovieGenreIds]).toEqual([28]);
    expect([...ctl.selectedTvGenreIds]).toEqual([18]);
    ctl.toggleMovieGenre(28);
    expect([...ctl.selectedMovieGenreIds]).toEqual([]);
  });

  it("fetches both genre lists once per language", async () => {
    const { ctl } = make();
    await ctl.loadGenres();
    expect(mocks.genreList).toHaveBeenCalledTimes(2);
    await ctl.loadGenres();
    expect(mocks.genreList).toHaveBeenCalledTimes(2);
  });

  it("refetches after the language changes", async () => {
    const { ctl } = make();
    await ctl.loadGenres();
    ctl.selectUiLanguage("de");
    await ctl.loadGenres();
    expect(mocks.genreList).toHaveBeenCalledTimes(4);
  });
});

describe("seen step", () => {
  it("browses generic discovery when no genres are picked", async () => {
    mocks.discover.mockResolvedValue([movie(1)]);
    const { ctl } = make();
    await ctl.loadBrowseMedia();
    expect(mocks.discover).toHaveBeenCalledTimes(2);
    expect(mocks.discoverByGenre).not.toHaveBeenCalled();
    expect(ctl.browseMedia).toHaveLength(2);
  });

  it("browses by genre and dedupes across genre fetches", async () => {
    mocks.discoverByGenre.mockResolvedValue([movie(1), movie(2)]);
    const { ctl } = make();
    ctl.toggleMovieGenre(28);
    ctl.toggleMovieGenre(12);
    await ctl.loadBrowseMedia();
    expect(ctl.browseMedia.map((m) => m.id)).toEqual([1, 2]);
  });

  it("falls back to an empty browse list on failure", async () => {
    mocks.discover.mockRejectedValue(new Error("offline"));
    const { ctl } = make();
    await ctl.loadBrowseMedia();
    expect(ctl.browseMedia).toEqual([]);
    expect(ctl.loadingMedia).toBe(false);
  });

  it("toggles a title in and out of the seen list", () => {
    const { ctl } = make();
    ctl.toggleSeenMedia(movie(1));
    expect(ctl.seenMedia).toHaveLength(1);
    ctl.toggleSeenMedia(movie(1));
    expect(ctl.seenMedia).toHaveLength(0);
  });

  it("keys the seen set by type as well as id", () => {
    const { ctl } = make();
    ctl.toggleSeenMedia(movie(1));
    ctl.toggleSeenMedia(show(1));
    expect(ctl.seenMedia).toHaveLength(2);
  });

  it("debounces the search and shows results while a query is present", async () => {
    mocks.search.mockResolvedValue([movie(9)]);
    const { ctl } = make();
    ctl.browseMedia = [movie(1)];
    ctl.onMediaQueryChange("mat");
    expect(mocks.search).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(350);
    expect(ctl.searchResults).toEqual([movie(9)]);
    expect(ctl.displayMedia.map((m) => m.id)).toEqual([9]);
  });

  it("clears results and falls back to browse when the query empties", async () => {
    const { ctl } = make();
    ctl.browseMedia = [movie(1)];
    ctl.searchResults = [movie(9)];
    ctl.onMediaQueryChange("  ");
    expect(ctl.searchResults).toEqual([]);
    expect(ctl.displayMedia.map((m) => m.id)).toEqual([1]);
    expect(mocks.search).not.toHaveBeenCalled();
  });
});

describe("navigation", () => {
  it("skips the rate step when nothing was marked seen", async () => {
    const { ctl } = make();
    at(ctl, "seen");
    await ctl.next();
    expect(ctl.step.id).toBe("preferences");
    expect(mocks.libraryUpsert).not.toHaveBeenCalled();
  });

  it("upserts every seen title before the rate step", async () => {
    const { ctl } = make();
    at(ctl, "seen");
    ctl.seenMedia = [movie(1), show(2)];
    await ctl.next();
    expect(mocks.libraryUpsert).toHaveBeenCalledTimes(2);
    expect(ctl.seenEntries).toHaveLength(2);
    expect(ctl.step.id).toBe("rate");
    expect(ctl.preparingEntries).toBe(false);
  });

  it("keeps a null entry for a title whose upsert failed", async () => {
    mocks.libraryUpsert.mockRejectedValue(new Error("500"));
    const { ctl } = make();
    at(ctl, "seen");
    ctl.seenMedia = [movie(1)];
    await ctl.next();
    expect(ctl.seenEntries).toEqual([null]);
  });

  it("writes every playback preference on leaving the preferences step", async () => {
    const { ctl } = make();
    at(ctl, "preferences");
    ctl.subtitleLang = "es";
    ctl.audioLang = "original";
    await ctl.next();
    expect(mocks.save).toHaveBeenCalledWith(
      expect.objectContaining({
        defaultSubtitleLang: "es",
        defaultAudioLang: "original",
        autoPlay: true,
        rememberPosition: false,
      }),
    );
  });

  it("marks onboarding done and closes on the last step", async () => {
    const { ctl, onClose } = make();
    at(ctl, "done");
    await ctl.next();
    expect(mocks.save).toHaveBeenCalledWith(
      expect.objectContaining({ onboardingDone: true }),
    );
    expect(onClose).toHaveBeenCalled();
  });

  it("does not close when the final save fails", async () => {
    const { ctl, onClose } = make();
    at(ctl, "done");
    mocks.save.mockResolvedValue(false);
    await ctl.next();
    expect(onClose).not.toHaveBeenCalled();
  });

  it("reloads instead of closing when the UI language changed", async () => {
    const reload = vi.fn();
    vi.stubGlobal("location", { reload });
    const { ctl, onClose } = make();
    at(ctl, "done");
    ctl.selectUiLanguage("de");
    await ctl.next();
    expect(reload).toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });

  it("steps back over the skipped rate step", () => {
    const { ctl } = make();
    at(ctl, "preferences");
    ctl.back();
    expect(ctl.step.id).toBe("seen");
  });

  it("steps back one step normally", () => {
    const { ctl } = make();
    at(ctl, "preferences");
    ctl.seenMedia = [movie(1)];
    ctl.back();
    expect(ctl.step.id).toBe("rate");
  });

  it("skip jumps past the rate step when nothing was seen", () => {
    const { ctl } = make();
    at(ctl, "seen");
    ctl.skip();
    expect(ctl.step.id).toBe("preferences");
  });

  it("skip advances one step when there is something to rate", () => {
    const { ctl } = make();
    at(ctl, "seen");
    ctl.seenMedia = [movie(1)];
    ctl.skip();
    expect(ctl.step.id).toBe("rate");
  });
});
