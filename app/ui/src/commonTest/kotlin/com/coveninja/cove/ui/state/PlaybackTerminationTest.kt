package com.coveninja.cove.ui.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackTerminationTest {
    @Test
    fun `positions inside the bounded end tolerance are natural`() {
        assertTrue(playbackReachedNaturalEnd(positionSeconds = 99.0, durationSeconds = 100.0))
        assertTrue(playbackReachedNaturalEnd(positionSeconds = 991.0, durationSeconds = 1000.0))
        assertTrue(playbackReachedNaturalEnd(positionSeconds = 9971.0, durationSeconds = 10_000.0))
        assertFalse(playbackReachedNaturalEnd(positionSeconds = 9969.0, durationSeconds = 10_000.0))
    }

    @Test
    fun `unknown and invalid positions are never completion`() {
        assertFalse(playbackReachedNaturalEnd(positionSeconds = 0.0, durationSeconds = 0.0))
        assertFalse(playbackReachedNaturalEnd(Double.NaN, durationSeconds = 1000.0))
        assertFalse(playbackReachedNaturalEnd(positionSeconds = 500.0, Double.POSITIVE_INFINITY))
        assertFalse(playbackReachedNaturalEnd(positionSeconds = -1.0, durationSeconds = 1000.0))
    }

    @Test
    fun `steady progress into the end is classified as natural completion`() {
        val terminal = classifyPlaybackTermination(
            positionSeconds = 1000.0,
            previousPositionSeconds = 995.0,
            durationSeconds = 1000.0,
        )

        assertTrue(terminal.ended)
        assertFalse(terminal.interrupted)
        assertEquals(1000.0, terminal.positionSeconds)
    }

    @Test
    fun `a single jump from mid-file to the duration is rolled back`() {
        val terminal = classifyPlaybackTermination(
            positionSeconds = 1000.0,
            previousPositionSeconds = 400.0,
            durationSeconds = 1000.0,
        )

        assertFalse(terminal.ended)
        assertTrue(terminal.interrupted)
        assertEquals(400.0, terminal.positionSeconds)
    }

    @Test
    fun `an early eof keeps its latest position`() {
        val terminal = classifyPlaybackTermination(
            positionSeconds = 401.0,
            previousPositionSeconds = 400.0,
            durationSeconds = 1000.0,
        )

        assertTrue(terminal.interrupted)
        assertEquals(401.0, terminal.positionSeconds)
    }
}
