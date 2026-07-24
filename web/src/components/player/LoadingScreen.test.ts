import { mount, tick, unmount } from "svelte";
import { describe, expect, it, vi } from "vitest";

import LoadingScreen from "./LoadingScreen.svelte";
import MobileLoadingScreen from "../../mobile/components/player/MobileLoadingScreen.svelte";
import TvLoadingScreen from "../../tv/components/player/TvLoadingScreen.svelte";

function findButton(target: HTMLElement, label: string): HTMLButtonElement {
  const button = [...target.querySelectorAll("button")].find(
    (candidate) => candidate.textContent?.trim() === label,
  );
  if (!(button instanceof HTMLButtonElement)) {
    throw new Error(`Could not find the ${label} button`);
  }
  return button;
}

describe("player loading screens", () => {
  it("lets desktop users cancel immediately during stream discovery", async () => {
    const target = document.createElement("div");
    document.body.append(target);
    const onCancel = vi.fn();
    const screen = mount(LoadingScreen, {
      target,
      props: {
        title: "Test movie",
        logoUrl: null,
        loadingMessage: "Finding streams…",
        takingAWhile: false,
        cancelVisible: true,
        onCancel,
      },
    });

    try {
      findButton(target, "Cancel").click();
      expect(onCancel).toHaveBeenCalledOnce();
      expect(target.textContent).not.toContain("This is taking a while");
    } finally {
      await unmount(screen, { outro: false });
      target.remove();
    }
  });

  it("lets mobile users cancel immediately during stream discovery", async () => {
    const target = document.createElement("div");
    document.body.append(target);
    const onCancel = vi.fn();
    const screen = mount(MobileLoadingScreen, {
      target,
      props: {
        title: "Test movie",
        logoUrl: null,
        loadingMessage: "Finding streams…",
        takingAWhile: false,
        cancelVisible: true,
        onCancel,
      },
    });

    try {
      findButton(target, "Cancel").click();
      expect(onCancel).toHaveBeenCalledOnce();
      expect(target.textContent).not.toContain("This is taking a while");
    } finally {
      await unmount(screen, { outro: false });
      target.remove();
    }
  });

  it("focuses the immediately available TV cancel action", async () => {
    const target = document.createElement("div");
    document.body.append(target);
    const onCancel = vi.fn();
    const screen = mount(TvLoadingScreen, {
      target,
      props: {
        title: "Test movie",
        logoUrl: null,
        loadingMessage: "Finding streams…",
        takingAWhile: false,
        cancelVisible: true,
        onCancel,
      },
    });

    try {
      await tick();
      await tick();
      const cancel = findButton(target, "Cancel");
      expect(document.activeElement).toBe(cancel);
      cancel.click();
      expect(onCancel).toHaveBeenCalledOnce();
      expect(target.textContent).not.toContain("This is taking a while");
    } finally {
      await unmount(screen, { outro: false });
      target.remove();
    }
  });
});
