import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  getMediaByID: vi.fn(),
  getLogos: vi.fn(),
  getTimestamps: vi.fn(),
  playUrl: vi.fn((src: string) => `http://backend/play?s=${src}`),
  subtitleProxyUrl: vi.fn((url: string) => `http://backend/sub?u=${url}`),
  progressGet: vi.fn(),
  progressSave: vi.fn(),
  progressStreamUrl: vi.fn(() => "http://backend/progress"),
  nextAiredEpisode: vi.fn(),
  loadShowTrackPrefs: vi.fn(),
  saveShowTrackPrefs: vi.fn(),
  loadAspectMode: vi.fn(() => "fit"),
}));

vi.mock("$lib/api", () => ({ api: mocks }));
vi.mock("$lib/nextEpisode", () => ({ nextAiredEpisode: mocks.nextAiredEpisode }));
vi.mock("$lib/player/trackPrefs", () => ({
  loadShowTrackPrefs: mocks.loadShowTrackPrefs,
  saveShowTrackPrefs: mocks.saveShowTrackPrefs,
}));
vi.mock("$lib/player/aspectRatio", () => ({
  loadAspectMode: mocks.loadAspectMode,
}));

import { Player } from "$lib/player/player.svelte";
import { PlayerCore, type PlayerCoreOptions } from "$lib/player/playerCore.svelte";
import type { Settings } from "$lib/types/settings";
import type { Media, TVEpisode } from "$lib/types/tmdb";

// ── Fixtures ────────────────────────────────────────────────────────────────

function show(over: Partial<Media> = {}): Media {
  return {
    id: 1396,
    media_type: "tv",
    name: "Breaking Bad",
    poster_path: "/bb.jpg",
    vote_average: 9.5,
    ...over,
  } as Media;
}

function track(id: number, lang: string, over = {}) {
  return { id, type: "audio", title: "", lang, selected: false, ...over };
}

class FakeEventSource {
  onmessage: ((e: MessageEvent<string>) => void) | null = null;
  onerror: ((e: Event) => void) | null = null;
  close = vi.fn();
  constructor(public url: string) {}
}

/** Puts the singleton into a known "playing" state. */
function readyPlayer(duration = 1000): void {
  Player.available = true;
  Player.ready = true;
  Player.duration = duration;
  Player.position = 0;
  Player.paused = false;
  Player.ended = false;
  Player.interrupted = false;
  Player.audioTracks = [];
  Player.subtitleTracks = [];
}

