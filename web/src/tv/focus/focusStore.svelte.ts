// Spatial focus / D-pad navigation engine — module-level singleton.
//
// All state lives at module scope (not inside a Svelte component) so any
// number of components can call `navigate()` without co-ordination, matching
// the playback.svelte.ts singleton pattern used elsewhere in the codebase.

import { geometricNavigate } from "./geometry";
import type { Direction, FocusGroupOptions } from "./types";

// ── Internal types ────────────────────────────────────────────────────────────

interface GroupEntry {
  el: HTMLElement;
  opts: FocusGroupOptions;
  /** WeakRef to the most recently focused member; null until the group is used. */
  last: WeakRef<HTMLElement> | null;
}

// ── Module-level registries ──────────────────────────────────────────────────

/**
 * Maps every registered focusable element to its group ID (or `undefined`
 * when the element is ungrouped but still annotated via `use:focusable`).
 */
// eslint-disable-next-line svelte/prefer-svelte-reactivity -- imperative registry, not reactive state
const focusables = new Map<HTMLElement, string | undefined>();

/**
 * Maps group IDs to their container element, navigation options, and a
 * `WeakRef` to the most recently focused member (for `rememberFocus` routing).
 */
// eslint-disable-next-line svelte/prefer-svelte-reactivity -- imperative registry, not reactive state
const groups = new Map<string, GroupEntry>();

// ── Selectors ─────────────────────────────────────────────────────────────────

const NATIVE_SELECTOR = [
  "button:not([disabled])",
  "a[href]",
  "input:not([disabled])",
  "select:not([disabled])",
  "textarea:not([disabled])",
  "[tabindex]:not([tabindex='-1'])",
].join(", ");

const ALL_SELECTOR = `[data-tv-focusable], ${NATIVE_SELECTOR}`;

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Input types that should receive text via keyboard rather than navigating. */
const EDITABLE_TYPES = new Set([
  "text",
  "search",
  "url",
  "email",
  "password",
  "number",
  "",
]);

/**
 * Returns true when `el` currently holds editable text — i.e. arrow keys
 * should move the cursor, not the spatial focus.
 */
function isEditable(el: Element): boolean {
  if (el instanceof HTMLTextAreaElement) return true;
  if (el instanceof HTMLInputElement && EDITABLE_TYPES.has(el.type.toLowerCase()))
    return true;
  if ((el as HTMLElement).isContentEditable) return true;
  return false;
}

/**
 * Returns true when an arrow press in `dir` belongs to the focused editable
 * element rather than spatial navigation:
 *  - left/right: always (caret movement);
 *  - textarea / contenteditable: all four directions (multi-line caret);
 *  - number inputs: up/down step the value;
 *  - single-line text inputs: up/down have no caret meaning → returns false,
 *    so the D-pad can leave the field without reaching for Back/Escape.
 */
export function editableKeepsArrow(el: Element, dir: Direction): boolean {
  if (!isEditable(el)) return false;
  if (dir === "left" || dir === "right") return true;
  if (el instanceof HTMLInputElement && el.type.toLowerCase() !== "number")
    return false;
  return true;
}

/** Returns true when `el` is rendered and reachable via programmatic focus. */
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
 * Returns true when `el`'s horizontal centre lies within the visible span of
 * every `overflow-x: hidden/clip` scroll container in its ancestor chain.
 *
 * Carousel rows use `overflow-x: hidden` and advance via `scrollIntoView`; a
 * card scrolled out of the row's visible area still passes `isVisible()` (it
 * has a client rect and no `aria-hidden`) but its centre-X falls outside the
 * row's bounding rect.  This helper catches that case so `rememberFocus`
 * restore does not snap focus back to a card the user has already scrolled
 * past.
 *
 * The `scrollWidth > clientWidth` guard avoids treating ordinary layout
 * containers (e.g. a wrapper `div` that uses `overflow-x: hidden` purely to
 * contain floats) as clippers — only ancestors that actually have scrollable
 * horizontal overflow are considered.
 *
 * Only the horizontal axis is checked: vertical scrolling is handled by
 * `focusEl`'s `scrollIntoView` call, so vertical position never needs gating.
 */
