import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const settingsValue = vi.hoisted(() => ({
  streamSelectionMode: "balanced",
  measuredBandwidthMbps: 80,
  defaultProvider: "Torrentio",
  sourcePreference: "cached",
  probeStreams: true,
  defaultAudioLang: "original",
}));

const apiMock = vi.hoisted(() => ({
  getSubtitles: vi.fn(),
  getStreams: vi.fn(),
  tvEpisodes: vi.fn(),
}));
const rankStreamsWithProbe = vi.hoisted(() => vi.fn());
const playerMock = vi.hoisted(() => ({ setFullscreen: vi.fn() }));

vi.mock("$lib/api", () => ({ api: apiMock }));
vi.mock("$lib/streamSelection", () => ({ rankStreamsWithProbe }));
vi.mock("$lib/player/player.svelte", () => ({ Player: playerMock }));
vi.mock("$lib/stores/settings", () => ({
  settings: {
    subscribe(run: (value: typeof settingsValue) => void) {
      run(settingsValue);
      return () => {};
    },
  },
}));

import { PlaybackStore } from "$lib/playback.svelte";
import type { Stream } from "$lib/types/addons";
import type { Media } from "$lib/types/tmdb";

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
    original_language: "ja",
  };
}

function stream(key: string): Stream {
  return {
    name: key,
    title: key,
    url: `https://stream.test/${key}`,
    infoHash: "",
    addonName: "Test",
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

describe("PlaybackStore", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiMock.getSubtitles.mockResolvedValue([]);
    apiMock.tvEpisodes.mockResolvedValue([]);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("starts immediately and only applies subtitles from the current session", async () => {
    const store = new PlaybackStore();
    const sound = vi.fn().mockResolvedValue(undefined);
    store.init({ playStartSound: sound, openMediaDetail: vi.fn() });
    const firstSubs =
      deferred<Array<{ id: string; url: string; lang: string }>>();
    const secondSubs =
      deferred<Array<{ id: string; url: string; lang: string }>>();
    apiMock.getSubtitles
      .mockReturnValueOnce(firstSubs.promise)
      .mockReturnValueOnce(secondSubs.promise);

    store.startPlayback(media(1), stream("first"));
    store.startPlayback(media(2, "tv"), stream("second"), 3, 4, "Episode");

    expect(sound).toHaveBeenCalledTimes(2);
    expect(store.playerMode).toBe("full");
    expect(store.playerSession).toMatchObject({
      media: { id: 2 },
      season: 3,
      episode: 4,
      episodeName: "Episode",
      subtitles: [],
    });
    expect(apiMock.getSubtitles).toHaveBeenLastCalledWith({
      id: 2,
      type: "tv",
      season: 3,
      episode: 4,
    });

    firstSubs.resolve([{ id: "old", url: "old", lang: "en" }]);
    await Promise.resolve();
    expect(store.playerSession?.subtitles).toEqual([]);

    secondSubs.resolve([{ id: "new", url: "new", lang: "ja" }]);
    await Promise.resolve();
    expect(store.playerSession?.subtitles).toEqual([
      { id: "new", url: "new", lang: "ja" },
    ]);
  });

  it("treats sound and malformed subtitle failures as non-critical", async () => {
    const store = new PlaybackStore();
    const rejectedSound = vi
      .fn()
      .mockRejectedValue(new Error("audio context blocked"));
    store.init({ playStartSound: rejectedSound, openMediaDetail: vi.fn() });
    apiMock.getSubtitles.mockResolvedValue(null);

    store.startPlayback(media(1), stream("playable"));
    await Promise.resolve();
    await Promise.resolve();

    expect(rejectedSound).toHaveBeenCalledOnce();
    expect(store.playerSession?.stream.name).toBe("playable");
    expect(store.playerSession?.subtitles).toEqual([]);

    apiMock.getSubtitles.mockRejectedValueOnce(new Error("addon unavailable"));
    store.startPlayback(media(2), stream("still-playable"));
    await Promise.resolve();
    await Promise.resolve();
    expect(store.playerSession?.stream.name).toBe("still-playable");
  });

  it("replaces and expires playback toast timers", () => {
    vi.useFakeTimers();
    const store = new PlaybackStore();

    store.showPlaybackToast("first", 100);
    vi.advanceTimersByTime(50);
    store.showPlaybackToast("second", 200);
    vi.advanceTimersByTime(50);
    expect(store.playbackToast).toBe("second");

    vi.advanceTimersByTime(149);
    expect(store.playbackToast).toBe("second");
    vi.advanceTimersByTime(1);
    expect(store.playbackToast).toBeNull();
  });

  it("quick-plays the highest ranked movie using current selection preferences", async () => {
    const store = new PlaybackStore();
    const input = [stream("slow"), stream("best")];
    apiMock.getStreams.mockResolvedValue(input);
    rankStreamsWithProbe.mockResolvedValue([input[1], input[0]]);

    await store.quickPlay(media(603));

    expect(apiMock.getStreams).toHaveBeenCalledWith(
      603,
      {},
      expect.any(AbortSignal),
    );
    expect(rankStreamsWithProbe).toHaveBeenCalledWith(
      input,
      "balanced",
      {
        measuredBandwidthMbps: 80,
        preferredProvider: "Torrentio",
        sourcePreference: "cached",
        probeEnabled: true,
        defaultAudioLang: "ja",
      },
      expect.any(AbortSignal),
    );
    expect(store.quickPlayPending).toBeNull();
    expect(store.playerSession).toMatchObject({
      media: { id: 603 },
      stream: input[1],
      candidates: [input[1], input[0]],
      attempt: 0,
    });
  });

  it("retries empty stream searches and accepts the eighth attempt", async () => {
    vi.useFakeTimers();
    const store = new PlaybackStore();
    const winner = stream("eventual-winner");
    apiMock.getStreams
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([winner]);
    rankStreamsWithProbe.mockResolvedValue([winner]);

    const pending = store.quickPlay(media(44));
    await vi.advanceTimersByTimeAsync(14_000);
    await pending;

    expect(apiMock.getStreams).toHaveBeenCalledTimes(8);
    expect(store.playerSession?.stream).toEqual(winner);
  });

  it("reports no streams immediately after the final retry, then clears it", async () => {
    vi.useFakeTimers();
    const store = new PlaybackStore();
    apiMock.getStreams.mockResolvedValue([]);

    const pending = store.quickPlay(media(45));
    await vi.advanceTimersByTimeAsync(14_000);
    await pending;

    expect(apiMock.getStreams).toHaveBeenCalledTimes(8);
    expect(store.quickPlayPending?.message).toBe("No stream found");

    await vi.advanceTimersByTimeAsync(2_499);
    expect(store.quickPlayPending?.message).toBe("No stream found");
    await vi.advanceTimersByTimeAsync(1);
    expect(store.quickPlayPending).toBeNull();
  });

  it("releases retry backoff immediately when quick play is cancelled", async () => {
    vi.useFakeTimers();
    const store = new PlaybackStore();
    apiMock.getStreams.mockResolvedValue([]);

    const pending = store.quickPlay(media(46));
    await vi.advanceTimersByTimeAsync(0);
    store.cancelQuickPlay();
    await pending;

    expect(apiMock.getStreams).toHaveBeenCalledOnce();
    expect(store.quickPlayPending).toBeNull();
  });

  it("starts TV playback at episode one and includes its episode name", async () => {
    const store = new PlaybackStore();
    const winner = stream("pilot");
    apiMock.getStreams.mockResolvedValue([winner]);
    rankStreamsWithProbe.mockResolvedValue([winner]);
    apiMock.tvEpisodes.mockResolvedValue([
      {
        episode_number: 1,
        name: "Pilot",
        overview: "",
        still_path: "",
        air_date: "",
        runtime: 42,
      },
    ]);

    await store.quickPlay(media(50, "tv"));

    expect(apiMock.getStreams).toHaveBeenCalledWith(
      50,
      { type: "tv", season: 1, episode: 1 },
      expect.any(AbortSignal),
    );
    expect(apiMock.tvEpisodes).toHaveBeenCalledWith(50, 1);
    expect(store.playerSession).toMatchObject({
      season: 1,
      episode: 1,
      episodeName: "Pilot",
    });
  });

  it("continues TV playback when episode metadata cannot be loaded", async () => {
    const store = new PlaybackStore();
    const winner = stream("episode");
    const error = new Error("TMDB unavailable");
    const consoleError = vi
      .spyOn(console, "error")
      .mockImplementation(() => undefined);
    apiMock.getStreams.mockResolvedValue([winner]);
    rankStreamsWithProbe.mockResolvedValue([winner]);
    apiMock.tvEpisodes.mockRejectedValue(error);

    await store.quickPlay(media(51, "tv"), 3, 7);

    expect(consoleError).toHaveBeenCalledWith(
      "quickPlay: failed to fetch episode name",
      error,
    );
    expect(store.playerSession).toMatchObject({
      season: 3,
      episode: 7,
      episodeName: undefined,
    });
  });

  it("shows no-stream feedback when ranking produces no candidates", async () => {
    vi.useFakeTimers();
    const store = new PlaybackStore();
    apiMock.getStreams.mockResolvedValue([stream("candidate")]);
    rankStreamsWithProbe.mockResolvedValue([]);

    await store.quickPlay(media(52));
    expect(store.quickPlayPending?.message).toBe("No stream found");

    await vi.advanceTimersByTimeAsync(2_500);
    expect(store.quickPlayPending).toBeNull();
  });

  it("reports stream lookup errors without retrying a failed request", async () => {
    const store = new PlaybackStore();
    const error = new Error("provider unavailable");
    const consoleError = vi
      .spyOn(console, "error")
      .mockImplementation(() => undefined);
    apiMock.getStreams.mockRejectedValue(error);

    await store.quickPlay(media(53));

    expect(apiMock.getStreams).toHaveBeenCalledOnce();
    expect(consoleError).toHaveBeenCalledWith(
      "quickPlay: failed to fetch streams",
      error,
    );
    expect(store.quickPlayPending?.message).toBe("No stream found");
  });

  it("aborts and ignores a superseded quick-play request", async () => {
    const store = new PlaybackStore();
    const firstMedia = media(1);
    const secondMedia = media(2);
    const winner = stream("winner");
    let firstSignal: AbortSignal | undefined;
    apiMock.getStreams
      .mockImplementationOnce(
        (_id: number, _opts: unknown, signal: AbortSignal) =>
          new Promise<Stream[]>((_resolve, reject) => {
            firstSignal = signal;
            signal.addEventListener("abort", () =>
              reject(new DOMException("aborted", "AbortError")),
            );
          }),
      )
      .mockResolvedValueOnce([winner]);
    rankStreamsWithProbe.mockResolvedValue([winner]);

    const first = store.quickPlay(firstMedia);
    const second = store.quickPlay(secondMedia);
    await Promise.all([first, second]);

    expect(firstSignal?.aborted).toBe(true);
    expect(store.playerSession?.media.id).toBe(2);
    expect(store.quickPlayPending).toBeNull();
  });

  it("falls back through candidates and reopens details when exhausted", () => {
    const store = new PlaybackStore();
    const openMediaDetail = vi.fn();
    store.init({
      playStartSound: vi.fn().mockResolvedValue(undefined),
      openMediaDetail,
    });
    const item = media(10);
    const dead = stream("dead");
    const next = stream("next");
    const last = stream("last");
    store.startPlayback(item, dead, undefined, undefined, undefined, [
      dead,
      next,
      last,
    ]);

    store.handlePlaybackFailed();
    expect(store.playerSession).toMatchObject({
      stream: next,
      candidates: [last],
      attempt: 1,
    });
    expect(store.playbackToast).toContain("trying another");

    store.handlePlaybackFailed();
    expect(store.playerSession).toMatchObject({
      stream: last,
      candidates: [],
      attempt: 2,
    });

    store.handlePlaybackFailed();
    expect(store.playerSession).toBeNull();
    expect(store.playerMode).toBeNull();
    expect(playerMock.setFullscreen).toHaveBeenCalledWith(false);
    expect(openMediaDetail).toHaveBeenCalledWith(item);
    expect(store.playbackToast).toContain("Couldn't start");
  });

  it("gives up after three attempts even when another candidate remains", () => {
    const store = new PlaybackStore();
    const openMediaDetail = vi.fn();
    store.init({
      playStartSound: vi.fn().mockResolvedValue(undefined),
      openMediaDetail,
    });
    const item = media(12);
    const dead = stream("fourth-dead");
    const untried = stream("untried");
    store.startPlayback(
      item,
      dead,
      undefined,
      undefined,
      undefined,
      [dead, untried],
      3,
    );

    store.handlePlaybackFailed();

    expect(store.playerSession).toBeNull();
    expect(openMediaDetail).toHaveBeenCalledWith(item);
  });

  it("ignores playback-failed notifications when there is no session", () => {
    const store = new PlaybackStore();

    store.handlePlaybackFailed();

    expect(playerMock.setFullscreen).not.toHaveBeenCalled();
  });

  it("cancels a pending quick play and clears its overlay", async () => {
    const store = new PlaybackStore();
    let signal: AbortSignal | undefined;
    apiMock.getStreams.mockImplementation(
      (_id: number, _opts: unknown, nextSignal: AbortSignal) =>
        new Promise<Stream[]>((_resolve, reject) => {
          signal = nextSignal;
          nextSignal.addEventListener("abort", () =>
            reject(new DOMException("aborted", "AbortError")),
          );
        }),
    );

    const pending = store.quickPlay(media(42));
    store.cancelQuickPlay();
    await pending;

    expect(signal?.aborted).toBe(true);
    expect(store.quickPlayPending).toBeNull();
    expect(store.playerSession).toBeNull();
  });
});
