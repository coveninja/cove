import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  api: {
    authSync: vi.fn(),
    getDetails: vi.fn(),
    libraryRemove: vi.fn(),
    notInterested: vi.fn(),
    progressBulkSave: vi.fn(),
    tvEpisodes: vi.fn(),
    tvSeasons: vi.fn(),
    undoNotInterested: vi.fn(),
  },
  libraryUpdate: vi.fn(),
}));

vi.mock("$lib/api", () => ({ api: mocks.api }));
vi.mock("$lib/stores/library", () => ({
  libraryChanged: { update: mocks.libraryUpdate },
}));

import {
  markMediaWatched,
  mediaUtilityItems,
  performMediaUtilityAction,
} from "$lib/mediaActions";
import type { LibraryEntry } from "$lib/types/library";
import type { Media } from "$lib/types/tmdb";

function media(mediaType: "movie" | "tv"): Media {
  return {
    id: 10,
    title: mediaType === "movie" ? "Film" : "",
    name: mediaType === "tv" ? "Series" : "",
    overview: "",
    release_date: "",
    first_air_date: "",
    poster_path: "/poster.jpg",
    vote_average: 8,
    media_type: mediaType,
    trailer_url: "",
    clip_urls: "",
    images: [],
    popularity: 1,
  };
}

describe("whole-title media actions", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 6, 20, 12));
    vi.clearAllMocks();
    mocks.api.authSync.mockResolvedValue(undefined);
    mocks.api.progressBulkSave.mockResolvedValue({ entry: {}, progress: [] });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("marks a movie watched using its real runtime", async () => {
    mocks.api.getDetails.mockResolvedValue({ runtime: 125 });

    await markMediaWatched(media("movie"));

    expect(mocks.api.progressBulkSave).toHaveBeenCalledWith({
      tmdb_id: 10,
      media_type: "movie",
      title: "Film",
      poster_path: "/poster.jpg",
      vote_average: 8,
      completed: true,
      status: "finished",
      duration_seconds: 7500,
    });
    expect(mocks.libraryUpdate).toHaveBeenCalledOnce();
    expect(mocks.api.authSync).toHaveBeenCalledOnce();
  });

  it("marks every aired regular TV episode watched and excludes specials and future episodes", async () => {
    mocks.api.getDetails.mockResolvedValue({ status: "Returning Series" });
    mocks.api.tvSeasons.mockResolvedValue([
      { season_number: 0, episode_count: 2 },
      { season_number: 1, episode_count: 3 },
      { season_number: 2, episode_count: 1 },
    ]);
    mocks.api.tvEpisodes
      .mockResolvedValueOnce([
        {
          episode_number: 1,
          name: "Past",
          overview: "",
          still_path: "",
          air_date: "2026-07-19",
          runtime: 45,
        },
        {
          episode_number: 2,
          name: "Today",
          overview: "",
          still_path: "",
          air_date: "2026-07-20",
          runtime: 0,
        },
        {
          episode_number: 3,
          name: "Future",
          overview: "",
          still_path: "",
          air_date: "2026-07-21",
          runtime: 45,
        },
      ])
      .mockResolvedValueOnce([
        {
          episode_number: 1,
          name: "Season two",
          overview: "",
          still_path: "",
          air_date: "2026-07-18",
          runtime: 50,
        },
      ]);

    await markMediaWatched(media("tv"));

    expect(mocks.api.tvEpisodes).toHaveBeenCalledTimes(2);
    expect(mocks.api.progressBulkSave).toHaveBeenCalledWith(
      expect.objectContaining({
        tmdb_id: 10,
        media_type: "tv",
        title: "Series",
        completed: true,
        status: "watching",
        episodes: [
          { season: 1, episode: 1, duration_seconds: 2700 },
          { season: 1, episode: 2, duration_seconds: 1 },
          { season: 2, episode: 1, duration_seconds: 3000 },
        ],
      }),
    );
  });

  it.each(["Ended", "Canceled", "Cancelled"])(
    "moves a TV title to Finished when its TMDB status is %s",
    async (status) => {
      mocks.api.getDetails.mockResolvedValue({ status });
      mocks.api.tvSeasons.mockResolvedValue([
        { season_number: 1, episode_count: 1 },
      ]);
      mocks.api.tvEpisodes.mockResolvedValue([
        {
          episode_number: 1,
          name: "Finale",
          overview: "",
          still_path: "",
          air_date: "2026-07-19",
          runtime: 45,
        },
      ]);

      await markMediaWatched(media("tv"));

      expect(mocks.api.progressBulkSave).toHaveBeenCalledWith(
        expect.objectContaining({ status: "finished" }),
      );
    },
  );

  it("resets progress, toggles recommendations, and removes library entries", async () => {
    const movie = media("movie");
    const entry = { id: "entry" } as LibraryEntry;

    await performMediaUtilityAction("mark-unwatched", movie, {
      entry,
      dismissed: false,
    });
    expect(mocks.api.progressBulkSave).toHaveBeenCalledWith(
      expect.objectContaining({
        tmdb_id: 10,
        completed: false,
        status: "watching",
      }),
    );

    await performMediaUtilityAction("toggle-not-interested", movie, {
      entry,
      dismissed: false,
    });
    expect(mocks.api.notInterested).toHaveBeenCalledWith(movie);

    await performMediaUtilityAction("toggle-not-interested", movie, {
      entry,
      dismissed: true,
    });
    expect(mocks.api.undoNotInterested).toHaveBeenCalledWith(movie);

    await performMediaUtilityAction("remove-from-library", movie, {
      entry,
      dismissed: false,
    });
    expect(mocks.api.libraryRemove).toHaveBeenCalledWith(10, "movie");
  });

  it("builds consistent action labels from the current title state", () => {
    const withoutEntry = mediaUtilityItems(media("tv"), {
      entry: null,
      dismissed: false,
    });
    expect(withoutEntry.map((item) => item.id)).toEqual([
      "mark-watched",
      "toggle-not-interested",
    ]);
    expect(withoutEntry[0].label).toBe("Mark aired episodes watched");

    const withEntry = mediaUtilityItems(media("movie"), {
      entry: { id: "entry", status: "finished" } as LibraryEntry,
      dismissed: true,
      hasProgress: true,
    });
    expect(withEntry[0].id).toBe("mark-unwatched");
    expect(withEntry.at(-1)?.id).toBe("remove-from-library");
    expect(withEntry[1].label).toBe("Undo not interested");
  });
});
