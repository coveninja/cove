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

    // ── Per-piece fill, shared by the played and buffered layers ─────────────

    @Test
    fun `a piece fills in proportion to how far into it the mark is`() {
        val chunk = SeekChunk(startSeconds = 100.0, endSeconds = 200.0, kind = null)

        assertEquals(0.25f, chunk.fillFraction(125.0))
        assertEquals(0.5f, chunk.fillFraction(150.0))
    }

    @Test
    fun `a mark outside the piece clamps rather than running past it`() {
        val chunk = SeekChunk(startSeconds = 100.0, endSeconds = 200.0, kind = null)

        assertEquals(0f, chunk.fillFraction(0.0))
        assertEquals(1f, chunk.fillFraction(400.0))
    }

    // The buffered layer is fed a polled value that is briefly absent, and NaN
    // reaching fillMaxWidth is a crash rather than a wrong-looking bar.
    @Test
    fun `a non-finite mark fills nothing`() {
        val chunk = SeekChunk(startSeconds = 0.0, endSeconds = 100.0, kind = null)

        assertEquals(0f, chunk.fillFraction(Double.NaN))
    }

    // ── Chapters ─────────────────────────────────────────────────────────────

    private fun chapter(index: Int, start: Double, title: String = "") =
        com.coveninja.cove.ui.state.MediaChapter(index, title, start)

    // Chapters have a start and no end; the one you are in is the last one to have
    // begun. Searching for a range instead would find nothing.
    @Test
    fun `the current chapter is the last one to have started`() {
        val chapters = listOf(chapter(0, 0.0), chapter(1, 300.0), chapter(2, 900.0))

        assertEquals(1, chapterAt(500.0, chapters)?.index)
        assertEquals(2, chapterAt(1200.0, chapters)?.index)
    }

    @Test
    fun `landing exactly on a boundary is inside the new chapter`() {
        val chapters = listOf(chapter(0, 0.0), chapter(1, 300.0))

        assertEquals(1, chapterAt(300.0, chapters)?.index)
    }

    // A tick on the first pixel is indistinguishable from the end cap of the bar.
    @Test
    fun `a chapter starting at zero draws no mark`() {
        val marks = chapterMarks(
            chapters = listOf(chapter(0, 0.0), chapter(1, 300.0)),
            durationSeconds = 600.0,
        )

        assertEquals(listOf(0.5f), marks)
    }

    @Test
    fun `a chapter at or past the end draws no mark`() {
        val marks = chapterMarks(
            chapters = listOf(chapter(0, 600.0), chapter(1, 900.0)),
            durationSeconds = 600.0,
        )

        assertTrue(marks.isEmpty(), "was: $marks")
    }

    @Test
    fun `no duration yields no chapter marks`() {
        assertTrue(chapterMarks(listOf(chapter(0, 30.0)), durationSeconds = 0.0).isEmpty())
    }

    // Most files have none, and the ticks are the only thing that says so.
    @Test
    fun `a file without chapters is unmarked and has no current chapter`() {
        assertTrue(chapterMarks(emptyList(), durationSeconds = 600.0).isEmpty())
        assertEquals(null, chapterAt(120.0, emptyList()))
    }
}
