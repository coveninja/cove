import type { Action } from "svelte/action";

export interface PressableOptions {
  scale?: number;
  duration?: number;
}

/**
 * Svelte action that gives an element press-scale touch feedback via pointer
 * events. This action owns the node's inline `transition` property — if the
 * node has an inline transition before mount it will be replaced.
 *
 * Usage: `<button use:pressable={{ scale: 0.94, duration: 100 }}>…</button>`
 */
export const pressable: Action<HTMLElement, PressableOptions | undefined> = (
  node,
  opts,
) => {
  let scale = opts?.scale ?? 0.94;
  let duration = opts?.duration ?? 100;

  function applyTransition() {
    node.style.transition = `transform ${duration}ms ease`;
  }

  function onPointerDown() {
    node.style.transform = `scale(${scale})`;
  }

  function onPointerUp() {
    node.style.transform = "";
  }

  applyTransition();

  node.addEventListener("pointerdown", onPointerDown);
  node.addEventListener("pointerup", onPointerUp);
  node.addEventListener("pointercancel", onPointerUp);
  node.addEventListener("pointerleave", onPointerUp);

  return {
    update(newOpts) {
      scale = newOpts?.scale ?? 0.94;
      duration = newOpts?.duration ?? 100;
      applyTransition();
    },
    destroy() {
      node.removeEventListener("pointerdown", onPointerDown);
      node.removeEventListener("pointerup", onPointerUp);
      node.removeEventListener("pointercancel", onPointerUp);
      node.removeEventListener("pointerleave", onPointerUp);
      node.style.transform = "";
      node.style.transition = "";
    },
  };
};
