import { SvelteMap } from "svelte/reactivity";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { Stream } from "$lib/types/addons";
import type { WatchProgress } from "$lib/types/library";
import type {
  Details,
  MediaImageObject,
  MediaImages,
  MediaVideoObject,
  MediaVideos,
} from "$lib/types/tmdb";
import {
  cn,
  countryName,
  epKey,
  epProgress,
  formatRating,
  formatRuntime,
  getImageOpt,
  getMaxQuality,
  getVideoOpt,
  inferQuality,
  progressPct,
  qualityClass,
  relativeDate,
} from "$lib/utils";

function stream(name: string, title = ""): Stream {
  return {
    name,
    title,
    url: "",
    infoHash: "hash",
    addonName: "Test",
  };
}

function details(overrides: Partial<Details> = {}): Details {
  return {
    title: "",
    name: "",
    poster_path: "",
    overview: "",
    genres: [],
    runtime: 0,
    episode_run_time: [],
    release_date: "",
    credits: { cast: [], crew: [] },
    release_dates: { results: [] },
    content_ratings: { results: [] },
    keywords: { keywords: [], results: [] },
    origin_country: [],
    production_companies: [],
    networks: [],
    status: "",
    number_of_seasons: 0,
    number_of_episodes: 0,
    seasons: [],
    ...overrides,
  };
}

function image(
  url: string,
  overrides: Partial<MediaImageObject> = {},
): MediaImageObject {
  return {
    aspect_ratio: 1.78,
    height: 1080,
    iso_639_1: "en",
    file_path: "",
    url,
    vote_average: 6,
    vote_count: 10,
    width: 1920,
    ...overrides,
  };
}

function images(
  backdrops: MediaImageObject[] = [],
  logos: MediaImageObject[] = [],
  posters: MediaImageObject[] = [],
): MediaImages {
  return { backdrops, logos, posters };
}

function video(
  key: string,
  overrides: Partial<MediaVideoObject> = {},
): MediaVideoObject {
  return {
    iso_639_1: "en",
    name: key,
    key,
    site: "YouTube",
    size: 1080,
    type: "Trailer",
    official: true,
    published_at: "",
    embed_url: "",
    ...overrides,
  };
}

function progress(overrides: Partial<WatchProgress> = {}): WatchProgress {
  return {
    id: "progress",
    library_entry_id: "entry",
    tmdb_id: 1,
    media_type: "tv",
    season: 1,
    episode: 2,
    position_seconds: 50,
    duration_seconds: 100,
    completed: false,
    watched_at: "",
    ...overrides,
  };
}