function isWithinScrollClip(el: HTMLElement): boolean {
  const rect = el.getBoundingClientRect();
  const cx = rect.left + rect.width / 2;
  let ancestor = el.parentElement;
  while (ancestor && ancestor !== document.documentElement) {
    const style = getComputedStyle(ancestor);
    const ox = style.overflowX;
    if (
      (ox === "hidden" || ox === "clip") &&
      ancestor.scrollWidth > ancestor.clientWidth
    ) {
      const ar = ancestor.getBoundingClientRect();
      if (cx < ar.left || cx > ar.right) return false;
    }
    ancestor = ancestor.parentElement;
  }
  return true;
}

/** Sorts two elements in DOM (document) order. */
function domOrder(a: HTMLElement, b: HTMLElement): number {
  const pos = a.compareDocumentPosition(b);
  if (pos & Node.DOCUMENT_POSITION_FOLLOWING) return -1;
  if (pos & Node.DOCUMENT_POSITION_PRECEDING) return 1;
  return 0;
}

/**
 * Focuses `el` and scrolls it into view smoothly.
 *
 * If the element sits inside a `[data-tv-scroll-anchor]` container, the whole
 * container is scrolled into view instead — e.g. the hero's action buttons
 * live at its bottom edge, and scrolling only the button into view would
 * leave the backdrop above it cut off.
 */
function focusEl(el: HTMLElement): void {
  el.focus({ preventScroll: true });
  const anchor = el.closest<HTMLElement>("[data-tv-scroll-anchor]") ?? el;
  anchor.scrollIntoView({ block: "nearest", inline: "nearest", behavior: "smooth" });
}

// ── Group member resolution ───────────────────────────────────────────────────

/**
 * All navigable members of `entry`, sorted in document order.
 *
 * Members = registered focusables with matching groupId  ∪  native focusables
 * inside the group container that are not registered to a *different* group.
 */
function groupMembers(entry: GroupEntry): HTMLElement[] {
  const { el: container, opts } = entry;

  const registered: HTMLElement[] = [];
  for (const [fEl, gid] of focusables) {
    if (gid === opts.id) registered.push(fEl);
  }

  const native = Array.from(
    container.querySelectorAll<HTMLElement>(NATIVE_SELECTOR),
  ).filter((el) => {
    if (!isVisible(el)) return false;
    if ((el as HTMLInputElement).disabled) return false;
    // Skip if registered to a different group.
    if (focusables.has(el)) return focusables.get(el) === opts.id;
    return true;
  });

  const all = [...new Set([...registered, ...native])];
  return all.filter(isVisible).sort(domOrder);
}

// ── Policy navigation ─────────────────────────────────────────────────────────

type PolicyResult = "handled" | "unhandled";

function policyNavigate(
  dir: Direction,
  active: HTMLElement,
  entry: GroupEntry,
): PolicyResult {
  const members = groupMembers(entry);
  if (members.length === 0) return "unhandled";

  const idx = members.indexOf(active);
  const { policy } = entry.opts;

  if (policy.type === "row") {
    if (dir === "left") {
      if (idx <= 0) return "unhandled";
      focusEl(members[idx - 1]);
      return "handled";
    }
    if (dir === "right") {
      if (idx < 0 || idx >= members.length - 1) return "unhandled";
      focusEl(members[idx + 1]);
      return "handled";
    }
    // up / down fall through to cross-group nav.
    return "unhandled";
  }

  if (policy.type === "column") {
    if (dir === "up") {
      if (idx <= 0) return "unhandled";
      focusEl(members[idx - 1]);
      return "handled";
    }
    if (dir === "down") {
      if (idx < 0 || idx >= members.length - 1) return "unhandled";
      focusEl(members[idx + 1]);
      return "handled";
    }
    // left / right fall through.
    return "unhandled";
  }

  if (policy.type === "grid") {
    const { cols } = policy;
    if (idx < 0) return "unhandled";

    const col = idx % cols;
    const row = Math.floor(idx / cols);
    const totalRows = Math.ceil(members.length / cols);

    let targetIdx = idx;
    if (dir === "left") {
      if (col <= 0) return "unhandled";
      targetIdx = idx - 1;
    } else if (dir === "right") {
      if (col >= cols - 1 || idx >= members.length - 1) return "unhandled";
      targetIdx = idx + 1;
    } else if (dir === "up") {
      if (row <= 0) return "unhandled";
      targetIdx = idx - cols;
    } else {
      // down
      if (row >= totalRows - 1 || idx + cols >= members.length) return "unhandled";
      targetIdx = idx + cols;
    }

    if (targetIdx < 0 || targetIdx >= members.length) return "unhandled";
    focusEl(members[targetIdx]);
    return "handled";
  }

  if (policy.type === "free") {
    return geometricNavigate(dir, active, entry.el) ? "handled" : "unhandled";
  }

  return "unhandled";
}

