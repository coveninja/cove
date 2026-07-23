import type { Action } from "svelte/action";
import {
  registerFocusable,
  unregisterFocusable,
  registerGroup,
  unregisterGroup,
} from "./focusStore.svelte";
import type { FocusableOptions, FocusGroupOptions } from "./types";

/**
 * Svelte action that marks an element as D-pad-focusable.
 *
 * - Sets `el.dataset.tvFocusable = ""` so the geometric engine and CSS focus
 *   ring can target it via `[data-tv-focusable]`.
 * - Sets `el.tabIndex = -1` **only** when the element has no existing
 *   `tabindex` attribute, preserving the natural tab order of native
 *   interactive elements (buttons, links, inputs).
 * - Registers the element with the focus store on mount; unregisters on
 *   destroy.
 *
 * @example
 * ```svelte
 * <div use:focusable={{ groupId: "hero" }}>Card</div>
 * ```
 */
export const focusable: Action<HTMLElement, FocusableOptions | undefined> = (
  el,
  options,
) => {
  const addedTabIndex = !el.hasAttribute("tabindex");
  const addedFocusableMarker = !el.hasAttribute("data-tv-focusable");
  if (addedTabIndex) {
    el.tabIndex = -1;
  }
  if (addedFocusableMarker) el.dataset.tvFocusable = "";
  registerFocusable(el, options?.groupId);

  return {
    update(newOptions) {
      unregisterFocusable(el);
      registerFocusable(el, newOptions?.groupId);
    },
    destroy() {
      unregisterFocusable(el);
      if (addedFocusableMarker) delete el.dataset.tvFocusable;
      if (addedTabIndex) el.removeAttribute("tabindex");
    },
  };
};

/**
 * Svelte action that registers an element as a focus-group container.
 *
 * The element acts as the spatial boundary for policy-based navigation
 * (row / column / grid / free).  Child elements are either:
 * - Explicitly annotated via `use:focusable` with a matching `groupId`, or
 * - Native focusables (buttons, inputs, …) that the engine discovers by
 *   containment — making reused desktop components D-pad-navigable without
 *   modification.
 *
 * @example
 * ```svelte
 * <div use:focusGroup={{ id: "media-row", policy: { type: "row" } }}>
 *   {#each cards as card}
 *     <MediaCard use:focusable={{ groupId: "media-row" }} {card} />
 *   {/each}
 * </div>
 * ```
 */
export const focusGroup: Action<HTMLElement, FocusGroupOptions> = (
  el,
  options,
) => {
  registerGroup(el, options);

  return {
    update(newOptions) {
      if (newOptions.id !== options.id) unregisterGroup(options.id);
      registerGroup(el, newOptions);
      options = newOptions;
    },
    destroy() {
      unregisterGroup(options.id);
    },
  };
};
