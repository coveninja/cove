package com.coveninja.cove.desktop.player

import kotlin.math.roundToLong

data class PlayerSnapshot(
    val initialized:     Boolean = false,
    val hasMedia:        Boolean = false,
    val paused:          Boolean = true,
    val positionSeconds: Double  = 0.0,
    val durationSeconds: Double  = 0.0,
    val volume:          Double  = 100.0,
    /**
     * mpv's own mute flag, which is independent of [volume] — muted playback at
     * volume 100 is silent. Polled rather than assumed: the "start muted" setting
     * mutes the handle at load, and nothing else would ever tell the UI about it.
     */
    val muted:           Boolean = false,
    /** 1.0 is normal; mpv keeps pitch corrected by default. */
    val speed:           Double  = 1.0,
    val title:           String  = "",
    val videoCodec:      String  = "",
    /** Value of mpv's hwdec-current property; blank or "no" means software decode. */
    val hwdecCurrent:    String  = "",
    val renderBackend:   String  = "",
    val trackListJson:   String  = "",
    /**
     * mpv's cache-buffering-state: how full the demuxer cache is, 0-100, while it
     * is filling. This is the only honest answer to "is anything happening yet",
     * and it covers both the torrent and direct-HTTP paths because mpv reads
     * everything through the same /api/play endpoint.
     */
    val cacheBufferingPercent: Int = 0,
    /**
     * mpv's demuxer-cache-time: the timestamp read-ahead currently reaches, in the
     * same units as [positionSeconds]. Unlike [cacheBufferingPercent], which only
     * says something while the cache is filling, this says *where* the data ends —
     * which on a torrent is the difference between a seek that lands instantly and
     * one that stalls.
     */
    val cacheEndSeconds: Double = 0.0,
    /** demuxer-cache-duration: seconds of read-ahead beyond the playhead. */
    val cacheDurationSeconds: Double = 0.0,
    /** mpv is stalled waiting for more data rather than decoding. */
    val pausedForCache:  Boolean = false,
    /** mpv's chapter-list, verbatim. Empty for the many files that have none. */
    val chapterListJson: String = "",
    /** mpv's sub-delay/audio-delay, in seconds; negative pulls the track earlier. */
    val subtitleDelaySeconds: Double = 0.0,
    val audioDelaySeconds: Double = 0.0,
    /** Playback diagnostics for distinguishing decode, timing, and presentation pressure. */
    val frameDropCount:  Int    = 0,
    val decoderFrameDropCount: Int = 0,
    val mistimedFrameCount: Int = 0,
    val delayedFrameCount: Int = 0,
    val estimatedFps:    Double = 0.0,
    val videoBitrate:    Double = 0.0,
    /** Software-render target and time spent producing its latest frame. */
    val renderWidth: Int = 0,
    val renderHeight: Int = 0,
    val renderTimeMillis: Double = 0.0,
    /**
     * Set on MPV_EVENT_FILE_LOADED. idle-active is not a usable substitute: it
     * goes false the moment loadfile is accepted, long before the demuxer has
     * read anything, so it cannot tell "open in progress" from "playing".
     */
    val fileLoaded:      Boolean = false,
    /** Last line mpv logged, which is all it reports while opening a file. */
    val lastMessage:     String  = "",
    /**
     * mpv's eof-reached. With keep-open=yes the player parks at the last frame
     * rather than closing, so this is the only signal that the episode finished
     * rather than merely being paused near the end.
     */
    val endReached:      Boolean = false,
    val error:           String? = null,
    /**
     * Why the current file failed to open, held until the next load rather than cleared by the
     * next state poll the way [error] is: the poll runs every few hundred milliseconds and would
     * otherwise erase the one report a viewer needs to act on.
     */
    val loadError:       String? = null,
) {
    val usingHardwareDecoding: Boolean
        get() = hwdecCurrent.isNotBlank() && hwdecCurrent != "no"

    val progressFraction: Float
        get() = if (durationSeconds > 0.0) {
            (positionSeconds / durationSeconds).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }
}

internal fun formatDuration(seconds: Double): String {
    if (!seconds.isFinite() || seconds < 0.0) return "--:--"
    val total   = seconds.roundToLong()
    val hours   = total / 3600
    val minutes = (total % 3600) / 60
    val secs    = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs)
    else                  "%d:%02d".format(minutes, secs)
}

internal fun Double.finiteOrZero(): Double =
    takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0

/** Null for both an unanswered poll and a garbage one, so callers can hold a value. */
internal fun Double?.finiteOrNull(): Double? =
    this?.takeIf(Double::isFinite)?.coerceAtLeast(0.0)

/**
 * Resolves a polled time property (time-pos, duration) against the last known value.
 *
 * mpv answers MPV_ERROR_PROPERTY_UNAVAILABLE for these while a seek is resolving and
 * during the gap between accepting a file and demuxing it. Treating that silence as
 * zero — which is what `?: 0.0` did — is the difference between "we don't know yet"
 * and "the playhead is at the start of the file", and the UI cannot tell them apart:
 * the seek bar snaps to zero, and any relative seek computed from it lands somewhere
 * the viewer never asked for. The neighbouring volume and mute polls already fall
 * back to the previous snapshot for exactly this reason.
 *
 * [idle] is the one case where zero is the honest answer: no file is loaded, so there
 * is no position to remember.
 */
internal fun resolveTimeProperty(polled: Double?, previous: Double, idle: Boolean): Double =
    when {
        idle -> 0.0
        polled != null && polled.isFinite() -> polled.finiteOrZero()
        else -> previous
    }
