package com.coveninja.cove.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable

/**
 * The screen brightness this window asks for while playback is on screen.
 *
 * A window override rather than the system setting: it lasts as long as the player does and
 * needs no permission, which is what makes it usable at all — writing the device brightness
 * would outlive the film and require `WRITE_SETTINGS`.
 *
 * [supported] is false wherever there is nothing to adjust. The gesture that drives this is a
 * vertical swipe over the picture, so on a desktop the whole idea is inapplicable rather than
 * merely unimplemented; the caller must not offer it there.
 */
@Stable
interface ScreenBrightnessController {
    val supported: Boolean

    /** 0..1, or null while the window is still following the system value. */
    val level: Float?

    /** Moves by [delta] of the full range, clamping. Ignored where [supported] is false. */
    fun adjust(delta: Float)

    /** Hands brightness back to the system. Called when playback ends. */
    fun release()
}

/** A controller bound to the current window, or an inert one where there is no such thing. */
@Composable
expect fun rememberScreenBrightness(): ScreenBrightnessController
