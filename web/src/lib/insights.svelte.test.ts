import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  libraryStats: vi.fn(),
  discoverInsights: vi.fn(),
  activityStats: vi.fn(),
  getPerson: vi.fn(),
}));

vi.mock("$lib/api", () => ({ api: mocks }));

import {
  InsightsController,
  displayInsightPerson,
  insightConicGradient,
  insightGenreSlices,
  insightMediaSlices,
  insightStatusSlices,
} from "$lib/insights.svelte";
import type { DiscoverInsights, LibraryStats } from "$lib/api";

const stats: LibraryStats = {
  total: 3,
  by_type: { movie: 2, tv: 1 },
  by_status: { watching: 2, finished: 1 },
  finished: { movie: 1 },
  dismissed: 0,
  rated: 0,
  avg_rating: 0,
  movie_share: 2 / 3,
  tv_share: 1 / 3,
};

const insights: DiscoverInsights = {
  top_movie_genres: [],
  top_tv_genres: [],
  disliked_genres: [],
  top_keywords: [],
  top_people: [{ id: 7, name: "Person", score: 1 }],
  signals_used: 1,
  top_studios: [],
  top_contributors: [],
  negative_contributors: [],
};

function make(): InsightsController {
  let controller!: InsightsController;
  $effect.root(() => {
    controller = new InsightsController();
  });
  return controller;
}

beforeEach(() => {
  mocks.libraryStats.mockReset().mockResolvedValue(stats);
  mocks.discoverInsights.mockReset().mockResolvedValue(insights);
  mocks.activityStats.mockReset().mockResolvedValue({ total_seconds: 60 });
  mocks.getPerson.mockReset().mockResolvedValue({
    id: 7,
    name: "Full Person",
    profile_path: "/person.jpg",
    known_for_department: "Acting",
  });
});

describe("InsightsController", () => {
  it("loads insight datasets and hydrates people", async () => {
    const controller = make();
    await controller.load();
    await vi.waitFor(() =>
      expect(controller.peopleSlots[0]?.person).not.toBeNull(),
    );

    expect(controller.stats).toEqual(stats);
    expect(controller.insights).toEqual(insights);
    expect(controller.loading).toBe(false);
    expect(displayInsightPerson(controller.peopleSlots[0])).toMatchObject({
      id: 7,
      name: "Full Person",
      profile_path: "/person.jpg",
    });
  });

  it("surfaces a primary request failure and ends initial loading", async () => {
    mocks.libraryStats.mockRejectedValue(new Error("offline"));
    const controller = make();
    await controller.load();
    expect(controller.loadError).toBe("offline");
    expect(controller.loading).toBe(false);
  });
});

describe("insight chart helpers", () => {
  it("builds deterministic conic-gradient stops", () => {
    expect(
      insightConicGradient([
        { label: "A", value: 1, color: "red" },
        { label: "B", value: 3, color: "blue" },
      ]),
    ).toBe("conic-gradient(red 0% 25%, blue 25% 100%)");
  });

  it("groups genre overflow into Other", () => {
    const slices = insightGenreSlices(
      Array.from({ length: 7 }, (_, index) => ({
        id: index,
        name: `Genre ${index}`,
        score: index + 1,
      })),
    );
    expect(slices).toHaveLength(6);
    expect(slices.at(-1)).toMatchObject({ label: "Other", value: 13 });
  });

  it("maps media and non-empty status shares", () => {
    expect(insightMediaSlices(stats).map((slice) => slice.count)).toEqual([
      2, 1,
    ]);
    expect(insightStatusSlices(stats).map((slice) => slice.value)).toEqual([
      2, 1,
    ]);
  });
});
