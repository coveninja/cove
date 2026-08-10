package com.coveninja.cove.ui.components.player

import com.coveninja.cove.shared.model.LabelledSegment
import com.coveninja.cove.shared.model.SegmentKind

/**
 * One piece of the seek bar.
 *
 * The bar is drawn as a run of adjacent pieces rather than one track with
 * coloured blocks laid over it, so every boundary — including the ordinary
 * stretches between labelled segments — gets the same gap and rounding.
 * [kind] is null for ordinary playback.
 */
internal data class SeekChunk(
    val startSeconds: Double,
    val endSeconds: Double,
    val kind: SegmentKind?,
) {
    val lengthSeconds: Double get() = endSeconds - startSeconds
}

/**
 * Splits the whole duration into contiguous pieces, filling the space between
 * labelled segments with unlabelled ones so the pieces tile the bar exactly.
 *
 * Segments are clamped to the duration and taken in order; anything overlapping
 * one already emitted is trimmed to start where that ended. Providers do supply
 * overlapping and out-of-range ranges, and two pieces claiming the same stretch
 * would render on top of each other.
 */
internal fun seekChunks(
    durationSeconds: Double,
    segments: List<LabelledSegment>,
): List<SeekChunk> {
    if (durationSeconds <= 0.0) return emptyList()

    val usable = segments
        .mapNotNull { segment ->
            val start = segment.startSeconds.coerceIn(0.0, durationSeconds)
            val end = segment.endSeconds.coerceIn(0.0, durationSeconds)
            if (end > start) Triple(start, end, segment.kind) else null
        }
        .sortedBy { it.first }

    val chunks = mutableListOf<SeekChunk>()
    var cursor = 0.0
    usable.forEach { (start, end, kind) ->
        if (end <= cursor) return@forEach
        val from = maxOf(start, cursor)
        if (from > cursor) chunks += SeekChunk(cursor, from, null)
        chunks += SeekChunk(from, end, kind)
        cursor = end
    }
    if (cursor < durationSeconds) chunks += SeekChunk(cursor, durationSeconds, null)
    return chunks
}
