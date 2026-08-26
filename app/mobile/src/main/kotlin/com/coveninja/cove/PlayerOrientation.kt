package com.coveninja.cove

import android.content.pm.ActivityInfo
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The narrowest smallest-width, in dp, that still counts as a tablet rather than a handset.
 * Matches the platform's own `sw600dp` resource qualifier.
 */
private const val TABLET_WIDTH_DP = 600

/**
 * Which orientation the activity asks for, given what is on screen.
 *
 * Cove is one activity, so without this the player simply inherits whatever orientation the
 * home screen was in — portrait for anyone who launched upright, and stuck there for anyone
 * with auto-rotate locked, which is the shipping default on most phones.
 *
 * [ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE] rather than either neighbour it could be
 * confused with: plain `LANDSCAPE` pins one rotation, so a viewer who turns the phone the other
 * way watches upside-down, and `USER_LANDSCAPE` defers to the rotation lock — which is the very
 * setting that produced the complaint. The sensor variant beats the lock and still allows both
 * directions.
 *
 * The release value is `UNSPECIFIED` rather than `PORTRAIT`: it hands the decision back to the
 * system, so someone who is physically holding the phone sideways keeps landscape after closing
 * the player instead of being snapped upright.
 *
 * Two exclusions. A television is already landscape and cannot be turned, and a tablet in
 * portrait has the width to render the player perfectly well — rotating a 10" screen out from
 * under someone is a surprise, not a fix. The picture-in-picture term matters because a fixed
 * orientation means nothing to a floating window; dropping it there leaves the PiP window
 * governed by its 16:9 aspect alone, and it re-applies on the way back to fullscreen.
 *
 * Pure so the policy can be checked without a device — see PlayerOrientationTest.
 */
internal fun playerOrientation(
    fullscreenPlayback: Boolean,
    inPictureInPicture: Boolean,
    isTelevision: Boolean,
    smallestScreenWidthDp: Int,
): Int = if (
    fullscreenPlayback &&
    !inPictureInPicture &&
    !isTelevision &&
    smallestScreenWidthDp < TABLET_WIDTH_DP
) {
    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
} else {
    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}

/**
 * The narrowest and widest shapes Android will accept for a floating window.
 *
 * Not advisory: `PictureInPictureParams.Builder.setAspectRatio` throws
 * IllegalArgumentException outside this range, so an unusually wide film — anything past
 * 2.39:1, which arthouse and IMAX releases genuinely are — would crash the transition into
 * picture-in-picture rather than being letterboxed.
 */
private const val MIN_PIP_ASPECT = 1 / 2.39
private const val MAX_PIP_ASPECT = 2.39

/**
 * The shape of the floating window for a video of [width] × [height].
 *
 * Taken from the picture rather than assumed, which it had been: a hardcoded 16:9 pillarboxes
 * a scope film and letterboxes an old television episode inside a window that is already the
 * size of a postage stamp. Before the first frame there is nothing to measure and the
 * assumption is all there is, which is what the default is for.
 *
 * Returned as a pair rather than a Rational so the arithmetic can be checked without a device;
 * the caller builds the Rational.
 */
internal fun pictureInPictureAspect(width: Int, height: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 16 to 9
    val ratio = width.toDouble() / height.toDouble()
    val clamped = ratio.coerceIn(MIN_PIP_ASPECT, MAX_PIP_ASPECT)
    if (clamped == ratio) return width to height
    // Clamping changed the shape, so the original dimensions no longer describe it. Scaled
    // against a fixed denominator because Rational wants integers and the exact numbers stop
    // mattering the moment the ratio has been altered anyway.
    //
    // Rounded *away* from the bound that was violated, not to nearest: truncating a ratio
    // clamped up to the minimum lands fractionally back underneath it — 1/2.39 scaled by a
    // thousand truncates to 418, and 418/1000 is below the legal floor again — which throws
    // on the device, which is the whole thing the clamp exists to prevent.
    val numerator = if (clamped <= MIN_PIP_ASPECT) {
        ceil(clamped * ASPECT_PRECISION).toInt()
    } else {
        floor(clamped * ASPECT_PRECISION).toInt()
    }
    return numerator to ASPECT_PRECISION
}

/** Denominator for a clamped ratio. Large enough that the rounding is invisible. */
private const val ASPECT_PRECISION = 1_000
