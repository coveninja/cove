import { afterEach, describe, expect, it, vi } from "vitest";

import { api, setTokenSource } from "$lib/api";
import type { Stream } from "$lib/types/addons";
import type { Settings } from "$lib/types/settings";
import type { Media } from "$lib/types/tmdb";

const BASE = "http://127.0.0.1:6969/api";

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    headers: { "Content-Type": "application/json" },
  });
}

const movie: Media = {
  id: 603,
  title: "The Matrix",
  name: "",
  overview: "",
  release_date: "",
  first_air_date: "",
  poster_path: "",
  vote_average: 8,
  media_type: "movie",
  trailer_url: "",
  clip_urls: "",
  images: [],
  popularity: 10,
};

const stream: Stream = {
  name: "Stream",
  title: "Stream",
  url: "https://stream.test/video",
  infoHash: "",
  addonName: "Addon",
};

const settingsPatch = {
  onboardingDone: true,
  defaultVolume: 0.75,
} as Settings;

interface Contract {
  name: string;
  invoke: () => Promise<unknown>;
  path: string;
  method?: string;
  body?: unknown;
  response?: unknown;
}

const metadataContracts: Contract[] = [
  {
    name: "search encodes its query",
    invoke: () => api.search("sci fi & fantasy"),
    path: "/search?q=sci%20fi%20%26%20fantasy",
  },
  {
    name: "multi-search encodes its query",
    invoke: () => api.searchMulti("person/title"),
    path: "/search/multi?q=person%2Ftitle",
  },
  {
    name: "person details",
    invoke: () => api.getPerson(42),
    path: "/person?id=42",
  },
  {
    name: "provider titles retain a zero limit",
    invoke: () => api.providerTitles(8, 0),
    path: "/provider?id=8&limit=0",
  },
  {
    name: "keyword search",
    invoke: () => api.getKeywords("time travel"),
    path: "/keywords?q=time%20travel",
  },
  {
    name: "similar titles",
    invoke: () => api.getSimilar(movie),
    path: "/similar?id=603&type=movie",
  },
  {
    name: "media by id",
    invoke: () => api.getMediaByID(603, "movie"),
    path: "/media?id=603&type=movie",
  },
  {
    name: "details",
    invoke: () => api.getDetails(movie),
    path: "/details?id=603&type=movie",
  },
  {
    name: "images",
    invoke: () => api.getImages(movie),
    path: "/images?id=603&type=movie",
  },
  {
    name: "videos",
    invoke: () => api.getVideos(movie),
    path: "/videos?id=603&type=movie",
  },
  {
    name: "logos",
    invoke: () => api.getLogos(603, "movie"),
    path: "/logos?id=603&type=movie",
  },
  {
    name: "TV seasons",
    invoke: () => api.tvSeasons(1396),
    path: "/tv/seasons?id=1396",
  },
  {
    name: "TV episodes",
    invoke: () => api.tvEpisodes(1396, 2),
    path: "/tv/episodes?id=1396&season=2",
  },
  {
    name: "stream lookup retains zero-valued episode coordinates",
    invoke: () => api.getStreams(1396, { type: "tv", season: 0, episode: 0 }),
    path: "/streams?id=1396&type=tv&season=0&episode=0",
  },
  {
    name: "subtitle lookup encodes all coordinates",
    invoke: () =>
      api.getSubtitles({ id: 1396, type: "tv", season: 2, episode: 3 }),
    path: "/subtitles?id=1396&type=tv&season=2&episode=3",
  },
  {
    name: "stream probe",
    invoke: () => api.probeStreams([stream], 900),
    path: "/streams/probe",
    method: "POST",
    body: { streams: [stream], timeoutMs: 900 },
  },
  {
    name: "torrent prefetch",
    invoke: () =>
      api.prefetchDownload("hash value", {
        season: 1,
        episode: 2,
        fileIdx: 0,
      }),
    path: "/prefetch-download?hash=hash+value&season=1&episode=2&fileIdx=0",
    method: "POST",
  },
];

