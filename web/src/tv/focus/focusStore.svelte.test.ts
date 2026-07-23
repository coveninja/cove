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
