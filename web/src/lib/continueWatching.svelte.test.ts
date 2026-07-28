import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  libraryList: vi.fn(),
  libraryGet: vi.fn(),
  tvEpisodes: vi.fn(),
}));

vi.mock("$lib/api", async (importOriginal) => {
  const original = await importOriginal<typeof import("$lib/api")>();
  return {
    ...original,
    api: {
      libraryList: mocks.libraryList,
      libraryGet: mocks.libraryGet,
      tvEpisodes: mocks.tvEpisodes,
    },
  };
});

import {
  ContinueWatchingController,
  continuePercent,
  continueSubtitle,
  type ContinueItem,
} from "$lib/continueWatching.svelte";
import type { LibraryEntry, WatchProgress } from "$lib/types/library";
import type { TVEpisode } from "$lib/types/tmdb";

function entry(
  id: number,
  mediaType: "movie" | "tv",
  over: Partial<LibraryEntry> = {},
): LibraryEntry {
  return {
    id: `entry-${id}`,
    tmdb_id: id,
    media_type: mediaType,
    title: `Title ${id}`,
    poster_path: `/poster-${id}.jpg`,
    status: "watching",
    vote_average: 8,
    last_air_date: "",
    added_at: "2026-01-01T00:00:00Z",
    updated_at: "2026-01-01T00:00:00Z",
    ...over,
  };
}

function progress(
  item: LibraryEntry,
  over: Partial<WatchProgress> = {},
): WatchProgress {
  return {
    id: `progress-${item.tmdb_id}`,
    library_entry_id: item.id,
    tmdb_id: item.tmdb_id,
    media_type: item.media_type,
    position_seconds: 600,
    duration_seconds: 1_200,
    completed: false,
    watched_at: "2026-07-01T00:00:00Z",
    ...over,
  };
}

function episode(number: number, over: Partial<TVEpisode> = {}): TVEpisode {
  return {
    episode_number: number,
    name: `Episode ${number}`,
    overview: "",
    still_path: `/episode-${number}.jpg`,
    air_date: "2020-01-01",
    runtime: 45,
    ...over,
  };
}

function make(): ContinueWatchingController {
  let controller!: ContinueWatchingController;
  $effect.root(() => {
    controller = new ContinueWatchingController();
  });
  return controller;
}

beforeEach(() => {
  mocks.libraryList.mockReset().mockResolvedValue([]);
  mocks.libraryGet.mockReset().mockResolvedValue(null);
  mocks.tvEpisodes.mockReset().mockResolvedValue([]);
});

describe("continue labels", () => {
  const base: ContinueItem = {
    key: "1-movie",
    media: {
      id: 1,
      title: "Movie",
      name: "",
      overview: "",
      release_date: "",
      first_air_date: "",
      poster_path: "",
      vote_average: 0,
      media_type: "movie",
      trailer_url: "",
      clip_urls: "",
      images: [],
      popularity: 0,
    },
    title: "Movie",
    image: "",
    mediaType: "movie",
    season: null,
    episode: null,
    upNext: false,
    position: 600,
    duration: 1_200,
    watchedAt: "",
    progress: 0.5,
  };

  it("formats remaining movie time and clamps percentages", () => {
    expect(continueSubtitle(base)).toBe("10m 0s left");
    expect(continuePercent({ ...base, progress: 1.5 })).toBe(100);
    expect(continuePercent({ ...base, progress: -1 })).toBe(0);
  });

  it("formats resumed and up-next episode labels", () => {
    const tv = {
      ...base,
      mediaType: "tv" as const,
      season: 2,
      episode: 4,
    };
    expect(continueSubtitle(tv)).toBe("S2E4");
    expect(continueSubtitle({ ...tv, upNext: true })).toBe("S2E4 · Up Next");
  });
});

describe("ContinueWatchingController", () => {
  it("keeps resumable movies and filters trivial or nearly-complete progress", async () => {
    const keep = entry(1, "movie");
    const tooEarly = entry(2, "movie");
    const finished = entry(3, "movie");
    mocks.libraryList.mockResolvedValue([tooEarly, keep, finished]);
    mocks.libraryGet.mockImplementation(async (id: number) => {
      const item = id === 1 ? keep : id === 2 ? tooEarly : finished;
      return {
        entry: item,
        progress: [
          progress(item, {
            position_seconds: id === 2 ? 10 : id === 3 ? 1_190 : 600,
          }),
        ],
      };
    });

    const controller = make();
    await controller.load();

    expect(controller.items).toHaveLength(1);
    expect(controller.items[0]).toMatchObject({
      key: "1-movie",
      mediaType: "movie",
      progress: 0.5,
    });
    expect(controller.loading).toBe(false);
  });

  it("uses an episode still for a resumable TV episode", async () => {
    const show = entry(10, "tv");
    mocks.libraryList.mockResolvedValue([show]);
    mocks.libraryGet.mockResolvedValue({
      entry: show,
      progress: [progress(show, { season: 2, episode: 3 })],
    });
    mocks.tvEpisodes.mockResolvedValue([episode(3)]);

    const controller = make();
    await controller.load();

    expect(controller.items[0]).toMatchObject({
      mediaType: "tv",
      season: 2,
      episode: 3,
      image: "/episode-3.jpg",
      upNext: false,
    });
    expect(mocks.tvEpisodes).toHaveBeenCalledOnce();
    expect(mocks.tvEpisodes).toHaveBeenCalledWith(10, 2);
  });

  it("rolls completed TV progress forward to the next unwatched aired episode", async () => {
    const show = entry(20, "tv");
    mocks.libraryList.mockResolvedValue([show]);
    mocks.libraryGet.mockResolvedValue({
      entry: show,
      progress: [
        progress(show, {
          season: 1,
          episode: 1,
          completed: true,
          position_seconds: 1_200,
        }),
      ],
    });
    mocks.tvEpisodes.mockResolvedValue([episode(1), episode(2)]);

    const controller = make();
    await controller.load();

    expect(controller.items[0]).toMatchObject({
      season: 1,
      episode: 2,
      image: "/episode-2.jpg",
      upNext: true,
      progress: 0,
    });
    expect(mocks.tvEpisodes).toHaveBeenCalledOnce();
  });

  it("sorts cards by their latest progress timestamp", async () => {
    const older = entry(30, "movie");
    const newer = entry(31, "movie");
    mocks.libraryList.mockResolvedValue([older, newer]);
    mocks.libraryGet.mockImplementation(async (id: number) => {
      const item = id === 30 ? older : newer;
      return {
        entry: item,
        progress: [
          progress(item, {
            watched_at:
              id === 30 ? "2026-07-01T00:00:00Z" : "2026-07-02T00:00:00Z",
          }),
        ],
      };
    });

    const controller = make();
    await controller.load();

    expect(controller.items.map((item) => item.media.id)).toEqual([31, 30]);
  });

  it("falls back to an empty row when the library request fails", async () => {
    mocks.libraryList.mockRejectedValue(new Error("offline"));
    const controller = make();
    await controller.load();
    expect(controller.items).toEqual([]);
    expect(controller.loading).toBe(false);
  });
});
