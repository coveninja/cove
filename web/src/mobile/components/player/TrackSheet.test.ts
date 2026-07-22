import { mount, tick, unmount } from "svelte";
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";

import TrackSheet from "./TrackSheet.svelte";

const originalAnimate = Element.prototype.animate;

beforeAll(() => {
  // jsdom does not implement Web Animations, which Svelte transitions use.
  Object.defineProperty(Element.prototype, "animate", {
    configurable: true,
    value: () =>
      ({
        cancel: vi.fn(),
        currentTime: 0,
        effect: null,
        onfinish: null,
        playState: "finished",
      }) as unknown as Animation,
  });
});

afterAll(() => {
  if (originalAnimate) {
    Object.defineProperty(Element.prototype, "animate", {
      configurable: true,
      value: originalAnimate,
    });
  } else {
    delete (Element.prototype as Partial<Element>).animate;
  }
});

function touchEvent(
  type: "touchstart" | "touchmove" | "touchend",
  y = 0,
): Event {
  const event = new Event(type, { bubbles: true, cancelable: true });
  Object.defineProperty(event, "touches", {
    value: type === "touchend" ? [] : [{ clientY: y }],
  });
  return event;
}

async function renderSheet(onClose = vi.fn()) {
  const target = document.createElement("div");
  document.body.append(target);
  const sheet = mount(TrackSheet, {
    target,
    intro: false,
    props: {
      title: "Subtitles",
      items: [{ id: "off", label: "Off" }],
      selectedId: "off",
      onSelect: vi.fn(),
      onClose,
    },
  });
  await tick();

  const dialog = target.querySelector<HTMLElement>('[role="dialog"]');
  const button = target.querySelector<HTMLButtonElement>("button");
  const handle = target.querySelector<HTMLElement>('[aria-hidden="true"]');
  if (!dialog || !button || !handle || !button.parentElement) {
    throw new Error("Track sheet did not render its expected structure");
  }

  return {
    button,
    dialog,
    handle,
    list: button.parentElement,
    onClose,
    async cleanup() {
      await unmount(sheet, { outro: false });
      target.remove();
    },
  };
}

describe("TrackSheet", () => {
  it("constrains the complete sheet and lets the item list shrink and scroll", async () => {
    const rendered = await renderSheet();
    try {
      expect(rendered.dialog.getAttribute("style")).toContain(
        "max-height: calc(100vh - max(0.5rem, var(--safe-top)))",
      );
      expect(rendered.list.classList).toContain("min-h-0");
      expect(rendered.list.classList).toContain("flex-1");
      expect(rendered.list.classList).toContain("overflow-y-auto");
    } finally {
      await rendered.cleanup();
    }
  });

  it("does not dismiss when the user pulls down on the scrollable list", async () => {
    const rendered = await renderSheet();
    try {
      rendered.button.dispatchEvent(touchEvent("touchstart", 100));
      rendered.button.dispatchEvent(touchEvent("touchmove", 200));
      rendered.button.dispatchEvent(touchEvent("touchend"));

      expect(rendered.onClose).not.toHaveBeenCalled();
      expect(rendered.dialog.style.transform).toBe("");
    } finally {
      await rendered.cleanup();
    }
  });

  it("retains drag-to-dismiss on the dedicated handle", async () => {
    const rendered = await renderSheet();
    try {
      rendered.handle.dispatchEvent(touchEvent("touchstart", 100));
      rendered.handle.dispatchEvent(touchEvent("touchmove", 200));
      rendered.handle.dispatchEvent(touchEvent("touchend"));

      expect(rendered.onClose).toHaveBeenCalledOnce();
    } finally {
      await rendered.cleanup();
    }
  });
});
