import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  searchMulti: vi.fn(),
  getKeywords: vi.fn(),
}));

vi.mock("$lib/api", () => ({ api: mocks }));

import { SearchController } from "$lib/searchController.svelte";
import { emptyResults } from "$lib/search";
import type { SearchResults } from "$lib/api";

function results(id: number): SearchResults {
  return {
    ...emptyResults(),
    movies: [
      {
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
      },
    ],
    title_order: [`movie:${id}`],
  };
}

function make(): SearchController {
  let search!: SearchController;
  $effect.root(() => {
    search = new SearchController();
  });
  return search;
}

beforeEach(() => {
  vi.useFakeTimers();
  mocks.searchMulti.mockReset().mockResolvedValue(emptyResults());
  mocks.getKeywords.mockReset().mockResolvedValue([]);
});

afterEach(() => {
  vi.useRealTimers();
});

describe("SearchController", () => {
  it("debounces and trims a search query", async () => {
    mocks.searchMulti.mockResolvedValue(results(1));
    mocks.getKeywords.mockResolvedValue([{ id: 7, name: "matrix" }]);
    const search = make();
    search.schedule("  matrix  ");

    await vi.advanceTimersByTimeAsync(399);
    expect(mocks.searchMulti).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);

    expect(mocks.searchMulti).toHaveBeenCalledWith("matrix");
    expect(search.data.movies[0].id).toBe(1);
    expect(search.keywords).toEqual([{ id: 7, name: "matrix" }]);
    expect(search.loading).toBe(false);
  });

  it("clears immediately for an empty query", () => {
    const onClear = vi.fn();
    const search = make();
    search.data = results(1);
    search.keywords = [{ id: 1, name: "old" }];
    search.schedule("   ", { onClear });

    expect(search.data).toEqual(emptyResults());
    expect(search.keywords).toEqual([]);
    expect(onClear).toHaveBeenCalledOnce();
  });

  it("does not let a slower old request replace newer results", async () => {
    let resolveOld!: (value: SearchResults) => void;
    mocks.searchMulti
      .mockReturnValueOnce(
        new Promise((resolve) => {
          resolveOld = resolve;
        }),
      )
      .mockResolvedValueOnce(results(2));
    const search = make();
    search.schedule("old", {}, 0);
    await vi.advanceTimersByTimeAsync(0);

    search.schedule("new", {}, 0);
    await vi.advanceTimersByTimeAsync(0);
    expect(search.data.movies[0].id).toBe(2);

    resolveOld(results(1));
    await Promise.resolve();
    await Promise.resolve();
    expect(search.data.movies[0].id).toBe(2);
  });

  it("runs shell-specific hooks around a successful load", async () => {
    const beforeLoad = vi.fn();
    const afterLoad = vi.fn();
    const onLoading = vi.fn();
    const search = make();
    search.schedule("query", { beforeLoad, afterLoad, onLoading }, 0);
    await vi.advanceTimersByTimeAsync(0);

    expect(beforeLoad).toHaveBeenCalledWith("query");
    expect(onLoading).toHaveBeenCalledWith(true);
    expect(onLoading).toHaveBeenLastCalledWith(false);
    expect(afterLoad).toHaveBeenCalledWith(search.data);
  });
});
