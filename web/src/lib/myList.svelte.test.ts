import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  libraryList: vi.fn(),
  genreList: vi.fn(),
  getMediaByID: vi.fn(),
}));

vi.mock("$lib/api", () => ({ api: mocks }));

import { MyListDataController } from "$lib/myList.svelte";
import type { LibraryEntry } from "$lib/types/library";
import type { Media } from "$lib/types/tmdb";

function entry(id: number): LibraryEntry {
  return {
    id: `entry-${id}`,
    tmdb_id: id,
    media_type: "movie",
    title: `Title ${id}`,
    poster_path: `/poster-${id}.jpg`,
    status: "watch_later",
    vote_average: 0,
    last_air_date: "",
    added_at: "2026-01-01T00:00:00Z",
    updated_at: "2026-01-01T00:00:00Z",
  };
}

function media(id: number): Media {
  return {
    id,
    title: `Title ${id}`,
    name: "",
    overview: "Loaded metadata",
    release_date: "",
    first_air_date: "",
    poster_path: `/poster-${id}.jpg`,
    vote_average: 0,
    media_type: "movie",
    trailer_url: "",
    clip_urls: "",
    images: [],
    popularity: 0,
  };
}

function make(): MyListDataController {
  let data!: MyListDataController;
  $effect.root(() => {
    data = new MyListDataController();
  });
  return data;
}

beforeEach(() => {
  mocks.libraryList.mockReset().mockResolvedValue([]);
  mocks.genreList.mockReset().mockResolvedValue([]);
  mocks.getMediaByID
    .mockReset()
    .mockImplementation(async (id: number) => media(id));
});

describe("MyListDataController", () => {
  it("loads entries and hydrates their complete media records", async () => {
    mocks.libraryList.mockResolvedValue([entry(1), entry(2)]);
    const data = make();
    await data.loadEntries();
    await vi.waitFor(() =>
      expect(Object.keys(data.mediaByKey)).toHaveLength(2),
    );

    expect(data.entries).toHaveLength(2);
    expect(data.mediaByKey["1-movie"]?.overview).toBe("Loaded metadata");
    expect(data.loading).toBe(false);
  });

  it("does not duplicate metadata requests during a silent refresh", async () => {
    let resolveMedia!: (value: Media) => void;
    mocks.getMediaByID.mockReturnValue(
      new Promise((resolve) => {
        resolveMedia = resolve;
      }),
    );
    mocks.libraryList.mockResolvedValue([entry(1)]);
    const data = make();
    await data.loadEntries();
    await data.loadEntries(false);

    expect(mocks.getMediaByID).toHaveBeenCalledOnce();
    resolveMedia(media(1));
    await vi.waitFor(() => expect(data.mediaByKey["1-movie"]).toBeDefined());
  });

  it("keeps the last successful list when a refresh fails", async () => {
    mocks.libraryList
      .mockResolvedValueOnce([entry(1)])
      .mockRejectedValueOnce(new Error("offline"));
    const data = make();
    await data.loadEntries();
    await data.loadEntries(false);
    expect(data.entries.map((item) => item.tmdb_id)).toEqual([1]);
  });

  it("loads separate movie and TV genre maps", async () => {
    mocks.genreList
      .mockResolvedValueOnce([{ id: 28, name: "Action" }])
      .mockResolvedValueOnce([{ id: 18, name: "Drama" }]);
    const data = make();
    await data.loadGenreNames();
    expect(data.genreNames).toEqual({
      movie: { 28: "Action" },
      tv: { 18: "Drama" },
    });
  });
});
