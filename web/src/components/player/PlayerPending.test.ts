import { mount, tick, unmount } from "svelte";
import { describe, expect, it, vi } from "vitest";

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
