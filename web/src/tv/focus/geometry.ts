import type { Direction } from "./types";

/**
 * CSS selector that matches all natively-focusable elements.
 *
 * Included in candidate queries so that reused desktop pages (SettingsPage,
 * MyAccountPage, etc.) are D-pad-navigable without any `use:focusable`
 * annotations on their elements — zero edits to `web/src/components/`.
 */
const NATIVE_SELECTOR = [
  "button:not([disabled])",
  "a[href]",
  "input:not([disabled])",
  "select:not([disabled])",
  "textarea:not([disabled])",
  "[tabindex]:not([tabindex='-1'])",
].join(", ");

/** Combined selector: both annotated and native focusables. */
const ALL_SELECTOR = `[data-tv-focusable], ${NATIVE_SELECTOR}`;

/** Returns the viewport-relative centre of an element's bounding rect. */
function centre(el: HTMLElement): { x: number; y: number } {
  const r = el.getBoundingClientRect();
  return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
}

/**
 * Returns true when `el` is visible to the user and reachable via programmatic
 * focus.  Checks:
 *   - `offsetParent !== null` OR non-empty `getClientRects()` (handles fixed pos).
 *   - Non-zero bounding dimensions.
 *   - No `aria-hidden="true"` ancestor.
 *   - No `inert` ancestor.
 */
function isVisible(el: HTMLElement): boolean {
  if (el.offsetParent === null && el.getClientRects().length === 0) return false;
  const rect = el.getBoundingClientRect();
  if (rect.width === 0 || rect.height === 0) return false;
  // `visibility: hidden` elements (e.g. pages behind the open player, hidden
  // via the `invisible` class) still have offsetParent and client rects —
  // checkVisibility() is the only cheap way to exclude them.
  if (typeof el.checkVisibility === "function" && !el.checkVisibility()) return false;
  let node: Element | null = el;
  while (node) {
    if (node.getAttribute("aria-hidden") === "true") return false;
    if ((node as HTMLElement).inert) return false;
    node = node.parentElement;
  }
  return true;
}

/**
 * Moves focus in `dir` from `from`, constrained to `scope` (default: document
 * root).
 *
 * **Candidate set**: all elements inside `scope` matching `[data-tv-focusable]`
 * or native focusables, deduplicated, visible, not disabled, not `from` itself.
 *
 * **Direction cone**: a candidate's centre must be strictly beyond `from`'s
 * centre on the primary axis.  Candidates whose orthogonal offset is ≤ the
 * primary distance fall within the preferred 45° cone.  When the cone is
 * empty the best beyond-only candidate wins (prevents dead-ends on ragged
 * layouts).
 *
 * **Score**: `primaryDistance + 0.3 × orthogonalOffset`; lowest wins.
 *
 * On move: `el.focus({ preventScroll: true })` then
 * `el.scrollIntoView({ block: "nearest", inline: "nearest", behavior: "smooth" })`.
 *
 * @returns `true` if focus moved; `false` if no suitable candidate was found.
 */
export function geometricNavigate(
  dir: Direction,
  from: HTMLElement,
  scope?: HTMLElement,
): boolean {
  const root = scope ?? document.documentElement;
  const raw = root.querySelectorAll<HTMLElement>(ALL_SELECTOR);

  // Deduplicate (an element can match both [data-tv-focusable] and a native
  // selector), then filter.
  const seen = new Set<HTMLElement>();
  const candidates: HTMLElement[] = [];
  for (const el of raw) {
    if (seen.has(el)) continue;
    seen.add(el);
    if (el === from) continue;
    if (!isVisible(el)) continue;
    if ((el as HTMLInputElement).disabled) continue;
    candidates.push(el);
  }

  if (candidates.length === 0) return false;

  const fc = centre(from);

  // Map direction to primary axis and sign.
  let primaryKey: "x" | "y";
  let sign: 1 | -1;
  if (dir === "left") {
    primaryKey = "x";
    sign = -1;
  } else if (dir === "right") {
    primaryKey = "x";
    sign = 1;
  } else if (dir === "up") {
    primaryKey = "y";
    sign = -1;
  } else {
    primaryKey = "y";
    sign = 1;
  }
  const orthKey = primaryKey === "x" ? "y" : "x";

  let coneBest: HTMLElement | null = null;
  let coneBestScore = Infinity;
  let fallbackBest: HTMLElement | null = null;
  let fallbackBestScore = Infinity;

  for (const el of candidates) {
    const c = centre(el);
    const primary = sign * (c[primaryKey] - fc[primaryKey]);
    if (primary <= 0) continue; // not in the requested direction

    const orthogonal = Math.abs(c[orthKey] - fc[orthKey]);
    const score = primary + 0.3 * orthogonal;

    // 45° cone: orthogonal offset ≤ primary distance.
    if (orthogonal <= primary && score < coneBestScore) {
      coneBestScore = score;
      coneBest = el;
    }
    // Unconditional fallback (best beyond-only).
    if (score < fallbackBestScore) {
      fallbackBestScore = score;
      fallbackBest = el;
    }
  }

  const target = coneBest ?? fallbackBest;
  if (!target) return false;

  target.focus({ preventScroll: true });
  // Scroll the [data-tv-scroll-anchor] container instead of the bare element
  // when one wraps it (see focusEl in focusStore.svelte.ts for rationale).
  const anchor = target.closest<HTMLElement>("[data-tv-scroll-anchor]") ?? target;
  anchor.scrollIntoView({ block: "nearest", inline: "nearest", behavior: "smooth" });
  return true;
}
