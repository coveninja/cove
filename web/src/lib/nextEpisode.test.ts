import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const apiMock = vi.hoisted(() => ({
  tvEpisodes: vi.fn(),
}));

vi.mock("$lib/api", () => ({ api: apiMock }));

import { hasAired, nextAiredEpisode } from "$lib/nextEpisode";
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
});
