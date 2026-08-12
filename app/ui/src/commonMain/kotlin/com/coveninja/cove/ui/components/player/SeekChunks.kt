package com.coveninja.cove.ui.components.player

import com.coveninja.cove.shared.model.LabelledSegment
import com.coveninja.cove.shared.model.SegmentKind
import com.coveninja.cove.ui.state.MediaChapter

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

    /**
     * How much of this piece lies behind [uptoSeconds], as a 0..1 fraction of the
     * piece itself.
     *
     * The bar is a run of separate pieces, so a fill that spanned the whole width
     * would have to be drawn across the gaps between them. Each piece fills itself
     * instead, and this is the arithmetic both the played and the buffered layer use
     * to do it — one function, so the two layers cannot drift apart.
     */
    fun fillFraction(uptoSeconds: Double): Float {
        if (lengthSeconds <= 0.0 || !uptoSeconds.isFinite()) return 0f
        return ((uptoSeconds - startSeconds) / lengthSeconds).coerceIn(0.0, 1.0).toFloat()
    }
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

/**
 * The chapter containing [positionSeconds], or null before the first one starts.
 *
 * mpv reports chapters in file order and the last one to have begun is the one you
 * are in, so this walks backwards rather than searching for a range: chapters have a
 * start and no end, and the end of one is only implied by the start of the next.
 */
internal fun chapterAt(
    positionSeconds: Double,
    chapters: List<MediaChapter>,
): MediaChapter? = chapters.lastOrNull { positionSeconds >= it.startSeconds }

/**
 * Where the chapter ticks go, as 0..1 fractions of the bar.
 *
 * The first chapter almost always starts at zero, and a tick on the very first pixel
 * is indistinguishable from the end cap of the bar — so it is dropped rather than
 * drawn. Anything at or past the duration is dropped for the same reason at the other
 * end, and a file whose chapter list is one long chapter produces no ticks at all,
 * which is correct: there is nothing to divide.
 */
internal fun chapterMarks(
    chapters: List<MediaChapter>,
    durationSeconds: Double,
): List<Float> {
    if (durationSeconds <= 0.0) return emptyList()
    return chapters
        .map { it.startSeconds }
        .filter { it > 0.0 && it < durationSeconds }
        .map { (it / durationSeconds).toFloat() }
}