// ── Group-to-group navigation ─────────────────────────────────────────────────

/** Tolerance (px): allow groups whose facing edges overlap by this much. */
const GROUP_EDGE_TOLERANCE = 10;

/**
 * Attempts to navigate from a source element into the nearest group in `dir`.
 *
 * Candidates must be visible, have ≥1 visible member, and their container
 * must lie strictly beyond `sourceRect` in `dir` (with a small overlap
 * tolerance so pixel-perfect alignment is not required).
 *
 * Selection order:
 *  1. Nearest by facing-edge distance (candidate.top − source.bottom for "down").
 *  2. Tie-break: most horizontal overlap with the source rect.
 *
 * Entry:
 *  - If `rememberFocus` (default true) and the group's `last` deref is
 *    alive, visible, AND within its scroll container's visible span
 *    (`isWithinScrollClip`) → restore that element.  The scroll-clip guard
 *    prevents restoring a carousel card that has already scrolled off-screen.
 *  - Otherwise → focus the member whose centre-X is closest to `sourceEl`'s
 *    centre-X, preserving the user's horizontal "lane".  `sourceEl` (the
 *    focused card) is used rather than `sourceRect` (the full group container)
 *    so the lane aligns with the card's actual position, not the row midpoint.
 *
 * @returns true if focus moved; false if no qualifying group was found.
 */
function navigateBetweenGroups(
  dir: "up" | "down",
  sourceRect: DOMRect,
  sourceEl: HTMLElement,
  excludeGroupId?: string,
): boolean {
  interface Candidate {
    entry: GroupEntry;
    members: HTMLElement[];
    edgeDist: number;
    horizOverlap: number;
  }

  const candidates: Candidate[] = [];

  for (const [id, entry] of groups) {
    if (id === excludeGroupId) continue;
    if (!isVisible(entry.el)) continue;

    const members = groupMembers(entry).filter(isVisible);
    if (members.length === 0) continue;

    const rect = entry.el.getBoundingClientRect();

    let edgeDist: number;
    if (dir === "down") {
      edgeDist = rect.top - sourceRect.bottom;
      if (edgeDist < -GROUP_EDGE_TOLERANCE) continue;
    } else {
      // "up": candidate must be above the source
      edgeDist = sourceRect.top - rect.bottom;
      if (edgeDist < -GROUP_EDGE_TOLERANCE) continue;
    }

    const overlapLeft = Math.max(sourceRect.left, rect.left);
    const overlapRight = Math.min(sourceRect.right, rect.right);
    const horizOverlap = Math.max(0, overlapRight - overlapLeft);

    candidates.push({ entry, members, edgeDist, horizOverlap });
  }

  if (candidates.length === 0) return false;

  // Primary sort: smallest edge distance. Tie-break (within 5 px): most overlap.
  candidates.sort((a, b) => {
    const distDiff = a.edgeDist - b.edgeDist;
    if (Math.abs(distDiff) > 5) return distDiff;
    return b.horizOverlap - a.horizOverlap;
  });

  const { entry, members } = candidates[0];

  // Restore last-focused member if rememberFocus and still alive+visible —
  // but not when it has scrolled out of its row's visible span, which would
  // snap the user sideways to a stale position.
  if (entry.opts.rememberFocus !== false) {
    const lastEl = entry.last?.deref();
    if (lastEl && isVisible(lastEl) && isWithinScrollClip(lastEl)) {
      focusEl(lastEl);
      return true;
    }
  }

  // Otherwise pick member whose centre-X is closest to the focused element's
  // lane (sourceRect is the whole group container, so its centre would always
  // be the row midpoint regardless of which card is focused).
  const srcRect = sourceEl.getBoundingClientRect();
  const srcCx = srcRect.left + srcRect.width / 2;
  let best: HTMLElement | null = null;
  let bestDist = Infinity;
  for (const member of members) {
    const mr = member.getBoundingClientRect();
    const mx = mr.left + mr.width / 2;
    const dist = Math.abs(mx - srcCx);
    if (dist < bestDist) {
      bestDist = dist;
      best = member;
    }
  }

  if (best) {
    focusEl(best);
    return true;
  }

  return false;
}

