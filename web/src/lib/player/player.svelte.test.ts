import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const isDesktopTvMode = vi.hoisted(() => vi.fn(() => true));
vi.mock("$lib/platform", () => ({ isDesktopTvMode }));

import {
  ASPECT_MODES,
  MpvPlayer,
  type MpvTrack,
} from "$lib/player/player.svelte";

class FakeSignal<T extends unknown[]> {
  callback: ((...args: T) => void) | null = null;
  connect = vi.fn((callback: (...args: T) => void) => {
    this.callback = callback;
  });
  disconnect = vi.fn();

  emit(...args: T): void {
    this.callback?.(...args);
  }
}

function makeBridge(valid: boolean | undefined = true) {
  return {
    valid,
    positionChanged: new FakeSignal<[number]>(),
    durationChanged: new FakeSignal<[number]>(),
    pausedChanged: new FakeSignal<[boolean]>(),
    volumeChanged: new FakeSignal<[number]>(),
    fileLoaded: new FakeSignal<[]>(),
    endReached: new FakeSignal<[]>(),
    tracksChanged: new FakeSignal<[MpvTrack[]]>(),
    play: vi.fn(),
    pause: vi.fn(),
    resume: vi.fn(),
    stop: vi.fn(),
    seek: vi.fn(),
    setAudioTrack: vi.fn(),
    setSubtitleTrack: vi.fn(),
    addSubtitle: vi.fn(),
    setVolume: vi.fn(),
    setFullscreen: vi.fn(),
    setMpvProperty: vi.fn(),
    requestState: vi.fn(),
    reloadMpvConf: vi.fn(),
  };
}

function connectWith(
  mpv: ReturnType<typeof makeBridge> | undefined,
  shell: { setTvZoom?: () => void } = { setTvZoom: vi.fn() },
): { player: MpvPlayer; shell: typeof shell } {
  const transport = {};
  window.qt = { webChannelTransport: transport };
  window.QWebChannel = class {
    constructor(
      actualTransport: unknown,
      callback: (channel: { objects: Record<string, unknown> }) => void,
    ) {
      expect(actualTransport).toBe(transport);
      callback({ objects: { mpv, shell } });
    }
  } as unknown as typeof window.QWebChannel;
  return { player: new MpvPlayer(), shell };
}

