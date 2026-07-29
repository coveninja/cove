import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushSync } from "svelte";

const mocks = vi.hoisted(() => ({
  getStreams: vi.fn(),
  tvSeasons: vi.fn(),
  tvEpisodes: vi.fn(),
  libraryGet: vi.fn(),
  progressGet: vi.fn(),
  getWatchOptions: vi.fn(),
  probeStreams: vi.fn(),
}));

vi.mock("$lib/api", () => ({
  api: {
    getStreams: mocks.getStreams,
    tvSeasons: mocks.tvSeasons,
    tvEpisodes: mocks.tvEpisodes,
    libraryGet: mocks.libraryGet,
    progressGet: mocks.progressGet,
    getWatchOptions: mocks.getWatchOptions,
    probeStreams: mocks.probeStreams,
  },
}));

import { DEFAULT_SETTINGS } from "$lib/stores/settings";
import { StreamsListController, watchTypeLabel } from "$lib/streamsList.svelte";
import type { StreamsListOptions } from "$lib/streamsList.svelte";
import type { Stream } from "$lib/types/addons";
import type { Media, TVEpisode } from "$lib/types/tmdb";
import type { Settings } from "$lib/types/settings";

// ── Fixtures ────────────────────────────────────────────────────────────────

function movie(over: Partial<Media> = {}): Media {
  return {
    id: 603,
    media_type: "movie",
    title: "The Matrix",
    ...over,
  } as Media;
}

function show(over: Partial<Media> = {}): Media {
  return { id: 1396, media_type: "tv", name: "Breaking Bad", ...over } as Media;
}

function torrent(name: string, over: Partial<Stream> = {}): Stream {
  return {
    name,
    title: name,
    infoHash: name.toLowerCase().replace(/\s+/g, ""),
    ...over,
  } as Stream;
}

function episode(n: number): TVEpisode {
  return { episode_number: n, name: `Episode ${n}` } as TVEpisode;
}

/**
 * Builds a controller inside an effect root with reactive, mutable options —
 * mirroring how a component supplies props and $settings as getters.
 */
function harness(
  init: {
    media?: Media;
    streamActive?: boolean;
    activeSeason?: number;
    activeEpisode?: number;
    autoJumpToActive?: boolean;
    settings?: Partial<Settings>;
  } = {},
) {
  const props = $state({
    media: init.media ?? movie(),
    streamActive: init.streamActive ?? false,
    activeSeason: init.activeSeason,
    activeEpisode: init.activeEpisode,
    autoJumpToActive: init.autoJumpToActive ?? true,
    settings: { ...DEFAULT_SETTINGS, ...init.settings } as Settings,
  });
  const onPlayStream = vi.fn();
  const setMaxQuality = vi.fn();

  const opts: StreamsListOptions = {
    getMedia: () => props.media,
    getStreamActive: () => props.streamActive,
    getActiveSeason: () => props.activeSeason,
    getActiveEpisode: () => props.activeEpisode,
    getAutoJumpToActive: () => props.autoJumpToActive,
    getSettings: () => props.settings,
    setMaxQuality,
    onPlayStream,
  };

  let ctl!: StreamsListController;
  const destroyRoot = $effect.root(() => {
    ctl = new StreamsListController(opts);
  });

  return {
    get ctl() {
      return ctl;
    },
    props,
    onPlayStream,
    setMaxQuality,
    destroyRoot,
  };
}

/** Runs `fn` inside an effect root so $effect-driven assertions are possible. */
function withRoot(fn: () => void | (() => void)): () => void {
  return $effect.root(fn);
}

beforeEach(() => {
  vi.useFakeTimers();
  delete window.__coveCaps;
  mocks.getStreams.mockReset().mockResolvedValue([]);
  mocks.tvSeasons.mockReset().mockResolvedValue([]);
  mocks.tvEpisodes.mockReset().mockResolvedValue([]);
  mocks.libraryGet.mockReset().mockResolvedValue(null);
  mocks.progressGet.mockReset().mockResolvedValue(null);
  mocks.getWatchOptions.mockReset().mockResolvedValue([]);
  mocks.probeStreams.mockReset().mockResolvedValue({ results: [] });
});

