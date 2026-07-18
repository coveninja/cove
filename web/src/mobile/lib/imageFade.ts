import type { Action } from "svelte/action";

export interface ImageFadeOptions {
  duration?: number;
}

/**
 * Svelte action for `<img>` elements that fades them in on load.
 *
 * If the image is already cached (`complete && naturalWidth > 0`) the action
 * is a no-op so the element is never invisible.
 *
 * After the fade transition completes, **both** inline `opacity` and
 * `transition` are cleared so that consumers can drive watched/dropped dimming
 * via opacity utility classes without a lingering inline style overriding them.
 *
 * Usage: `<img use:imageFade={{ duration: 250 }} src={…} />`
 */
export const imageFade: Action<
  HTMLImageElement,
  ImageFadeOptions | undefined
> = (node, opts) => {
  const d = opts?.duration ?? 250;

  // Already cached — skip the fade entirely.
  if (node.complete && node.naturalWidth > 0) {
    return {};
  }

  node.style.opacity = "0";
  node.style.transition = `opacity ${d}ms ease`;

  let fallbackTimer: ReturnType<typeof setTimeout> | undefined;

  function clearStyles() {
    node.style.opacity = "";
    node.style.transition = "";
  }

  function onTransitionEnd() {
    clearTimeout(fallbackTimer);
    clearStyles();
  }

  function onReveal() {
    node.style.opacity = "1";

    // After the transition completes, remove inline styles so consumers
    // can control opacity via utility classes (e.g. dimming watched items).
    node.addEventListener("transitionend", onTransitionEnd, { once: true });
    fallbackTimer = setTimeout(clearStyles, d + 50);
  }

  node.addEventListener("load", onReveal, { once: true });
  node.addEventListener("error", onReveal, { once: true });

  return {
    destroy() {
      node.removeEventListener("load", onReveal);
      node.removeEventListener("error", onReveal);
      node.removeEventListener("transitionend", onTransitionEnd);
      clearTimeout(fallbackTimer);
    },
  };
};
