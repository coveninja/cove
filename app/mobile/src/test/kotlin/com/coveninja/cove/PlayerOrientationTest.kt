package com.coveninja.cove

import android.content.pm.ActivityInfo
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

/**
 * The shape of the floating window.
 *
 * A hardcoded 16:9 pillarboxed every scope film inside a window already the size of a postage
 * stamp — and the clamp is not cosmetic: Android throws outside its legal range, so an
 * unusually wide release crashed the transition rather than being letterboxed.
 */
class PictureInPictureAspectTest {

    @Test
    fun `an ordinary frame is used as it is`() {
        assertEquals(1920 to 1080, pictureInPictureAspect(1920, 1080))
    }

    // Mutation check: without the guard this divides by zero or reports a 0:0 ratio, and
    // zero is exactly what the player publishes until the first frame arrives.
    @Test
    fun `no frame yet falls back to sixteen by nine`() {
        assertEquals(16 to 9, pictureInPictureAspect(0, 0))
        assertEquals(16 to 9, pictureInPictureAspect(1920, 0))
        assertEquals(16 to 9, pictureInPictureAspect(-1, -1))
    }

    /** 2.39:1 is the legal edge, and a real release shape — it must pass through untouched. */
    @Test
    fun `the widest legal shape is not clamped`() {
        val (width, height) = pictureInPictureAspect(2390, 1000)
        assertEquals(2390 to 1000, width to height)
    }

    // Mutation check: drop the clamp and this ratio is out of range, which throws on a device.
    @Test
    fun `an ultra wide frame is clamped into the legal range`() {
        val (width, height) = pictureInPictureAspect(3000, 1000)
        val ratio = width.toDouble() / height.toDouble()
        assertTrue(ratio <= 2.39, "was $ratio")
        assertTrue(ratio >= 1 / 2.39, "was $ratio")
    }

    @Test
    fun `an extremely tall frame is clamped too`() {
        val (width, height) = pictureInPictureAspect(1000, 3000)
        val ratio = width.toDouble() / height.toDouble()
        assertTrue(ratio >= 1 / 2.39, "was $ratio")
    }
}