const settingsAndAddonContracts: Contract[] = [
  {
    name: "settings read",
    invoke: () => api.getSettings(),
    path: "/settings",
  },
  {
    name: "settings update",
    invoke: () => api.updateSettings(settingsPatch),
    path: "/settings",
    method: "PUT",
    body: settingsPatch,
  },
  {
    name: "mpv config read",
    invoke: () => api.getMpvConf(),
    path: "/settings/mpv-conf",
    response: "profile=gpu-hq",
  },
  {
    name: "mpv config update",
    invoke: () => api.setMpvConf("profile=gpu-hq"),
    path: "/settings/mpv-conf",
    method: "PUT",
    body: "profile=gpu-hq",
  },
  {
    name: "discovery algorithm test",
    invoke: () => api.testDiscoveryAlgorithm("https://algo.test/a?b=1"),
    path: "/discover/algorithm/test",
    method: "POST",
    body: { url: "https://algo.test/a?b=1" },
  },
  {
    name: "addon list",
    invoke: () => api.getAddons(),
    path: "/addons",
  },
  {
    name: "addon install",
    invoke: () => api.addAddon("https://addon.test/manifest.json"),
    path: "/addons",
    method: "POST",
    body: { url: "https://addon.test/manifest.json" },
  },
  {
    name: "addon removal query encoding",
    invoke: () =>
      api.removeAddon("addon id", "https://addon.test/a?token=one two"),
    path: "/addons?id=addon+id&url=https%3A%2F%2Faddon.test%2Fa%3Ftoken%3Done+two",
    method: "DELETE",
  },
  {
    name: "addon toggle",
    invoke: () => api.toggleAddon("addon id", false),
    path: "/addons?id=addon+id",
    method: "PATCH",
    body: { enabled: false },
  },
  {
    name: "catalog list",
    invoke: () => api.getCatalogs(),
    path: "/catalogs",
  },
  {
    name: "catalog page options",
    invoke: () =>
      api.catalogPage(
        "addon id",
        "movie",
        "top picks",
        20,
        10,
        "https://addon.test/manifest.json",
      ),
    path:
      "/catalog?addonId=addon+id&catalogType=movie&catalogId=top+picks&skip=20" +
      "&limit=10&addonUrl=https%3A%2F%2Faddon.test%2Fmanifest.json",
  },
  {
    name: "catalog toggle",
    invoke: () =>
      api.toggleCatalog(
        "addon id",
        "movie:top",
        true,
        "https://addon.test/manifest.json",
      ),
    path:
      "/addons/catalog?id=addon+id&catalog=movie%3Atop" +
      "&url=https%3A%2F%2Faddon.test%2Fmanifest.json",
    method: "PATCH",
    body: { enabled: true },
  },
  {
    name: "Nuvio repository list",
    invoke: () => api.getNuvioRepos(),
    path: "/nuvio/repos",
  },
  {
    name: "Nuvio repository add",
    invoke: () => api.addNuvioRepo("https://repo.test/index.json"),
    path: "/nuvio/repos",
    method: "POST",
    body: { url: "https://repo.test/index.json" },
  },
  {
    name: "Nuvio repository removal",
    invoke: () => api.removeNuvioRepo("repo id"),
    path: "/nuvio/repos?id=repo+id",
    method: "DELETE",
  },
  {
    name: "Nuvio repository toggle",
    invoke: () => api.setNuvioRepoEnabled("repo id", false),
    path: "/nuvio/repos?id=repo+id",
    method: "PATCH",
    body: { enabled: false },
  },
  {
    name: "Nuvio repository refresh",
    invoke: () => api.refreshNuvioRepo("repo id"),
    path: "/nuvio/repos/refresh?id=repo+id",
    method: "POST",
  },
  {
    name: "Nuvio scraper toggle",
    invoke: () => api.setNuvioScraperEnabled("repo id", "scraper id", true),
    path: "/nuvio/scrapers?repoId=repo+id&scraperId=scraper+id",
    method: "PATCH",
    body: { enabled: true },
  },
  {
    name: "watch options",
    invoke: () => api.getWatchOptions(603, "movie"),
    path: "/watch-options?id=603&type=movie",
  },
  {
    name: "timestamps retain zero-valued coordinates",
    invoke: () => api.getTimestamps(1396, { season: 0, episode: 0 }),
    path: "/timestamps?id=1396&season=0&episode=0",
  },
];

