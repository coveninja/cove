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
    // Mutation applied to verify: restored the original `?: 0.0` behaviour by
    // returning 0.0 in the else branch → test failed, got 0.0 instead of 30.0.
    @Test
    fun `an unanswered poll holds the last known position`() {
        assertEquals(30.0, resolveTimeProperty(polled = null, previous = 30.0, idle = false))
    }

    // Mutation applied to verify: dropped the polled branch so previous always
    // won → test failed, the position froze at 30.0 and never advanced.
    @Test
    fun `an answered poll wins over the last known position`() {
        assertEquals(50.0, resolveTimeProperty(polled = 50.0, previous = 30.0, idle = false))
    }

    // Nothing is loaded, so there is no position to remember. Without this the
    // bar would keep showing the previous file's playhead after it closed.
    // Mutation applied to verify: removed the idle branch → test failed, got the
    // polled 50.0 rather than 0.0.
    @Test
    fun `an idle player reports no position at all`() {
        assertEquals(0.0, resolveTimeProperty(polled = 50.0, previous = 30.0, idle = true))
    }

    // NaN reaches the layout as a weight and infinity as an offset; both are
    // crashes rather than cosmetic problems. Treated like an unanswered poll,
    // because that is what a garbage value means.
    // Mutation applied to verify: dropped the isFinite check → test failed with
    // NaN propagating out.
    @Test
    fun `a non-finite poll is treated as no answer`() {
        assertEquals(30.0, resolveTimeProperty(Double.NaN, previous = 30.0, idle = false))
        assertEquals(30.0, resolveTimeProperty(Double.POSITIVE_INFINITY, 30.0, idle = false))
    }

    // ── clampSeekTarget ──────────────────────────────────────────────────────

    // The bug in one assertion: landing exactly on the duration parks mpv at EOF,
    // where it refuses to unpause. Dragging the scrubber to the far right end did
    // precisely this.
    // Mutation applied to verify: removed the endGuard subtraction → test failed,
    // the seek landed on 3600.0.
    @Test
    fun `a seek to the very end is pulled back short of it`() {
        assertEquals(3599.0, clampSeekTarget(requestedSeconds = 3600.0, durationSeconds = 3600.0))
    }

    // Ten seconds from the end, one press of the forward key asks for a position
    // past the file. Nothing used to stop it.
    // Mutation applied to verify: replaced coerceIn with coerceAtLeast(0.0) →
    // test failed, the seek went to 4000.0.
    @Test
    fun `a seek past the end is pulled back short of it`() {
        assertEquals(3599.0, clampSeekTarget(requestedSeconds = 4000.0, durationSeconds = 3600.0))
    }

    // Mutation applied to verify: dropped the lower bound → test failed at -10.0.
    @Test
    fun `a seek before the start is pulled up to it`() {
        assertEquals(0.0, clampSeekTarget(requestedSeconds = -10.0, durationSeconds = 3600.0))
    }

    // Mutation applied to verify: made the clamp unconditional → test failed,
    // an unknown duration clamped every seek to -1.0.
    @Test
    fun `an unknown duration leaves the target alone`() {
        assertEquals(90.0, clampSeekTarget(requestedSeconds = 90.0, durationSeconds = 0.0))
    }

    // The guard must not become a general-purpose position filter.
    // Mutation applied to verify: clamped to a fraction of the duration instead
    // of duration minus the guard → test failed, the seek moved to 3240.0.
    @Test
    fun `an ordinary mid-file seek is untouched`() {
        assertEquals(90.0, clampSeekTarget(requestedSeconds = 90.0, durationSeconds = 3600.0))
    }

    // A garbage target is what a freed JNA argument array used to hand mpv, and
    // it is worth refusing here too rather than stringifying "NaN" into a command.
    // Mutation applied to verify: dropped the isFinite branch → test failed with
    // NaN passing through.
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

    // Mutation applied to verify: returned a non-null pending → test failed, an
    // ordinary poll started overriding its own position.
    @Test
    fun `with nothing outstanding the polled status passes straight through`() {
        val resolved = applyPendingSeek(playing(position = 90.0), pendingSeconds = null)

        assertEquals(90.0, resolved.status.positionSeconds)
        assertNull(resolved.pendingSeconds)
    }

    // The 200 ms poll is the whole reason this exists: until mpv gets there, the
    // bar has to show where the viewer asked to go, not where the player still is.
    // Mutation applied to verify: dropped the override so live passed through →
    // test failed, the position snapped back to 5.0 under the pointer.
    @Test
    fun `an outstanding seek overrides the position mpv still reports`() {
        val resolved = applyPendingSeek(playing(position = 5.0), pendingSeconds = 900.0)

        assertEquals(900.0, resolved.status.positionSeconds)
        assertEquals(900.0, resolved.pendingSeconds)
    }

    // Once mpv arrives, its own position is the better signal — it advances with
    // playback, and the pending target does not.
    // Mutation applied to verify: removed the tolerance branch → test failed, the
    // position froze at 900.0 and playback appeared to stop.
    @Test
    fun `an arrived seek hands control back to the player`() {
        val resolved = applyPendingSeek(playing(position = 900.2), pendingSeconds = 900.0)

        assertEquals(900.2, resolved.status.positionSeconds)
        assertNull(resolved.pendingSeconds)
    }

    // load() and stop() both pass through a no-media status. A target left over
    // from the previous file would drag the next one's playhead to it.
    // Mutation applied to verify: removed the hasMedia guard → test failed, the
    // stale 900.0 survived into the new session.
    @Test
    fun `losing the media abandons an outstanding seek`() {
        val resolved = applyPendingSeek(PlaybackStatus(hasMedia = false), pendingSeconds = 900.0)

        assertNull(resolved.pendingSeconds)
        assertEquals(0.0, resolved.status.positionSeconds)
    }

    // Seeking backwards out of the credits should not leave "finished" on screen:
    // endReached belongs to where mpv still is, which is somewhere the viewer has
    // already left. It drives both the replay icon and the up-next card.
    // Mutation applied to verify: dropped `endReached = false` from the copy →
    // test failed, the card stayed up through a seek back into the episode.
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
    // Mutation applied to verify: removed the optimistic echo from seek() → test
    // failed at 10.0 rather than 50.0. That echo is what carries the accumulation:
    // each press publishes its target, and the next press reads it back.
    //
    // Worth recording what did NOT kill this, since it contradicts the obvious
    // reading of seekRelative: making it read status.positionSeconds rather than
    // the pending field leaves the test green. In the settled state the two are
    // equal by construction — applyPendingSeek keeps the published position pinned
    // to the pending target for exactly as long as one is outstanding. The pending
    // field is load-bearing for that override (see the tests above) and as a guard
    // against the mirror coroutine writing a status it computed before this seek,
    // which is a race no single-threaded test can stage.
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

    // Mutation applied to verify: dropped the lower clamp in clampSeekTarget →
    // test failed at -20.0, and mpv would have been sent a negative timestamp.
    // The same mutation made unconditional also failed it, at -20.0.
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