afterEach(() => {
  delete window.__coveCaps;
  vi.useRealTimers();
});

// ── Derived view state ──────────────────────────────────────────────────────

describe("isTV", () => {
  it("follows the media prop reactively", () => {
    const h = harness({ media: movie() });
    expect(h.ctl.isTV).toBe(false);
    h.props.media = show();
    expect(h.ctl.isTV).toBe(true);
    h.destroyRoot();
  });
});

describe("alreadyPlayingThisSelection", () => {
  it("is false for a movie that is not playing", () => {
    const h = harness({ media: movie(), streamActive: false });
    expect(h.ctl.alreadyPlayingThisSelection).toBe(false);
    h.destroyRoot();
  });

  it("is true for a movie as soon as a stream is active", () => {
    const h = harness({ media: movie(), streamActive: true });
    expect(h.ctl.alreadyPlayingThisSelection).toBe(true);
    h.destroyRoot();
  });

  it("requires an exact season+episode match for a show", () => {
    const h = harness({
      media: show(),
      streamActive: true,
      activeSeason: 2,
      activeEpisode: 5,
    });
    h.ctl.selectedSeason = 2;
    h.ctl.selectedEpisode = episode(4);
    expect(h.ctl.alreadyPlayingThisSelection).toBe(false);
    h.ctl.selectedEpisode = episode(5);
    expect(h.ctl.alreadyPlayingThisSelection).toBe(true);
    h.destroyRoot();
  });
});

describe("effectiveAudioLang", () => {
  it("passes a concrete language code through", () => {
    const h = harness({ settings: { defaultAudioLang: "de" } });
    expect(h.ctl.effectiveAudioLang).toBe("de");
    h.destroyRoot();
  });

  it("resolves 'original' to the title's TMDB original language", () => {
    const h = harness({
      media: movie({ original_language: "ja" }),
      settings: { defaultAudioLang: "original" },
    });
    expect(h.ctl.effectiveAudioLang).toBe("ja");
    h.destroyRoot();
  });

  it("falls back to empty string when the title has no original language", () => {
    const h = harness({ settings: { defaultAudioLang: "original" } });
    expect(h.ctl.effectiveAudioLang).toBe("");
    h.destroyRoot();
  });
});

describe("availableQualities", () => {
  it("lists the distinct qualities present, best first, behind 'all'", () => {
    const h = harness();
    h.ctl.streams = [
      torrent("A 720p"),
      torrent("B 2160p"),
      torrent("C 1080p"),
      torrent("D 1080p"),
    ];
    expect(h.ctl.availableQualities).toEqual(["all", "4k", "1080p", "720p"]);
    h.destroyRoot();
  });
});

describe("parsedStreams", () => {
  it("dedupes rows sharing an identity key", () => {
    const h = harness();
    h.ctl.streams = [torrent("Dup 1080p"), torrent("Dup 1080p")];
    expect(h.ctl.parsedStreams).toHaveLength(1);
    h.destroyRoot();
  });

  it("skips streams with no url, infoHash or title", () => {
    const h = harness();
    h.ctl.streams = [{ name: "n", title: "", infoHash: "" } as Stream];
    expect(h.ctl.parsedStreams).toEqual([]);
    h.destroyRoot();
  });
});

describe("filteredStreams", () => {
  it("honours the quality filter", () => {
    const h = harness();
    h.ctl.streams = [torrent("A 1080p"), torrent("B 720p")];
    expect(h.ctl.filteredStreams).toHaveLength(2);
    h.ctl.qualityFilter = "720p";
    expect(h.ctl.filteredStreams.map((s) => s.stream.name)).toEqual(["B 720p"]);
    h.destroyRoot();
  });

  it("re-sorts when sortMode changes", () => {
    const h = harness();
    h.ctl.streams = [
      torrent("Small 1080p 👤 5 💾 1 GB"),
      torrent("Big 1080p 👤 1 💾 9 GB"),
    ];
    expect(h.ctl.filteredStreams[0].stream.name).toContain("Small");
    h.ctl.sortMode = "largest";
    expect(h.ctl.filteredStreams[0].stream.name).toContain("Big");
    h.destroyRoot();
  });

  // The reason every option is a getter: a derived that captured the settings
  // value at construction would memoise it and never see this change.
  it("re-sorts when the settings signal changes the preferred provider", () => {
    const h = harness();
    h.ctl.streams = [
      torrent("A 1080p", { addonName: "alpha" }),
      torrent("B 1080p", { addonName: "beta" }),
    ];
    expect(h.ctl.filteredStreams[0].stream.addonName).toBe("alpha");
    h.props.settings = { ...h.props.settings, defaultProvider: "beta" };
    expect(h.ctl.filteredStreams[0].stream.addonName).toBe("beta");
    h.destroyRoot();
  });
});