const libraryAndDiscoveryContracts: Contract[] = [
  {
    name: "calendar",
    invoke: () => api.libraryCalendar(),
    path: "/library/calendar",
  },
  {
    name: "unfiltered library",
    invoke: () => api.libraryList(),
    path: "/library",
  },
  {
    name: "filtered library",
    invoke: () => api.libraryList("watch_later"),
    path: "/library?status=watch_later",
  },
  {
    name: "library item lookup",
    invoke: () => api.libraryGet(603, "movie"),
    path: "/library/603/movie",
  },
  {
    name: "library removal",
    invoke: () => api.libraryRemove(603, "movie"),
    path: "/library/603/movie",
    method: "DELETE",
  },
  {
    name: "library status update",
    invoke: () => api.librarySetStatus(603, "movie", "finished"),
    path: "/library/603/movie/status",
    method: "PATCH",
    body: { status: "finished" },
  },
  {
    name: "library rating removal",
    invoke: () => api.librarySetRating(603, "movie", null),
    path: "/library/603/movie/rating",
    method: "PATCH",
    body: { rating: null },
  },
  {
    name: "progress lookup",
    invoke: () => api.progressGet(1396, "tv", 0, 0),
    path: "/library/progress?tmdb_id=1396&media_type=tv&season=0&episode=0",
  },
  {
    name: "progress save",
    invoke: () =>
      api.progressSave({
        tmdb_id: 1396,
        media_type: "tv",
        season: 2,
        episode: 3,
        position_seconds: 120,
        duration_seconds: 2400,
        completed: false,
      }),
    path: "/library/progress",
    method: "POST",
    body: {
      tmdb_id: 1396,
      media_type: "tv",
      season: 2,
      episode: 3,
      position_seconds: 120,
      duration_seconds: 2400,
      completed: false,
    },
  },
  {
    name: "bulk progress save",
    invoke: () =>
      api.progressBulkSave({
        tmdb_id: 1396,
        media_type: "tv",
        title: "Breaking Bad",
        completed: true,
        status: "finished",
        episodes: [
          {
            season: 1,
            episode: 1,
            duration_seconds: 3480,
          },
        ],
      }),
    path: "/library/progress/bulk",
    method: "POST",
    body: {
      tmdb_id: 1396,
      media_type: "tv",
      title: "Breaking Bad",
      completed: true,
      status: "finished",
      episodes: [
        {
          season: 1,
          episode: 1,
          duration_seconds: 3480,
        },
      ],
    },
  },
  {
    name: "discovery options",
    invoke: () => api.discover("all", { limit: 0, profile: "kid" }),
    path: "/discover?type=all&limit=0&profile=kid",
  },
  {
    name: "genre discovery",
    invoke: () =>
      api.discoverByGenre("movie", 878, { limit: 12, profile: "adult" }),
    path: "/discover/genre?type=movie&genre=878&limit=12&profile=adult",
  },
  {
    name: "keyword discovery",
    invoke: () =>
      api.discoverByKeyword("tv", 123, { limit: 8, profile: "kid" }),
    path: "/discover/keyword?type=tv&keyword=123&limit=8&profile=kid",
  },
  {
    name: "top genres",
    invoke: () => api.discoverTopGenres("tv", 0),
    path: "/discover/genres?type=tv&limit=0",
  },
  {
    name: "top keywords",
    invoke: () => api.discoverTopKeywords(5),
    path: "/discover/keywords?limit=5",
  },
  {
    name: "genre list",
    invoke: () => api.genreList("movie"),
    path: "/genres?type=movie",
  },
  {
    name: "dismiss title",
    invoke: () => api.notInterested(movie),
    path: "/library/dismiss",
    method: "POST",
    body: { tmdb_id: 603, media_type: "movie" },
  },
  {
    name: "undo title dismissal",
    invoke: () => api.undoNotInterested(movie),
    path: "/library/dismiss",
    method: "DELETE",
    body: { tmdb_id: 603, media_type: "movie" },
  },
  {
    name: "library stats",
    invoke: () => api.libraryStats(),
    path: "/library/stats",
  },
  {
    name: "discovery insights",
    invoke: () => api.discoverInsights(),
    path: "/discover/insights",
  },
  {
    name: "activity stats",
    invoke: () => api.activityStats(),
    path: "/library/activity",
  },
  {
    name: "update check",
    invoke: () => api.checkUpdate(),
    path: "/update/check",
  },
];

