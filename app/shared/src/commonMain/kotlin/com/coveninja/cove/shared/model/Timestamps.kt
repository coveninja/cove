package com.coveninja.cove.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A labelled stretch of an episode, in milliseconds from the start. */
@Serializable
data class TimestampSegment(
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
) {
    val startSeconds: Double? get() = startMs?.let { it / 1000.0 }
    val endSeconds: Double? get() = endMs?.let { it / 1000.0 }
}

/** Intro/recap/credits ranges, as supplied by the IntroDB integration. */
@Serializable
data class MediaTimestamps(
    val intro: List<TimestampSegment> = emptyList(),
    val recap: List<TimestampSegment> = emptyList(),
    val credits: List<TimestampSegment> = emptyList(),
    val preview: List<TimestampSegment> = emptyList(),
) {
    val isEmpty: Boolean
        get() = intro.isEmpty() && recap.isEmpty() && credits.isEmpty() && preview.isEmpty()

    companion object {
        val None = MediaTimestamps()
    }
}

/** What a segment is, which decides how the seek bar colours it. */
enum class SegmentKind { Recap, Intro, Credits, Preview }

data class LabelledSegment(
    val kind: SegmentKind,
    val startSeconds: Double,
    val endSeconds: Double,
)

/** Flattened and sorted, dropping anything without both ends or with no length. */
fun MediaTimestamps.labelled(): List<LabelledSegment> = buildList {
    fun addAll(kind: SegmentKind, segments: List<TimestampSegment>) {
        segments.forEach { segment ->
            val start = segment.startSeconds
            val end = segment.endSeconds
            if (start != null && end != null && end > start) {
                add(LabelledSegment(kind, start, end))
            }
        }
    }
    addAll(SegmentKind.Recap, recap)
    addAll(SegmentKind.Intro, intro)
    addAll(SegmentKind.Credits, credits)
    addAll(SegmentKind.Preview, preview)
}.sortedBy { it.startSeconds }