// ── focusin listener (installed once on the document) ─────────────────────────

let listenerInstalled = false;

function ensureListener(): void {
  if (listenerInstalled || typeof document === "undefined") return;
  listenerInstalled = true;

  document.addEventListener(
    "focusin",
    (e) => {
      const target = e.target as HTMLElement | null;
      if (!target) return;

      // Determine which group the newly-focused element belongs to.
      let gid: string | undefined;
      if (focusables.has(target)) {
        gid = focusables.get(target);
      } else {
        for (const [id, entry] of groups) {
          if (entry.el.contains(target)) {
            gid = id;
            break;
          }
        }
      }

      if (gid) {
        const entry = groups.get(gid);
        if (entry) entry.last = new WeakRef(target);
      }
    },
    { capture: true },
  );
}

// ── Group resolution ──────────────────────────────────────────────────────────

/**
 * Finds which registered group `el` belongs to, either by its registered
 * `groupId` or by containment inside a group's container element.
 */
function resolveGroup(
  el: HTMLElement,
): { id: string; entry: GroupEntry } | null {
  // 1. Registered focusable with an explicit groupId.
  if (focusables.has(el)) {
    const gid = focusables.get(el);
    if (gid) {
      const entry = groups.get(gid);
      if (entry) return { id: gid, entry };
    }
    return null;
  }
  // 2. Inside a registered group container (catches native focusables).
  for (const [id, entry] of groups) {
    if (entry.el !== el && entry.el.contains(el)) {
      return { id, entry };
    }
  }
  return null;
}

// ── Public API ────────────────────────────────────────────────────────────────

/**
 * Register an element as a D-pad-focusable member.
 *
 * Called by `use:focusable` on mount; paired with `unregisterFocusable` on
 * destroy.
 */
export function registerFocusable(el: HTMLElement, groupId?: string): void {
  ensureListener();
  focusables.set(el, groupId);
}

/** Unregister a previously registered focusable element. */
export function unregisterFocusable(el: HTMLElement): void {
  focusables.delete(el);
}

/**
 * Register a focus-group container.
 *
 * Existing `last` WeakRef is preserved across re-registrations (e.g. when
 * `use:focusGroup` receives updated options).
 */
export function registerGroup(el: HTMLElement, opts: FocusGroupOptions): void {
  ensureListener();
  const existing = groups.get(opts.id);
  groups.set(opts.id, { el, opts, last: existing?.last ?? null });
}

/** Unregister a focus group. */
export function unregisterGroup(id: string): void {
  groups.delete(id);
}

/**
 * Focuses the element produced by `getEl` only after the currently-pressed key
 * is released (first keyup, or a 300 ms fallback for pointer-initiated opens).
 *
 * Use this whenever Enter/DPAD_CENTER opens a surface that auto-focuses an
 * activatable control (detail overlay → Play, player → play/pause): Android
 * WebView synthesizes the activation across the key's down/up sequence, so
 * focusing the new control mid-press lets the same press "click" it — Enter
 * on a card would open the overlay AND immediately start playback.
 *
 * Returns a cleanup function; call it if the surface unmounts before focus.
 */
export function focusAfterKeyRelease(getEl: () => HTMLElement | null): () => void {
  let done = false;

  const run = () => {
    if (done) return;
    done = true;
    clearTimeout(timer);
    window.removeEventListener("keyup", onKeyUp, true);
    const el = getEl();
    if (el) focusEl(el);
  };
  // Defer past the keyup's own dispatch so late activation lands on the old target.
  const onKeyUp = () => setTimeout(run, 0);

  window.addEventListener("keyup", onKeyUp, { once: true, capture: true });
  const timer = setTimeout(run, 300);

  return () => {
    done = true;
    clearTimeout(timer);
    window.removeEventListener("keyup", onKeyUp, true);
  };
}

