import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const apiMock = vi.hoisted(() => ({
  tvEpisodes: vi.fn(),
}));

vi.mock("$lib/api", () => ({ api: apiMock }));

import {
  hasAired,
  nextAiredEpisode,
  nextUnwatchedAiredEpisode,
} from "$lib/nextEpisode";
import type { WatchProgress } from "$lib/types/library";
import type { TVEpisode } from "$lib/types/tmdb";

function episode(number: number, airDate: string): TVEpisode {
  return {
    episode_number: number,
    name: `Episode ${number}`,
    overview: "",
    still_path: "",
    air_date: airDate,
    runtime: 45,
  };
}

function progress(
  season: number,
  episodeNumber: number,
  completed = true,
): WatchProgress {
  return {
    id: `progress-${season}-${episodeNumber}`,
    library_entry_id: "entry-10",
    tmdb_id: 10,
    media_type: "tv",
    season,
    episode: episodeNumber,
    position_seconds: completed ? 45 : 20,
    duration_seconds: 45,
    completed,
    watched_at: "2026-07-19T12:00:00Z",
  };
}

describe("next episode resolution", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 6, 20, 12));
    apiMock.tvEpisodes.mockReset();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("treats today and past dates as aired, but not future or missing dates", () => {
    expect(hasAired(episode(1, "2026-07-19"))).toBe(true);
    expect(hasAired(episode(1, "2026-07-20"))).toBe(true);
    expect(hasAired(episode(1, "2026-07-21"))).toBe(false);
    expect(hasAired(episode(1, ""))).toBe(false);
  });

  it("returns the lowest numbered aired episode later in the same season", async () => {
    const fetchSeason = vi
      .fn()
      .mockResolvedValue([
        episode(4, "2026-07-18"),
        episode(1, "2026-07-01"),
        episode(3, "2026-07-15"),
      ]);

    await expect(nextAiredEpisode(10, 2, 1, fetchSeason)).resolves.toEqual({
      season: 2,
      episode: episode(3, "2026-07-15"),
    });
    expect(fetchSeason).toHaveBeenCalledTimes(1);
  });

  it("stops when the next same-season episode exists but has not aired", async () => {
    const fetchSeason = vi
      .fn()
      .mockResolvedValue([episode(2, "2026-07-21"), episode(3, "2026-07-28")]);

    await expect(nextAiredEpisode(10, 1, 1, fetchSeason)).resolves.toBeNull();
    expect(fetchSeason).toHaveBeenCalledTimes(1);
  });

  it("rolls into the next season, ignores specials, and sorts its episodes", async () => {
    const fetchSeason = vi
      .fn()
      .mockResolvedValueOnce([episode(1, "2026-01-01")])
      .mockResolvedValueOnce([
        episode(2, "2026-07-10"),
        episode(0, "2026-06-01"),
        episode(1, "2026-07-01"),
      ]);

    await expect(nextAiredEpisode(10, 1, 1, fetchSeason)).resolves.toEqual({
      season: 2,
      episode: episode(1, "2026-07-01"),
    });
    expect(fetchSeason).toHaveBeenNthCalledWith(1, 10, 1);
    expect(fetchSeason).toHaveBeenNthCalledWith(2, 10, 2);
  });

  it("returns caught-up when the first next-season episode is unaired", async () => {
    const fetchSeason = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([episode(1, "2026-07-21")]);

    await expect(nextAiredEpisode(10, 1, 8, fetchSeason)).resolves.toBeNull();
  });

  it("normalizes empty and failed default API responses to caught-up", async () => {
    apiMock.tvEpisodes
      .mockResolvedValueOnce(null)
      .mockRejectedValueOnce(new Error("unavailable"));

    await expect(nextAiredEpisode(10, 1, 8)).resolves.toBeNull();
    expect(apiMock.tvEpisodes).toHaveBeenCalledTimes(2);
  });

  it("skips completed aired episodes and returns the first unwatched gap", async () => {
    const fetchSeason = vi
      .fn()
      .mockResolvedValue([
        episode(1, "2026-07-01"),
        episode(2, "2026-07-02"),
        episode(3, "2026-07-03"),
        episode(4, "2026-07-04"),
      ]);

    await expect(
      nextUnwatchedAiredEpisode(
        10,
        1,
        1,
        [
          progress(1, 1),
          progress(1, 2),
          progress(1, 3, false),
          progress(1, 4),
        ],
        fetchSeason,
      ),
    ).resolves.toEqual({
      season: 1,
      episode: episode(3, "2026-07-03"),
    });
    expect(fetchSeason).toHaveBeenCalledTimes(1);
  });

  it("returns caught-up when every later aired episode is completed", async () => {
    const seasons = new Map<number, TVEpisode[]>([
      [
        1,
        [
          episode(1, "2026-07-01"),
          episode(2, "2026-07-02"),
          episode(3, "2026-07-03"),
        ],
      ],
      [2, [episode(1, "2026-07-10"), episode(2, "2026-07-17")]],
      [3, []],
    ]);
    const fetchSeason = vi.fn(
      async (_id: number, season: number) => seasons.get(season) ?? [],
    );

    await expect(
      nextUnwatchedAiredEpisode(
        10,
        1,
        1, // Deliberately stale/tied "latest" record.
        [
          progress(1, 1),
          progress(1, 2),
          progress(1, 3),
          progress(2, 1),
          progress(2, 2),
        ],
        fetchSeason,
      ),
    ).resolves.toBeNull();
    expect(fetchSeason.mock.calls.map((call) => call[1])).toEqual([1, 2, 3]);
  });
});