describe("selectedSeasonLabel", () => {
  it("prefers the season's own name", () => {
    const h = harness({ media: show() });
    h.ctl.seasons = [
      {
        season_number: 1,
        episode_count: 7,
        name: "Chapter One",
        poster_path: "",
      },
    ];
    h.ctl.selectedSeason = 1;
    expect(h.ctl.selectedSeasonLabel).toBe("Chapter One");
    h.destroyRoot();
  });

  it("falls back to a generated label for an unknown season", () => {
    const h = harness({ media: show() });
    h.ctl.selectedSeason = 3;
    expect(h.ctl.selectedSeasonLabel).toContain("3");
    h.destroyRoot();
  });
});

// ── UI helpers ──────────────────────────────────────────────────────────────

describe("cycleQuality / cycleSort", () => {
  it("cycles the quality filter and wraps back to 'all'", () => {
    const h = harness();
    h.ctl.streams = [torrent("A 1080p")];
    expect(h.ctl.availableQualities).toEqual(["all", "1080p"]);
    h.ctl.cycleQuality();
    expect(h.ctl.qualityFilter).toBe("1080p");
    h.ctl.cycleQuality();
    expect(h.ctl.qualityFilter).toBe("all");
    h.destroyRoot();
  });

  it("cycles the sort mode and wraps", () => {
    const h = harness();
    const first = h.ctl.sortMode;
    for (let i = 0; i < 6; i++) h.ctl.cycleSort();
    expect(h.ctl.sortMode).toBe(first);
    h.destroyRoot();
  });
});

describe("clearSelectedEpisode / cancelAutoPick", () => {
  it("drops the episode and its stream list", () => {
    const h = harness({ media: show() });
    h.ctl.selectedEpisode = episode(3);
    h.ctl.streams = [torrent("A 1080p")];
    h.ctl.clearSelectedEpisode();
    expect(h.ctl.selectedEpisode).toBeNull();
    expect(h.ctl.streams).toEqual([]);
    h.destroyRoot();
  });

  it("marks auto-pick cancelled and stops the spinner", () => {
    const h = harness();
    h.ctl.autoPicking = true;
    h.ctl.cancelAutoPick();
    expect(h.ctl.autoPickCancelled).toBe(true);
    expect(h.ctl.autoPicking).toBe(false);
    h.destroyRoot();
  });
});

// ── Lifecycle ───────────────────────────────────────────────────────────────

describe("resetOnMediaChange", () => {
  it("does not clear anything on the first run", () => {
    const h = harness({ media: show() });
    h.ctl.selectedSeason = 2;
    h.ctl.resetOnMediaChange();
    expect(h.ctl.selectedSeason).toBe(2);
    h.destroyRoot();
  });

  it("clears browsing state once the media identity changes", () => {
    const h = harness({ media: show() });
    h.ctl.resetOnMediaChange();
    h.ctl.selectedSeason = 2;
    h.ctl.selectedEpisode = episode(4);
    h.ctl.seasons = [
      { season_number: 1, episode_count: 7, name: "S1", poster_path: "" },
    ];
    h.ctl.episodes = [episode(1)];

    h.props.media = show({ id: 999 });
    h.ctl.resetOnMediaChange();

    expect(h.ctl.selectedSeason).toBeNull();
    expect(h.ctl.selectedEpisode).toBeNull();
    expect(h.ctl.seasons).toEqual([]);
    expect(h.ctl.episodes).toEqual([]);
    h.destroyRoot();
  });

  it("leaves state alone when the same media re-renders", () => {
    const h = harness({ media: show() });
    h.ctl.resetOnMediaChange();
    h.ctl.selectedSeason = 2;
    h.ctl.resetOnMediaChange();
    expect(h.ctl.selectedSeason).toBe(2);
    h.destroyRoot();
  });
});

