package com.coveninja.cove.ui.platform

/**
 * Whether this platform has a pointer that can hover.
 *
 * Shared UI reveals secondary actions on hover, which on a touch screen means they can
 * never be reached at all: a finger is either not touching or already clicking. Anything
 * gated on hover has to consult this and stay visible where hovering is impossible.
 */
expect val hasPointerHover: Boolean

/**
 * Whether this platform has a keyboard to press the shortcuts with.
 *
 * The opposite worst case to [hasPointerHover]: a shortcut sheet is advice about keys
 * that a touch-only device does not have, so advertising it there spends a slot on the
 * control bar to tell a phone about a keyboard it will never grow. Anything that only
 * describes or invites keyboard input has to consult this and stay out of the way.
 */
expect val hasHardwareKeyboard: Boolean
