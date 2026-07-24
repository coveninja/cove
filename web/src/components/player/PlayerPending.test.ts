import { mount, tick, unmount } from "svelte";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const playerMock = vi.hoisted(() => ({
  available: false,
  ready: false,
  duration: 0,
  position: 0,
  paused: true,
  ended: false,
  volume: 100,
  playbackSpeed: 1,
  audioTracks: [],
  subtitleTracks: [],
  chapters: [],
  isFullscreen: false,
  stop: vi.fn(),
  setFullscreen: vi.fn(),
  togglePause: vi.fn(),
  // Extra slots needed when available=true and src is set (the main play
  // $effect calls these before the component finishes mounting).
  play: vi.fn(),
  setAspectMode: vi.fn(),
  setPlaybackSpeed: vi.fn(),
}));

vi.mock("$lib/player/player.svelte", () => ({ Player: playerMock }));
vi.mock("$lib/stores/settings", () => ({
  settings: {
    subscribe(run: (value: Record<string, never>) => void) {
      run({});
      return () => {};
    },
  },
}));

import Player from "./Player.svelte";
import MobilePlayer from "../../mobile/components/player/MobilePlayer.svelte";
import TvPlayer from "../../tv/components/player/TvPlayer.svelte";

function cancelButton(target: HTMLElement): HTMLButtonElement {
  const button = [...target.querySelectorAll("button")].find(
    (candidate) => candidate.textContent?.trim() === "Cancel",
  );
  if (!(button instanceof HTMLButtonElement)) {
    throw new Error("Player did not render its pending Cancel action");
  }
  return button;
}

describe("platform players during stream discovery", () => {
  for (const [name, component] of [
    ["desktop", Player],
    ["mobile", MobilePlayer],
    ["TV", TvPlayer],
  ] as const) {
    it(`${name} owns the pending state without starting native playback`, async () => {
      const target = document.createElement("div");
      document.body.append(target);
      const onCancelPending = vi.fn();
      const instance = mount(component, {
        target,
        props: {
          src: "",
          pendingMessage: "Finding streams…",
          onCancelPending,
        },
      });

      try {
        await tick();
        expect(target.textContent).toContain("Finding streams…");
        expect(target.textContent).not.toContain("Native player unavailable");
        cancelButton(target).click();
        expect(onCancelPending).toHaveBeenCalledOnce();
      } finally {
        await unmount(instance, { outro: false });
        target.remove();
      }
    });
  }
});

describe("platform players with native bridge absent", () => {
  // Ensure the mock stays at available=false for this suite (the default).
  beforeEach(() => {
    playerMock.available = false;
  });

  for (const [name, component] of [
    ["desktop", Player],
    ["mobile", MobilePlayer],
    ["TV", TvPlayer],
  ] as const) {
    it(`${name} shows the bridge-unavailable notice when a real src is given but Player is not available`, async () => {
      // Covers the {#if !Player.available && !streamDiscoveryPending} block:
      // Player.svelte line 1088, TvPlayer.svelte line 1067, MobilePlayer.svelte line 947.
      // The main play $effect returns early at its own guard (!Player.available),
      // so no Player methods are invoked despite src being non-empty.
      const target = document.createElement("div");
      document.body.append(target);
      const instance = mount(component, {
        target,
        props: { src: "https://direct.test/video.mkv" },
      });

      try {
        await tick();
        expect(target.textContent).toContain("Native player unavailable");
      } finally {
        await unmount(instance, { outro: false });
        target.remove();
      }
    });
  }
});

describe("platform players buffering with bridge present", () => {
  // Switch the mock to available=true so the loading-screen branch fires.
  // Reset to false afterward so other suites are unaffected.
  beforeEach(() => {
    playerMock.available = true;
    playerMock.ready = false;
    playerMock.duration = 0;
  });

  afterEach(() => {
    playerMock.available = false;
  });

  for (const [name, component] of [
    ["desktop", Player],
    ["mobile", MobilePlayer],
    ["TV", TvPlayer],
  ] as const) {
    it(`${name} renders the loading screen when the bridge is ready but the stream has not started`, async () => {
      // Covers the (Player.available && !canPlay) branch of:
      //   Player.svelte line 1166, TvPlayer.svelte line 1123, MobilePlayer.svelte line 1005.
      // Also covers the false branch of cancelVisible={streamDiscoveryPending}
      // (lines 1174 / 1131) and onClose/onCancel ternaries (1177 / 1131) since
      // streamDiscoveryPending is false when src is non-empty.
      // The main play $effect calls play() and setAspectMode() — both are mocked.
      const target = document.createElement("div");
      document.body.append(target);
      const instance = mount(component, {
        target,
        props: { src: "https://direct.test/video.mkv" },
      });

      try {
        await tick();
        // Loading screen is present; the bridge-unavailable notice is not.
        expect(target.textContent).not.toContain("Native player unavailable");
        expect(playerMock.play).toHaveBeenCalled();
      } finally {
        await unmount(instance, { outro: false });
        target.remove();
      }
    });
  }
});
