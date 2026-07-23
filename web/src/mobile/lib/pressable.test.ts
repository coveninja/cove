import { describe, expect, it } from "vitest";

import { pressable } from "./pressable";

function requireAction<T>(result: T | void): T {
  if (!result) throw new Error("action did not return lifecycle hooks");
  return result;
}

describe("pressable", () => {
  it("applies pointer feedback and clears it on every release path", () => {
    const node = document.createElement("button");
    const action = requireAction(pressable(node, { scale: 0.9, duration: 80 }));
    expect(node.style.transition).toBe("transform 80ms ease");

    node.dispatchEvent(new Event("pointerdown"));
    expect(node.style.transform).toBe("scale(0.9)");
    for (const event of ["pointerup", "pointercancel", "pointerleave"]) {
      node.dispatchEvent(new Event("pointerdown"));
      node.dispatchEvent(new Event(event));
      expect(node.style.transform).toBe("");
    }

    action.destroy?.();
  });

  it("updates options and restores defaults when options become absent", () => {
    const node = document.createElement("button");
    const action = requireAction(pressable(node, undefined));
    expect(node.style.transition).toBe("transform 100ms ease");

    action.update?.({ scale: 0.8, duration: 200 });
    expect(node.style.transition).toBe("transform 200ms ease");
    node.dispatchEvent(new Event("pointerdown"));
    expect(node.style.transform).toBe("scale(0.8)");

    action.update?.(undefined);
    expect(node.style.transition).toBe("transform 100ms ease");
    node.dispatchEvent(new Event("pointerdown"));
    expect(node.style.transform).toBe("scale(0.94)");
    action.destroy?.();
  });

  it("removes listeners and owned inline styles on destroy", () => {
    const node = document.createElement("button");
    const action = requireAction(pressable(node, { scale: 0.9 }));
    node.dispatchEvent(new Event("pointerdown"));
    expect(node.style.transform).toBe("scale(0.9)");

    action.destroy?.();
    expect(node.style.transform).toBe("");
    expect(node.style.transition).toBe("");

    node.dispatchEvent(new Event("pointerdown"));
    expect(node.style.transform).toBe("");
  });
});
