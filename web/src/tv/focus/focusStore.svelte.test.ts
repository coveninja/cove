import {
  afterAll,
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";

import {
  editableKeepsArrow,
  focusAfterKeyRelease,
  focusFirst,
  navigate,
  registerFocusable,
  registerGroup,
  unregisterFocusable,
  unregisterGroup,
} from "./focusStore.svelte";

// ── jsdom shims ────────────────────────────────────────────────────────────────

// jsdom does not implement scrollIntoView — install a no-op so focusEl() and
// geometricNavigate() can call it without throwing.
const originalScrollIntoView = Element.prototype.scrollIntoView;
beforeAll(() => {
  Element.prototype.scrollIntoView = vi.fn();
});
afterAll(() => {
  Element.prototype.scrollIntoView = originalScrollIntoView;
});

// ── Helpers ────────────────────────────────────────────────────────────────────

function makeEl(
  x: number,
  y: number,
  w = 40,
  h = 40,
  parent: HTMLElement = document.body,
): HTMLButtonElement {
  const el = document.createElement("button");
  parent.append(el);
  mockRect(el, x, y, w, h);
  return el;
}

function mockRect(
  el: HTMLElement,
  x: number,
  y: number,
  w: number,
  h: number,
): void {
  const rect = {
    left: x,
    top: y,
    right: x + w,
    bottom: y + h,
    width: w,
    height: h,
    x,
    y,
    toJSON: () => ({}),
  } as DOMRect;
  vi.spyOn(el, "getBoundingClientRect").mockReturnValue(rect);
  // isVisible(): passes when offsetParent !== null OR getClientRects().length > 0
  el.getClientRects = () => ({ length: 1 }) as DOMRectList;
}

afterEach(() => {
  document.body.innerHTML = "";
  vi.restoreAllMocks();
});

// ── editableKeepsArrow ─────────────────────────────────────────────────────────

describe("editableKeepsArrow", () => {
  it("returns false for a plain button in any direction", () => {
    const el = document.createElement("button");
    for (const dir of ["left", "right", "up", "down"] as const) {
      expect(editableKeepsArrow(el, dir)).toBe(false);
    }
  });

  it("returns true for a text input on left/right (caret movement)", () => {
    const input = document.createElement("input");
    input.type = "text";
    expect(editableKeepsArrow(input, "left")).toBe(true);
    expect(editableKeepsArrow(input, "right")).toBe(true);
  });

  it("returns false for a text input on up/down (no caret meaning)", () => {
    const input = document.createElement("input");
    input.type = "text";
    expect(editableKeepsArrow(input, "up")).toBe(false);
    expect(editableKeepsArrow(input, "down")).toBe(false);
  });

  it("returns true for a textarea in all four directions", () => {
    const ta = document.createElement("textarea");
    for (const dir of ["left", "right", "up", "down"] as const) {
      expect(editableKeepsArrow(ta, dir)).toBe(true);
    }
  });

  it("returns true for a number input on up/down (value stepping)", () => {
    const input = document.createElement("input");
    input.type = "number";
    expect(editableKeepsArrow(input, "up")).toBe(true);
    expect(editableKeepsArrow(input, "down")).toBe(true);
  });

  it("reports non-editable for a plain div regardless of direction", () => {
    // A bare div with no contenteditable attribute is never editable.
    const div = document.createElement("div");
    for (const dir of ["left", "right", "up", "down"] as const) {
      expect(editableKeepsArrow(div, dir)).toBe(false);
    }
  });
});

// ── registerFocusable / unregisterFocusable ────────────────────────────────────

describe("registerFocusable / unregisterFocusable", () => {
  it("registered element participates in focusFirst", () => {
    const el = makeEl(0, 0);
    el.setAttribute("data-tv-focusable", "");
    registerFocusable(el);

    focusFirst();
    expect(document.activeElement).toBe(el);

    unregisterFocusable(el);
  });
});

// ── focusFirst ─────────────────────────────────────────────────────────────────

describe("focusFirst", () => {
  it("focuses the first visible button in document order", () => {
    const a = makeEl(0, 0);
    const b = makeEl(100, 0);
    void b;

    focusFirst();
    // a is appended before b so it comes first in document order.
    expect(document.activeElement).toBe(a);
  });

  it("focuses within the provided scope element", () => {
    const outer = makeEl(0, 0); // outside scope
    void outer;
    const scope = document.createElement("div");
    document.body.append(scope);
    const inner = makeEl(100, 0, 40, 40, scope);

    focusFirst(scope);
    expect(document.activeElement).toBe(inner);
  });

  it("does not throw when there are no focusable elements", () => {
    // Empty body — nothing to focus.
    expect(() => focusFirst()).not.toThrow();
  });
});

// ── navigate — no active element ───────────────────────────────────────────────

describe("navigate with no active element", () => {
  it("calls focusFirst when nothing is focused", () => {
    const el = makeEl(0, 0);
    document.body.focus();
    navigate("right");
    expect(document.activeElement).toBe(el);
  });
});

// ── navigate — row group policy ────────────────────────────────────────────────

describe("navigate inside a row group", () => {
  const GROUP_ID = "test-row";
  let container: HTMLElement;
  let a: HTMLElement, b: HTMLElement, c: HTMLElement;

  beforeEach(() => {
    container = document.createElement("div");
    container.id = "row-container";
    document.body.append(container);
    mockRect(container, 0, 0, 300, 40);
    container.getClientRects = () => ({ length: 1 }) as DOMRectList;

    a = makeEl(0, 0, 40, 40, container);
    b = makeEl(60, 0, 40, 40, container);
    c = makeEl(120, 0, 40, 40, container);

    registerFocusable(a, GROUP_ID);
    registerFocusable(b, GROUP_ID);
    registerFocusable(c, GROUP_ID);
    registerGroup(container, { id: GROUP_ID, policy: { type: "row" } });
  });

  afterEach(() => {
    unregisterFocusable(a);
    unregisterFocusable(b);
    unregisterFocusable(c);
    unregisterGroup(GROUP_ID);
  });

  it("moves right to the next member", () => {
    a.focus();
    navigate("right");
    expect(document.activeElement).toBe(b);
  });

  it("moves left to the previous member", () => {
    b.focus();
    navigate("left");
    expect(document.activeElement).toBe(a);
  });

  it("does not move left at the start of the row (stays on a)", () => {
    a.focus();
    navigate("left");
    expect(document.activeElement).toBe(a);
  });

  it("does not move right at the end of the row (stays on c)", () => {
    c.focus();
    navigate("right");
    expect(document.activeElement).toBe(c);
  });
});

// ── navigate — column group policy ────────────────────────────────────────────

describe("navigate inside a column group", () => {
  const GROUP_ID = "test-col";
  let container: HTMLElement;
  let a: HTMLElement, b: HTMLElement;

  beforeEach(() => {
    container = document.createElement("div");
    document.body.append(container);
    mockRect(container, 0, 0, 40, 200);
    container.getClientRects = () => ({ length: 1 }) as DOMRectList;

    a = makeEl(0, 0, 40, 40, container);
    b = makeEl(0, 60, 40, 40, container);

    registerFocusable(a, GROUP_ID);
    registerFocusable(b, GROUP_ID);
    registerGroup(container, { id: GROUP_ID, policy: { type: "column" } });
  });

  afterEach(() => {
    unregisterFocusable(a);
    unregisterFocusable(b);
    unregisterGroup(GROUP_ID);
  });

  it("moves down to the next member", () => {
    a.focus();
    navigate("down");
    expect(document.activeElement).toBe(b);
  });

  it("moves up to the previous member", () => {
    b.focus();
    navigate("up");
    expect(document.activeElement).toBe(a);
  });
});

// ── navigate — trapFocus ───────────────────────────────────────────────────────

describe("navigate with trapFocus", () => {
  const GROUP_ID = "trapped";
  let container: HTMLElement;
  let a: HTMLElement;

  beforeEach(() => {
    container = document.createElement("div");
    document.body.append(container);
    mockRect(container, 0, 0, 120, 40);
    container.getClientRects = () => ({ length: 1 }) as DOMRectList;

    a = makeEl(0, 0, 40, 40, container);
    registerFocusable(a, GROUP_ID);
    registerGroup(container, {
      id: GROUP_ID,
      policy: { type: "row" },
      trapFocus: true,
    });
  });

  afterEach(() => {
    unregisterFocusable(a);
    unregisterGroup(GROUP_ID);
  });

  it("keeps focus inside the group at the edge when trapFocus is true", () => {
    // Create an external element that would normally receive focus geometrically.
    makeEl(200, 0);
    a.focus();
    navigate("right");
    // trapFocus: the move is silently dropped, focus stays on a.
    expect(document.activeElement).toBe(a);
  });
});

// ── navigate — grid group policy ──────────────────────────────────────────────

describe("navigate inside a grid group", () => {
  const GROUP_ID = "test-grid";
  let container: HTMLElement;
  let a: HTMLElement, b: HTMLElement, c: HTMLElement, d: HTMLElement;

  beforeEach(() => {
    container = document.createElement("div");
    document.body.append(container);
    mockRect(container, 0, 0, 200, 100);
    container.getClientRects = () => ({ length: 1 }) as DOMRectList;

    // 2-column grid: [a, b] / [c, d]
    a = makeEl(0, 0, 40, 40, container);
    b = makeEl(60, 0, 40, 40, container);
    c = makeEl(0, 60, 40, 40, container);
    d = makeEl(60, 60, 40, 40, container);
    void d;

    for (const el of [a, b, c, d]) registerFocusable(el, GROUP_ID);
    registerGroup(container, {
      id: GROUP_ID,
      policy: { type: "grid", cols: 2 },
    });
  });

  afterEach(() => {
    for (const el of [a, b, c, d]) unregisterFocusable(el);
    unregisterGroup(GROUP_ID);
  });

  it("moves right within the same row", () => {
    a.focus();
    navigate("right");
    expect(document.activeElement).toBe(b);
  });

  it("moves down to the element below in the grid", () => {
    a.focus();
    navigate("down");
    expect(document.activeElement).toBe(c);
  });

  it("does not move right at the last column", () => {
    b.focus();
    navigate("right");
    expect(document.activeElement).toBe(b);
  });

  it("does not move down from the last row", () => {
    c.focus();
    navigate("down");
    expect(document.activeElement).toBe(c);
  });
});

// ── focusAfterKeyRelease ─────────────────────────────────────────────────────

describe("focusAfterKeyRelease", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("focuses after keyup has finished dispatching", () => {
    const target = makeEl(0, 0);
    const getEl = vi.fn(() => target);
    focusAfterKeyRelease(getEl);

    window.dispatchEvent(new KeyboardEvent("keyup", { key: "Enter" }));
    expect(document.activeElement).not.toBe(target);
    vi.advanceTimersByTime(0);

    expect(document.activeElement).toBe(target);
    expect(getEl).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(300);
    expect(getEl).toHaveBeenCalledTimes(1);
  });

  it("uses the fallback timer when no keyup arrives", () => {
    const target = makeEl(0, 0);
    focusAfterKeyRelease(() => target);

    vi.advanceTimersByTime(299);
    expect(document.activeElement).not.toBe(target);
    vi.advanceTimersByTime(1);
    expect(document.activeElement).toBe(target);
  });

  it("cleanup prevents pending focus", () => {
    const target = makeEl(0, 0);
    const getEl = vi.fn(() => target);
    const cleanup = focusAfterKeyRelease(getEl);

    cleanup();
    window.dispatchEvent(new KeyboardEvent("keyup", { key: "Enter" }));
    vi.runAllTimers();

    expect(getEl).not.toHaveBeenCalled();
    expect(document.activeElement).not.toBe(target);
  });
});

