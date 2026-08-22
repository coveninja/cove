package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.model.LabelledSegment
import com.coveninja.cove.shared.model.MediaTimestamps
import com.coveninja.cove.shared.model.SegmentKind
import com.coveninja.cove.shared.model.labelled

/**
 * Semantic playback ranges from both metadata sources.
 *
 * A file's chapters describe the exact encode being played, so a usable embedded
 * range replaces IntroDB for that kind. IntroDB still fills every kind the file
 * does not label. Ordinary chapters remain navigation metadata and never become
 * skip ranges.
 */
internal fun playbackSegments(
    timestamps: MediaTimestamps,
    chapters: List<MediaChapter>,
    durationSeconds: Double,
): List<LabelledSegment> {
    val embedded = chapters.embeddedSegments(durationSeconds)
    val embeddedKinds = embedded.mapTo(mutableSetOf()) { it.kind }
    return (timestamps.labelled().filterNot { it.kind in embeddedKinds } + embedded)
        .sortedBy { it.startSeconds }
}

/** A chapter ends where the next one begins, or at the file duration if it is last. */
private fun List<MediaChapter>.embeddedSegments(durationSeconds: Double): List<LabelledSegment> {
    val ordered = asSequence()
        .filter { it.startSeconds.isFinite() && it.startSeconds >= 0.0 }
        .sortedWith(compareBy<MediaChapter> { it.startSeconds }.thenBy { it.index })
        .toList()
    val mediaEnd = durationSeconds.takeIf { it.isFinite() && it > 0.0 }

    return ordered.mapIndexedNotNull { index, chapter ->
        val kind = chapter.title.segmentKind() ?: return@mapIndexedNotNull null
        val boundary = ordered.getOrNull(index + 1)?.startSeconds ?: mediaEnd
            ?: return@mapIndexedNotNull null
        val end = mediaEnd?.let { minOf(boundary, it) } ?: boundary
        if (end <= chapter.startSeconds) return@mapIndexedNotNull null
        LabelledSegment(kind, chapter.startSeconds, end)
    }
}

/** Conservative aliases only: normalization is forgiving, classification is exact. */
private fun String.segmentKind(): SegmentKind? = CHAPTER_KINDS[normalizedChapterTitle()]

private fun String.normalizedChapterTitle(): String = lowercase()
    .replace(CHAPTER_TITLE_SEPARATOR, " ")
    .trim()
    .replace(CHAPTER_TITLE_WHITESPACE, " ")
    .replace(CHAPTER_NUMBER_PREFIX, "")

private val CHAPTER_KINDS = mapOf(
    "intro" to SegmentKind.Intro,
    "introduction" to SegmentKind.Intro,
    "opening" to SegmentKind.Intro,
    "opening credits" to SegmentKind.Intro,
    "opening titles" to SegmentKind.Intro,
    "op" to SegmentKind.Intro,
    "recap" to SegmentKind.Recap,
    "previously on" to SegmentKind.Recap,
    "previous episode" to SegmentKind.Recap,
    "credits" to SegmentKind.Credits,
    "end credits" to SegmentKind.Credits,
    "ending credits" to SegmentKind.Credits,
    "closing credits" to SegmentKind.Credits,
    "closing titles" to SegmentKind.Credits,
    "outro" to SegmentKind.Credits,
    "ed" to SegmentKind.Credits,
    "preview" to SegmentKind.Preview,
    "next episode preview" to SegmentKind.Preview,
    "next episode" to SegmentKind.Preview,
    "next time" to SegmentKind.Preview,
    "next on" to SegmentKind.Preview,
)

private val CHAPTER_TITLE_SEPARATOR = Regex("[^a-z0-9]+")
private val CHAPTER_TITLE_WHITESPACE = Regex("\\s+")
private val CHAPTER_NUMBER_PREFIX = Regex("^(?:chapter\\s+)?\\d+\\s+")

