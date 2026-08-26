package com.coveninja.cove.ui.platform

import androidx.compose.runtime.Composable

/**
 * Nothing to adjust.
 *
 * A desktop's brightness belongs to the monitor and the OS, not to one window, and the gesture
 * that drives this is a swipe over the picture that a desktop never receives. Reported as
 * unsupported rather than silently doing nothing, so the caller can leave the gesture out
 * instead of offering one that appears broken.
 */
@Composable
actual fun rememberScreenBrightness(): ScreenBrightnessController = DesktopScreenBrightness

private object DesktopScreenBrightness : ScreenBrightnessController {
    override val supported: Boolean = false
    override val level: Float? = null
    override fun adjust(delta: Float) = Unit
    override fun release() = Unit
}
