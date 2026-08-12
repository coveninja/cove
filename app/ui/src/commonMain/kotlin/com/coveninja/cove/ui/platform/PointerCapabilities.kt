package com.coveninja.cove.ui.platform

/**
 * Whether this platform has a pointer that can hover.
 *
 * Shared UI reveals secondary actions on hover, which on a touch screen means they can
 * never be reached at all: a finger is either not touching or already clicking. Anything
 * gated on hover has to consult this and stay visible where hovering is impossible.
 */
expect val hasPointerHover: Boolean
