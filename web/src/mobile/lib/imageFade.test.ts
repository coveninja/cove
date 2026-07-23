import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { imageFade } from "./imageFade";

function requireAction<T>(result: T | void): T {
  if (!result) throw new Error("action did not return lifecycle hooks");
  return result;
}

function image(complete = false, naturalWidth = 0): HTMLImageElement {
  const node = document.createElement("img");
  Object.defineProperty(node, "complete", {
    configurable: true,
    value: complete,
  });
  Object.defineProperty(node, "naturalWidth", {
    configurable: true,
    value: naturalWidth,
  });
  return node;
}

describe("imageFade", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("does nothing for an already-cached image", () => {
    const node = image(true, 100);
    const action = imageFade(node, { duration: 100 });

    expect(node.style.opacity).toBe("");
    expect(node.style.transition).toBe("");
    expect(action).toEqual({});
  });

  it("reveals on load and clears inline styles after transition end", () => {
    const node = image();
    const action = requireAction(imageFade(node, { duration: 120 }));
    expect(node.style.opacity).toBe("0");
    expect(node.style.transition).toBe("opacity 120ms ease");

    node.dispatchEvent(new Event("load"));
    expect(node.style.opacity).toBe("1");
    node.dispatchEvent(new Event("transitionend"));
    expect(node.style.opacity).toBe("");
    expect(node.style.transition).toBe("");

    action.destroy?.();
  });

  it("reveals errors and uses a fallback timer when transitionend is absent", () => {
    const node = image();
    const action = requireAction(imageFade(node, undefined));

    node.dispatchEvent(new Event("error"));
    expect(node.style.opacity).toBe("1");
    vi.advanceTimersByTime(299);
    expect(node.style.opacity).toBe("1");
    vi.advanceTimersByTime(1);
    expect(node.style.opacity).toBe("");
    expect(node.style.transition).toBe("");

    action.destroy?.();
  });

  it("removes listeners, timers, and owned styles on destroy", () => {
    const node = image();
    const action = requireAction(imageFade(node, { duration: 100 }));
    action.destroy?.();

    expect(node.style.opacity).toBe("");
    expect(node.style.transition).toBe("");
    node.dispatchEvent(new Event("load"));
    expect(node.style.opacity).toBe("");
    vi.runAllTimers();
    expect(node.style.opacity).toBe("");
  });
});
