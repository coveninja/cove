import { describe, expect, it } from "vitest";

import {
  compareEntries,
  defaultCompare,
  genresFor,
  hasNewEpisodes,
  lastWatchedAt,
  personalRating,
  releaseDate,
  titleOf,
  tmdbRating,
  toMediaKey,
  ts,
  type MediaByKey,
  type SortKey,
} from "$lib/myList";
import type { LibraryEntry } from "$lib/types/library";
import type { Media } from "$lib/types/tmdb";

function entry(over: Partial<LibraryEntry> = {}): LibraryEntry {
  return {
    id: "id-1",
    tmdb_id: 1,
    media_type: "movie",
    title: "Title",
    poster_path: "",
    status: "watch_later",
    vote_average: 0,
    last_air_date: "",
    added_at: "2024-01-01T00:00:00Z",
    ...over,
  } as LibraryEntry;
}

function media(over: Partial<Media> = {}): Media {
  return { id: 1, media_type: "movie", ...over } as Media;
}

/** Sorts a copy with the given key, returning titles in order. */
function order(
  entries: LibraryEntry[],
  key: SortKey,
  byKey: MediaByKey = {},
): string[] {
  return entries
    .toSorted((a, b) => compareEntries(a, b, key, byKey))
    .map((e) => e.title);
}

describe("ts", () => {
  it("returns 0 for missing or unparseable dates", () => {
    expect(ts(undefined)).toBe(0);
    expect(ts(null)).toBe(0);
    expect(ts("")).toBe(0);
    expect(ts("not-a-date")).toBe(0);
  });

  it("parses ISO dates to epoch millis", () => {
    expect(ts("2024-01-01T00:00:00Z")).toBe(Date.UTC(2024, 0, 1));
  });
});

describe("hasNewEpisodes", () => {
  const watching = {
    media_type: "tv",
    status: "watching",
    last_aired_season: 2,
    last_aired_episode: 5,
  } as const;

  it("is false for movies and for non-watching shows", () => {
    expect(hasNewEpisodes(entry({ ...watching, media_type: "movie" }))).toBe(
      false,
    );
    expect(hasNewEpisodes(entry({ ...watching, status: "finished" }))).toBe(
      false,
    );
  });

  it("is false when the show has no aired-episode data", () => {
    expect(
      hasNewEpisodes(
        entry({ media_type: "tv", status: "watching", last_aired_season: 2 }),
      ),
    ).toBe(false);
  });

  it("is true when a later season has aired", () => {
    const e = entry({
      ...watching,
      last_watched_season: 1,
      last_watched_episode: 9,
    });
    expect(hasNewEpisodes(e)).toBe(true);
  });

  it("compares episode numbers within the same season, not timestamps", () => {
    expect(
      hasNewEpisodes(
        entry({ ...watching, last_watched_season: 2, last_watched_episode: 4 }),
      ),
    ).toBe(true);
    expect(
      hasNewEpisodes(
        entry({ ...watching, last_watched_season: 2, last_watched_episode: 5 }),
      ),
    ).toBe(false);
    // Watched ahead of what has aired — not "new".
    expect(
      hasNewEpisodes(
        entry({ ...watching, last_watched_season: 3, last_watched_episode: 1 }),
      ),
    ).toBe(false);
  });

  it("treats a never-watched show as having new episodes", () => {
    expect(hasNewEpisodes(entry(watching))).toBe(true);
  });
});

describe("accessors", () => {
  it("prefers vote_average on the entry over the loaded media", () => {
    const e = entry({ tmdb_id: 7, vote_average: 8.1 });
    const byKey: MediaByKey = { "7-movie": media({ vote_average: 2 }) };
    expect(tmdbRating(e, byKey)).toBe(8.1);
  });

  it("falls back to the loaded media, then to 0", () => {
    const e = entry({ tmdb_id: 7, vote_average: 0 });
    expect(tmdbRating(e, { "7-movie": media({ vote_average: 6.5 }) })).toBe(6.5);
    expect(tmdbRating(e, {})).toBe(0);
  });

  it("sinks unrated entries below rated ones", () => {
    expect(personalRating(entry({ rating: 0 }))).toBe(0);
    expect(personalRating(entry())).toBe(-1);
  });

  it("prefers last_watched_at, then watched_at/updated_at, then added_at", () => {
    expect(
      lastWatchedAt(
        entry({
          last_watched_at: "2024-06-01T00:00:00Z",
          added_at: "2020-01-01T00:00:00Z",
        }),
      ),
    ).toBe(Date.UTC(2024, 5, 1));
    expect(lastWatchedAt(entry({ added_at: "2020-01-01T00:00:00Z" }))).toBe(
      Date.UTC(2020, 0, 1),
    );
  });

  it("prefers the entry's last_air_date, then media release/air dates", () => {
    const byKey: MediaByKey = {
      "7-movie": media({ release_date: "2019-05-05" } as Partial<Media>),
    };
    expect(
      releaseDate(entry({ tmdb_id: 7, last_air_date: "2022-03-03" }), byKey),
    ).toBe(ts("2022-03-03"));
    expect(
      releaseDate(
        entry({ tmdb_id: 7, last_air_date: undefined as unknown as string }),
        byKey,
      ),
    ).toBe(ts("2019-05-05"));
    expect(releaseDate(entry({ tmdb_id: 7 }), {})).toBe(0);
  });

  // Pre-existing quirk, preserved verbatim from the three inline copies this
  // module replaced: the chain uses `??`, but the Go backend stores
  // last_air_date as "" (not null) for movies — and "" is not nullish, so the
  // media release_date fallback never fires for them and release_desc sorts
  // every movie as epoch 0. Changing `??` to `||` would fix it, but that is a
  // behaviour change, not a refactor.
  it("does not fall back to media dates when last_air_date is empty string", () => {
    const byKey: MediaByKey = {
      "7-movie": media({ release_date: "2019-05-05" } as Partial<Media>),
    };
    expect(releaseDate(entry({ tmdb_id: 7, last_air_date: "" }), byKey)).toBe(0);
  });

  it("lowercases titles for case-insensitive ordering", () => {
    expect(titleOf(entry({ title: "Zulu" }))).toBe("zulu");
    expect(titleOf(entry({ title: undefined as unknown as string }))).toBe("");
  });

  it("keys media by tmdb id and type", () => {
    expect(toMediaKey(entry({ tmdb_id: 42, media_type: "tv" }))).toBe("42-tv");
  });
});

