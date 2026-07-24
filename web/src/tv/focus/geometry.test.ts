import {
  afterEach,
  beforeAll,
  afterAll,
  describe,
  expect,
  it,
  vi,
} from "vitest";

import { geometricNavigate } from "./geometry";

// jsdom has no layout engine: getBoundingClientRect() always returns zeros and
// offsetParent is always null. We mock both so isVisible() passes.

function makeEl(
  x: number,
  y: number,
  w = 40,
  h = 40,
  parent: HTMLElement = document.body,
): HTMLButtonElement {
  const el = document.createElement("button");
  parent.append(el);
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
  // isVisible: passes when offsetParent !== null OR getClientRects().length > 0
  el.getClientRects = () => ({ length: 1 }) as DOMRectList;
  return el;
}

// jsdom does not implement scrollIntoView — install a no-op so geometry.ts
// can call it without throwing.
const originalScrollIntoView = Element.prototype.scrollIntoView;
beforeAll(() => {
  Element.prototype.scrollIntoView = vi.fn();
});
afterAll(() => {
  Element.prototype.scrollIntoView = originalScrollIntoView;
});

afterEach(() => {
  document.body.innerHTML = "";
  vi.restoreAllMocks();
});

describe("geometricNavigate", () => {
  it("returns false when the scope contains no other candidates", () => {
    const scope = document.createElement("div");
    document.body.append(scope);
    const from = makeEl(0, 0, 40, 40, scope);
    expect(geometricNavigate("right", from, scope)).toBe(false);
  });

  it("returns false when all candidates are in the opposite direction", () => {
    const from = makeEl(200, 0);
    makeEl(0, 0); // centre (20, 20) — left of from's centre (220, 20)
    expect(geometricNavigate("right", from)).toBe(false);
  });

  it("moves focus right to the nearest element on the right", () => {
    const from = makeEl(0, 0); // centre (20, 20)
    const right = makeEl(100, 0); // centre (120, 20)
    makeEl(-120, 0); // centre (-100, 20) — to the left, excluded

    const result = geometricNavigate("right", from);
    expect(result).toBe(true);
    expect(document.activeElement).toBe(right);
  });

  it("moves focus left", () => {
    const from = makeEl(200, 0); // centre (220, 20)
    const left = makeEl(50, 0); // centre (70, 20) — to the left
    makeEl(300, 0); // to the right — excluded

    expect(geometricNavigate("left", from)).toBe(true);
    expect(document.activeElement).toBe(left);
  });

  it("moves focus up", () => {
    const from = makeEl(0, 200); // centre (20, 220)
    const above = makeEl(0, 50); // centre (20, 70)
    makeEl(0, 300); // below — excluded

    expect(geometricNavigate("up", from)).toBe(true);
    expect(document.activeElement).toBe(above);
  });

  it("moves focus down", () => {
    const from = makeEl(0, 0); // centre (20, 20)
    makeEl(0, -120); // above — excluded
    const below = makeEl(0, 100); // centre (20, 120)

    expect(geometricNavigate("down", from)).toBe(true);
    expect(document.activeElement).toBe(below);
  });

  it("picks the lower-score candidate when multiple are in the same direction", () => {
    const from = makeEl(0, 0); // centre (20, 20)
    // near: primary=80, orth=0  → score=80
    const near = makeEl(60, 0);
    // far: primary=200, orth=0  → score=200
    makeEl(180, 0);

    expect(geometricNavigate("right", from)).toBe(true);
    expect(document.activeElement).toBe(near);
  });

  it("prefers an in-cone candidate over a lower-score off-axis one", () => {
    // Score formula: primary + 0.3 * orthogonal. Cone: orthogonal ≤ primary.
    const from = makeEl(0, 0); // centre (20, 20)

    // inCone: centre (120, 20) → primary=100, orth=0, score=100, IN cone
    const inCone = makeEl(100, 0);

    // offAxis: centre (70, 130) → primary=50, orth=110 (110>50: NOT in cone), score=83
    // Lower score but outside the 45° cone — should lose to inCone.
    makeEl(50, 110);

    expect(geometricNavigate("right", from)).toBe(true);
    expect(document.activeElement).toBe(inCone);
  });

  it("falls back to the off-axis candidate when the cone is empty", () => {
    const from = makeEl(0, 0); // centre (20, 20)
    // Only candidate to the right: centre (70, 200) → primary=50, orth=180 (outside cone)
    const fallback = makeEl(50, 180);

    expect(geometricNavigate("right", from)).toBe(true);
    expect(document.activeElement).toBe(fallback);
  });

  it("uses the supplied scope to limit the candidate set", () => {
    const scope = document.createElement("div");
    document.body.append(scope);

    const from = makeEl(0, 0, 40, 40, scope); // in scope
    const inScope = makeEl(100, 0, 40, 40, scope); // in scope, to the right
    makeEl(200, 0); // in document.body but outside scope — excluded

    expect(geometricNavigate("right", from, scope)).toBe(true);
    expect(document.activeElement).toBe(inScope);
  });

  it("deduplicates elements that match both [data-tv-focusable] and a native selector", () => {
    const from = makeEl(0, 0);
    // A button with data-tv-focusable matches both selectors; must not count twice.
    const target = makeEl(100, 0);
    target.setAttribute("data-tv-focusable", "");

    expect(geometricNavigate("right", from)).toBe(true);
    expect(document.activeElement).toBe(target);
  });

  it("skips hidden, zero-sized, inert, and disabled candidates", () => {
    const from = makeEl(0, 0);

    const detachedLayout = makeEl(40, 0);
    detachedLayout.getClientRects = () => ({ length: 0 }) as DOMRectList;

    makeEl(60, 0, 0, 40);
    makeEl(80, 0, 40, 0);

    const visibilityHidden = makeEl(100, 0);
    visibilityHidden.checkVisibility = vi.fn(() => false);

    const ariaParent = document.createElement("div");
    ariaParent.setAttribute("aria-hidden", "true");
    document.body.append(ariaParent);
    makeEl(120, 0, 40, 40, ariaParent);

    const inertParent = document.createElement("div");
    inertParent.inert = true;
    document.body.append(inertParent);
    makeEl(140, 0, 40, 40, inertParent);

    const disabled = makeEl(160, 0);
    disabled.disabled = true;

    const visible = makeEl(200, 0);

    expect(geometricNavigate("right", from)).toBe(true);
    expect(document.activeElement).toBe(visible);
  });

  it("scrolls an enclosing TV anchor instead of only the focused control", () => {
    const from = makeEl(0, 0);
    const anchor = document.createElement("div");
    anchor.setAttribute("data-tv-scroll-anchor", "");
    document.body.append(anchor);
    const target = makeEl(100, 0, 40, 40, anchor);
    const anchorScroll = vi.fn();
    const targetScroll = vi.fn();
    anchor.scrollIntoView = anchorScroll;
    target.scrollIntoView = targetScroll;

    expect(geometricNavigate("right", from)).toBe(true);

    expect(anchorScroll).toHaveBeenCalledOnce();
    expect(targetScroll).not.toHaveBeenCalled();
  });
});
