package com.coveninja.cove.desktop.player

import com.coveninja.cove.ui.state.PlaybackStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The seek path, which used to send the player somewhere nobody asked for.
 *
 * Seeking repeatedly could land on the last frame of the file, and mpv parked
 * there with `keep-open=yes` ignores `pause=no` — so playback could not be
 * resumed and the session looked dead. Three separate pieces of arithmetic had
 * to be wrong together for that to happen, and each one is pinned here.
 *
 * Constructing MpvVideoPlayerHost touches no native code — the mpv handle is
 * created when the Surface composable mounts — so these run anywhere.
 */
class SeekLogicTest {

    // ── resolveTimeProperty ──────────────────────────────────────────────────

    // mpv answers "unavailable" for time-pos while a seek is resolving. Reading
    // that as position zero is what made the bar jump to the start mid-seek, and
    // what gave the next relative jump a false place to start from.
    @Test
    fun `an unanswered poll holds the last known position`() {
        assertEquals(30.0, resolveTimeProperty(polled = null, previous = 30.0, idle = false))
    }

    @Test
    fun `an answered poll wins over the last known position`() {
        assertEquals(50.0, resolveTimeProperty(polled = 50.0, previous = 30.0, idle = false))
    }

    // Nothing is loaded, so there is no position to remember. Without this the
    // bar would keep showing the previous file's playhead after it closed.
    @Test
    fun `an idle player reports no position at all`() {
        assertEquals(0.0, resolveTimeProperty(polled = 50.0, previous = 30.0, idle = true))
    }

    // NaN reaches the layout as a weight and infinity as an offset; both are
    // crashes rather than cosmetic problems. Treated like an unanswered poll,
    // because that is what a garbage value means.
    @Test
    fun `a non-finite poll is treated as no answer`() {
        assertEquals(30.0, resolveTimeProperty(Double.NaN, previous = 30.0, idle = false))
        assertEquals(30.0, resolveTimeProperty(Double.POSITIVE_INFINITY, 30.0, idle = false))
    }

    // ── clampSeekTarget ──────────────────────────────────────────────────────

    // The bug in one assertion: landing exactly on the duration parks mpv at EOF,
    // where it refuses to unpause. Dragging the scrubber to the far right end did
    // precisely this.
    @Test
    fun `a seek to the very end is pulled back short of it`() {
        assertEquals(3599.0, clampSeekTarget(requestedSeconds = 3600.0, durationSeconds = 3600.0))
    }

    // Ten seconds from the end, one press of the forward key asks for a position
    // past the file. Nothing used to stop it.
    @Test
    fun `a seek past the end is pulled back short of it`() {
        assertEquals(3599.0, clampSeekTarget(requestedSeconds = 4000.0, durationSeconds = 3600.0))
    }

    @Test
    fun `a seek before the start is pulled up to it`() {
        assertEquals(0.0, clampSeekTarget(requestedSeconds = -10.0, durationSeconds = 3600.0))
    }

    @Test
    fun `an unknown duration leaves the target alone`() {
        assertEquals(90.0, clampSeekTarget(requestedSeconds = 90.0, durationSeconds = 0.0))
    }

    // The guard must not become a general-purpose position filter.
    @Test
    fun `an ordinary mid-file seek is untouched`() {
        assertEquals(90.0, clampSeekTarget(requestedSeconds = 90.0, durationSeconds = 3600.0))
    }

    // A garbage target is what a freed JNA argument array used to hand mpv, and
    // it is worth refusing here too rather than stringifying "NaN" into a command.
    @Test
    fun `a non-finite target is refused`() {
        assertEquals(0.0, clampSeekTarget(Double.NaN, durationSeconds = 3600.0))
    }

    // ── applyPendingSeek ─────────────────────────────────────────────────────

    private fun playing(position: Double, ended: Boolean = false) = PlaybackStatus(
        hasMedia = true,
        positionSeconds = position,
        durationSeconds = 3600.0,
        endReached = ended,
    )

    @Test
    fun `with nothing outstanding the polled status passes straight through`() {
        val resolved = applyPendingSeek(playing(position = 90.0), pendingSeconds = null)

        assertEquals(90.0, resolved.status.positionSeconds)
        assertNull(resolved.pendingSeconds)
    }

    // The 200 ms poll is the whole reason this exists: until mpv gets there, the
    // bar has to show where the viewer asked to go, not where the player still is.
    @Test
    fun `an outstanding seek overrides the position mpv still reports`() {
        val resolved = applyPendingSeek(playing(position = 5.0), pendingSeconds = 900.0)

        assertEquals(900.0, resolved.status.positionSeconds)
        assertEquals(900.0, resolved.pendingSeconds)
    }

    // Once mpv arrives, its own position is the better signal — it advances with
    // playback, and the pending target does not.
    @Test
    fun `an arrived seek hands control back to the player`() {
        val resolved = applyPendingSeek(playing(position = 900.2), pendingSeconds = 900.0)

        assertEquals(900.2, resolved.status.positionSeconds)
        assertNull(resolved.pendingSeconds)
    }

    // load() and stop() both pass through a no-media status. A target left over
    // from the previous file would drag the next one's playhead to it.
    @Test
    fun `losing the media abandons an outstanding seek`() {
        val resolved = applyPendingSeek(PlaybackStatus(hasMedia = false), pendingSeconds = 900.0)

        assertNull(resolved.pendingSeconds)
        assertEquals(0.0, resolved.status.positionSeconds)
    }

    // Seeking backwards out of the credits should not leave "finished" on screen:
    // endReached belongs to where mpv still is, which is somewhere the viewer has
    // already left. It drives both the replay icon and the up-next card.
    @Test
    fun `seeking away from the end clears the finished flag immediately`() {
        val resolved = applyPendingSeek(playing(3599.0, ended = true), pendingSeconds = 120.0)

        assertTrue(!resolved.status.endReached)
        assertEquals(120.0, resolved.status.positionSeconds)
    }

    // ── relative seeks accumulate ────────────────────────────────────────────

    // The reported bug, at the level it was actually visible: tapping the forward
    // key five times moved the playhead ten seconds, because every press read the
    // same polled position and computed the same target. Nothing here advances the
    // poll, which is exactly the condition that used to break it.
    //
    @Test
    fun `repeated relative seeks stack instead of collapsing`() {
        val host = MpvVideoPlayerHost()
        try {
            repeat(5) { host.seekRelative(10.0) }

            assertEquals(50.0, host.status.value.positionSeconds)
        } finally {
            host.dispose()
        }
    }

    @Test
    fun `relative seeks backwards stop at the start of the file`() {
        val host = MpvVideoPlayerHost()
        try {
            repeat(2) { host.seekRelative(-10.0) }

            assertEquals(0.0, host.status.value.positionSeconds)
        } finally {
            host.dispose()
        }
    }
}