describe("MpvPlayer", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-07-23T12:00:00Z"));
    delete window.qt;
    delete window.QWebChannel;
  });

  afterEach(() => {
    delete window.qt;
    delete window.QWebChannel;
    vi.useRealTimers();
  });

  it("stays unavailable without the shell bridge", () => {
    const player = new MpvPlayer();

    expect(player.available).toBe(false);
    expect(player.ready).toBe(false);
    player.play("https://stream.test/video");
    expect(player.position).toBe(0);
  });

  it("requires both WebChannel globals before connecting", () => {
    window.qt = { webChannelTransport: {} };
    expect(new MpvPlayer().available).toBe(false);

    delete window.qt;
    window.QWebChannel = class {} as unknown as typeof window.QWebChannel;
    expect(new MpvPlayer().available).toBe(false);
  });

  it("connects signals, requests initial state, and splits tracks", async () => {
    const bridge = makeBridge();
    const { player, shell } = connectWith(bridge);

    expect(player.available).toBe(true);
    expect(player.ready).toBe(true);
    expect(shell.setTvZoom).toHaveBeenCalledWith(true);
    expect(bridge.requestState).toHaveBeenCalledTimes(1);
    await expect(player.whenReady).resolves.toBeUndefined();

    bridge.positionChanged.emit(12);
    bridge.durationChanged.emit(100);
    bridge.pausedChanged.emit(false);
    bridge.volumeChanged.emit(72);
    bridge.endReached.emit();
    expect(player).toMatchObject({
      position: 12,
      duration: 100,
      paused: false,
      volume: 72,
      ended: true,
    });

    bridge.fileLoaded.emit();
    expect(player.ended).toBe(false);

    const video: MpvTrack = {
      id: 1,
      type: "video",
      title: "",
      lang: "",
      selected: true,
    };
    const audio: MpvTrack = {
      id: 2,
      type: "audio",
      title: "Stereo",
      lang: "en",
      selected: true,
    };
    const subtitle: MpvTrack = {
      id: 3,
      type: "sub",
      title: "English",
      lang: "en",
      selected: false,
    };
    bridge.tracksChanged.emit([video, audio, subtitle]);
    expect(player.audioTracks).toEqual([audio]);
    expect(player.subtitleTracks).toEqual([subtitle]);
  });

  it("reports an invalid or missing mpv bridge without becoming ready", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => {});
    const error = vi.spyOn(console, "error").mockImplementation(() => {});
    const invalid = connectWith(makeBridge(false)).player;

    expect(invalid.available).toBe(false);
    expect(invalid.ready).toBe(false);
    expect(warn).toHaveBeenCalled();

    const missing = connectWith(undefined).player;
    expect(missing.available).toBe(true);
    expect(missing.ready).toBe(false);
    expect(error).toHaveBeenCalledWith("[player] mpv missing from channel");
  });

  it("accepts older bridges with no validity flag or optional shell methods", () => {
    const bridge = makeBridge();
    bridge.valid = undefined;
    const { player } = connectWith(bridge, {});

    expect(player.available).toBe(true);
    expect(player.ready).toBe(true);
    expect(bridge.requestState).toHaveBeenCalledOnce();

    delete (
      bridge as unknown as {
        reloadMpvConf?: ReturnType<typeof vi.fn>;
      }
    ).reloadMpvConf;
    expect(() => player.reloadMpvConf()).not.toThrow();
  });

  it("resets stale playback state and suppresses queued pre-load positions", () => {
    const bridge = makeBridge();
    const { player } = connectWith(bridge);
    player.position = 95;
    player.duration = 100;
    player.ended = true;
    player.playbackSpeed = 2;

    player.play("https://stream.test/new");

    expect(player).toMatchObject({
      position: 0,
      duration: 0,
      ended: false,
      playbackSpeed: 1,
    });
    expect(bridge.setMpvProperty).toHaveBeenCalledWith("speed", "1");
    expect(bridge.play).toHaveBeenCalledWith("https://stream.test/new");

    bridge.positionChanged.emit(95);
    expect(player.position).toBe(0);
    bridge.positionChanged.emit(2.5);
    expect(player.position).toBe(2.5);
  });

  it("clamps seeks and volume while forwarding playback controls", () => {
    const bridge = makeBridge();
    const { player } = connectWith(bridge);
    player.duration = 120;

    player.seek(200);
    expect(player.position).toBe(120);
    expect(bridge.seek).toHaveBeenCalledWith(120);
    bridge.positionChanged.emit(20);
    expect(player.position).toBe(120);
    bridge.positionChanged.emit(118);
    expect(player.position).toBe(118);

    player.seek(-10);
    expect(player.position).toBe(0);
    expect(bridge.seek).toHaveBeenLastCalledWith(0);

    player.setVolume(-1);
    player.setVolume(150);
    expect(bridge.setVolume).toHaveBeenNthCalledWith(1, 0);
    expect(bridge.setVolume).toHaveBeenNthCalledWith(2, 100);
    expect(player.volume).toBe(100);

    player.resume();
    expect(player.paused).toBe(false);
    expect(bridge.resume).toHaveBeenCalled();
    player.togglePause();
    expect(player.paused).toBe(true);
    expect(bridge.pause).toHaveBeenCalled();

    player.stop();
    expect(player.position).toBe(0);
    expect(player.duration).toBe(0);
    expect(bridge.stop).toHaveBeenCalled();
  });

  it("seeks without a known duration and ignores invalid numeric controls", () => {
    const bridge = makeBridge();
    const { player } = connectWith(bridge);
    player.position = 12;
    player.volume = 45;
    player.playbackSpeed = 1.25;

    player.seek(30);
    expect(player.position).toBe(30);
    expect(bridge.seek).toHaveBeenCalledWith(30);

    player.seek(Number.NaN);
    player.setVolume(Number.POSITIVE_INFINITY);
    player.setPlaybackSpeed(0);
    player.setPlaybackSpeed(Number.NaN);

    expect(player.position).toBe(30);
    expect(player.volume).toBe(45);
    expect(player.playbackSpeed).toBe(1.25);
    expect(bridge.seek).toHaveBeenCalledOnce();
    expect(bridge.setVolume).not.toHaveBeenCalled();
    expect(bridge.setMpvProperty).not.toHaveBeenCalled();
  });

  it("maps aspect, subtitle, track, speed, and fullscreen controls to mpv", () => {
    const bridge = makeBridge();
    const { player } = connectWith(bridge);

    expect(ASPECT_MODES).toEqual(["fit", "fill", "stretch", "zoom"]);
    player.setAspectMode("fill");
    expect(bridge.setMpvProperty).toHaveBeenCalledWith("panscan", "1.0");
    expect(bridge.setMpvProperty).toHaveBeenCalledWith(
      "sub-ass-force-margins",
      "yes",
    );
    expect(player.cycleAspectMode()).toBe("stretch");
    expect(bridge.setMpvProperty).toHaveBeenCalledWith("keepaspect", "no");

    player.setSubtitleStyle(150, 8, true);
    expect(bridge.setMpvProperty).toHaveBeenCalledWith("sub-scale", "1.5");
    expect(bridge.setMpvProperty).toHaveBeenCalledWith("sub-pos", "92");
    expect(bridge.setMpvProperty).toHaveBeenCalledWith(
      "sub-border-style",
      "opaque-box",
    );

    player.setPlaybackSpeed(1.75);
    player.setAudioTrack(2);
    player.setSubtitleTrack(-1);
    player.addSubtitle("https://subs.test/en.srt", "English", "en");
    player.reloadMpvConf();
    expect(bridge.setMpvProperty).toHaveBeenCalledWith("speed", "1.75");
    expect(bridge.setAudioTrack).toHaveBeenCalledWith(2);
    expect(bridge.setSubtitleTrack).toHaveBeenCalledWith(-1);
    expect(bridge.addSubtitle).toHaveBeenCalledWith(
      "https://subs.test/en.srt",
      "English",
      "en",
    );
    expect(bridge.reloadMpvConf).toHaveBeenCalled();

    player.setFullscreen(true);
    player.setFullscreen(true);
    player.toggleFullscreen();
    expect(bridge.setFullscreen).toHaveBeenCalledTimes(2);
    expect(bridge.setFullscreen).toHaveBeenNthCalledWith(1, true);
    expect(bridge.setFullscreen).toHaveBeenNthCalledWith(2, false);
  });

  it("maps fit and zoom aspect modes, subtitle defaults, and cycle wrapping", () => {
    const bridge = makeBridge();
    const { player } = connectWith(bridge);

    player.setAspectMode("zoom");
    expect(bridge.setMpvProperty).toHaveBeenCalledWith("video-zoom", "0.263");
    expect(bridge.setMpvProperty).toHaveBeenCalledWith(
      "sub-ass-force-margins",
      "yes",
    );
    expect(player.cycleAspectMode()).toBe("fit");
    expect(bridge.setMpvProperty).toHaveBeenLastCalledWith(
      "sub-ass-force-margins",
      "no",
    );

    player.setSubtitleStyle(100, 10, false);
    expect(bridge.setMpvProperty).toHaveBeenCalledWith(
      "sub-border-style",
      "outline-and-shadow",
    );
    player.addSubtitle("https://subs.test/default.srt");
    expect(bridge.addSubtitle).toHaveBeenCalledWith(
      "https://subs.test/default.srt",
      "",
      "",
    );

    player.togglePause();
    expect(player.paused).toBe(false);
    expect(bridge.resume).toHaveBeenCalledOnce();
  });
});