const identityContracts: Contract[] = [
  {
    name: "profile list",
    invoke: () => api.profilesList(),
    path: "/profiles",
  },
  {
    name: "profile creation",
    invoke: () => api.profileCreate("Kids & Family"),
    path: "/profiles",
    method: "POST",
    body: { name: "Kids & Family" },
  },
  {
    name: "profile rename",
    invoke: () => api.profileRename("profile-id", "New Name"),
    path: "/profiles/profile-id",
    method: "PATCH",
    body: { name: "New Name" },
  },
  {
    name: "profile deletion",
    invoke: () => api.profileDelete("profile-id"),
    path: "/profiles/profile-id",
    method: "DELETE",
  },
  {
    name: "profile activation",
    invoke: () => api.profileActivate("profile-id"),
    path: "/profiles/profile-id/activate",
    method: "POST",
  },
  {
    name: "registration",
    invoke: () => api.authRegister("user@test.dev", "secret", "Primary"),
    path: "/auth/register",
    method: "POST",
    body: {
      email: "user@test.dev",
      password: "secret",
      profile_name: "Primary",
    },
  },
  {
    name: "registration confirmation",
    invoke: () =>
      api.authConfirmRegister("user@test.dev", "123456", "secret", "Primary"),
    path: "/auth/register/confirm",
    method: "POST",
    body: {
      email: "user@test.dev",
      token: "123456",
      password: "secret",
      profile_name: "Primary",
    },
  },
  {
    name: "password login",
    invoke: () => api.authLogin("user@test.dev", "secret"),
    path: "/auth/login",
    method: "POST",
    body: { email: "user@test.dev", password: "secret" },
  },
  {
    name: "OTP request",
    invoke: () => api.authSendOTP("user@test.dev"),
    path: "/auth/otp",
    method: "POST",
    body: { email: "user@test.dev" },
  },
  {
    name: "OTP verification",
    invoke: () => api.authVerifyOTP("user@test.dev", "123456"),
    path: "/auth/verify-otp",
    method: "POST",
    body: { email: "user@test.dev", token: "123456" },
  },
  {
    name: "logout",
    invoke: () => api.authLogout(),
    path: "/auth/logout",
    method: "POST",
  },
  {
    name: "current account",
    invoke: () => api.authMe(),
    path: "/auth/me",
  },
  {
    name: "account sync",
    invoke: () => api.authSync(),
    path: "/auth/sync",
    method: "POST",
  },
  {
    name: "client session read",
    invoke: () => api.clientSessionGet(),
    path: "/client-session",
  },
  {
    name: "client session save",
    invoke: () =>
      api.clientSessionSave({
        accessToken: "access",
        refreshToken: "refresh",
        email: "user@test.dev",
      }),
    path: "/client-session",
    method: "POST",
    body: {
      accessToken: "access",
      refreshToken: "refresh",
      email: "user@test.dev",
    },
  },
  {
    name: "client session deletion",
    invoke: () => api.clientSessionDelete(),
    path: "/client-session",
    method: "DELETE",
  },
  {
    name: "Trakt device flow",
    invoke: () => api.traktStartDeviceFlow(),
    path: "/trakt/device-code",
    method: "POST",
  },
  {
    name: "Trakt polling",
    invoke: () => api.traktPoll("device-code"),
    path: "/trakt/poll",
    method: "POST",
    body: { device_code: "device-code" },
  },
  {
    name: "Trakt unlink",
    invoke: () => api.traktUnlink(),
    path: "/trakt/unlink",
    method: "POST",
  },
  {
    name: "Trakt scrobble",
    invoke: () =>
      api.traktScrobble({
        action: "pause",
        tmdb_id: 1396,
        media_type: "tv",
        season: 2,
        episode: 3,
        progress: 42.5,
      }),
    path: "/trakt/scrobble",
    method: "POST",
    body: {
      action: "pause",
      tmdb_id: 1396,
      media_type: "tv",
      season: 2,
      episode: 3,
      progress: 42.5,
    },
  },
  {
    name: "Trakt sync",
    invoke: () => api.traktSyncNow(),
    path: "/trakt/sync",
    method: "POST",
  },
];