describe("genresFor", () => {
  it("is empty until the media has loaded", () => {
    expect(genresFor(entry({ tmdb_id: 7 }), {}, {})).toEqual([]);
  });

  it("prefers names already on the media", () => {
    const byKey: MediaByKey = {
      "7-movie": media({
        genres: [{ id: 1, name: "Drama" }],
        genre_ids: [2],
      } as Partial<Media>),
    };
    expect(genresFor(entry({ tmdb_id: 7 }), byKey, { movie: { 2: "Comedy" } })).toEqual(
      ["Drama"],
    );
  });

  it("maps genre_ids through the per-type genre list, dropping unknowns", () => {
    const byKey: MediaByKey = {
      "7-movie": media({ genre_ids: [2, 99] } as Partial<Media>),
    };
    expect(
      genresFor(entry({ tmdb_id: 7 }), byKey, { movie: { 2: "Comedy" } }),
    ).toEqual(["Comedy"]);
  });
});

describe("defaultCompare", () => {
  it("puts shows with new episodes first", () => {
    const stale = entry({ title: "stale", added_at: "2025-01-01T00:00:00Z" });
    const fresh = entry({
      title: "fresh",
      media_type: "tv",
      status: "watching",
      last_aired_season: 1,
      last_aired_episode: 2,
      added_at: "2020-01-01T00:00:00Z",
    });
    expect([stale, fresh].toSorted(defaultCompare).map((e) => e.title)).toEqual([
      "fresh",
      "stale",
    ]);
  });

  it("falls back to most-recent last_air_date or added_at", () => {
    const older = entry({ title: "older", added_at: "2020-01-01T00:00:00Z" });
    const newer = entry({ title: "newer", added_at: "2024-01-01T00:00:00Z" });
    expect([older, newer].toSorted(defaultCompare).map((e) => e.title)).toEqual([
      "newer",
      "older",
    ]);
  });
});

describe("compareEntries", () => {
  const a = entry({
    tmdb_id: 1,
    title: "Alpha",
    added_at: "2020-01-01T00:00:00Z",
    rating: 1,
    vote_average: 9,
    last_watched_at: "2020-06-01T00:00:00Z",
    last_air_date: "2001-01-01",
  });
  const b = entry({
    tmdb_id: 2,
    title: "Beta",
    added_at: "2024-01-01T00:00:00Z",
    rating: 5,
    vote_average: 3,
    last_watched_at: "2024-06-01T00:00:00Z",
    last_air_date: "2011-01-01",
  });
  const entries = [a, b];

  it("orders by added_at in both directions", () => {
    expect(order(entries, "added_desc")).toEqual(["Beta", "Alpha"]);
    expect(order(entries, "added_asc")).toEqual(["Alpha", "Beta"]);
  });

  it("orders by title ascending", () => {
    expect(order(entries, "title_asc")).toEqual(["Alpha", "Beta"]);
  });

  it("orders by TMDB rating descending", () => {
    expect(order(entries, "tmdb_desc")).toEqual(["Alpha", "Beta"]);
  });

  it("orders by personal rating descending", () => {
    expect(order(entries, "personal_desc")).toEqual(["Beta", "Alpha"]);
  });

  it("orders by last watched descending", () => {
    expect(order(entries, "watched_desc")).toEqual(["Beta", "Alpha"]);
  });

  it("orders by release date descending", () => {
    expect(order(entries, "release_desc")).toEqual(["Beta", "Alpha"]);
  });

  it("falls back to defaultCompare for the default key", () => {
    expect(order(entries, "default")).toEqual(["Beta", "Alpha"]);
  });
});
