package com.coveninja.cove.ui.components.player

import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.state.MediaChapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How the control bar decides what to show.
 *
 * The bug this encodes: the bar had only ever asked how *wide* it was, and a phone is wide.
 * The player forces landscape on a handset, so it arrives 780–900dp across and took the
 * pointer layout every time — a hover-only volume readout on a screen that cannot hover.
 */
class ControlsLayoutTest {

    // Mutation check: dropping the pointerHover term makes this report a pointer layout.
    @Test
    fun `a phone in the player is wide and still a touch layout`() {
        // A 1080x2400 handset in the forced-landscape player, in dp.
        val layout = controlsLayout(873.dp, pointerHover = false)
        assertTrue(layout.touch)
        assertFalse(layout.narrow, "873dp has room for the full row")
    }

    // Mutation check: keying `narrow` off touch instead of width breaks this.
    @Test
    fun `a wide desktop window is neither narrow nor touch`() {
        val layout = controlsLayout(1400.dp, pointerHover = true)
        assertFalse(layout.touch)
        assertFalse(layout.narrow)
    }

    @Test
    fun `a small desktop window is narrow but still a pointer`() {
        val layout = controlsLayout(600.dp, pointerHover = true)
        assertFalse(layout.touch)
        assertTrue(layout.narrow)
    }

    /** A phone held upright for an inline video: both at once, and both have to be honoured. */
    @Test
    fun `a narrow touch screen reports both`() {
        val layout = controlsLayout(400.dp, pointerHover = false)
        assertTrue(layout.touch)
        assertTrue(layout.narrow)
    }

    // Mutation check: `<=` instead of `<` flips the boundary case.
    @Test
    fun `the threshold itself is not narrow`() {
        assertFalse(controlsLayout(COMPACT_CONTROLS_WIDTH, pointerHover = true).narrow)
        assertTrue(controlsLayout(COMPACT_CONTROLS_WIDTH - 1.dp, pointerHover = true).narrow)
    }
}

/**
 * Which chapter the playhead is in.
 *
 * mpv reports chapter starts and no ends, so the answer is the last one that has begun. A
 * containment test would need information that does not exist.
 */
class ChapterIndexTest {

    private val chapters = listOf(
        MediaChapter(index = 0, title = "Cold open", startSeconds = 0.0),
        MediaChapter(index = 1, title = "Titles", startSeconds = 60.0),
        MediaChapter(index = 2, title = "Act one", startSeconds = 120.0),
    )

    @Test
    fun `a position inside a chapter finds it`() {
        assertEquals(1, chapterIndexAt(90.0, chapters))
    }

    // Mutation check: using firstOrNull instead of lastOrNull returns 0 for everything.
    @Test
    fun `a position past the last start stays in the last chapter`() {
        assertEquals(2, chapterIndexAt(9_999.0, chapters))
    }

    @Test
    fun `a chapter boundary belongs to the chapter it opens`() {
        assertEquals(1, chapterIndexAt(60.0, chapters))
    }

    /** A file whose first chapter starts late — the space before it belongs to nothing. */
    @Test
    fun `a position before the first chapter is in none of them`() {
        assertNull(
            chapterIndexAt(
                positionSeconds = 5.0,
                chapters = listOf(MediaChapter(index = 0, title = "Later", startSeconds = 30.0)),
            ),
        )
    }

    @Test
    fun `a file with no chapters has no current one`() {
        assertNull(chapterIndexAt(42.0, emptyList()))
    }
}
