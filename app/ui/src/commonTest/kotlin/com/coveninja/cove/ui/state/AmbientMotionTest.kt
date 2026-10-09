package com.coveninja.cove.ui.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AmbientMotionTest {
    @Test
    fun backdropPushEndsAtItsScaleAndStaysThere() {
        assertEquals(1f, pushScaleAt(elapsedMillis = 0, durationMillis = 20_000, scaleTo = 1.08f))
        assertEquals(1.08f, pushScaleAt(elapsedMillis = 20_000, durationMillis = 20_000, scaleTo = 1.08f), 1e-6f)
        // The clock is capped at the duration, but the scale must not overshoot even if it were not.
        assertEquals(1.08f, pushScaleAt(elapsedMillis = 90_000, durationMillis = 20_000, scaleTo = 1.08f), 1e-6f)
        val halfway = pushScaleAt(elapsedMillis = 10_000, durationMillis = 20_000, scaleTo = 1.08f)
        assertTrue(halfway > 1f && halfway < 1.08f, "halfway was $halfway")
    }

    @Test
    fun driftGoesOutAndComesBackOverTwoLegs() {
        assertEquals(0f, driftAt(elapsedMillis = 0, legMillis = 1_000))
        assertEquals(1f, driftAt(elapsedMillis = 1_000, legMillis = 1_000), 1e-6f)
        assertEquals(0f, driftAt(elapsedMillis = 2_000, legMillis = 1_000), 1e-6f)
        // The way back retraces the way out, so a pause anywhere resumes without a jump.
        assertEquals(
            driftAt(elapsedMillis = 300, legMillis = 1_000),
            driftAt(elapsedMillis = 1_700, legMillis = 1_000),
            1e-6f,
        )
        // Long-running: the modulo keeps it in range however long Cove has been in front.
        val late = driftAt(elapsedMillis = 86_400_000L * 30 + 500, legMillis = 1_000)
        assertTrue(late in 0f..1f, "late drift was $late")
    }

    @Test
    fun sweepStartsOverEachPeriod() {
        assertEquals(0f, sweepAt(elapsedMillis = 0, periodMillis = 2_600))
        assertEquals(0.5f, sweepAt(elapsedMillis = 1_300, periodMillis = 2_600), 1e-6f)
        assertEquals(0f, sweepAt(elapsedMillis = 2_600, periodMillis = 2_600))
    }
}
