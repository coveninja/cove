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
  it("shows the cancel button via takingAWhile alone on the desktop screen", async () => {
    // Covers the takingAWhile branch of line 69: {#if cancelVisible || takingAWhile}
    // when cancelVisible is false but the timer has fired.
    const target = document.createElement("div");
    document.body.append(target);
    const onCancel = vi.fn();
    const screen = mount(LoadingScreen, {
      target,
      props: {
        title: "Slow stream",
        logoUrl: null,
        loadingMessage: "Connecting…",
        takingAWhile: true,
        cancelVisible: false,
        onCancel,
      },
    });

    try {
      expect(target.textContent).toContain("This is taking a while");
      findButton(target, "Cancel").click();
      expect(onCancel).toHaveBeenCalledOnce();
    } finally {
      await unmount(screen, { outro: false });
      target.remove();
    }
  });

  it("shows the cancel button via takingAWhile alone on the mobile screen", async () => {
    // Covers MobileLoadingScreen line 71: {#if cancelVisible || takingAWhile}
    const target = document.createElement("div");
    document.body.append(target);
    const onCancel = vi.fn();
    const screen = mount(MobileLoadingScreen, {
      target,
      props: {
        title: "Slow stream",
        logoUrl: null,
        loadingMessage: "Connecting…",
        takingAWhile: true,
        cancelVisible: false,
        onCancel,
      },
    });

    try {
      expect(target.textContent).toContain("This is taking a while");
      findButton(target, "Cancel").click();
      expect(onCancel).toHaveBeenCalledOnce();
    } finally {
      await unmount(screen, { outro: false });
      target.remove();
    }
  });

  it("shows the cancel button via takingAWhile alone on the TV screen", async () => {
    // Covers TvLoadingScreen line 68: {#if cancelVisible || takingAWhile}
    const target = document.createElement("div");
    document.body.append(target);
    const onCancel = vi.fn();
    const screen = mount(TvLoadingScreen, {
      target,
      props: {
        title: "Slow stream",
        logoUrl: null,
        loadingMessage: "Connecting…",
        takingAWhile: true,
        cancelVisible: false,
        onCancel,
      },
    });

    try {
      expect(target.textContent).toContain("This is taking a while");
      findButton(target, "Cancel").click();
      expect(onCancel).toHaveBeenCalledOnce();
    } finally {
      await unmount(screen, { outro: false });
      target.remove();
    }
  });

  it("does not auto-focus the TV cancel button when cancelVisible is false", async () => {
    // Covers the false branch of TvLoadingScreen line 28:
    // if (cancelVisible && cancelButton) — when cancelVisible is false the
    // button must NOT receive focus (the effect body is skipped).
    const target = document.createElement("div");
    document.body.append(target);
    const onCancel = vi.fn();
    const screen = mount(TvLoadingScreen, {
      target,
      props: {
        title: "Buffering",
        logoUrl: null,
        loadingMessage: "Connecting…",
        takingAWhile: true,
        cancelVisible: false,
        onCancel,
      },
    });

    try {
      await tick();
      await tick();
      const cancel = findButton(target, "Cancel");
      // Button is present (shown via takingAWhile) but must NOT have been focused.
      expect(document.activeElement).not.toBe(cancel);
    } finally {
      await unmount(screen, { outro: false });
      target.remove();
    }
  });

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
