import { describe, expect, it } from "vitest";

import {
  emptyResults,
  normalizeResults,
  qualityTargets,
  toggleSearchType,
  withKnownFor,
} from "$lib/search";
import type { SearchResults } from "$lib/api";
import type { Media } from "$lib/types/tmdb";

function movie(id: number, over: Partial<Media> = {}): Media {
  return { id, media_type: "movie", title: `M${id}`, ...over } as Media;
}

function person(
  knownFor: Media[],
  popularity = 1,
): SearchResults["people"][number] {
  return { id: 900, name: "P", popularity, known_for: knownFor } as
    SearchResults["people"][number];
}

describe("emptyResults", () => {
  it("returns all five sections empty", () => {
    expect(emptyResults()).toEqual({
      movies: [],
      tv: [],
      people: [],
      providers: [],
      title_order: [],
    });
  });

  it("returns a fresh object each call so callers can't share state", () => {
    const a = emptyResults();
    a.movies.push(movie(1));
    expect(emptyResults().movies).toEqual([]);
  });
});

describe("normalizeResults", () => {
  it("substitutes empty arrays for null/absent sections", () => {
    expect(normalizeResults({})).toEqual(emptyResults());
    expect(
      normalizeResults({ movies: null as unknown as Media[] }),
    ).toEqual(emptyResults());
  });

  it("passes through populated sections unchanged", () => {
    const movies = [movie(1)];
    expect(normalizeResults({ movies }).movies).toBe(movies);
  });
});

describe("withKnownFor", () => {
  it("appends person credits of the requested type", () => {
    const out = withKnownFor(
      [movie(1)],
      "movie",
      [person([movie(2), { ...movie(3), media_type: "tv" } as Media])],
    );
    expect(out.map((m) => m.id)).toEqual([1, 2]);
  });

  it("does not duplicate a title already in the list", () => {
    const out = withKnownFor([movie(1)], "movie", [person([movie(1)])]);
    expect(out.map((m) => m.id)).toEqual([1]);
  });

  it("dedupes across multiple people", () => {
    const out = withKnownFor(
      [],
      "movie",
      [person([movie(5)]), person([movie(5), movie(6)])],
    );
    expect(out.map((m) => m.id)).toEqual([5, 6]);
  });

  it("tolerates people with no known_for and leaves the input untouched", () => {
    const list = [movie(1)];
    const out = withKnownFor(list, "movie", [
      { id: 1, name: "P", popularity: 1 } as SearchResults["people"][number],
    ]);
    expect(out).toEqual(list);
    expect(out).not.toBe(list);
  });
});

describe("toggleSearchType", () => {
  it("adds a type that is not selected", () => {
    expect(toggleSearchType(["movie"], "tv")).toEqual(["movie", "tv"]);
  });

  it("removes a selected type", () => {
    expect(toggleSearchType(["movie", "tv"], "movie")).toEqual(["tv"]);
  });

  it("refuses to clear the last remaining type", () => {
    expect(toggleSearchType(["movie"], "movie")).toEqual(["movie"]);
  });
});

describe("qualityTargets", () => {
  it("tags each id with its media type, movies first", () => {
    expect(
      qualityTargets({
        movies: [movie(1)],
        tv: [{ ...movie(2), media_type: "tv" } as Media],
      }),
    ).toEqual([
      { id: 1, type: "movie" },
      { id: 2, type: "tv" },
    ]);
  });

  it("is empty for an empty result set", () => {
    expect(qualityTargets({ movies: [], tv: [] })).toEqual([]);
  });
});
