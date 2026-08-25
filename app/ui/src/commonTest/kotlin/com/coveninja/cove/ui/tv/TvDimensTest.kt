package com.coveninja.cove.ui.tv

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvDimensTest {

    /** 1080p at xhdpi, which is what almost every television reports. */
    private val television = tvDimensFor(width = 960.dp, height = 540.dp)

    // The grid is built by chunking results into rows this wide, so a column count that does
    // not actually fit produces a row that scrolls sideways when it is supposed to be a grid.
    @Test
    fun `every column fits inside the content area`() {
        val columns = television.posterColumns
        val used = television.posterWidth * columns + television.cardSpacing * (columns - 1)
        val available = television.width -
            television.railCollapsedWidth -
            television.overscanHorizontal * 2

        assertTrue(used <= available, "$columns columns need $used but only $available is free")
    }

    // One more column would not fit, or the grid is wasting a poster's width of screen.
    @Test
    fun `the grid uses every column that fits`() {
        val columns = television.posterColumns
        val withOneMore = television.posterWidth * (columns + 1) +
            television.cardSpacing * columns
        val available = television.width -
            television.railCollapsedWidth -
            television.overscanHorizontal * 2

        assertTrue(withOneMore > available, "another column would still have fitted")
    }

    // A narrow window is the desktop harness, not a television, and the floor is what stops it
    // resolving to a single enormous poster per row.
    @Test
    fun `a viewport too narrow to matter still gets a grid`() {
        assertEquals(3, tvDimensFor(width = 320.dp, height = 240.dp).posterColumns)
    }

    // Overscan is a fraction of the panel, but a fraction of a very large one runs away with
    // the screen and a fraction of a small one stops protecting anything.
    @Test
    fun `overscan stays within its bounds at any size`() {
        listOf(320.dp, 960.dp, 3840.dp).forEach { width ->
            val dimens = tvDimensFor(width = width, height = width * 9f / 16f)
            assertTrue(
                dimens.overscanHorizontal >= 24.dp && dimens.overscanHorizontal <= 64.dp,
                "overscan ${dimens.overscanHorizontal} out of bounds at $width",
            )
        }
    }
}