function contractSuite(name: string, contracts: Contract[]): void {
  describe(name, () => {
    afterEach(() => {
      vi.unstubAllGlobals();
      setTokenSource(() => null);
      api.clearInflight();
    });

    it.each(contracts)("$name", async (contract) => {
      const fetchMock = vi
        .fn()
        .mockResolvedValue(jsonResponse(contract.response ?? {}));
      vi.stubGlobal("fetch", fetchMock);

      await contract.invoke();

      expect(fetchMock).toHaveBeenCalledTimes(1);
      const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
      expect(url).toBe(`${BASE}${contract.path}`);
      expect(init.method ?? "GET").toBe(contract.method ?? "GET");
      if (contract.body === undefined) {
        expect(init.body).toBeUndefined();
      } else {
        expect(JSON.parse(String(init.body))).toEqual(contract.body);
        expect(new Headers(init.headers).get("Content-Type")).toBe(
          "application/json",
        );
      }
    });
  });
}

contractSuite("metadata and stream endpoint contracts", metadataContracts);
contractSuite(
  "settings, addon, and integration endpoint contracts",
  settingsAndAddonContracts,
);
contractSuite(
  "library and discovery endpoint contracts",
  libraryAndDiscoveryContracts,
);
contractSuite("profile, auth, and Trakt endpoint contracts", identityContracts);

describe("special API contracts", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    setTokenSource(() => null);
    api.clearInflight();
  });

  it("returns the unmasked remote-access token", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(jsonResponse({ token: "real-token" })),
    );

    await expect(api.revealRemoteAccessToken()).resolves.toBe("real-token");
  });

  it("builds fixed asset and speed-test URLs", () => {
    expect(api.speedtestUrl()).toBe(`${BASE}/speedtest`);
    expect(api.imgUrl("w342", "/poster.jpg")).toBe(
      `${BASE}/img/w342/poster.jpg`,
    );
  });

  it("accepts a successful updater restart response", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(api.applyUpdate()).resolves.toBeUndefined();
    expect(fetchMock).toHaveBeenCalledWith(`${BASE}/update/apply`, {
      method: "POST",
    });
  });

  it("preserves updater failure details", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(new Response("replace failed", { status: 500 })),
    );

    await expect(api.applyUpdate()).rejects.toMatchObject({
      status: 500,
      body: "replace failed",
      path: "/update/apply",
    });
  });

  it("preserves updater status when its failure body cannot be read", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 502,
        text: vi.fn().mockRejectedValue(new Error("connection closed")),
      } as unknown as Response),
    );

    await expect(api.applyUpdate()).rejects.toMatchObject({
      status: 502,
      body: "",
      path: "/update/apply",
    });
  });
});
