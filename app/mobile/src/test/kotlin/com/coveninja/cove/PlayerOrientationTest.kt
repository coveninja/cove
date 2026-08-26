package com.coveninja.cove

import android.content.pm.ActivityInfo
import kotlin.test.assertEquals
import org.junit.Test

class PlayerOrientationTest {
    // Asserted against the sensor variant by name: plain SCREEN_ORIENTATION_LANDSCAPE also reads
    // as "landscape" and would pass any looser check, while pinning one rotation so that turning
    // the phone the other way leaves the viewer watching upside-down.
    @Test
    fun `a handset turns landscape for fullscreen playback`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            orientation(fullscreenPlayback = true),
        )
    }

    @Test
    fun `nothing is asked for when no player is open`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            orientation(fullscreenPlayback = false),
        )
    }

    @Test
    fun `a television is left alone`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            orientation(fullscreenPlayback = true, isTelevision = true),
        )
    }

    // Both sides, because the tablet half alone passes against a `<=` and the handset half alone
    // passes against a gate that was dropped altogether.
    @Test
    fun `the handset gate is the platform's own tablet boundary`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            orientation(fullscreenPlayback = true, smallestScreenWidthDp = 599),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            orientation(fullscreenPlayback = true, smallestScreenWidthDp = 600),
        )
    }

    // Playback is still fullscreen as far as the session is concerned while the activity is a
    // floating window, so this is the one case the fullscreenPlayback flag cannot speak for.
    @Test
    fun `a floating window is not held to an orientation`() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            orientation(fullscreenPlayback = true, inPictureInPicture = true),
        )
    }

    private fun orientation(
        fullscreenPlayback: Boolean,
        inPictureInPicture: Boolean = false,
        isTelevision: Boolean = false,
        smallestScreenWidthDp: Int = 411,
    ) = playerOrientation(
        fullscreenPlayback = fullscreenPlayback,
        inPictureInPicture = inPictureInPicture,
        isTelevision = isTelevision,
        smallestScreenWidthDp = smallestScreenWidthDp,
    )
}