function harness(init: {
  src?: string;
  media?: Media | undefined;
  season?: number;
  episode?: number;
  fileIdx?: number;
  externalSubtitles?: { id: string; url: string; lang: string }[];
  pendingMessage?: string;
  settings?: Partial<Settings>;
  hooks?: Partial<PlayerCoreOptions>;
} = {}) {
  const props = $state({
    src: init.src ?? "http://stream/1.mkv",
    media: "media" in init ? init.media : show(),
    season: init.season,
    episode: init.episode,
    fileIdx: init.fileIdx,
    externalSubtitles: init.externalSubtitles ?? [],
    pendingMessage: init.pendingMessage,
    settings: { ...init.settings } as Settings,
  });
  const spies = {
    onPlaybackFailed: vi.fn(),
    onPlayNext: vi.fn(),
    onSrcChange: vi.fn(),
    onEnded: vi.fn(),
    onBeforeDestroy: vi.fn(),
  };
  let core!: PlayerCore;
  const destroyRoot = $effect.root(() => {
    core = new PlayerCore({
      getSrc: () => props.src,
      getMedia: () => props.media,
      getSeason: () => props.season,
      getEpisode: () => props.episode,
      getFileIdx: () => props.fileIdx,
      getExternalSubtitles: () => props.externalSubtitles,
      getPendingMessage: () => props.pendingMessage,
      getSettings: () => props.settings,
      getTitle: () => "Breaking Bad",
      ...spies,
      ...init.hooks,
    });
  });
  return { get core() { return core; }, props, ...spies, destroyRoot };
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.stubGlobal("EventSource", FakeEventSource);
  for (const fn of Object.values(mocks)) fn.mockReset();
  mocks.playUrl.mockImplementation((src: string) => `http://backend/play?s=${src}`);
  mocks.subtitleProxyUrl.mockImplementation((url: string) => `http://backend/sub?u=${url}`);
  mocks.progressStreamUrl.mockReturnValue("http://backend/progress");
  mocks.loadAspectMode.mockReturnValue("fit");
  mocks.loadShowTrackPrefs.mockReturnValue({});
  mocks.getLogos.mockResolvedValue([]);
  mocks.getTimestamps.mockResolvedValue(null);
  mocks.getMediaByID.mockResolvedValue({});
  mocks.progressGet.mockResolvedValue(null);
  mocks.progressSave.mockResolvedValue(undefined);
  mocks.nextAiredEpisode.mockResolvedValue(null);
  readyPlayer();
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

// ── Derived state ───────────────────────────────────────────────────────────

describe("canPlay", () => {
  it("is true once a src is loaded and mpv reports a duration", () => {
    const h = harness();
    expect(h.core.canPlay).toBe(true);
    h.destroyRoot();
  });

  it("is false with no src", () => {
    const h = harness({ src: "" });
    expect(h.core.canPlay).toBe(false);
    h.destroyRoot();
  });

  it("is false while switching sources", () => {
    const h = harness();
    h.core.switching = true;
    expect(h.core.canPlay).toBe(false);
    h.destroyRoot();
  });

  it("is false before mpv reports a duration", () => {
    const h = harness();
    Player.duration = 0;
    expect(h.core.canPlay).toBe(false);
    h.destroyRoot();
  });

  it("is false after mpv reports an interrupted stream", () => {
    const h = harness();
    Player.interrupted = true;
    expect(h.core.canPlay).toBe(false);
    h.destroyRoot();
  });
});

describe("isHash", () => {
  it("treats a non-http src as a torrent hash", () => {
    const h = harness({ src: "abc123def" });
    expect(h.core.isHash).toBe(true);
    h.destroyRoot();
  });

  it("treats an http src as a direct link", () => {
    const h = harness({ src: "http://stream/1.mkv" });
    expect(h.core.isHash).toBe(false);
    h.destroyRoot();
  });
});

describe("loadingMessage", () => {
  it("shows the pending message while streams are still being discovered", () => {
    const h = harness({ src: "", pendingMessage: "Finding streams…" });
    expect(h.core.streamDiscoveryPending).toBe(true);
    expect(h.core.loadingMessage).toBe("Finding streams…");
    h.destroyRoot();
  });

  it("buffers for a direct link", () => {
    const h = harness();
    expect(h.core.loadingMessage).toBe("Buffering…");
    h.destroyRoot();
  });

  it("reports peer count and speed once a torrent has peers", () => {
    const h = harness({ src: "abc123" });
    h.core.torrent.peers = 7;
    h.core.torrent.speed = "1.2 MB/s";
    expect(h.core.loadingMessage).toBe("Connecting · 7 peers · 1.2 MB/s");
    h.destroyRoot();
  });

  it("waits for peers before reporting any", () => {
    const h = harness({ src: "abc123" });
    expect(h.core.loadingMessage).toBe("Connecting to peers…");
    h.destroyRoot();
  });
});

// ── startPlayback ───────────────────────────────────────────────────────────

describe("startPlayback", () => {
  it("does nothing without a src", () => {
    const h = harness({ src: "" });
    const play = vi.spyOn(Player, "play");
    h.core.startPlayback();
    expect(play).not.toHaveBeenCalled();
    h.destroyRoot();
  });

  it("plays the backend url for the current selection", () => {
    const h = harness({ src: "abc", season: 2, episode: 5, fileIdx: 3 });
    const play = vi.spyOn(Player, "play");
    h.core.startPlayback();
    expect(mocks.playUrl).toHaveBeenCalledWith("abc", {
      season: 2,
      episode: 5,
      fileIdx: 3,
    });
    expect(play).toHaveBeenCalledWith("http://backend/play?s=abc");
    h.destroyRoot();
  });

  it("clears every per-src flag and lets the shell reset its own UI", () => {
    const h = harness();
    h.core.nextEp = { season: 1, episode: { episode_number: 2 } as TVEpisode };
    h.core.upNextDismissed = true;
    h.core.countdownSecs = 4;
    h.core.originalLang = "ja";
    h.core.subSelection = { kind: "embedded", id: 3 };

    h.core.startPlayback();

    expect(h.core.switching).toBe(true);
    expect(h.core.nextEp).toBeNull();
    expect(h.core.upNextDismissed).toBe(false);
    expect(h.core.countdownSecs).toBeNull();
    expect(h.core.originalLang).toBeNull();
    expect(h.core.subSelection).toEqual({ kind: "off" });
    expect(h.onSrcChange).toHaveBeenCalledTimes(1);
    h.destroyRoot();
  });

  it("starts muted when openOnMute is set", () => {
    const h = harness({ settings: { openOnMute: true, defaultVolume: 0.8 } });
    const setVolume = vi.spyOn(Player, "setVolume");
    h.core.startPlayback();
    expect(setVolume).toHaveBeenCalledWith(0);
    h.destroyRoot();
  });

  it("applies the default volume as a percentage", () => {
    const h = harness({ settings: { defaultVolume: 0.8 } });
    const setVolume = vi.spyOn(Player, "setVolume");
    h.core.startPlayback();
    expect(setVolume).toHaveBeenCalledWith(80);
    h.destroyRoot();
  });

  it("restores the title's aspect mode and remembered speed", () => {
    mocks.loadAspectMode.mockReturnValue("fill");
    mocks.loadShowTrackPrefs.mockReturnValue({ speed: 1.5 });
    const h = harness();
    const setAspect = vi.spyOn(Player, "setAspectMode");
    const setSpeed = vi.spyOn(Player, "setPlaybackSpeed");
    h.core.startPlayback();
    expect(setAspect).toHaveBeenCalledWith("fill");
    expect(setSpeed).toHaveBeenCalledWith(1.5);
    expect(h.core.showPrefs).toEqual({ speed: 1.5 });
    h.destroyRoot();
  });

  it("does not re-apply a remembered speed of 1x", () => {
    mocks.loadShowTrackPrefs.mockReturnValue({ speed: 1 });
    const h = harness();
    const setSpeed = vi.spyOn(Player, "setPlaybackSpeed");
    h.core.startPlayback();
    expect(setSpeed).not.toHaveBeenCalled();
    h.destroyRoot();
  });
});

describe("clearSwitchingWhenReady", () => {
  it("clears switching once mpv reports a duration", () => {
    const h = harness();
    h.core.switching = true;
    h.core.clearSwitchingWhenReady();
    expect(h.core.switching).toBe(false);
    h.destroyRoot();
  });

  it("stays switching while the duration is still unknown", () => {
    const h = harness();
    h.core.switching = true;
    Player.duration = 0;
    h.core.clearSwitchingWhenReady();
    expect(h.core.switching).toBe(true);
    h.destroyRoot();
  });
});

// ── Watchdog ────────────────────────────────────────────────────────────────

describe("playback-start watchdog", () => {
  it("gives a direct link 25 seconds before declaring failure", () => {
    const h = harness();
    h.core.armWatchdog();
    vi.advanceTimersByTime(24_999);
    expect(h.onPlaybackFailed).not.toHaveBeenCalled();
    vi.advanceTimersByTime(2);
    expect(h.onPlaybackFailed).toHaveBeenCalledTimes(1);
    h.destroyRoot();
  });

  it("gives a torrent 50 seconds, so the backend's own timeout fails first", () => {
    const h = harness({ src: "abc123" });
    h.core.armWatchdog();
    vi.advanceTimersByTime(25_001);
    expect(h.onPlaybackFailed).not.toHaveBeenCalled();
    vi.advanceTimersByTime(25_000);
    expect(h.onPlaybackFailed).toHaveBeenCalledTimes(1);
    h.destroyRoot();
  });

  it("flags a slow start at 15 seconds", () => {
    const h = harness();
    h.core.armWatchdog();
    expect(h.core.takingAWhile).toBe(false);
    vi.advanceTimersByTime(15_000);
    expect(h.core.takingAWhile).toBe(true);
    h.destroyRoot();
  });

  it("cancels both timers on teardown", () => {
    const h = harness();
    const teardown = h.core.armWatchdog();
    teardown?.();
    vi.advanceTimersByTime(60_000);
    expect(h.onPlaybackFailed).not.toHaveBeenCalled();
    expect(h.core.takingAWhile).toBe(false);
    h.destroyRoot();
  });

  it("fires onPlaybackFailed only once", () => {
    const h = harness();
    h.core.armWatchdog();
    vi.advanceTimersByTime(25_001);
    h.core.triggerPlaybackFailed();
    expect(h.onPlaybackFailed).toHaveBeenCalledTimes(1);
    h.destroyRoot();
  });

  it("never fires once playback has started for this src", () => {
    const h = harness();
    h.core.armWatchdog();
    h.core.markPlaybackStarted();
    expect(h.core.everCanPlay).toBe(true);
    expect(h.core.takingAWhile).toBe(false);
    vi.advanceTimersByTime(60_000);
    expect(h.onPlaybackFailed).not.toHaveBeenCalled();
    h.destroyRoot();
  });

  it("treats a stalled torrent as a failed start", () => {
    const h = harness({ src: "abc123" });
    h.core.armWatchdog();
    Player.duration = 0; // never got going
    h.core.torrent.stalled = true;
    h.core.failOnStalledTorrent();
    expect(h.onPlaybackFailed).toHaveBeenCalledTimes(1);
    h.destroyRoot();
  });

  it("ignores a stall once playback is running", () => {
    const h = harness({ src: "abc123" });
    h.core.armWatchdog();
    h.core.torrent.stalled = true;
    h.core.failOnStalledTorrent();
    expect(h.onPlaybackFailed).not.toHaveBeenCalled();
    h.destroyRoot();
  });

  it("falls back automatically when mpv terminates before playback starts", () => {
    const h = harness();
    Player.duration = 0;
    Player.interrupted = true;
    h.core.failOnPlaybackInterruption();
    expect(h.onPlaybackFailed).toHaveBeenCalledTimes(1);
    h.destroyRoot();
  });

  it("keeps a mid-playback interruption open for explicit recovery", () => {
    const h = harness();
    h.core.markPlaybackStarted();
    Player.interrupted = true;
    h.core.failOnPlaybackInterruption();
    expect(h.onPlaybackFailed).not.toHaveBeenCalled();
    h.destroyRoot();
  });

  it("lets the user explicitly switch streams after playback had started", () => {
    const h = harness();
    h.core.markPlaybackStarted();
    Player.interrupted = true;
    h.core.tryAnotherStream();
    expect(h.onPlaybackFailed).toHaveBeenCalledTimes(1);
    h.destroyRoot();
  });
});

describe("interrupted playback retry", () => {
  it("reloads the same source and resumes at the last reported position", () => {
    const h = harness({ season: 2, episode: 5, fileIdx: 3 });
    Player.position = 420;
    Player.interrupted = true;
    const play = vi.spyOn(Player, "play");
    const seek = vi.spyOn(Player, "seek");

    h.core.retryPlayback();

    expect(play).toHaveBeenCalledWith("http://backend/play?s=http://stream/1.mkv");
    expect(Player.interrupted).toBe(false);
    expect(Player.position).toBe(0);

    Player.duration = 1000;
    h.core.clearSwitchingWhenReady();
    h.core.resumeRetriedPlayback();
    h.core.resumeRetriedPlayback();
    expect(seek).toHaveBeenCalledOnce();
    expect(seek).toHaveBeenCalledWith(420);
    h.destroyRoot();
  });

  it("does nothing unless the current stream is interrupted", () => {
    const h = harness();
    const play = vi.spyOn(Player, "play");
    h.core.retryPlayback();
    expect(play).not.toHaveBeenCalled();
    h.destroyRoot();
  });
});

// ── Progress ────────────────────────────────────────────────────────────────

describe("watch progress", () => {
  it("loads the saved position for the current episode", () => {
    const h = harness({ season: 2, episode: 5 });
    h.core.loadProgress();
    expect(mocks.progressGet).toHaveBeenCalledWith(1396, "tv", 2, 5);
    h.destroyRoot();
  });

  it("skips loading when rememberPosition is off", () => {
    const h = harness({ settings: { rememberPosition: false } });
    h.core.loadProgress();
    expect(mocks.progressGet).not.toHaveBeenCalled();
    h.destroyRoot();
  });

  it("seeks to the saved position once playback is ready", async () => {
    mocks.progressGet.mockResolvedValue({
      completed: false,
      position_seconds: 300,
    });
    const h = harness();
    const seek = vi.spyOn(Player, "seek");
    h.core.loadProgress();
    await vi.waitFor(() => expect(h.core.progress.savedPosition).toBe(300));
    h.core.resumeProgress();
    expect(seek).toHaveBeenCalledWith(300);
    h.destroyRoot();
  });

  it("does not save while paused", () => {
    const h = harness();
    Player.paused = true;
    h.core.saveProgressTick();
    expect(mocks.progressSave).not.toHaveBeenCalled();
    h.destroyRoot();
  });

  it("marks the episode complete when the file ends", async () => {
    const h = harness();
    Player.ended = true;
    h.core.saveProgressOnEnded();
    await vi.waitFor(() => expect(mocks.progressSave).toHaveBeenCalled());
    expect(h.onEnded).toHaveBeenCalledTimes(1);
    h.destroyRoot();
  });

  it("does nothing on the ended path while still playing", () => {
    const h = harness();
    h.core.saveProgressOnEnded();
    expect(mocks.progressSave).not.toHaveBeenCalled();
    expect(h.onEnded).not.toHaveBeenCalled();
    h.destroyRoot();
  });
});

describe("torrent progress", () => {
  it("opens the SSE stream only for hash sources", () => {
    const h = harness({ src: "http://stream/1.mkv" });
    expect(h.core.trackTorrentProgress()).toBeUndefined();
    h.destroyRoot();
  });

  it("streams for a hash source and returns its teardown", () => {
    const h = harness({ src: "abc123", season: 1, episode: 2 });
    const teardown = h.core.trackTorrentProgress();
    expect(mocks.progressStreamUrl).toHaveBeenCalledWith("abc123", {
      season: 1,
      episode: 2,
      fileIdx: undefined,
    });
    expect(typeof teardown).toBe("function");
    h.destroyRoot();
  });
});

// ── Metadata fetches ────────────────────────────────────────────────────────

describe("loadLogo", () => {
  it("stores the first logo returned", async () => {
    mocks.getLogos.mockResolvedValue(["/logo.png", "/other.png"]);
    const h = harness();
    h.core.loadLogo();
    await vi.waitFor(() => expect(h.core.logoUrl).toBe("/logo.png"));
    h.destroyRoot();
  });

  it("drops a response that arrives after the media changed", async () => {
    let release!: (v: string[]) => void;
    mocks.getLogos.mockReturnValue(new Promise((r) => (release = r)));
    const h = harness();
    h.core.loadLogo();
    h.props.media = show({ id: 999 });
    release(["/stale.png"]);
    await vi.advanceTimersByTimeAsync(0);
    expect(h.core.logoUrl).toBeNull();
    h.destroyRoot();
  });

  it("clears the logo when there is no media", () => {
    const h = harness({ media: undefined });
    h.core.logoUrl = "/old.png";
    h.core.loadLogo();
    expect(h.core.logoUrl).toBeNull();
    expect(mocks.getLogos).not.toHaveBeenCalled();
    h.destroyRoot();
  });
});

describe("loadTimestamps", () => {
  it("stores the fetched IntroDB data", async () => {
    mocks.getTimestamps.mockResolvedValue({ intro: [{ start_ms: 0, end_ms: 10 }] });
    const h = harness();
    h.core.loadTimestamps();
    await vi.waitFor(() => expect(h.core.timestamps).not.toBeNull());
    h.destroyRoot();
  });

  it("drops a response that arrives after the src changed", async () => {
    let release!: (v: unknown) => void;
    mocks.getTimestamps.mockReturnValue(new Promise((r) => (release = r)));
    const h = harness();
    h.core.loadTimestamps();
    h.props.src = "http://stream/2.mkv";
    release({ intro: [{ start_ms: 0, end_ms: 10 }] });
    await vi.advanceTimersByTimeAsync(0);
    expect(h.core.timestamps).toBeNull();
    h.destroyRoot();
  });
});

// ── Segments ────────────────────────────────────────────────────────────────

describe("activeSegment", () => {
  it("reports the segment the position sits inside", () => {
    const h = harness();
    h.core.timestamps = { intro: [{ start_ms: 5_000, end_ms: 20_000 }] } as never;
    Player.position = 10;
    expect(h.core.activeSegment?.type).toBe("intro");
    h.destroyRoot();
  });

  it("reports nothing outside every segment", () => {
    const h = harness();
    h.core.timestamps = { intro: [{ start_ms: 5_000, end_ms: 20_000 }] } as never;
    Player.position = 30;
    expect(h.core.activeSegment).toBeNull();
    h.destroyRoot();
  });

  it("prefers recap over a coincident intro", () => {
    const h = harness();
    h.core.timestamps = {
      intro: [{ start_ms: 0, end_ms: 30_000 }],
      recap: [{ start_ms: 0, end_ms: 30_000 }],
    } as never;
    Player.position = 10;
    expect(h.core.activeSegment?.type).toBe("recap");
    h.destroyRoot();
  });

  it("treats a missing end as running to the end of the file", () => {
    const h = harness();
    h.core.timestamps = { credits: [{ start_ms: 900_000 }] } as never;
    Player.position = 950;
    expect(h.core.activeSegment?.type).toBe("credits");
    h.destroyRoot();
  });
});

describe("autoSkipSegment", () => {
  it("skips to the end of an intro when auto-skip is on", () => {
    const h = harness({ settings: { autoSkipIntro: true } });
    const seek = vi.spyOn(Player, "seek");
    h.core.timestamps = { intro: [{ start_ms: 5_000, end_ms: 20_000 }] } as never;
    Player.position = 10;
    h.core.autoSkipSegment();
    expect(seek).toHaveBeenCalledWith(20);
    h.destroyRoot();
  });

  it("leaves the segment alone when the setting is off", () => {
    const h = harness({ settings: { autoSkipIntro: false } });
    const seek = vi.spyOn(Player, "seek");
    h.core.timestamps = { intro: [{ start_ms: 5_000, end_ms: 20_000 }] } as never;
    Player.position = 10;
    h.core.autoSkipSegment();
    expect(seek).not.toHaveBeenCalled();
    h.destroyRoot();
  });

  it("does not re-skip a segment the user seeked back into", () => {
    const h = harness({ settings: { autoSkipIntro: true } });
    const seek = vi.spyOn(Player, "seek");
    h.core.timestamps = { intro: [{ start_ms: 5_000, end_ms: 20_000 }] } as never;
    Player.position = 10;
    h.core.autoSkipSegment();
    h.core.autoSkipSegment();
    expect(seek).toHaveBeenCalledTimes(1);
    h.destroyRoot();
  });

  it("skipSegment jumps to the segment end on demand", () => {
    const h = harness();
    const seek = vi.spyOn(Player, "seek");
    h.core.skipSegment({ seg: { start_ms: 0, end_ms: 42_000 } as never });
    expect(seek).toHaveBeenCalledWith(42);
    h.destroyRoot();
  });
});

// ── Track selection ─────────────────────────────────────────────────────────

describe("applyAudioDefault", () => {
  it("does nothing when there is only one audio track", () => {
    const h = harness({ settings: { defaultAudioLang: "de" } });
    Player.audioTracks = [track(1, "eng")] as never;
    const set = vi.spyOn(Player, "setAudioTrack");
    h.core.applyAudioDefault();
    expect(set).not.toHaveBeenCalled();
    h.destroyRoot();
  });

  it("selects the track matching the default language", () => {
    const h = harness({ settings: { defaultAudioLang: "de" } });
    Player.audioTracks = [track(1, "eng"), track(2, "ger")] as never;
    const set = vi.spyOn(Player, "setAudioTrack");
    h.core.applyAudioDefault();
    expect(set).toHaveBeenCalledWith(2);
    h.destroyRoot();
  });

  it("prefers a remembered per-show language over the global default", () => {
    const h = harness({ settings: { defaultAudioLang: "de" } });
    h.core.showPrefs = { audioLang: "jpn" };
    Player.audioTracks = [track(1, "ger"), track(2, "jpn")] as never;
    const set = vi.spyOn(Player, "setAudioTrack");
    h.core.applyAudioDefault();
    expect(set).toHaveBeenCalledWith(2);
    h.destroyRoot();
  });

  it("waits rather than latching while 'original' is still resolving", () => {
    const h = harness({ settings: { defaultAudioLang: "original" } });
    Player.audioTracks = [track(1, "eng"), track(2, "jpn")] as never;
    const set = vi.spyOn(Player, "setAudioTrack");
    h.core.applyAudioDefault();
    expect(set).not.toHaveBeenCalled();

    h.core.originalLang = "ja";
    h.core.applyAudioDefault();
    expect(set).toHaveBeenCalledWith(2);
    h.destroyRoot();
  });

  it("gives up on 'original' once it resolves to nothing", () => {
    const h = harness({ settings: { defaultAudioLang: "original" } });
    Player.audioTracks = [track(1, "eng"), track(2, "jpn")] as never;
    const set = vi.spyOn(Player, "setAudioTrack");
    h.core.originalLang = "";
    h.core.applyAudioDefault();
    expect(set).not.toHaveBeenCalled();
    // …and stays given up even if a language arrives later.
    h.core.originalLang = "ja";
    h.core.applyAudioDefault();
    expect(set).not.toHaveBeenCalled();
    h.destroyRoot();
  });

  it("leaves an already-selected match alone", () => {
    const h = harness({ settings: { defaultAudioLang: "de" } });
    Player.audioTracks = [
      track(1, "eng"),
      track(2, "ger", { selected: true }),
    ] as never;
    const set = vi.spyOn(Player, "setAudioTrack");
    h.core.applyAudioDefault();
    expect(set).not.toHaveBeenCalled();
    h.destroyRoot();
  });
});

describe("resolveOriginalLang", () => {
  it("uses the language already on the media object", () => {
    const h = harness({
      media: show({ original_language: "ja" }),
      settings: { defaultAudioLang: "original" },
    });
    h.core.resolveOriginalLang();
    expect(h.core.originalLang).toBe("ja");
    expect(mocks.getMediaByID).not.toHaveBeenCalled();
    h.destroyRoot();
  });

  it("fetches the full record when the partial media lacks one", async () => {
    mocks.getMediaByID.mockResolvedValue({ original_language: "ko" });
    const h = harness({ settings: { defaultAudioLang: "original" } });
    h.core.resolveOriginalLang();
    await vi.waitFor(() => expect(h.core.originalLang).toBe("ko"));
    h.destroyRoot();
  });

  it("records an unresolvable title as empty rather than null", async () => {
    mocks.getMediaByID.mockResolvedValue({});
    const h = harness({ settings: { defaultAudioLang: "original" } });
    h.core.resolveOriginalLang();
    await vi.waitFor(() => expect(h.core.originalLang).toBe(""));
    h.destroyRoot();
  });

  it("gives up on a fetch failure", async () => {
    mocks.getMediaByID.mockRejectedValue(new Error("offline"));
    const h = harness({ settings: { defaultAudioLang: "original" } });
    h.core.resolveOriginalLang();
    await vi.waitFor(() => expect(h.core.originalLang).toBe(""));
    h.destroyRoot();
  });

  it("does nothing unless the preference is 'original'", () => {
    const h = harness({ settings: { defaultAudioLang: "en" } });
    h.core.resolveOriginalLang();
    expect(h.core.originalLang).toBeNull();
    h.destroyRoot();
  });
});

describe("applySubtitleDefault", () => {
  it("honours a remembered 'off' choice", () => {
    const h = harness({ settings: { subtitlesEnabled: true, defaultSubtitleLang: "en" } });
    h.core.showPrefs = { sub: { kind: "off" } };
    Player.subtitleTracks = [track(1, "eng", { type: "sub" })] as never;
    const set = vi.spyOn(Player, "setSubtitleTrack");
    h.core.applySubtitleDefault();
    expect(set).toHaveBeenCalledWith(-1);
    h.destroyRoot();
  });

  it("matches a remembered language against embedded tracks first", () => {
    const h = harness();
    h.core.showPrefs = { sub: { kind: "lang", lang: "de" } };
    Player.subtitleTracks = [track(4, "ger", { type: "sub" })] as never;
    const set = vi.spyOn(Player, "setSubtitleTrack");
    h.core.applySubtitleDefault();
    expect(set).toHaveBeenCalledWith(4);
    h.destroyRoot();
  });

  it("falls back to an external subtitle in the remembered language", () => {
    const h = harness({
      externalSubtitles: [{ id: "os-1", url: "http://s/1.srt", lang: "de" }],
    });
    h.core.showPrefs = { sub: { kind: "lang", lang: "de" } };
    const add = vi.spyOn(Player, "addSubtitle");
    h.core.applySubtitleDefault();
    expect(add).toHaveBeenCalled();
    h.destroyRoot();
  });

  it("stays unlatched while the external list has not arrived", () => {
    const h = harness({ settings: { subtitlesEnabled: true, defaultSubtitleLang: "de" } });
    const set = vi.spyOn(Player, "setSubtitleTrack");
    h.core.applySubtitleDefault();
    expect(set).not.toHaveBeenCalled();

    // Once a matching embedded track shows up it is picked.
    Player.subtitleTracks = [track(9, "ger", { type: "sub" })] as never;
    h.core.applySubtitleDefault();
    expect(set).toHaveBeenCalledWith(9);
    h.destroyRoot();
  });

  it("does nothing when subtitles are disabled and no per-show pref exists", () => {
    const h = harness({ settings: { subtitlesEnabled: false } });
    Player.subtitleTracks = [track(1, "eng", { type: "sub" })] as never;
    const set = vi.spyOn(Player, "setSubtitleTrack");
    h.core.applySubtitleDefault();
    expect(set).not.toHaveBeenCalled();
    h.destroyRoot();
  });

  it("uses the first external subtitle when none match the preferred language", () => {
    const h = harness({
      settings: { subtitlesEnabled: true, defaultSubtitleLang: "de" },
      externalSubtitles: [{ id: "os-1", url: "http://s/1.srt", lang: "fr" }],
    });
    const add = vi.spyOn(Player, "addSubtitle");
    h.core.applySubtitleDefault();
    expect(add).toHaveBeenCalled();
    h.destroyRoot();
  });

  it("only applies once per source", () => {
    const h = harness({ settings: { subtitlesEnabled: true, defaultSubtitleLang: "en" } });
    Player.subtitleTracks = [track(1, "eng", { type: "sub" })] as never;
    const set = vi.spyOn(Player, "setSubtitleTrack");
    h.core.applySubtitleDefault();
    h.core.applySubtitleDefault();
    expect(set).toHaveBeenCalledTimes(1);
    h.destroyRoot();
  });
});

describe("selectSubtitle", () => {
  it("turns subtitles off", () => {
    const h = harness();
    const set = vi.spyOn(Player, "setSubtitleTrack");
    h.core.selectSubtitle({ kind: "off" });
    expect(set).toHaveBeenCalledWith(-1);
    expect(h.core.subSelection).toEqual({ kind: "off" });
    h.destroyRoot();
  });

  it("selects an embedded track by id", () => {
    const h = harness();
    const set = vi.spyOn(Player, "setSubtitleTrack");
    h.core.selectSubtitle({ kind: "embedded", id: 3 });
    expect(set).toHaveBeenCalledWith(3);
    h.destroyRoot();
  });

  it("adds an external subtitle once, then re-selects the loaded track", () => {
    const h = harness({
      externalSubtitles: [{ id: "os-1", url: "http://s/1.srt", lang: "de" }],
    });
    const add = vi.spyOn(Player, "addSubtitle");
    const set = vi.spyOn(Player, "setSubtitleTrack");
    h.core.selectSubtitle({ kind: "external", id: "os-1" });
    expect(add).toHaveBeenCalledWith("http://backend/sub?u=http://s/1.srt", "DE", "de");

    Player.subtitleTracks = [track(7, "de", { type: "sub" })] as never;
    h.core.selectSubtitle({ kind: "external", id: "os-1" });
    expect(add).toHaveBeenCalledTimes(1);
    expect(set).toHaveBeenCalledWith(7);
    h.destroyRoot();
  });

  it("ignores an external id that is not in the list", () => {
    const h = harness();
    const add = vi.spyOn(Player, "addSubtitle");
    h.core.selectSubtitle({ kind: "external", id: "missing" });
    expect(add).not.toHaveBeenCalled();
    h.destroyRoot();
  });
});

describe("user track choices", () => {
  it("remembers an embedded subtitle by language", () => {
    const h = harness();
    Player.subtitleTracks = [track(3, "ger", { type: "sub" })] as never;
    h.core.chooseSubtitle({ kind: "embedded", id: 3 });
    expect(mocks.saveShowTrackPrefs).toHaveBeenCalledWith(1396, {
      sub: { kind: "lang", lang: "ger" },
    });
    h.destroyRoot();
  });

  it("remembers an explicit 'off'", () => {
    const h = harness();
    h.core.chooseSubtitle({ kind: "off" });
    expect(mocks.saveShowTrackPrefs).toHaveBeenCalledWith(1396, {
      sub: { kind: "off" },
    });
    h.destroyRoot();
  });

  it("stores nothing for an untagged track — there'd be no way to re-match it", () => {
    const h = harness();
    Player.subtitleTracks = [track(3, "", { type: "sub" })] as never;
    h.core.chooseSubtitle({ kind: "embedded", id: 3 });
    expect(mocks.saveShowTrackPrefs).not.toHaveBeenCalled();
    h.destroyRoot();
  });

  it("remembers the chosen audio language", () => {
    const h = harness();
    Player.audioTracks = [track(2, "jpn")] as never;
    h.core.chooseAudioTrack(2);
    expect(mocks.saveShowTrackPrefs).toHaveBeenCalledWith(1396, {
      audioLang: "jpn",
    });
    h.destroyRoot();
  });

  it("remembers the chosen speed", () => {
    const h = harness();
    const set = vi.spyOn(Player, "setPlaybackSpeed");
    h.core.chooseSpeed(1.25);
    expect(set).toHaveBeenCalledWith(1.25);
    expect(mocks.saveShowTrackPrefs).toHaveBeenCalledWith(1396, { speed: 1.25 });
    h.destroyRoot();
  });
});

describe("applySubtitleStyle", () => {
  it("pushes the configured size, position and background to mpv", () => {
    const h = harness({
      settings: { subtitleSize: 140, subtitlePosition: 12, subtitleBackground: true },
    });
    const set = vi.spyOn(Player, "setSubtitleStyle");
    h.core.applySubtitleStyle();
    expect(set).toHaveBeenCalledWith(140, 12, true);
    h.destroyRoot();
  });

  it("waits until the bridge is ready", () => {
    const h = harness();
    Player.ready = false;
    const set = vi.spyOn(Player, "setSubtitleStyle");
    h.core.applySubtitleStyle();
    expect(set).not.toHaveBeenCalled();
    h.destroyRoot();
  });
});

// ── Up next ─────────────────────────────────────────────────────────────────

describe("resolveNextEpisode", () => {
  it("resolves once per source", async () => {
    mocks.nextAiredEpisode.mockResolvedValue({
      season: 2,
      episode: { episode_number: 6 } as TVEpisode,
    });
    const h = harness({ season: 2, episode: 5 });
    h.core.resolveNextEpisode();
    h.core.resolveNextEpisode();
    await vi.waitFor(() => expect(h.core.nextEp?.season).toBe(2));
    expect(mocks.nextAiredEpisode).toHaveBeenCalledTimes(1);
    h.destroyRoot();
  });

  it("does not resolve for a movie", () => {
    const h = harness({ media: show({ media_type: "movie" }) });
    h.core.resolveNextEpisode();
    expect(mocks.nextAiredEpisode).not.toHaveBeenCalled();
    h.destroyRoot();
  });

  it("discards a response that arrives after the src changed", async () => {
    let release!: (v: unknown) => void;
    mocks.nextAiredEpisode.mockReturnValue(new Promise((r) => (release = r)));
    const h = harness({ season: 2, episode: 5 });
    h.core.resolveNextEpisode();
    h.props.src = "http://stream/2.mkv";
    release({ season: 2, episode: { episode_number: 6 } });
    await vi.advanceTimersByTimeAsync(0);
    expect(h.core.nextEp).toBeNull();
    h.destroyRoot();
  });
});

describe("showUpNext", () => {
  it("stays hidden without a resolved next episode", () => {
    const h = harness();
    expect(h.core.showUpNext).toBe(false);
    h.destroyRoot();
  });

  it("shows in the last 40 seconds of the file", () => {
    const h = harness();
    h.core.nextEp = { season: 1, episode: { episode_number: 2 } as TVEpisode };
    Player.position = 970;
    expect(h.core.showUpNext).toBe(true);
    h.destroyRoot();
  });

  it("shows as soon as a credits segment starts", () => {
    const h = harness();
    h.core.nextEp = { season: 1, episode: { episode_number: 2 } as TVEpisode };
    h.core.timestamps = { credits: [{ start_ms: 0, end_ms: 999_000 }] } as never;
    Player.position = 10;
    expect(h.core.showUpNext).toBe(true);
    h.destroyRoot();
  });

  it("stays hidden once dismissed", () => {
    const h = harness();
    h.core.nextEp = { season: 1, episode: { episode_number: 2 } as TVEpisode };
    Player.position = 970;
    h.core.dismissUpNext();
    expect(h.core.showUpNext).toBe(false);
    expect(h.core.countdownSecs).toBeNull();
    h.destroyRoot();
  });

  it("stays hidden when the shell cannot play a next episode", () => {
    const h = harness({ hooks: { onPlayNext: undefined } });
    h.core.nextEp = { season: 1, episode: { episode_number: 2 } as TVEpisode };
    Player.position = 970;
    expect(h.core.showUpNext).toBe(false);
    h.destroyRoot();
  });
});

describe("runUpNextCountdown", () => {
  function armed(h: ReturnType<typeof harness>) {
    h.core.nextEp = { season: 1, episode: { episode_number: 2 } as TVEpisode };
    Player.position = 970;
  }

  it("counts down from ten and then advances", () => {
    const h = harness({ settings: { autoPlay: true } });
    armed(h);
    const teardown = h.core.runUpNextCountdown();
    expect(h.core.countdownSecs).toBe(10);
    vi.advanceTimersByTime(9000);
    expect(h.core.countdownSecs).toBe(1);
    vi.advanceTimersByTime(1000);
    expect(h.core.countdownSecs).toBe(0);
    expect(h.onPlayNext).toHaveBeenCalledWith(1, 2);
    teardown?.();
    h.destroyRoot();
  });

  it("does not arm without autoplay", () => {
    const h = harness({ settings: { autoPlay: false } });
    armed(h);
    h.core.runUpNextCountdown();
    expect(h.core.countdownSecs).toBeNull();
    h.destroyRoot();
  });

  it("clears the countdown when the overlay hides again", () => {
    const h = harness({ settings: { autoPlay: true } });
    armed(h);
    const teardown = h.core.runUpNextCountdown();
    expect(h.core.countdownSecs).toBe(10);
    teardown?.();

    Player.position = 10; // user seeked back
    h.core.runUpNextCountdown();
    expect(h.core.countdownSecs).toBeNull();
    h.destroyRoot();
  });

  it("stops ticking after its teardown runs", () => {
    const h = harness({ settings: { autoPlay: true } });
    armed(h);
    const teardown = h.core.runUpNextCountdown();
    teardown?.();
    vi.advanceTimersByTime(20_000);
    expect(h.onPlayNext).not.toHaveBeenCalled();
    h.destroyRoot();
  });
});

describe("advance", () => {
  it("fires onPlayNext exactly once", () => {
    const h = harness();
    h.core.nextEp = { season: 3, episode: { episode_number: 4 } as TVEpisode };
    h.core.advance();
    h.core.advance();
    expect(h.onPlayNext).toHaveBeenCalledTimes(1);
    expect(h.onPlayNext).toHaveBeenCalledWith(3, 4);
    expect(h.core.advanced).toBe(true);
    h.destroyRoot();
  });

  it("marks the outgoing episode complete on the way out", () => {
    const h = harness();
    h.core.nextEp = { season: 3, episode: { episode_number: 4 } as TVEpisode };
    h.core.advance();
    expect(mocks.progressSave).toHaveBeenCalled();
    h.destroyRoot();
  });

  it("does nothing without a next episode", () => {
    const h = harness();
    h.core.advance();
    expect(h.onPlayNext).not.toHaveBeenCalled();
    h.destroyRoot();
  });
});

describe("advanceOnEnded", () => {
  it("advances when the file ends with autoplay on", () => {
    const h = harness({ settings: { autoPlay: true } });
    h.core.nextEp = { season: 1, episode: { episode_number: 2 } as TVEpisode };
    h.core.markPlaybackStarted();
    Player.ended = true;
    h.core.advanceOnEnded();
    expect(h.onPlayNext).toHaveBeenCalledTimes(1);
    h.destroyRoot();
  });

  it("ignores a spurious ended before playback ever started", () => {
    const h = harness({ settings: { autoPlay: true } });
    h.core.nextEp = { season: 1, episode: { episode_number: 2 } as TVEpisode };
    Player.ended = true;
    h.core.advanceOnEnded();
    expect(h.onPlayNext).not.toHaveBeenCalled();
    h.destroyRoot();
  });

  it("does not advance once the overlay was dismissed", () => {
    const h = harness({ settings: { autoPlay: true } });
    h.core.nextEp = { season: 1, episode: { episode_number: 2 } as TVEpisode };
    h.core.markPlaybackStarted();
    h.core.dismissUpNext();
    Player.ended = true;
    h.core.advanceOnEnded();
    expect(h.onPlayNext).not.toHaveBeenCalled();
    h.destroyRoot();
  });

  it("does not complete or advance an interrupted mid-episode stream", () => {
    const h = harness({ settings: { autoPlay: true } });
    h.core.nextEp = { season: 1, episode: { episode_number: 2 } as TVEpisode };
    h.core.markPlaybackStarted();
    Player.position = 400;
    Player.duration = 1000;
    Player.ended = false;
    Player.interrupted = true;

    h.core.saveProgressOnEnded();
    h.core.advanceOnEnded();

    expect(mocks.progressSave).not.toHaveBeenCalled();
    expect(h.onEnded).not.toHaveBeenCalled();
    expect(h.onPlayNext).not.toHaveBeenCalled();
    h.destroyRoot();
  });
});

// ── Teardown ────────────────────────────────────────────────────────────────

describe("destroy", () => {
  it("lets the shell run its own teardown, saves, then stops mpv", async () => {
    const h = harness();
    Player.position = 500;
    const stop = vi.spyOn(Player, "stop");
    h.core.destroy();
    expect(h.onBeforeDestroy).toHaveBeenCalledTimes(1);
    await vi.waitFor(() => expect(mocks.progressSave).toHaveBeenCalled());
    expect(stop).toHaveBeenCalled();
    h.destroyRoot();
  });

  // Position deliberately mid-file: ProgressSaver auto-upgrades to completed
  // past 90%, which would mask the flag these two tests are about.
  it("reports the real ended state by default", async () => {
    const h = harness();
    Player.position = 500;
    Player.ended = true;
    h.core.destroy();
    await vi.waitFor(() => expect(mocks.progressSave).toHaveBeenCalled());
    expect(mocks.progressSave.mock.calls[0][0]).toMatchObject({
      completed: true,
    });
    h.destroyRoot();
  });

  it("honours a shell that never reports completion on teardown", async () => {
    const h = harness({ hooks: { getDestroyCompleted: () => false } });
    Player.position = 500;
    Player.ended = true;
    h.core.destroy();
    await vi.waitFor(() => expect(mocks.progressSave).toHaveBeenCalled());
    expect(mocks.progressSave.mock.calls[0][0]).toMatchObject({
      completed: false,
    });
    h.destroyRoot();
  });

  it("stops mpv even if the save throws", () => {
    const h = harness();
    vi.spyOn(console, "error").mockImplementation(() => {});
    const stop = vi.spyOn(Player, "stop");
    h.core.destroy();
    expect(stop).toHaveBeenCalled();
    h.destroyRoot();
  });

  it("does nothing when the shell has no mpv at all", () => {
    const h = harness();
    Player.available = false;
    const stop = vi.spyOn(Player, "stop");
    h.core.destroy();
    expect(h.onBeforeDestroy).not.toHaveBeenCalled();
    expect(stop).not.toHaveBeenCalled();
    h.destroyRoot();
  });
});
