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

    // Mutation applied to verify: dropped the addition and kept the latest delta →
    // test failed at 10.0 rather than 30.0.
    @Test
    fun `jumps inside the window add up`() {
        var feedback = accumulateSeekFeedback(null, 10.0, withinWindow = false)
        feedback = accumulateSeekFeedback(feedback, 10.0, withinWindow = true)
        feedback = accumulateSeekFeedback(feedback, 10.0, withinWindow = true)

        assertEquals(30.0, feedback.totalSeconds)
        assertTrue(feedback.forward)
    }

    // Mutation applied to verify: ignored withinWindow → test failed at 20.0, so a
    // jump minutes after the last one would have been reported as a continuation.
    @Test
    fun `a jump after the window starts a new burst`() {
        val first = accumulateSeekFeedback(null, 10.0, withinWindow = false)
        val second = accumulateSeekFeedback(first, 10.0, withinWindow = false)

        assertEquals(10.0, second.totalSeconds)
    }

    // Netting off would report 0s, which describes the arithmetic rather than the
    // action: the viewer pressing back wants to see the back press land.
    // Mutation applied to verify: removed the direction comparison → test failed at
    // 0.0, and the burst showed neither direction.
    @Test
    fun `reversing direction starts over rather than cancelling out`() {
        val forward = accumulateSeekFeedback(null, 10.0, withinWindow = false)
        val back = accumulateSeekFeedback(forward, -10.0, withinWindow = true)

        assertEquals(-10.0, back.totalSeconds)
        assertTrue(!back.forward)
    }

    // The animation restarts on a change of id, and pressing forward twice leaves
    // every other field looking identical.
    // Mutation applied to verify: held the id constant across restarts → test
    // failed, both bursts shared an id and the second would not have animated.
    @Test
    fun `each new burst gets a fresh id`() {
        val first = accumulateSeekFeedback(null, 10.0, withinWindow = false)
        val second = accumulateSeekFeedback(first, -10.0, withinWindow = true)

        assertTrue(second.id != first.id, "ids were both ${first.id}")
    }

    // Mutation applied to verify: bumped the id on continuation too → test failed,
    // which would have restarted the animation mid-burst on every press.
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
    // Mutation applied to verify: returned true whenever a position was reported →
    // test failed, a stationary pointer read as movement.
    @Test
    fun `a pointer reported at the same place has not moved`() {
        assertTrue(!pointerMovedEnough(Offset(100f, 100f), Offset(100f, 100f)))
    }

    // Mutation applied to verify: compared for exact equality instead of a
    // threshold → test failed, trackpad jitter under a resting finger counted.
    @Test
    fun `jitter under a resting finger does not count`() {
        assertTrue(!pointerMovedEnough(Offset(100f, 100f), Offset(101f, 101f)))
    }

    // Mutation applied to verify: widened the threshold past the test's delta →
    // test failed, and real movement would no longer have woken the controls.
    @Test
    fun `real movement counts`() {
        assertTrue(pointerMovedEnough(Offset(100f, 100f), Offset(140f, 100f)))
        assertTrue(pointerMovedEnough(Offset(100f, 100f), Offset(100f, 140f)))
    }

    // The first position of a session has nothing to compare against, and treating
    // that as stillness would leave the controls hidden until the second event.
    // Mutation applied to verify: returned false for a null previous → test failed.
    @Test
    fun `the first reported position counts as movement`() {
        assertTrue(pointerMovedEnough(null, Offset(10f, 10f)))
    }

    // Compose reports Offset.Unspecified — NaN in both axes — for a pointer that has
    // left the window, and NaN comparisons are false in a way that silently reads as
    // stillness. Refused explicitly rather than by accident.
    // Mutation applied to verify: dropped the isValid guard → test still passed,
    // because NaN > threshold is already false. Kept as a statement of intent: the
    // behaviour is correct either way, and this pins it against a future rewrite
    // that compares distances instead, where NaN would no longer be safe.
    @Test
    fun `an unspecified position is not movement`() {
        assertTrue(!pointerMovedEnough(Offset(10f, 10f), Offset.Unspecified))
    }
}