/**
 * Which labelled stretch the playhead is inside, if any.
 *
 * The last matching segment wins so overlapping ranges resolve to the one that
 * started most recently, which is what a viewer sees on the bar.
 */
internal fun segmentAt(
    positionSeconds: Double,
    segments: List<LabelledSegment>,
): LabelledSegment? = segments.lastOrNull {
    positionSeconds >= it.startSeconds && positionSeconds < it.endSeconds
}

/** Whether this kind of segment is set to be skipped without asking. */
internal fun AppSettings.skipsAutomatically(kind: SegmentKind): Boolean = when (kind) {
    SegmentKind.Intro -> autoSkipIntro
    SegmentKind.Recap -> autoSkipRecap
    SegmentKind.Credits -> autoSkipCredits
    SegmentKind.Preview -> autoSkipPreview
}

/** Label for the manual skip button offered when auto-skip is off. */
internal fun SegmentKind.skipLabel(): String = when (this) {
    SegmentKind.Intro -> "Skip intro"
    SegmentKind.Recap -> "Skip recap"
    SegmentKind.Credits -> "Skip credits"
    SegmentKind.Preview -> "Skip preview"
}

/** Identifies a segment across position updates, for "already skipped this one". */
internal fun LabelledSegment.identity(): String = "$kind:$startSeconds"

/**
 * Where to jump when skipping, or null if the jump is not worth making.
 *
 * Two guards, both learned from how these ranges actually behave:
 *
 * A segment ending within a breath of the end of the file is left alone — that
 * is a credits roll running to the last frame, and seeking there just ends
 * playback, which is not what "skip credits" should do to the episode you are
 * still watching.
 *
 * A jump of under a second is not worth the seek: remote seeks cost a round trip,
 * and the viewer would not notice the difference.
 */
internal fun skipTarget(
    segment: LabelledSegment,
    positionSeconds: Double,
    durationSeconds: Double,
): Double? {
    if (durationSeconds > 0.0 && segment.endSeconds >= durationSeconds - END_OF_FILE_GRACE) return null
    if (segment.endSeconds - positionSeconds < MINIMUM_SKIP_SECONDS) return null
    return segment.endSeconds
}

private const val END_OF_FILE_GRACE = 2.0
private const val MINIMUM_SKIP_SECONDS = 1.0

/**
 * When the "up next" card should appear.
 *
 * Credits are the honest marker: once they roll the episode is effectively over,
 * and waiting for the last frame means the card arrives after the viewer has
 * already reached for something else. IntroDB or an embedded chapter supplies
 * that boundary when available; failing that, a fixed tail is the best guess.
 */
internal fun upNextThreshold(
    durationSeconds: Double,
    segments: List<LabelledSegment>,
): Double? {
    if (durationSeconds <= 0.0) return null

    // Only trust a credits marker that falls somewhere near the end. Providers
    // do occasionally return a stray early range, and one of those would put the
    // card on screen in the first act.
    val earliestCredible = durationSeconds * CREDITS_CREDIBLE_FRACTION
    val marker = segments
        .filter { it.kind == SegmentKind.Credits || it.kind == SegmentKind.Preview }
        .map { it.startSeconds }
        .filter { it >= earliestCredible }
        .minOrNull()

    return marker ?: (durationSeconds - UP_NEXT_TAIL_SECONDS).coerceAtLeast(0.0)
}

/** Whether the episode has reached the point where what follows matters more. */
internal fun showUpNext(
    positionSeconds: Double,
    durationSeconds: Double,
    segments: List<LabelledSegment>,
    endReached: Boolean,
): Boolean {
    if (endReached) return true
    val threshold = upNextThreshold(durationSeconds, segments) ?: return false
    return positionSeconds >= threshold
}

/** Anything earlier than this is a mislabelled range, not a credits roll. */
private const val CREDITS_CREDIBLE_FRACTION = 0.5

/** Fallback when nothing labels the credits: roughly one closing sequence. */
private const val UP_NEXT_TAIL_SECONDS = 75.0
