package com.coveninja.cove.ui.platform

/**
 * False even though Android tablets and Chromebooks can have a mouse attached.
 *
 * This decides whether an action is *reachable*, so it has to answer for the worst case:
 * treating a touch-only phone as hover-capable hides those actions permanently, while
 * treating a mouse-equipped tablet as touch-only merely shows them all the time.
 */
actual val hasPointerHover: Boolean = false

/** True only where a keyboard is part of the device, which on Android it never is. */
actual val hasHardwareKeyboard: Boolean = false
