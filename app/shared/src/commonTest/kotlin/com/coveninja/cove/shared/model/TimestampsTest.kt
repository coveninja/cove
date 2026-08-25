package com.coveninja.cove.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimestampsTest {

    @Test
    fun `segments without two usable ends are discarded`() {
        val timestamps = MediaTimestamps(
            intro = listOf(
                TimestampSegment(startMs = 1_000, endMs = null),
                TimestampSegment(startMs = null, endMs = 5_000),
                TimestampSegment(startMs = 4_000, endMs = 4_000),
                TimestampSegment(startMs = 9_000, endMs = 6_000),
                TimestampSegment(startMs = 10_000, endMs = 20_000),
            ),
        )

        val labelled = timestamps.labelled()

        assertEquals(1, labelled.size, "was: $labelled")
        assertEquals(10.0, labelled.single().startSeconds)
        assertEquals(20.0, labelled.single().endSeconds)
    }

    // The seek bar draws these in order, so they have to arrive in order rather
    // than grouped by which list they came from.
    //
    // Positions are deliberately the reverse of the order the lists are collected
    // in (recap, intro, credits, preview). An earlier version of this test used a
    // fixture that was already in collection order, so deleting the sort changed
    // nothing and the test passed regardless.
    @Test
    fun `segments are ordered by position, not by kind`() {
        val timestamps = MediaTimestamps(
            recap = listOf(TimestampSegment(startMs = 200_000, endMs = 240_000)),
            intro = listOf(TimestampSegment(startMs = 10_000, endMs = 40_000)),
            credits = listOf(TimestampSegment(startMs = 100_000, endMs = 160_000)),
        )

        val kinds = timestamps.labelled().map { it.kind }

        assertEquals(listOf(SegmentKind.Intro, SegmentKind.Credits, SegmentKind.Recap), kinds)
    }

    @Test
    fun `a title with no timestamps reports empty`() {
        assertTrue(MediaTimestamps.None.isEmpty)
        assertTrue(MediaTimestamps.None.labelled().isEmpty())
        assertTrue(
            !MediaTimestamps(
                credits = listOf(TimestampSegment(startMs = 1, endMs = 2)),
            ).isEmpty,
        )
    }

    // Milliseconds on the wire, seconds everywhere the player works.
    @Test
    fun `wire milliseconds convert to seconds`() {
        val segment = TimestampSegment(startMs = 65_000, endMs = 152_500)
        assertEquals(65.0, segment.startSeconds)
        assertEquals(152.5, segment.endSeconds)
    }
}
