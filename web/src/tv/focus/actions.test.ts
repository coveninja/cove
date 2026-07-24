import { beforeEach, describe, expect, it, vi } from "vitest";

const focusStore = vi.hoisted(() => ({
  registerFocusable: vi.fn(),
  unregisterFocusable: vi.fn(),
  registerGroup: vi.fn(),
  unregisterGroup: vi.fn(),
}));

vi.mock("./focusStore.svelte", () => focusStore);

import { focusable, focusGroup } from "./actions";

function requireAction<T>(result: T | void): T {
  if (!result) throw new Error("action did not return lifecycle hooks");
  return result;
}

describe("TV focus actions", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("adds and later removes only the focusability attributes it owns", () => {
    const node = document.createElement("div");
    const action = requireAction(focusable(node, { groupId: "hero" }));

    expect(node.tabIndex).toBe(-1);
    expect(node.hasAttribute("data-tv-focusable")).toBe(true);
    expect(focusStore.registerFocusable).toHaveBeenCalledWith(node, "hero");

    action.destroy?.();
    expect(focusStore.unregisterFocusable).toHaveBeenCalledWith(node);
    expect(node.hasAttribute("tabindex")).toBe(false);
    expect(node.hasAttribute("data-tv-focusable")).toBe(false);
  });

  it("preserves pre-existing tabindex and focus marker attributes", () => {
    const node = document.createElement("button");
    node.tabIndex = 3;
    node.dataset.tvFocusable = "existing";

    const action = requireAction(focusable(node, undefined));
    action.destroy?.();

    expect(node.tabIndex).toBe(3);
    expect(node.dataset.tvFocusable).toBe("existing");
  });

  it("re-registers a focusable when its group changes", () => {
    const node = document.createElement("div");
    const action = requireAction(focusable(node, { groupId: "first" }));

    action.update?.({ groupId: "second" });

    expect(focusStore.unregisterFocusable).toHaveBeenCalledWith(node);
    expect(focusStore.registerFocusable).toHaveBeenLastCalledWith(
      node,
      "second",
    );
    action.destroy?.();
  });

  it("updates a same-id group without discarding remembered focus", () => {
    const node = document.createElement("div");
    const initial = { id: "row", policy: { type: "row" } as const };
    const updated = {
      id: "row",
      policy: { type: "row" } as const,
      trapFocus: true,
    };
    const action = requireAction(focusGroup(node, initial));

    action.update?.(updated);

    expect(focusStore.unregisterGroup).not.toHaveBeenCalled();
    expect(focusStore.registerGroup).toHaveBeenLastCalledWith(node, updated);
    action.destroy?.();
    expect(focusStore.unregisterGroup).toHaveBeenCalledWith("row");
  });

  it("unregisters the old id when a group changes identity", () => {
    const node = document.createElement("div");
    const action = requireAction(
      focusGroup(node, {
        id: "old",
        policy: { type: "row" },
      }),
    );

    action.update?.({ id: "new", policy: { type: "column" } });
    expect(focusStore.unregisterGroup).toHaveBeenCalledWith("old");
    expect(focusStore.registerGroup).toHaveBeenLastCalledWith(node, {
      id: "new",
      policy: { type: "column" },
    });

    action.destroy?.();
    expect(focusStore.unregisterGroup).toHaveBeenLastCalledWith("new");
  });
});