/**
 * Focus the first visible, reachable element (registered or native) in
 * document order within `scope` (default: document root).
 */
export function focusFirst(scope?: HTMLElement): void {
  const root = scope ?? document.documentElement;
  const all = root.querySelectorAll<HTMLElement>(ALL_SELECTOR);
  for (const el of all) {
    if (isVisible(el) && !(el as HTMLInputElement).disabled) {
      el.focus({ preventScroll: true });
      return;
    }
  }
}

/**
 * D-pad / arrow-key navigation entry point.
 *
 * Call from the TV shell's `onkeydown` handler for ArrowLeft/Right/Up/Down
 * *after* confirming the active element is not an editable input (the shell
 * owns that guard so it can also suppress `preventDefault`).
 *
 * Algorithm:
 *  1. No active element → `focusFirst()`.
 *  2. Active element is editable → no-op (shell should Escape to blur it).
 *  3. Resolve active element's group (by registration or containment).
 *  4. Run group-policy navigation; if handled → done.
 *  5. If `trapFocus` → do nothing on unhandled.
 *  6. Otherwise cross-group `geometricNavigate`.
 *  7. On entry into a new group with `rememberFocus`, redirect to last element.
 */
export function navigate(dir: Direction): void {
  const active = document.activeElement as HTMLElement | null;

  if (!active || active === document.body) {
    focusFirst();
    return;
  }

  // Editable elements keep the arrows that carry meaning for them (caret
  // movement, number stepping); vertical arrows on single-line text inputs
  // fall through so the D-pad can leave the field.
  if (editableKeepsArrow(active, dir)) return;

  const sourceGroup = resolveGroup(active);

  if (sourceGroup) {
    const result = policyNavigate(dir, active, sourceGroup.entry);
    if (result === "handled") return;

    // Unhandled at group edge.
    if (sourceGroup.entry.opts.trapFocus) return;

    // For row / grid groups: prefer group-to-group nav on Up/Down so the
    // engine jumps to the nearest row *below* or *above* rather than picking
    // the geometrically nearest individual element (which is often a filter
    // chip or see-all link half a screen away).
    if (
      (dir === "up" || dir === "down") &&
      (sourceGroup.entry.opts.policy.type === "row" ||
        sourceGroup.entry.opts.policy.type === "grid")
    ) {
      const containerRect = sourceGroup.entry.el.getBoundingClientRect();
      if (navigateBetweenGroups(dir, containerRect, active, sourceGroup.id)) return;
    }
  } else if (dir === "up" || dir === "down") {
    // Ungrouped active element (e.g. hero button area before any group is
    // entered): prefer group-to-group nav over raw element-level geometric so
    // the first Down press from the hero lands cleanly in the first media row.
    const activeRect = active.getBoundingClientRect();
    if (navigateBetweenGroups(dir, activeRect, active, undefined)) return;
  }

  // Cross-group geometric navigation (fallback for column-edge left/right and
  // any case where navigateBetweenGroups found no qualifying group).
  // Snapshot each group's last-focused element BEFORE focus changes (the
  // focusin listener fires synchronously during focus(), which would otherwise
  // overwrite the snapshot we need for rememberFocus redirect).
  // eslint-disable-next-line svelte/prefer-svelte-reactivity -- transient snapshot, not reactive state
  const lastSnapshot = new Map<string, HTMLElement | undefined>();
  for (const [id, entry] of groups) {
    lastSnapshot.set(id, entry.last?.deref());
  }

  const moved = geometricNavigate(dir, active);
  if (!moved) return;

  const newActive = document.activeElement as HTMLElement | null;
  if (!newActive || newActive === active) return;

  const targetGroup = resolveGroup(newActive);
  if (
    targetGroup &&
    targetGroup.id !== sourceGroup?.id &&
    (targetGroup.entry.opts.rememberFocus ?? true)
  ) {
    const prevLast = lastSnapshot.get(targetGroup.id);
    if (prevLast && prevLast !== newActive && isVisible(prevLast)) {
      focusEl(prevLast);
    }
  }
}