// ── Native and free-policy group navigation ──────────────────────────────────

describe("native and free-policy group members", () => {
  it("navigates native buttons without explicit focusable registration", () => {
    const container = document.createElement("div");
    document.body.append(container);
    mockRect(container, 0, 0, 200, 40);
    const first = makeEl(0, 0, 40, 40, container);
    const second = makeEl(80, 0, 40, 40, container);
    registerGroup(container, {
      id: "native-row",
      policy: { type: "row" },
    });

    first.focus();
    navigate("right");
    expect(document.activeElement).toBe(second);

    unregisterGroup("native-row");
  });

  it("uses scoped geometric navigation for a free group", () => {
    const container = document.createElement("div");
    document.body.append(container);
    mockRect(container, 0, 0, 200, 200);
    const first = makeEl(0, 0, 40, 40, container);
    const diagonal = makeEl(100, 80, 40, 40, container);
    registerGroup(container, {
      id: "free",
      policy: { type: "free" },
      trapFocus: true,
    });

    first.focus();
    navigate("right");
    expect(document.activeElement).toBe(diagonal);

    unregisterGroup("free");
  });
});

// ── Cross-group routing and rememberFocus ─────────────────────────────────────

describe("cross-group vertical navigation", () => {
  const registeredElements: HTMLElement[] = [];
  const registeredGroups: string[] = [];

  function group(
    id: string,
    y: number,
    rememberFocus = true,
  ): {
    container: HTMLElement;
    left: HTMLElement;
    right: HTMLElement;
  } {
    const container = document.createElement("div");
    document.body.append(container);
    mockRect(container, 0, y, 240, 40);
    const left = makeEl(0, y, 40, 40, container);
    const right = makeEl(120, y, 40, 40, container);
    for (const element of [left, right]) {
      registerFocusable(element, id);
      registeredElements.push(element);
    }
    registerGroup(container, {
      id,
      policy: { type: "row" },
      rememberFocus,
    });
    registeredGroups.push(id);
    return { container, left, right };
  }

  afterEach(() => {
    for (const element of registeredElements.splice(0)) {
      unregisterFocusable(element);
    }
    for (const id of registeredGroups.splice(0)) unregisterGroup(id);
  });

  it("restores the target row's last focused member", () => {
    const top = group("top", 0);
    const bottom = group("bottom", 100);
    bottom.right.focus();
    top.left.focus();

    navigate("down");

    expect(document.activeElement).toBe(bottom.right);
  });

  it("preserves remembered focus when a group is re-registered", () => {
    const top = group("top-update", 0);
    const bottom = group("bottom-update", 100);
    bottom.right.focus();
    registerGroup(bottom.container, {
      id: "bottom-update",
      policy: { type: "row" },
      trapFocus: false,
    });
    top.left.focus();

    navigate("down");

    expect(document.activeElement).toBe(bottom.right);
  });

  it("chooses the closest horizontal lane when remembering is disabled", () => {
    const top = group("top-lane", 0);
    const bottom = group("bottom-lane", 100, false);
    bottom.right.focus();
    top.left.focus();

    navigate("down");

    expect(document.activeElement).toBe(bottom.left);
  });

  it("moves upward into the closest group", () => {
    const top = group("top-up", 0, false);
    const bottom = group("bottom-up", 100, false);
    bottom.right.focus();

    navigate("up");

    expect(document.activeElement).toBe(top.right);
  });

  it("routes an ungrouped hero control into the first row", () => {
    const hero = makeEl(120, 0);
    const row = group("hero-target", 100, false);
    hero.focus();

    navigate("down");

    expect(document.activeElement).toBe(row.right);
  });

  it("does not restore a remembered carousel item that is clipped off-screen", () => {
    const top = group("clip-top", 0);
    const bottom = group("clip-bottom", 100);
    bottom.container.style.overflowX = "hidden";
    Object.defineProperty(bottom.container, "scrollWidth", {
      configurable: true,
      value: 240,
    });
    Object.defineProperty(bottom.container, "clientWidth", {
      configurable: true,
      value: 100,
    });
    vi.mocked(bottom.container.getBoundingClientRect).mockReturnValue({
      left: 0,
      top: 100,
      right: 100,
      bottom: 140,
      width: 100,
      height: 40,
      x: 0,
      y: 100,
      toJSON: () => ({}),
    } as DOMRect);
    bottom.right.focus();
    top.left.focus();

    navigate("down");

    expect(document.activeElement).toBe(bottom.left);
  });
});

