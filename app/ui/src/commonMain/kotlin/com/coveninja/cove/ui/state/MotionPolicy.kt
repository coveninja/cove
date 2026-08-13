package com.coveninja.cove.ui.state

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * One rendering decision shared by every page.
 *
 * Reduced motion is intentionally narrower than a visual "lite" theme: loading feedback and
 * direct press feedback remain, while motion that is decorative or delays navigation is removed.
 */
@Immutable
data class MotionPolicy(val reducedMotion: Boolean = false)

val LocalMotionPolicy = staticCompositionLocalOf { MotionPolicy() }