describe("clearAutoPickingWhenPlaying", () => {
  it("clears autoPicking once the pick is confirmed playing", () => {
    const h = harness({ media: movie(), streamActive: true });
    h.ctl.autoPicking = true;
    h.ctl.clearAutoPickingWhenPlaying();
    expect(h.ctl.autoPicking).toBe(false);
    h.destroyRoot();
  });

  it("leaves autoPicking alone while nothing is playing", () => {
    const h = harness({ media: movie(), streamActive: false });
    h.ctl.autoPicking = true;
    h.ctl.clearAutoPickingWhenPlaying();
    expect(h.ctl.autoPicking).toBe(true);
    h.destroyRoot();
  });
});

describe("loadProgress", () => {
  it("keys the show's episode progress by season:episode", async () => {
    mocks.libraryGet.mockResolvedValue({
      progress: [
        { season: 1, episode: 2, position_seconds: 60 },
        { season: null, episode: null, position_seconds: 10 },
      ],
    });
    const h = harness({ media: show() });
    h.ctl.loadProgress();
    await vi.waitFor(() => expect(h.ctl.progressMap.size).toBe(1));
    expect(h.ctl.progressMap.get("1:2")?.position_seconds).toBe(60);
    h.destroyRoot();
  });

  it("is a no-op for a movie", () => {
    const h = harness({ media: movie() });
    h.ctl.loadProgress();
    expect(mocks.libraryGet).not.toHaveBeenCalled();
    h.destroyRoot();
  });
});

describe("loadMovieProgress", () => {
  it("stores the single movie record", async () => {
    mocks.progressGet.mockResolvedValue({ position_seconds: 42 });
    const h = harness({ media: movie() });
    h.ctl.loadMovieProgress();
    await vi.waitFor(() =>
      expect(h.ctl.movieProgress?.position_seconds).toBe(42),
    );
    h.destroyRoot();
  });

  it("is a no-op for a show", () => {
    const h = harness({ media: show() });
    h.ctl.loadMovieProgress();
    expect(mocks.progressGet).not.toHaveBeenCalled();
    h.destroyRoot();
  });
});

describe("loadWatchOptions", () => {
  it("falls back to an empty list when the request fails", async () => {
    mocks.getWatchOptions.mockRejectedValue(new Error("nope"));
    const h = harness();
    h.ctl.watchOptions = [{ providerId: 1 } as never];
    h.ctl.loadWatchOptions();
    await vi.waitFor(() => expect(h.ctl.watchOptions).toEqual([]));
    h.destroyRoot();
  });
});

describe("loadSeasons", () => {
  it("defaults to the first season", async () => {
    mocks.tvSeasons.mockResolvedValue([
      { season_number: 1, episode_count: 7, name: "S1", poster_path: "" },
      { season_number: 2, episode_count: 13, name: "S2", poster_path: "" },
    ]);
    const h = harness({ media: show() });
    h.ctl.loadSeasons();
    await vi.waitFor(() => expect(h.ctl.selectedSeason).toBe(1));
    expect(h.ctl.loadingSeasons).toBe(false);
    h.destroyRoot();
  });

  it("lands on the season that is already playing", async () => {
    mocks.tvSeasons.mockResolvedValue([
      { season_number: 1, episode_count: 7, name: "S1", poster_path: "" },
      { season_number: 2, episode_count: 13, name: "S2", poster_path: "" },
    ]);
    const h = harness({ media: show(), activeSeason: 2 });
    h.ctl.loadSeasons();
    await vi.waitFor(() => expect(h.ctl.selectedSeason).toBe(2));
    h.destroyRoot();
  });

  it("ignores an active season the show does not have", async () => {
    mocks.tvSeasons.mockResolvedValue([
      { season_number: 1, episode_count: 7, name: "S1", poster_path: "" },
    ]);
    const h = harness({ media: show(), activeSeason: 9 });
    h.ctl.loadSeasons();
    await vi.waitFor(() => expect(h.ctl.selectedSeason).toBe(1));
    h.destroyRoot();
  });
});