describe("cross-group geometric fallback", () => {
  it("redirects geometric entry to the target group's remembered member", () => {
    const sourceContainer = document.createElement("div");
    const targetContainer = document.createElement("div");
    document.body.append(sourceContainer, targetContainer);
    mockRect(sourceContainer, 0, 0, 40, 40);
    mockRect(targetContainer, 100, 0, 200, 40);
    const source = makeEl(0, 0, 40, 40, sourceContainer);
    const nearest = makeEl(100, 0, 40, 40, targetContainer);
    const remembered = makeEl(180, 0, 40, 40, targetContainer);
    registerFocusable(source, "source-column");
    registerFocusable(nearest, "target-row");
    registerFocusable(remembered, "target-row");
    registerGroup(sourceContainer, {
      id: "source-column",
      policy: { type: "column" },
    });
    registerGroup(targetContainer, {
      id: "target-row",
      policy: { type: "row" },
    });
    remembered.focus();
    source.focus();

    navigate("right");

    expect(document.activeElement).toBe(remembered);

    unregisterFocusable(source);
    unregisterFocusable(nearest);
    unregisterFocusable(remembered);
    unregisterGroup("source-column");
    unregisterGroup("target-row");
  });
});

describe("focus visibility guards", () => {
  it("skips focusables inside aria-hidden and inert ancestors", () => {
    const hidden = document.createElement("div");
    hidden.setAttribute("aria-hidden", "true");
    document.body.append(hidden);
    makeEl(0, 0, 40, 40, hidden);

    const inert = document.createElement("div");
    inert.inert = true;
    document.body.append(inert);
    makeEl(50, 0, 40, 40, inert);

    const visible = makeEl(100, 0);
    focusFirst();

    expect(document.activeElement).toBe(visible);
  });

  it("skips an element rejected by checkVisibility", () => {
    const hidden = makeEl(0, 0);
    hidden.checkVisibility = vi.fn(() => false);
    const visible = makeEl(100, 0);

    focusFirst();

    expect(document.activeElement).toBe(visible);
  });
});
