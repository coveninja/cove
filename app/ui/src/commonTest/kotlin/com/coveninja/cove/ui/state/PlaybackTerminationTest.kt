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
    fun `a seek still resolving withholds the verdict entirely`() {
        val terminal = classifyPlaybackTermination(
            positionSeconds = 1000.0,
            previousPositionSeconds = 400.0,
            durationSeconds = 1000.0,
            seekUnsettled = true,
        )

        // The same arguments the test above calls an interruption. Fails if the flag is
        // ignored, which is what tore the stream down and reloaded it every time the viewer
        // seeked backwards out of the credits.
        assertFalse(terminal.interrupted)
        assertFalse(terminal.ended)
        // Held rather than rolled back: the hosts write this over the published position, and
        // while a seek is outstanding the published position is the target being waited on.
        assertEquals(1000.0, terminal.positionSeconds)
    }

    @Test
    fun `a seek still resolving does not swallow a real completion either`() {
        val terminal = classifyPlaybackTermination(
            positionSeconds = 1000.0,
            previousPositionSeconds = 995.0,
            durationSeconds = 1000.0,
            seekUnsettled = true,
        )

        // Deferred, not denied: the next observation after the seek settles decides. Fails if
        // the unsettled branch returns `ended = true` for a position near the end, which would
        // complete a title the viewer had just seeked away from.
        assertFalse(terminal.ended)
        assertFalse(terminal.interrupted)
    }

    @Test
    fun `a pending seek stops being an excuse once the grace window lapses`() {
        // Fails if the grace is unbounded: a seek into a genuinely dead region never lands, so
        // its target would suppress the interruption for ever and leave the player frozen with
        // nothing on screen explaining it.
        assertTrue(seekStillResolving(120.0, millisSinceSeekIssued = 0))
        assertTrue(seekStillResolving(120.0, millisSinceSeekIssued = SEEK_TERMINAL_GRACE_MILLIS - 1))
        assertFalse(seekStillResolving(120.0, millisSinceSeekIssued = SEEK_TERMINAL_GRACE_MILLIS))
        // No seek outstanding is never an excuse, however recently one landed.
        assertFalse(seekStillResolving(null, millisSinceSeekIssued = 0))
        // A clock that moved backwards counts as just-issued rather than long overdue.
        assertTrue(seekStillResolving(120.0, millisSinceSeekIssued = -5_000))
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