describe("shared UI utilities", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it("merges conditional and conflicting Tailwind classes", () => {
    expect(cn("px-2", false && "hidden", ["text-sm", "px-4"])).toBe(
      "text-sm px-4",
    );
  });

  it("resolves country names and falls back when Intl rejects a code", () => {
    expect(countryName("US")).toBe("United States");
    vi.spyOn(Intl, "DisplayNames").mockImplementation(() => {
      throw new RangeError("unsupported");
    });
    expect(countryName("not-a-region")).toBe("not-a-region");
  });

  it.each([
    ["Addon\n4K DV", "", "4k dv"],
    ["Addon\n4K HDR", "", "4k hdr"],
    ["Addon\n1080p", "4K source", "1080p"],
    ["Movie Dolby Vision 2160p", "", "4k dv"],
    ["Movie HDR UHD", "", "4k hdr"],
    ["Movie 4K", "", "4k"],
    ["Movie 1080 HDR", "", "1080p"],
    ["Movie FHD", "", "1080p"],
    ["Movie Full HD", "", "1080p"],
    ["Movie 720", "", "720p"],
    ["Movie 480", "", "480p"],
    ["Movie Telesync", "", "ts"],
    ["Movie [TS]", "", "ts"],
    ["Movie HDCAM", "", "cam"],
    ["Movie HDR", "", null],
    ["Unknown release", "", null],
  ])("infers quality from %j / %j as %j", (name, title, expected) => {
    expect(inferQuality(stream(name, title))).toBe(expected);
  });

  it("returns the highest quality present across streams", () => {
    expect(
      getMaxQuality([stream("720p"), stream("4K HDR"), stream("1080p")]),
    ).toBe("4k hdr");
    expect(getMaxQuality([stream("unknown")])).toBeNull();
  });

  it.each([
    ["4k dv", "purple"],
    ["4k hdr", "blue"],
    ["4k", "cyan"],
    ["1080p", "green"],
    ["720p", "yellow"],
    ["480p", "orange"],
    ["ts", "red-500"],
    ["cam", "red-700"],
    ["unknown", "secondary"],
  ])("maps %s to its visual quality class", (quality, token) => {
    expect(qualityClass(quality)).toContain(token);
  });

  it("formats movie runtimes and TV fallbacks", () => {
    expect(formatRuntime(details({ runtime: 125 }))).toBe("2h 5m");
    expect(formatRuntime(details({ episode_run_time: [48] }))).toBe("48m / ep");
    expect(formatRuntime(details({ number_of_seasons: 1 }))).toBe("1 Season");
    expect(formatRuntime(details({ number_of_seasons: 3 }))).toBe("3 Seasons");
    expect(formatRuntime(details({ number_of_episodes: 1 }))).toBe("1 Episode");
    expect(formatRuntime(details({ number_of_episodes: 8 }))).toBe(
      "8 Episodes",
    );
    expect(formatRuntime(details())).toBe("");
  });

  it("prefers US movie certification, then TV rating", () => {
    expect(
      formatRating(
        details({
          release_dates: {
            results: [
              {
                iso_3166_1: "US",
                release_dates: [
                  { certification: "" },
                  { certification: "PG-13" },
                ],
              },
            ],
          },
          content_ratings: {
            results: [{ iso_3166_1: "US", rating: "TV-MA" }],
          },
        }),
      ),
    ).toBe("PG-13");
    expect(
      formatRating(
        details({
          content_ratings: {
            results: [{ iso_3166_1: "US", rating: "TV-14" }],
          },
        }),
      ),
    ).toBe("TV-14");
    expect(formatRating(details())).toBe("");
  });

  it("filters images, prefers an exact language, and falls back safely", () => {
    const fallback = image("fallback", { iso_639_1: "fr", width: 500 });
    const neutral = image("neutral", {
      iso_639_1: null as unknown as string,
      width: 2000,
    });
    const matching = image("matching", { width: 2000 });
    const set = images([fallback, neutral, matching]);

    expect(
      getImageOpt(set, "backdrops", {
        iso: "en",
        minWidth: 1500,
        aspect_ratio: 1.8,
        voteAverage: 5,
        voteCount: 5,
      }),
    ).toBe("matching");
    expect(
      getImageOpt(set, "backdrops", {
        iso: "tr",
        minWidth: 1500,
      }),
    ).toBe("neutral");
    expect(getImageOpt(set, "backdrops", { height: 999 })).toBe("fallback");
    expect(getImageOpt(undefined, "backdrops")).toBe("");
    expect(getImageOpt(images(), "backdrops")).toBe("");
  });

  it("uses localized poster language priority before rating", () => {
    const turkish = image("turkish", {
      iso_639_1: "tr",
      vote_average: 0,
      vote_count: 0,
    });
    const english = image("english");
    // Go decodes TMDB's null iso_639_1 into the string field's zero value.
    const neutral = image("neutral", { iso_639_1: "" });

    expect(
      getImageOpt(images([], [], [english, neutral, turkish]), "posters", {
        iso: "tr",
        isoFallbacks: ["en", null],
      }),
    ).toBe("turkish");
    expect(
      getImageOpt(images([], [], [neutral, english]), "posters", {
        iso: "tr",
        isoFallbacks: ["en", null],
      }),
    ).toBe("english");
    expect(
      getImageOpt(images([], [], [neutral]), "posters", {
        iso: "tr",
        isoFallbacks: ["en", null],
      }),
    ).toBe("neutral");
  });

  it("can choose a deterministic random matching image", () => {
    vi.spyOn(Math, "random").mockReturnValue(0.75);
    expect(
      getImageOpt(images([image("first"), image("second")]), "backdrops", {
        randomize: true,
      }),
    ).toBe("second");
  });

  it("rejects images below each requested quality threshold", () => {
    const set = images([
      image("wrong-aspect", { aspect_ratio: 1.2 }),
      image("low-rating", { vote_average: 2 }),
      image("few-votes", { vote_count: 1 }),
      image("eligible"),
    ]);

    expect(
      getImageOpt(set, "backdrops", {
        aspect_ratio: 1.78,
        voteAverage: 5,
        voteCount: 5,
      }),
    ).toBe("eligible");
  });

  it("builds and filters video embeds with trailer fallback", () => {
    const videos: MediaVideos = {
      results: [
        video("clip", { type: "Clip", official: false, size: 720 }),
        video("trailer", { type: "Trailer" }),
        video("vimeo", {
          type: "Featurette",
          site: "Vimeo",
          embed_url: "",
        }),
      ],
    };

    expect(
      getVideoOpt(videos, "Clip", {
        iso: "en",
        site: "YouTube",
        size: 720,
        official: false,
      }),
    ).toBe("https://www.youtube.com/embed/clip");
    expect(getVideoOpt(videos, "Behind the Scenes")).toBe(
      "https://www.youtube.com/embed/trailer",
    );
    expect(getVideoOpt({ results: [videos.results[2]] }, "Featurette")).toBe(
      "https://player.vimeo.com/video/vimeo",
    );
  });

  it("uses explicit embed URLs and returns empty for unsupported sites", () => {
    expect(
      getVideoOpt(
        {
          results: [
            video("custom", {
              embed_url: "https://embed.test/custom",
              site: "Custom",
            }),
          ],
        },
        "Trailer",
      ),
    ).toBe("https://embed.test/custom");
    expect(
      getVideoOpt(
        { results: [video("unknown", { site: "Other" })] },
        "Trailer",
      ),
    ).toBe("");
    expect(getVideoOpt(null, "Trailer")).toBe("");
    expect(getVideoOpt({ results: [] }, "Trailer")).toBe("");
  });

  it("can choose a deterministic random matching video", () => {
    vi.spyOn(Math, "random").mockReturnValue(0.75);
    expect(
      getVideoOpt({ results: [video("first"), video("second")] }, "Trailer", {
        randomize: true,
      }),
    ).toBe("https://www.youtube.com/embed/second");
  });

  it("falls back to the first video when filters reject every match", () => {
    expect(
      getVideoOpt(
        {
          results: [
            video("first", { official: false }),
            video("second", { official: false }),
          ],
        },
        "Trailer",
        { official: true },
      ),
    ).toBe("https://www.youtube.com/embed/first");
  });

  it("formats near and distant release dates", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 6, 20, 12));

    expect(relativeDate(new Date(2026, 6, 21).toISOString())).toBe(
      "Coming Tomorrow",
    );
    expect(relativeDate(new Date(2026, 6, 25).toISOString())).toBe(
      "Coming in 5 Days",
    );
    expect(relativeDate(new Date(2026, 6, 30).toISOString())).toBe(
      "Coming Next Week",
    );
    expect(relativeDate(new Date(2026, 7, 20).toISOString())).toContain(
      "Coming ",
    );
  });

  it("keys episode progress and clamps percentages to a valid range", () => {
    const map = new SvelteMap<string, WatchProgress>();
    const saved = progress();
    map.set(epKey(1, 2), saved);

    expect(epKey(1, 2)).toBe("1:2");
    expect(epProgress(1, 2, map)).toBe(saved);
    expect(epProgress(1, 3, map)).toBeUndefined();
    expect(progressPct(saved)).toBe(50);
    expect(progressPct(progress({ position_seconds: 150 }))).toBe(100);
    expect(progressPct(progress({ position_seconds: -10 }))).toBe(0);
    expect(progressPct(progress({ duration_seconds: 0 }))).toBe(0);
    expect(progressPct(progress({ duration_seconds: -1 }))).toBe(0);
    expect(progressPct(progress({ position_seconds: Number.NaN }))).toBe(0);
    expect(progressPct(progress({ duration_seconds: Number.NaN }))).toBe(0);
    expect(
      progressPct(progress({ duration_seconds: Number.POSITIVE_INFINITY })),
    ).toBe(0);
  });
});
