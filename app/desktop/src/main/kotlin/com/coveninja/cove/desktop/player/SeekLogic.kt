package com.coveninja.cove.desktop.player

import com.coveninja.cove.ui.state.PlaybackStatus
import kotlin.math.abs

/**
 * The arithmetic behind seeking, kept out of [MpvVideoPlayerHost] so it can be tested
 * without a libmpv handle. Every one of these encodes something that went wrong when
 * the player did it inline.
 */

/**
 * Where a seek is actually allowed to land.
 *
 * The upper bound is the point of this function. mpv runs with `keep-open=yes`, so a
 * seek to the duration — or past it, which nothing used to prevent — parks the player
 * on the last frame with `eof-reached` set. From there mpv ignores `pause=no`: it
 * re-detects the end and pauses again, so the play button does nothing and the session
 * looks dead. Landing a whole second short costs the viewer nothing and makes that
 * state unreachable by seeking.
 *
 * Playing *into* the end is unaffected; this only governs deliberate jumps.
 *
 * A duration of zero means mpv has not reported one yet, in which case there is no
 * upper bound to enforce and guessing at one would be worse than not clamping.
 */
internal fun clampSeekTarget(
    requestedSeconds: Double,
    durationSeconds: Double,
    endGuardSeconds: Double = SEEK_END_GUARD_SECONDS,
): Double = when {
    !requestedSeconds.isFinite() -> 0.0
    durationSeconds > endGuardSeconds -> requestedSeconds.coerceIn(0.0, durationSeconds - endGuardSeconds)
    else -> requestedSeconds.coerceAtLeast(0.0)
}

/** The status to publish, and the pending target to carry into the next poll. */
internal data class PendingSeekResolution(
    val status: PlaybackStatus,
    val pendingSeconds: Double?,
)

/**
 * Merges a freshly polled status with a seek the viewer has asked for but mpv has not
 * yet reached.
 *
 * mpv's position is read on a 200 ms timer, so without this the bar reports the old
 * position for up to a fifth of a second after every seek. That lag is visible on its
 * own, but the real damage is that relative seeks are computed from it: press the key
 * five times inside one poll window and all five jumps start from the same place, so
 * they collapse into one.
 *
 * The pending target is released as soon as mpv reports a position near it, which is
 * the signal that the seek landed and mpv's own position is authoritative again. It is
 * also released whenever there is no media — [MpvVideoPlayerHost.load] and `stop` both
 * pass through that state, and a target left over from the previous file would drag the
 * new one's playhead to it.
 */
internal fun applyPendingSeek(
    live: PlaybackStatus,
    pendingSeconds: Double?,
    toleranceSeconds: Double = SEEK_SETTLE_TOLERANCE_SECONDS,
): PendingSeekResolution = when {
    pendingSeconds == null -> PendingSeekResolution(live, null)
    !live.hasMedia -> PendingSeekResolution(live, null)
    abs(live.positionSeconds - pendingSeconds) < toleranceSeconds ->
        PendingSeekResolution(live, null)
    else -> PendingSeekResolution(
        // endReached is suppressed alongside the position: it belongs to wherever mpv
        // still is, and reporting "finished" against a playhead the viewer has already
        // moved elsewhere would put the replay icon and the up-next card on screen
        // during an ordinary seek backwards out of the credits.
        live.copy(positionSeconds = pendingSeconds, endReached = false),
        pendingSeconds,
    )
}

/**
 * Keeps a track delay inside a range that can still be undone by hand.
 *
 * mpv accepts any finite value here, and a stepper held down by accident can put the
 * subtitles minutes away from the picture — at which point they are simply gone, with
 * nothing on screen explaining why. Ten seconds either way covers every genuine
 * mismatch between an addon subtitle and a release.
 */
internal fun clampTrackDelay(seconds: Double): Double =
    if (seconds.isFinite()) seconds.coerceIn(-MAX_TRACK_DELAY_SECONDS, MAX_TRACK_DELAY_SECONDS)
    else 0.0

private const val MAX_TRACK_DELAY_SECONDS = 10.0

/**
 * How far short of the end a seek is allowed to land. Large enough to clear the last
 * frame on any frame rate, small enough that nobody aiming at the end notices.
 */
internal const val SEEK_END_GUARD_SECONDS = 1.0

/**
 * How close mpv has to get before its own position is believed again. Wider than one
 * poll interval of ordinary playback so a settled seek is not mistaken for a lagging
 * one, and far narrower than any deliberate jump.
 */
internal const val SEEK_SETTLE_TOLERANCE_SECONDS = 1.0

/**
 * Minimum gap between the seek commands actually handed to mpv.
 *
 * Key repeat and a dragged scrubber both produce far more targets than are worth
 * executing, and each one is an exact seek that makes the demuxer re-read — over the
 * loopback HTTP boundary that every stream goes through, and over a torrent behind it.
 * Only the newest target in a window matters, so the rest are dropped rather than
 * queued; the viewer still ends up exactly where they aimed, having cost one seek
 * instead of thirty.
 */
internal const val SEEK_COMMAND_INTERVAL_MILLIS = 60L
