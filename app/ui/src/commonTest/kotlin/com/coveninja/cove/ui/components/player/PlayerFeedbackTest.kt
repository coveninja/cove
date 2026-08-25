package com.coveninja.cove.ui.components.player

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The accumulating label on the seek burst.
 *
 * It has to agree with what the player actually does, which is add repeated jumps
 * together — reporting "10s" three times while the playhead moves thirty would be
 * worse than reporting nothing.
 */
class PlayerFeedbackTest {

    @Test
    fun `jumps inside the window add up`() {
        var feedback = accumulateSeekFeedback(null, 10.0, withinWindow = false)
        feedback = accumulateSeekFeedback(feedback, 10.0, withinWindow = true)
        feedback = accumulateSeekFeedback(feedback, 10.0, withinWindow = true)

        assertEquals(30.0, feedback.totalSeconds)
        assertTrue(feedback.forward)
    }

    @Test
    fun `a jump after the window starts a new burst`() {
        val first = accumulateSeekFeedback(null, 10.0, withinWindow = false)
        val second = accumulateSeekFeedback(first, 10.0, withinWindow = false)

        assertEquals(10.0, second.totalSeconds)
    }

    // Netting off would report 0s, which describes the arithmetic rather than the
    // action: the viewer pressing back wants to see the back press land.
    @Test
    fun `reversing direction starts over rather than cancelling out`() {
        val forward = accumulateSeekFeedback(null, 10.0, withinWindow = false)
        val back = accumulateSeekFeedback(forward, -10.0, withinWindow = true)

        assertEquals(-10.0, back.totalSeconds)
        assertTrue(!back.forward)
    }

    // The animation restarts on a change of id, and pressing forward twice leaves
    // every other field looking identical.
    @Test
    fun `each new burst gets a fresh id`() {
        val first = accumulateSeekFeedback(null, 10.0, withinWindow = false)
        val second = accumulateSeekFeedback(first, -10.0, withinWindow = true)

        assertTrue(second.id != first.id, "ids were both ${first.id}")
    }

    @Test
    fun `continuing a burst keeps its id so the animation runs on`() {
        val first = accumulateSeekFeedback(null, 10.0, withinWindow = false)
        val second = accumulateSeekFeedback(first, 10.0, withinWindow = true)

        assertEquals(first.id, second.id)
    }

    // ── Pointer idleness ─────────────────────────────────────────────────────

    // The controls hide on stillness, and the video surface republishes a frame
    // many times a second. Compose re-delivers the pointer position when the layout
    // under it changes, so counting reported positions rather than actual movement
    // meant the controls and the cursor never went away over a playing video.
    @Test
    fun `a pointer reported at the same place has not moved`() {
        assertTrue(!pointerMovedEnough(Offset(100f, 100f), Offset(100f, 100f)))
    }

    @Test
    fun `jitter under a resting finger does not count`() {
        assertTrue(!pointerMovedEnough(Offset(100f, 100f), Offset(101f, 101f)))
    }

    @Test
    fun `real movement counts`() {
        assertTrue(pointerMovedEnough(Offset(100f, 100f), Offset(140f, 100f)))
        assertTrue(pointerMovedEnough(Offset(100f, 100f), Offset(100f, 140f)))
    }

    // The first position of a session has nothing to compare against, and treating
    // that as stillness would leave the controls hidden until the second event.
    @Test
    fun `the first reported position counts as movement`() {
        assertTrue(pointerMovedEnough(null, Offset(10f, 10f)))
    }

    // Compose reports Offset.Unspecified — NaN in both axes — for a pointer that has
    // left the window, and NaN comparisons are false in a way that silently reads as
    // stillness. Refused explicitly rather than by accident.
    @Test
    fun `an unspecified position is not movement`() {
        assertTrue(!pointerMovedEnough(Offset(10f, 10f), Offset.Unspecified))
    }
}
