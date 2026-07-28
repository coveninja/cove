import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  discover: vi.fn(),
  discoverInsights: vi.fn(),
  libraryStats: vi.fn(),
  discoverByGenre: vi.fn(),
  discoverByKeyword: vi.fn(),
  getCatalogs: vi.fn(),
  catalogPage: vi.fn(),
}));

vi.mock("$lib/api", () => ({ api: mocks }));

import {
  HOME_ROW_LIMIT,
  HomeFeedController,
  homeTasteSpecs,
} from "$lib/homeFeed.svelte";
import type { DiscoverInsights } from "$lib/api";
import type { Media } from "$lib/types/tmdb";

function media(id: number): Media {
  return {
    id,
    title: `Title ${id}`,
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
  };
}

function insights(over: Partial<DiscoverInsights> = {}): DiscoverInsights {
  return {
    top_movie_genres: [{ id: 1, name: "action", score: 1 }],
    top_tv_genres: [{ id: 2, name: "drama", score: 1 }],
    disliked_genres: [],
    top_keywords: [{ id: 3, name: "space opera", score: 1 }],
    top_people: [],
    signals_used: 3,
    top_studios: [],
    top_contributors: [],
    negative_contributors: [],
    ...over,
  };
}

function make(): HomeFeedController {
  let feed!: HomeFeedController;
  $effect.root(() => {
    feed = new HomeFeedController();
  });
  return feed;
}

beforeEach(() => {
  mocks.discover.mockReset().mockResolvedValue([media(1)]);
  mocks.discoverInsights.mockReset().mockResolvedValue(insights());
  mocks.libraryStats
    .mockReset()
    .mockResolvedValue({ movie_share: 0.25, tv_share: 0.75 });
  mocks.discoverByGenre.mockReset().mockResolvedValue([media(2)]);
  mocks.discoverByKeyword.mockReset().mockResolvedValue([media(3)]);
  mocks.getCatalogs.mockReset().mockResolvedValue([]);
  mocks.catalogPage.mockReset().mockResolvedValue({ medias: [], nextSkip: 0 });
});

describe("homeTasteSpecs", () => {
  it("interleaves movie, TV, and keyword rows and caps each bucket", () => {
    const profile = insights({
      top_movie_genres: [
        { id: 1, name: "one", score: 1 },
        { id: 2, name: "two", score: 1 },
        { id: 3, name: "three", score: 1 },
      ],
      top_tv_genres: [{ id: 4, name: "four", score: 1 }],
      top_keywords: [{ id: 5, name: "slow burn", score: 1 }],
    });
    expect(homeTasteSpecs(profile, "movie").map((spec) => spec.key)).toEqual([
      "mg-1",
      "tg-4",
      "kw-5",
      "mg-2",
    ]);
  });
});

describe("HomeFeedController", () => {
  it("loads the blended row and personalized rows through one feed", async () => {
    const feed = make();
    await feed.load();

    expect(feed.rows.map((row) => row.key)).toEqual([
      "tastes",
      "mg-1",
      "tg-2",
      "kw-3",
    ]);
    expect(feed.rows.every((row) => !row.loading)).toBe(true);
    expect(mocks.discover).toHaveBeenCalledWith("all", {
      limit: HOME_ROW_LIMIT,
    });
    expect(mocks.discoverByKeyword).toHaveBeenCalledWith("tv", 3, {
      limit: HOME_ROW_LIMIT,
    });
  });

  it("keeps addon catalogs available for a profile without taste signals", async () => {
    mocks.discoverInsights.mockResolvedValue(insights({ signals_used: 0 }));
    mocks.getCatalogs.mockResolvedValue([
      {
        addonId: "addon",
        addonName: "Addon",
        addonUrl: "https://addon.example/manifest.json",
        catalogType: "movie",
        catalogId: "popular",
        name: "Addon Popular",
      },
    ]);
    mocks.catalogPage.mockResolvedValue({
      medias: [media(9)],
      nextSkip: 20,
    });

    const feed = make();
    await feed.load();

    expect(feed.rows.map((row) => row.key)).toEqual(["tastes"]);
    expect(feed.catalogRows[0]).toMatchObject({
      key: "catalog-addon-movie/popular",
      header: "Addon Popular",
      medias: [expect.objectContaining({ id: 9 })],
      loading: false,
    });
    expect(feed.catalogRefs.get(feed.catalogRows[0].key)?.addonId).toBe(
      "addon",
    );
  });

  it("isolates a failing row instead of failing the feed", async () => {
    mocks.discoverByGenre.mockRejectedValue(new Error("offline"));
    const feed = make();
    await feed.load();

    expect(feed.rows.find((row) => row.key === "mg-1")).toMatchObject({
      medias: [],
      loading: false,
    });
    expect(feed.rows.find((row) => row.key === "kw-3")?.medias).toHaveLength(1);
  });
});
