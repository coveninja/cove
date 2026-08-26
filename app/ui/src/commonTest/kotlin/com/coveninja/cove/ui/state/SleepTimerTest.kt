package com.coveninja.cove.ui.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sleep timer, and its argument with autoplay.
 *
 * Both have an opinion about the end of an episode, and they have to be resolved somewhere
 * rather than raced: the end card would otherwise start the next episode while the timer was
 * still deciding that it should not.
 */
class SleepTimerTest {

    @Test
    fun `nothing is armed by default`() {
        assertFalse(SleepTimer.Off.armed)
        assertNull(SleepTimer.Off.label)
    }

    // Mutation check: seeding remainingSeconds as null makes the first tick start from zero.
    @Test
    fun `arming a duration seeds the countdown`() {
        val timer = armSleepTimer(SleepTimerChoice.After(30))
        assertEquals(30 * 60, timer.remainingSeconds)
        assertTrue(timer.armed)
    }

    @Test
    fun `after this episode counts down nothing`() {
        val timer = armSleepTimer(SleepTimerChoice.AfterThisEpisode)
        assertNull(timer.remainingSeconds)
        assertEquals(timer, tickSleepTimer(timer, elapsedSeconds = 600))
    }

    // Mutation check: ticking every choice makes the previous test fail instead of this one.
    @Test
    fun `a duration counts down and stops at zero`() {
        var timer = armSleepTimer(SleepTimerChoice.After(1))
        timer = tickSleepTimer(timer, elapsedSeconds = 59)
        assertEquals(1, timer.remainingSeconds)
        assertFalse(sleepTimerElapsed(timer))

        timer = tickSleepTimer(timer, elapsedSeconds = 30)
        assertEquals(0, timer.remainingSeconds, "clamped rather than negative")
        assertTrue(sleepTimerElapsed(timer))
    }

    // Mutation check: dropping the choice test makes an unarmed timer report as elapsed,
    // which would pause playback the moment anything looked at it.
    @Test
    fun `an unarmed timer never elapses`() {
        assertFalse(sleepTimerElapsed(SleepTimer.Off))
        assertFalse(sleepTimerElapsed(armSleepTimer(SleepTimerChoice.AfterThisEpisode)))
    }

    /**
     * Rounded up, or a timer with a minute and a bit left already reads as its last minute
     * and then sits there. The partial minute is the case that distinguishes the two: on a
     * whole number of minutes both roundings agree, and under a minute the floor below
     * catches it anyway.
     */
    @Test
    fun `the remaining label rounds up`() {
        val timer = armSleepTimer(SleepTimerChoice.After(30))
        assertEquals("30 min left", timer.label)
        assertEquals("29 min left", tickSleepTimer(timer, 60).label)
        // 61 seconds: rounding down would call this one minute and lose the remainder.
        assertEquals("2 min left", tickSleepTimer(timer, 30 * 60 - 61).label)
    }

    /** Never "0 min left": a timer still counting must not read as one that has stopped. */
    @Test
    fun `the last seconds still read as a minute`() {
        val timer = armSleepTimer(SleepTimerChoice.After(30))
        assertEquals("1 min left", tickSleepTimer(timer, 30 * 60 - 1).label)
    }

    // Mutation check: returning autoPlay unchanged makes this fail and nothing else.
    @Test
    fun `stopping after this episode suppresses autoplay`() {
        assertFalse(
            autoAdvanceAllowed(
                autoPlay = true,
                timer = armSleepTimer(SleepTimerChoice.AfterThisEpisode),
            ),
        )
    }

    /**
     * A duration timer is not an instruction about episodes: someone who asked for thirty
     * minutes expects the next episode to start if the thirty minutes have not run out.
     */
    @Test
    fun `a duration timer leaves autoplay alone`() {
        assertTrue(
            autoAdvanceAllowed(autoPlay = true, timer = armSleepTimer(SleepTimerChoice.After(30))),
        )
    }

    @Test
    fun `autoplay off stays off whatever the timer says`() {
        assertFalse(autoAdvanceAllowed(autoPlay = false, timer = SleepTimer.Off))
        assertFalse(
            autoAdvanceAllowed(autoPlay = false, timer = armSleepTimer(SleepTimerChoice.After(15))),
        )
    }
}
