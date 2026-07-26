import { mount, tick, unmount } from "svelte";
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";

import type { Media } from "$lib/types/tmdb";
import MobileMediaActionsSheet from "./MobileMediaActionsSheet.svelte";

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

const media: Media = {
  id: 10,
  title: "Film",
  name: "",
  overview: "",
  release_date: "",
  first_air_date: "",
  poster_path: "/poster.jpg",
  vote_average: 8,
  media_type: "movie",
  trailer_url: "",
  clip_urls: "",
  images: [],
  popularity: 1,
};

describe("MobileMediaActionsSheet", () => {
  it("portals outside paint-containment ancestors", async () => {
    const containingBlock = document.createElement("div");
    containingBlock.style.contentVisibility = "auto";
    containingBlock.style.overflow = "hidden";
    const target = document.createElement("div");
    containingBlock.append(target);
    document.body.append(containingBlock);

    const sheet = mount(MobileMediaActionsSheet, {
      target,
      intro: false,
      props: {
        media,
        libraryEntry: null,
        dismissed: false,
        hasProgress: false,
        open: true,
        showTrigger: false,
      },
    });
    await tick();

    try {
      const dialog =
        document.body.querySelector<HTMLElement>('[role="dialog"]');
      expect(dialog).not.toBeNull();
      expect(containingBlock.contains(dialog)).toBe(false);
      expect(dialog?.parentElement).toBe(document.body);
    } finally {
      await unmount(sheet, { outro: false });
      containingBlock.remove();
    }
  });
});
