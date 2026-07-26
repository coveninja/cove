import type { Action } from "svelte/action";

export interface LongPressOptions {
  onLongPress: () => void;
  duration?: number;
  movementTolerance?: number;
}

/**
 * Fires once when a primary pointer remains down without moving far enough to
 * indicate scrolling. It also suppresses the click generated when a successful
 * long press is released, so the card's ordinary tap action does not run.
 */
export const longpress: Action<HTMLElement, LongPressOptions> = (
  node,
  options,
) => {
  let onLongPress = options.onLongPress;
  let duration = options.duration ?? 500;
  let movementTolerance = options.movementTolerance ?? 12;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let clickResetTimer: ReturnType<typeof setTimeout> | null = null;
  let startX = 0;
  let startY = 0;
  let suppressClick = false;

  function cancel(): void {
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
  }

  function onPointerDown(event: PointerEvent): void {
    if (event.button !== 0) return;
    cancel();
    if (clickResetTimer !== null) clearTimeout(clickResetTimer);
    clickResetTimer = null;
    suppressClick = false;
    startX = event.clientX;
    startY = event.clientY;
    timer = setTimeout(() => {
      timer = null;
      suppressClick = true;
      onLongPress();
    }, duration);
  }

  function onPointerMove(event: PointerEvent): void {
    if (
      Math.hypot(event.clientX - startX, event.clientY - startY) >
      movementTolerance
    ) {
      cancel();
    }
  }

  function onPointerEnd(): void {
    cancel();
    if (suppressClick) {
      clickResetTimer = setTimeout(() => {
        suppressClick = false;
        clickResetTimer = null;
      }, 1000);
    }
  }

  function onClick(event: MouseEvent): void {
    if (!suppressClick) return;
    suppressClick = false;
    event.preventDefault();
    event.stopImmediatePropagation();
  }

  node.addEventListener("pointerdown", onPointerDown);
  node.addEventListener("pointermove", onPointerMove);
  node.addEventListener("pointerup", onPointerEnd);
  node.addEventListener("pointercancel", onPointerEnd);
  node.addEventListener("pointerleave", onPointerEnd);
  node.addEventListener("click", onClick, true);

  return {
    update(nextOptions) {
      onLongPress = nextOptions.onLongPress;
      duration = nextOptions.duration ?? 500;
      movementTolerance = nextOptions.movementTolerance ?? 12;
    },
    destroy() {
      cancel();
      if (clickResetTimer !== null) clearTimeout(clickResetTimer);
      node.removeEventListener("pointerdown", onPointerDown);
      node.removeEventListener("pointermove", onPointerMove);
      node.removeEventListener("pointerup", onPointerEnd);
      node.removeEventListener("pointercancel", onPointerEnd);
      node.removeEventListener("pointerleave", onPointerEnd);
      node.removeEventListener("click", onClick, true);
    },
  };
};
