package com.coveninja.cove.desktop.player

import kotlin.math.roundToLong

data class PlayerSnapshot(
    val initialized:     Boolean = false,
    val hasMedia:        Boolean = false,
    val paused:          Boolean = true,
    val positionSeconds: Double  = 0.0,
    val durationSeconds: Double  = 0.0,
    val volume:          Double  = 100.0,
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
    /** mpv is stalled waiting for more data rather than decoding. */
    val pausedForCache:  Boolean = false,
    /**
     * Set on MPV_EVENT_FILE_LOADED. idle-active is not a usable substitute: it
     * goes false the moment loadfile is accepted, long before the demuxer has
     * read anything, so it cannot tell "open in progress" from "playing".
     */
    val fileLoaded:      Boolean = false,
    /** Last line mpv logged, which is all it reports while opening a file. */
    val lastMessage:     String  = "",
    val error:           String? = null,
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