describe("loadEpisodes", () => {
  it("jumps to the episode already playing", async () => {
    mocks.tvEpisodes.mockResolvedValue([episode(1), episode(2), episode(3)]);
    const h = harness({ media: show(), activeSeason: 1, activeEpisode: 3 });
    h.ctl.selectedSeason = 1;
    h.ctl.loadEpisodes();
    await vi.waitFor(() =>
      expect(h.ctl.selectedEpisode?.episode_number).toBe(3),
    );
    h.destroyRoot();
  });

  it("stays on the episode list when autoJumpToActive is off", async () => {
    mocks.tvEpisodes.mockResolvedValue([episode(1), episode(2), episode(3)]);
    const h = harness({
      media: show(),
      activeSeason: 1,
      activeEpisode: 3,
      autoJumpToActive: false,
    });
    h.ctl.selectedSeason = 1;
    h.ctl.loadEpisodes();
    await vi.waitFor(() => expect(h.ctl.episodes).toHaveLength(3));
    expect(h.ctl.selectedEpisode).toBeNull();
    h.destroyRoot();
  });

  it("is a no-op until a season is selected", () => {
    const h = harness({ media: show() });
    h.ctl.loadEpisodes();
    expect(mocks.tvEpisodes).not.toHaveBeenCalled();
    h.destroyRoot();
  });
});

// ── loadStreams ─────────────────────────────────────────────────────────────

describe("loadStreams", () => {
  it("does nothing for a show until an episode is picked", () => {
    const h = harness({ media: show() });
    h.ctl.loadStreams();
    expect(mocks.getStreams).not.toHaveBeenCalled();
    h.destroyRoot();
  });

  it("requests the selected season and episode for a show", () => {
    const h = harness({ media: show() });
    h.ctl.selectedSeason = 2;
    h.ctl.selectedEpisode = episode(5);
    h.ctl.loadStreams();
    expect(mocks.getStreams).toHaveBeenCalledWith(
      1396,
      { type: "tv", season: 2, episode: 5 },
      expect.any(AbortSignal),
    );
    h.destroyRoot();
  });

  it("stores results and reports the best quality upward", async () => {
    mocks.getStreams.mockResolvedValue([torrent("A 1080p"), torrent("B 720p")]);
    const h = harness({ settings: { autoSelectStream: false } });
    h.ctl.loadStreams();
    await vi.waitFor(() => expect(h.ctl.streams).toHaveLength(2));
    expect(h.setMaxQuality).toHaveBeenCalledWith("1080p");
    h.destroyRoot();
  });

  it("resets the picker flags on every run", async () => {
    const h = harness();
    h.ctl.autoPickCancelled = true;
    h.ctl.showAlternatives = true;
    h.ctl.loadStreams();
    expect(h.ctl.autoPickCancelled).toBe(false);
    expect(h.ctl.showAlternatives).toBe(false);
    expect(h.ctl.loadingStreams).toBe(true);
    h.destroyRoot();
  });

  it("polls while the result set is empty and gives up after the cap", async () => {
    const h = harness();
    h.ctl.loadStreams();
    await vi.waitFor(() => expect(h.ctl.loadingStreams).toBe(false));
    expect(mocks.getStreams).toHaveBeenCalledTimes(1);

    // 10 polls land, the 11th trips MAX_POLL_ATTEMPTS and clears the interval.
    for (let i = 0; i < 15; i++) {
      await vi.advanceTimersByTimeAsync(2000);
    }
    expect(mocks.getStreams).toHaveBeenCalledTimes(11);
    h.destroyRoot();
  });

  it("stops polling as soon as streams arrive", async () => {
    const h = harness({ settings: { autoSelectStream: false } });
    h.ctl.loadStreams();
    await vi.waitFor(() => expect(h.ctl.loadingStreams).toBe(false));

    mocks.getStreams.mockResolvedValue([torrent("A 1080p")]);
    await vi.advanceTimersByTimeAsync(2000);
    expect(h.ctl.streams).toHaveLength(1);

    const callsAfterHit = mocks.getStreams.mock.calls.length;
    await vi.advanceTimersByTimeAsync(10_000);
    expect(mocks.getStreams).toHaveBeenCalledTimes(callsAfterHit);
    h.destroyRoot();
  });

  it("discards a response that a newer run has superseded", async () => {
    let release!: (v: Stream[]) => void;
    mocks.getStreams.mockImplementationOnce(
      () => new Promise<Stream[]>((r) => (release = r)),
    );
    mocks.getStreams.mockResolvedValue([torrent("Fresh 1080p")]);

    const h = harness({ settings: { autoSelectStream: false } });
    const teardownFirst = h.ctl.loadStreams();
    h.ctl.loadStreams(); // supersedes run 1
    await vi.waitFor(() => expect(h.ctl.streams).toHaveLength(1));

    release([torrent("Stale 720p")]);
    await vi.advanceTimersByTimeAsync(0);
    expect(h.ctl.streams.map((s) => s.name)).toEqual(["Fresh 1080p"]);

    teardownFirst();
    h.destroyRoot();
  });
});

