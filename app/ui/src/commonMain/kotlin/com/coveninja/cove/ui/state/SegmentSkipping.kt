package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.model.LabelledSegment
import com.coveninja.cove.shared.model.SegmentKind

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
 * already reached for something else. IntroDB supplies that boundary when it
 * knows it; failing that, a fixed tail is the best available guess.
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
