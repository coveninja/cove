package com.coveninja.cove

import android.content.pm.ActivityInfo

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