// ── Auto-selection ──────────────────────────────────────────────────────────

describe("auto-selection", () => {
  it("plays the top-ranked stream after the 500ms grace window", async () => {
    mocks.getStreams.mockResolvedValue([
      torrent("A 1080p 👤 500"),
      torrent("B 720p 👤 5"),
    ]);
    const h = harness({ settings: { autoSelectStream: true } });
    h.ctl.loadStreams();
    await vi.waitFor(() => expect(h.ctl.autoPicking).toBe(true));
    expect(h.onPlayStream).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(500);
    expect(h.onPlayStream).toHaveBeenCalledTimes(1);
    // …with runner-up candidates for App.svelte's dead-stream watchdog.
    expect(h.onPlayStream.mock.calls[0][4].length).toBeGreaterThan(0);
    h.destroyRoot();
  });

  it("passes the season, episode and episode name for a show", async () => {
    mocks.getStreams.mockResolvedValue([torrent("A 1080p")]);
    const h = harness({ media: show(), settings: { autoSelectStream: true } });
    h.ctl.selectedSeason = 2;
    h.ctl.selectedEpisode = episode(5);
    h.ctl.loadStreams();
    await vi.advanceTimersByTimeAsync(500);
    expect(h.onPlayStream.mock.calls[0].slice(1, 4)).toEqual([
      2,
      5,
      "Episode 5",
    ]);
    h.destroyRoot();
  });

  it("does not fire when autoSelectStream is off", async () => {
    mocks.getStreams.mockResolvedValue([torrent("A 1080p")]);
    const h = harness({ settings: { autoSelectStream: false } });
    h.ctl.loadStreams();
    await vi.advanceTimersByTimeAsync(1000);
    expect(h.onPlayStream).not.toHaveBeenCalled();
    h.destroyRoot();
  });

  it("does not auto-select when every stream codec is unsupported", async () => {
    window.__coveCaps = {
      hevcMain10: true,
      av1: false,
      hevc: true,
      vp9: true,
    };
    mocks.getStreams.mockResolvedValue([
      torrent("A 4K AV1 👤 500"),
      torrent("B 1080p AV1 👤 50"),
    ]);
    const h = harness({ settings: { autoSelectStream: true } });

    h.ctl.loadStreams();
    await vi.advanceTimersByTimeAsync(1_000);

    expect(h.ctl.streams).toHaveLength(2);
    expect(h.ctl.autoPicking).toBe(false);
    expect(h.onPlayStream).not.toHaveBeenCalled();
    h.destroyRoot();
  });

  it("does not fire for the selection that is already playing", async () => {
    mocks.getStreams.mockResolvedValue([torrent("A 1080p")]);
    const h = harness({
      media: movie(),
      streamActive: true,
      settings: { autoSelectStream: true },
    });
    h.ctl.loadStreams();
    await vi.advanceTimersByTimeAsync(1000);
    expect(h.onPlayStream).not.toHaveBeenCalled();
    h.destroyRoot();
  });

  it("cancelAutoPick stops the pending pick from firing", async () => {
    mocks.getStreams.mockResolvedValue([torrent("A 1080p")]);
    const h = harness({ settings: { autoSelectStream: true } });
    h.ctl.loadStreams();
    await vi.waitFor(() => expect(h.ctl.autoPicking).toBe(true));
    h.ctl.cancelAutoPick();
    await vi.advanceTimersByTimeAsync(1000);
    expect(h.onPlayStream).not.toHaveBeenCalled();
    h.destroyRoot();
  });

  // The wrong-episode-autoplay bug: a pick armed for the previous episode must
  // never fire once the user has moved on.
  it("teardown cancels a pick armed by the run it belongs to", async () => {
    mocks.getStreams.mockResolvedValue([torrent("A 1080p")]);
    const h = harness({ media: show(), settings: { autoSelectStream: true } });
    h.ctl.selectedSeason = 1;
    h.ctl.selectedEpisode = episode(1);
    const teardown = h.ctl.loadStreams();
    await vi.waitFor(() => expect(h.ctl.autoPicking).toBe(true));

    teardown();
    await vi.advanceTimersByTimeAsync(1000);
    expect(h.onPlayStream).not.toHaveBeenCalled();
    h.destroyRoot();
  });

  it("a superseding run cancels the previous run's armed pick", async () => {
    mocks.getStreams.mockResolvedValue([torrent("A 1080p")]);
    const h = harness({ media: show(), settings: { autoSelectStream: true } });
    h.ctl.selectedSeason = 1;
    h.ctl.selectedEpisode = episode(1);
    h.ctl.loadStreams();
    await vi.waitFor(() => expect(h.ctl.autoPicking).toBe(true));

    // User switches episode before the 500ms window closes.
    h.ctl.selectedEpisode = episode(2);
    h.ctl.loadStreams();
    await vi.advanceTimersByTimeAsync(500);

    expect(h.onPlayStream).toHaveBeenCalledTimes(1);
    expect(h.onPlayStream.mock.calls[0][2]).toBe(2);
    h.destroyRoot();
  });
});

