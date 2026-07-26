import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { longpress } from "./longpress";

function requireAction<T>(result: T | void): T {
  if (!result) throw new Error("action did not return lifecycle hooks");
  return result;
}

function pointer(
  type: string,
  { x = 0, y = 0, button = 0 } = {},
): PointerEvent {
  const event = new Event(type) as PointerEvent;
  Object.defineProperties(event, {
    button: { value: button },
    clientX: { value: x },
    clientY: { value: y },
  });
  return event;
}

describe("longpress", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("fires once after a stationary primary press reaches the duration", () => {
    const node = document.createElement("button");
    const onLongPress = vi.fn();
    const action = requireAction(
      longpress(node, { onLongPress, duration: 400 }),
    );

    node.dispatchEvent(pointer("pointerdown", { x: 10, y: 20 }));
    vi.advanceTimersByTime(399);
    expect(onLongPress).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(onLongPress).toHaveBeenCalledTimes(1);
    vi.runAllTimers();
    expect(onLongPress).toHaveBeenCalledTimes(1);

    action.destroy?.();
  });

  it("suppresses the click generated after a successful long press", () => {
    const node = document.createElement("button");
    const onClick = vi.fn();
    node.addEventListener("click", onClick);
    const action = requireAction(
      longpress(node, { onLongPress: vi.fn(), duration: 100 }),
    );

    node.dispatchEvent(pointer("pointerdown"));
    vi.advanceTimersByTime(100);
    node.dispatchEvent(pointer("pointerup"));
    const suppressed = new MouseEvent("click", { cancelable: true });
    expect(node.dispatchEvent(suppressed)).toBe(false);
    expect(onClick).not.toHaveBeenCalled();

    const nextClick = new MouseEvent("click", { cancelable: true });
    expect(node.dispatchEvent(nextClick)).toBe(true);
    expect(onClick).toHaveBeenCalledOnce();
    action.destroy?.();
  });

  it("cancels on release, cancellation, leaving, or scrolling movement", () => {
    const node = document.createElement("button");
    const onLongPress = vi.fn();
    const action = requireAction(longpress(node, { onLongPress }));

    for (const releaseEvent of ["pointerup", "pointercancel", "pointerleave"]) {
      node.dispatchEvent(pointer("pointerdown"));
      node.dispatchEvent(pointer(releaseEvent));
      vi.advanceTimersByTime(500);
    }
    node.dispatchEvent(pointer("pointerdown", { x: 2, y: 2 }));
    node.dispatchEvent(pointer("pointermove", { x: 20, y: 2 }));
    vi.advanceTimersByTime(500);

    expect(onLongPress).not.toHaveBeenCalled();
    action.destroy?.();
  });

  it("ignores non-primary presses and updates its callback and duration", () => {
    const node = document.createElement("button");
    const first = vi.fn();
    const second = vi.fn();
    const action = requireAction(
      longpress(node, { onLongPress: first, duration: 500 }),
    );

    node.dispatchEvent(pointer("pointerdown", { button: 2 }));
    vi.advanceTimersByTime(500);
    expect(first).not.toHaveBeenCalled();

    action.update?.({ onLongPress: second, duration: 100 });
    node.dispatchEvent(pointer("pointerdown"));
    vi.advanceTimersByTime(100);
    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledOnce();

    action.destroy?.();
  });

  it("clears pending work and listeners when destroyed", () => {
    const node = document.createElement("button");
    const onLongPress = vi.fn();
    const action = requireAction(longpress(node, { onLongPress }));

    node.dispatchEvent(pointer("pointerdown"));
    action.destroy?.();
    vi.advanceTimersByTime(500);
    node.dispatchEvent(pointer("pointerdown"));
    vi.advanceTimersByTime(500);

    expect(onLongPress).not.toHaveBeenCalled();
  });
});
