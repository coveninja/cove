package com.coveninja.cove.ui.components.player

import com.coveninja.cove.shared.model.LabelledSegment
import com.coveninja.cove.shared.model.SegmentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeekChunksTest {

    private fun segment(kind: SegmentKind, start: Double, end: Double) =
        LabelledSegment(kind, start, end)

    // The pieces are laid out with weights, so any hole or overlap shows up as a
    // bar that does not reach its own end.
    // Mutation applied to verify: dropped the trailing `cursor < duration` chunk
    // → test failed, the pieces stopped at 90 instead of 600.
    @Test
    fun `chunks tile the whole duration without gaps`() {
        val chunks = seekChunks(
            durationSeconds = 600.0,
            segments = listOf(segment(SegmentKind.Intro, 60.0, 90.0)),
        )

        assertEquals(0.0, chunks.first().startSeconds)
        assertEquals(600.0, chunks.last().endSeconds)
        chunks.zipWithNext().forEach { (left, right) ->
            assertEquals(left.endSeconds, right.startSeconds, "hole between $left and $right")
        }
    }

    // Mutation applied to verify: emitted only the labelled segments → test
    // failed, the unlabelled stretches were missing.
    @Test
    fun `the space between segments becomes its own piece`() {
        val chunks = seekChunks(
            durationSeconds = 600.0,
            segments = listOf(
                segment(SegmentKind.Intro, 60.0, 90.0),
                segment(SegmentKind.Credits, 500.0, 560.0),
            ),
        )

        assertEquals(
            listOf(null, SegmentKind.Intro, null, SegmentKind.Credits, null),
            chunks.map { it.kind },
        )
    }

    // Providers do supply overlapping ranges; two pieces claiming the same
    // stretch would render on top of each other.
    // Mutation applied to verify: removed the `end <= cursor` skip and the
    // `maxOf(start, cursor)` trim → test failed with overlapping pieces.
    @Test
    fun `overlapping segments are trimmed rather than stacked`() {
        val chunks = seekChunks(
            durationSeconds = 600.0,
            segments = listOf(
                segment(SegmentKind.Recap, 0.0, 100.0),
                segment(SegmentKind.Intro, 50.0, 150.0),
            ),
        )

        chunks.zipWithNext().forEach { (left, right) ->
            assertEquals(left.endSeconds, right.startSeconds)
        }
        assertTrue(chunks.all { it.lengthSeconds > 0 }, "was: $chunks")
        assertEquals(SegmentKind.Recap, chunks[0].kind)
        assertEquals(100.0, chunks[0].endSeconds)
        assertEquals(SegmentKind.Intro, chunks[1].kind)
        assertEquals(100.0, chunks[1].startSeconds)
    }

    // Mutation applied to verify: removed the coerceIn clamps → test failed, a
    // piece ran past the end of the bar.
    @Test
    fun `segments beyond the duration are clamped`() {
        val chunks = seekChunks(
            durationSeconds = 100.0,
            segments = listOf(segment(SegmentKind.Credits, 80.0, 400.0)),
        )

        assertEquals(100.0, chunks.last().endSeconds)
        assertTrue(chunks.none { it.endSeconds > 100.0 }, "was: $chunks")
    }

    // No mutation kills this one, and that is the honest finding: deleting the
    // early return in seekChunks changes nothing, because a zero duration clamps
    // every segment to zero length and they are all dropped anyway. The guard is
    // kept for intent, and this test pins the contract rather than the guard.
    @Test
    fun `no duration yields no pieces`() {
        assertTrue(seekChunks(0.0, listOf(segment(SegmentKind.Intro, 1.0, 2.0))).isEmpty())
    }

    // Pieces are laid out with Modifier.weight, which rejects a zero weight, so a
    // zero-length segment reaching the layout is a crash rather than a cosmetic
    // problem. An earlier version of this suite never fed one in, and removing the
    // filter went unnoticed.
    // Mutation applied to verify: dropped the `end > start` filter → test failed
    // with an extra zero-length piece.
    @Test
    fun `zero-length segments never reach the layout`() {
        val chunks = seekChunks(
            durationSeconds = 600.0,
            segments = listOf(
                segment(SegmentKind.Intro, 120.0, 120.0),
                segment(SegmentKind.Credits, 500.0, 560.0),
            ),
        )

        assertTrue(chunks.all { it.lengthSeconds > 0.0 }, "was: $chunks")
        assertEquals(
            listOf(null, SegmentKind.Credits, null),
            chunks.map { it.kind },
        )
    }

    @Test
    fun `a title with no segments is one continuous piece`() {
        val chunks = seekChunks(600.0, emptyList())

        assertEquals(1, chunks.size)
        assertEquals(null, chunks.single().kind)
        assertEquals(600.0, chunks.single().lengthSeconds)
    }
}