// ── Effect wiring ───────────────────────────────────────────────────────────

describe("effect wiring", () => {
  it("re-runs loadStreams when the selected episode changes", async () => {
    const h = harness({ media: show(), settings: { autoSelectStream: false } });
    h.ctl.selectedSeason = 1;
    h.ctl.selectedEpisode = episode(1);

    const destroy = withRoot(() => {
      $effect(() => h.ctl.loadStreams());
    });
    flushSync();
    expect(mocks.getStreams).toHaveBeenCalledTimes(1);

    h.ctl.selectedEpisode = episode(2);
    flushSync();
    expect(mocks.getStreams).toHaveBeenCalledTimes(2);
    expect(mocks.getStreams.mock.calls[1][1]).toEqual({
      type: "tv",
      season: 1,
      episode: 2,
    });

    destroy();
    h.destroyRoot();
  });

  it("re-runs loadSeasons when the media prop changes", () => {
    const h = harness({ media: show() });
    const destroy = withRoot(() => {
      $effect(() => h.ctl.loadSeasons());
    });
    flushSync();
    expect(mocks.tvSeasons).toHaveBeenCalledTimes(1);

    h.props.media = show({ id: 1399 });
    flushSync();
    expect(mocks.tvSeasons).toHaveBeenCalledTimes(2);
    expect(mocks.tvSeasons).toHaveBeenLastCalledWith(1399);

    destroy();
    h.destroyRoot();
  });

  it("clears autoPicking through its effect once playback is confirmed", () => {
    const h = harness({ media: movie() });
    const destroy = withRoot(() => {
      $effect(() => h.ctl.clearAutoPickingWhenPlaying());
    });
    h.ctl.autoPicking = true;
    flushSync();
    expect(h.ctl.autoPicking).toBe(true);

    h.props.streamActive = true;
    flushSync();
    expect(h.ctl.autoPicking).toBe(false);

    destroy();
    h.destroyRoot();
  });
});

describe("watchTypeLabel", () => {
  it("passes an unknown type through untranslated", () => {
    expect(watchTypeLabel("flatrate")).toBe("flatrate");
  });

  it("translates the known types", () => {
    for (const t of ["rent", "buy", "free", "ads"]) {
      expect(watchTypeLabel(t)).not.toBe(t);
    }
  });
});
