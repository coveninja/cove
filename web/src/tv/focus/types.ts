/** Cardinal direction for D-pad / arrow-key navigation. */
export type Direction = "left" | "right" | "up" | "down";

/**
 * Structural layout policy for a focus group.
 *
 * Determines how arrow-key moves are interpreted inside the group:
 * - `row`    — Left/Right navigate; Up/Down fall through to cross-group nav.
 * - `column` — Up/Down navigate; Left/Right fall through.
 * - `grid`   — All four axes navigate within the 2-D grid of `cols` columns.
 * - `free`   — Geometric nearest-neighbour scoped to the group element.
 */
export type FocusGroupPolicy =
  | { type: "row" }
  | { type: "column" }
  | { type: "grid"; cols: number }
  | { type: "free" };

/** Options for registering a focus group via `use:focusGroup`. */
export interface FocusGroupOptions {
  /** Unique identifier for this group; used for cross-group rememberFocus routing. */
  id: string;
  /** Navigation policy inside this group. */
  policy: FocusGroupPolicy;
  /**
   * When true (default), the group remembers the last-focused element and
   * restores focus to it when the group is re-entered from outside.
   */
  rememberFocus?: boolean;
  /**
   * When true, focus is trapped inside this group — unhandled moves at group
   * edges do nothing instead of falling through to geometric cross-group nav.
   */
  trapFocus?: boolean;
}

/** Options for registering a focusable element via `use:focusable`. */
export interface FocusableOptions {
  /** Optional ID of the focus group this element belongs to. */
  groupId?: string;
}
