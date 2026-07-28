import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({ catalogPage: vi.fn() }));

vi.mock("$lib/api", () => ({
  api: { catalogPage: mocks.catalogPage },
}));

import { CatalogPager, type CatalogIdentity } from "$lib/catalogPager.svelte";
import type { Media } from "$lib/types/tmdb";

const identity: CatalogIdentity = {
  addonId: "addon",
  catalogType: "movie",
  catalogId: "popular",
  addonUrl: "https://addon.example/manifest.json",
};

function media(id: number, mediaType = "movie"): Media {
  return {
    id,
    title: `Title ${id}`,
    name: "",
    overview: "",
    release_date: "",
    first_air_date: "",
    poster_path: "",
    vote_average: 0,
    media_type: mediaType,
    trailer_url: "",
    clip_urls: "",
    images: [],
    popularity: 0,
  };
}

function make(options: ConstructorParameters<typeof CatalogPager>[0] = {}) {
  let pager!: CatalogPager;
  $effect.root(() => {
    pager = new CatalogPager(options);
  });
  return pager;
}

beforeEach(() => {
  mocks.catalogPage.mockReset().mockResolvedValue({ medias: [], nextSkip: 0 });
});

describe("CatalogPager", () => {
  it("loads the first page when its identity is set", async () => {
    mocks.catalogPage.mockResolvedValue({
      medias: [media(1), media(2)],
      nextSkip: 40,
    });
    const pager = make();
    pager.reset(identity);

    await vi.waitFor(() => expect(pager.loading).toBe(false));

    expect(mocks.catalogPage).toHaveBeenCalledWith(
      "addon",
      "movie",
      "popular",
      0,
      40,
      identity.addonUrl,
    );
    expect(pager.medias.map((item) => item.id)).toEqual([1, 2]);
    expect(pager.nextSkip).toBe(40);
    expect(pager.hasMore).toBe(true);
  });

  it("deduplicates overlapping pages", async () => {
    mocks.catalogPage
      .mockResolvedValueOnce({
        medias: [media(1), media(2)],
        nextSkip: 40,
      })
      .mockResolvedValueOnce({
        medias: [media(2), media(3)],
        nextSkip: 80,
      });
    const pager = make();
    pager.reset(identity);
    await vi.waitFor(() => expect(pager.loading).toBe(false));
    await pager.loadMore();

    expect(pager.medias.map((item) => item.id)).toEqual([1, 2, 3]);
    expect(pager.nextSkip).toBe(80);
  });

  it("enforces the item cap exactly", async () => {
    mocks.catalogPage.mockResolvedValue({
      medias: [media(1), media(2), media(3)],
      nextSkip: 40,
    });
    const pager = make({ maxItems: 2 });
    pager.reset(identity);
    await vi.waitFor(() => expect(pager.loading).toBe(false));

    expect(pager.medias.map((item) => item.id)).toEqual([1, 2]);
    expect(pager.hasMore).toBe(false);
  });

  it("ignores an old response after the catalog changes", async () => {
    let resolveOld!: (value: { medias: Media[]; nextSkip: number }) => void;
    mocks.catalogPage
      .mockReturnValueOnce(
        new Promise((resolve) => {
          resolveOld = resolve;
        }),
      )
      .mockResolvedValueOnce({ medias: [media(2)], nextSkip: 40 });
    const pager = make();
    pager.reset(identity);
    pager.reset({ ...identity, catalogId: "new" });
    await vi.waitFor(() =>
      expect(pager.medias.map((item) => item.id)).toEqual([2]),
    );

    resolveOld({ medias: [media(1)], nextSkip: 40 });
    await Promise.resolve();

    expect(pager.medias.map((item) => item.id)).toEqual([2]);
    expect(pager.loading).toBe(false);
  });

  it("does not reload an unchanged identity", async () => {
    const pager = make();
    pager.reset(identity);
    await vi.waitFor(() => expect(pager.loading).toBe(false));
    pager.reset({ ...identity });
    expect(mocks.catalogPage).toHaveBeenCalledOnce();
  });
});
