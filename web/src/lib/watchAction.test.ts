import { beforeEach, describe, expect, it, vi } from "vitest";

const nextAiredEpisode = vi.hoisted(() => vi.fn());
vi.mock("$lib/nextEpisode", () => ({ nextAiredEpisode }));

import { resolveTvWatchAction } from "$lib/watchAction";
import type { WatchProgress } from "$lib/types/library";
import type { TVEpisode } from "$lib/types/tmdb";

function progress(
  watchedAt: string,
  completed: boolean,
  season?: number,
  episode?: number,
): WatchProgress {
  return {
    id: `${season}:${episode}:${watchedAt}`,
    library_entry_id: "entry",
    tmdb_id: 10,
    media_type: "tv",
    season,
    episode,
    position_seconds: completed ? 45 : 20,
    duration_seconds: 45,
    completed,
    watched_at: watchedAt,
  };
}

function episode(number: number): TVEpisode {
  return {
    episode_number: number,
    name: `Episode ${number}`,
    overview: "",
    still_path: "",
    air_date: "2026-07-01",
    runtime: 45,
  };
}

describe("TV watch actions", () => {
  beforeEach(() => {
    nextAiredEpisode.mockReset();
  });

  it("offers Watch when there is no progress", async () => {
    await expect(resolveTvWatchAction(10, [])).resolves.toEqual({
      label: "Watch",
    });
    expect(nextAiredEpisode).not.toHaveBeenCalled();
  });

  it("resumes the most recently watched incomplete episode without mutating input", async () => {
    const older = progress("2026-07-01T10:00:00Z", false, 1, 2);
    const latest = progress("2026-07-20T10:00:00Z", false, 3, 4);
    const input = [older, latest];

    await expect(resolveTvWatchAction(10, input)).resolves.toEqual({
      label: "Continue S3E4",
      season: 3,
      episode: 4,
    });
    expect(input).toEqual([older, latest]);
    expect(nextAiredEpisode).not.toHaveBeenCalled();
  });

  it("defaults absent season and episode values to the first episode", async () => {
    await expect(
      resolveTvWatchAction(10, [progress("2026-07-20T10:00:00Z", false)]),
    ).resolves.toEqual({
      label: "Continue S1E1",
      season: 1,
      episode: 1,
    });
  });

  it("advances completed progress to the next aired episode", async () => {
    nextAiredEpisode.mockResolvedValue({
      season: 4,
      episode: episode(2),
    });

    await expect(
      resolveTvWatchAction(10, [progress("2026-07-20T10:00:00Z", true, 3, 10)]),
    ).resolves.toEqual({
      label: "Continue S4E2",
      season: 4,
      episode: 2,
    });
    expect(nextAiredEpisode).toHaveBeenCalledWith(10, 3, 10);
  });

  it("offers a deterministic rewatch when no aired episode remains", async () => {
    nextAiredEpisode.mockResolvedValue(null);

    await expect(
      resolveTvWatchAction(10, [progress("2026-07-20T10:00:00Z", true, 5, 8)]),
    ).resolves.toEqual({
      label: "Rewatch",
      season: 1,
      episode: 1,
    });
  });

  it("propagates lookup failures for the consuming overlay to handle", async () => {
    const error = new Error("episode lookup failed");
    nextAiredEpisode.mockRejectedValue(error);

    await expect(
      resolveTvWatchAction(10, [progress("2026-07-20T10:00:00Z", true, 1, 8)]),
    ).rejects.toBe(error);
  });
});
