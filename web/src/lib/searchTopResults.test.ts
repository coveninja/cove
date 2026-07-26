import { describe, expect, it } from "vitest";

import { getTopSearchResults } from "$lib/searchTopResults";
import type { Media } from "$lib/types/tmdb";

function media(
  id: number,
  type: "movie" | "tv",
  popularity = 0,
  rating = 0,
): Media {
  return {
    id,
    title: type === "movie" ? `Movie ${id}` : "",
    name: type === "tv" ? `TV ${id}` : "",
    overview: "",
    release_date: "",
    first_air_date: "",
    poster_path: "",
    vote_average: rating,
    media_type: type,
    trailer_url: "",
    clip_urls: "",
    images: [],
    popularity,
  };
}

describe("getTopSearchResults", () => {
  it("reconstructs the interleaved backend relevance order", () => {
    const movies = [media(1, "movie"), media(3, "movie")];
    const tv = [media(2, "tv"), media(4, "tv")];

    const results = getTopSearchResults(
      movies,
      tv,
      ["tv:2", "movie:1", "tv:4", "movie:3"],
      { includeMovies: true, includeTV: true },
    );

    expect(results.map(({ media_type, id }) => `${media_type}:${id}`)).toEqual([
      "tv:2",
      "movie:1",
      "tv:4",
      "movie:3",
    ]);
  });

  it("applies type filters before taking the first six", () => {
    const movies = Array.from({ length: 7 }, (_, index) =>
      media(index + 1, "movie"),
    );
    const tv = Array.from({ length: 7 }, (_, index) =>
      media(index + 101, "tv"),
    );
    const order = [
      ...movies.map(({ id }) => `movie:${id}`),
      ...tv.map(({ id }) => `tv:${id}`),
    ];

    const results = getTopSearchResults(movies, tv, order, {
      includeMovies: false,
      includeTV: true,
    });

    expect(results.map(({ id }) => id)).toEqual([101, 102, 103, 104, 105, 106]);
  });

  it("ignores malformed and stale keys and deduplicates order and inputs", () => {
    const movie1 = media(1, "movie");
    const movie3 = media(3, "movie");
    const tv2 = media(2, "tv");

    const results = getTopSearchResults(
      [movie1, movie1, movie3],
      [tv2, tv2],
      ["bogus", "movie:999", "tv:2", "tv:2", "movie:1"],
      { includeMovies: true, includeTV: true },
    );

    expect(results).toEqual([tv2, movie1, movie3]);
  });

  it("falls back to current section order when title_order is absent", () => {
    const movies = [media(1, "movie"), media(2, "movie")];
    const tv = [media(3, "tv")];

    const results = getTopSearchResults(movies, tv, undefined, {
      includeMovies: true,
      includeTV: true,
    });

    expect(results).toEqual([...movies, ...tv]);
  });

  it("sorts the complete combined set before limiting and keeps relevance ties stable", () => {
    const movies = [
      media(1, "movie", 5),
      media(3, "movie", 100),
      media(5, "movie", 10),
      media(7, "movie", 1),
    ];
    const tv = [
      media(2, "tv", 50),
      media(4, "tv", 100),
      media(6, "tv", 20),
      media(8, "tv", 2),
    ];

    const results = getTopSearchResults(
      movies,
      tv,
      [
        "movie:1",
        "tv:2",
        "movie:3",
        "tv:4",
        "movie:5",
        "tv:6",
        "movie:7",
        "tv:8",
      ],
      {
        includeMovies: true,
        includeTV: true,
        compare: (a, b) => b.popularity - a.popularity,
      },
    );

    expect(results.map(({ media_type, id }) => `${media_type}:${id}`)).toEqual([
      "movie:3",
      "tv:4",
      "tv:2",
      "tv:6",
      "movie:5",
      "movie:1",
    ]);
  });

  it("returns no titles when both title types are filtered out", () => {
    expect(
      getTopSearchResults(
        [media(1, "movie")],
        [media(2, "tv")],
        ["movie:1", "tv:2"],
        { includeMovies: false, includeTV: false },
      ),
    ).toEqual([]);
  });
});
