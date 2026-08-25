package com.coveninja.cove.ui.onboarding

import com.coveninja.cove.ui.CoveColors
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The welcome backdrop: a uniform grid of card-shaped tiles scrolling upward on a loop.
 *
 * Two of its three properties are the kind that only misbehave in motion, which is precisely
 * where this repo has no coverage at all — there are no Compose UI tests, and a screenshot of a
 * scrolling loop proves nothing about the frame where it wraps. So the geometry and the colour
 * pattern are pure functions, and the awkward instants are asserted directly.
 *
 * Every assertion was mutation-checked before its comment was written.
 */
class OnboardingBackdropTest {

    private val targetTileWidth = 104f
    private val gap = 14f

    private fun grid(width: Float = 1280f, height: Float = 800f) =
        tileGridFor(width, height, targetTileWidth, gap)

    // ---- the grid ---------------------------------------------------------------------------

    // The tiles are meant to read as media cards, and every poster in Cove is 2:3.
    @Test
    fun `tiles carry a media card's proportions`() {
        val grid = grid()

        assertEquals(1.5f, grid.tileHeight / grid.tileWidth, 0.001f)
    }

    // The grid is a backdrop; stopping short of the screen edge reads as a mistake rather than
    // as a margin.
    @Test
    fun `the grid reaches both edges exactly`() {
        val width = 1280f
        val grid = grid(width = width)

        val used = grid.tileWidth * grid.columns + grid.gap * (grid.columns - 1)
        assertEquals(width, used, 0.01f)
    }

    // The defect this is written against: rows are drawn from a scroll fraction that runs to
    // just under 1, so at the end of every cycle the whole grid has lifted by nearly a full row.
    // With too few rows an empty band flickers along the bottom edge once per row — obvious in
    // motion, invisible in a screenshot, and untestable by this repo's Compose coverage, which
    // does not exist.
    //
    // Swept across a whole row of viewport heights rather than checked at one size, because the
    // failure is not uniform: it needs a height that is an exact multiple of the row pitch (which
    // breaks a single spare row) or one whose remainder exceeds the tile height (which breaks a
    // truncating row count). A fixed 1280×800 viewport happens to satisfy both bad versions,
    // so the first draft of this test passed against them.
    @Test
    fun `the bottom edge stays covered at every viewport height`() {
        val pitch = grid().rowPitch

        (0..40).forEach { step ->
            val height = pitch * (3f + step / 40f)
            val grid = tileGridFor(1280f, height, targetTileWidth, gap)

            // The instant before a wrap, when the grid has lifted furthest.
            val lastRowBottom = (grid.rowCount - 1 - 0.999f) * grid.rowPitch + grid.tileHeight
            assertTrue(
                lastRowBottom >= height,
                "at height $height the bottom row ends at $lastRowBottom",
            )
        }
    }

    // The same has to hold on a phone, where the viewport is a different shape entirely and the
    // column count — and so the pitch everything else derives from — is different too.
    @Test
    fun `the bottom edge stays covered on a phone-shaped viewport`() {
        val pitch = grid(width = 360f, height = 780f).rowPitch

        (0..40).forEach { step ->
            val height = pitch * (3f + step / 40f)
            val grid = tileGridFor(360f, height, targetTileWidth, gap)

            val lastRowBottom = (grid.rowCount - 1 - 0.999f) * grid.rowPitch + grid.tileHeight
            assertTrue(
                lastRowBottom >= height,
                "at height $height the bottom row ends at $lastRowBottom",
            )
        }
    }

    @Test
    fun `a viewport narrower than one tile still draws a column`() {
        assertTrue(grid(width = 40f).columns >= 1)
    }

    @Test
    fun `a viewport with no area is rejected outright`() {
        assertFailsWith<IllegalArgumentException> { tileGridFor(0f, 800f, targetTileWidth, gap) }
        assertFailsWith<IllegalArgumentException> { tileGridFor(1280f, 0f, targetTileWidth, gap) }
        assertFailsWith<IllegalArgumentException> { tileGridFor(1280f, 800f, 0f, gap) }
    }

    // ---- the loop ---------------------------------------------------------------------------

    // The claim the whole design rests on, and the reason the period is one named constant used
    // in two places rather than two numbers that happen to agree.
    //
    // The animation carries the scroll in rows and wraps it with `% BackdropScrollPeriodRows`,
    // then colours row `floor(scrolled) + r`. So the frame after a wrap draws the same on-screen
    // rows the frame before it did, with their absolute indices reduced by exactly that many
    // rows. It is invisible if and only if the colour pattern repeats on that same number.
    @Test
    fun `the colour pattern repeats on exactly the row the scroll wraps at`() {
        val grid = grid()

        (0 until grid.rowCount + 4).forEach { row ->
            (0 until grid.columns).forEach { column ->
                assertEquals(
                    tileColorIndex(row, column, PALETTE),
                    tileColorIndex(row + BackdropScrollPeriodRows, column, PALETTE),
                    "row $row column $column changes colour across the wrap",
                )
            }
        }
    }

    // The wrap has to be reached from below as well: `floor` of a scroll position just under the
    // period gives the last distinct row, and the next frame starts again from zero.
    @Test
    fun `rows either side of zero stay inside the pattern`() {
        (-3..3).forEach { row ->
            assertEquals(
                tileColorIndex(row, column = 2, paletteSize = PALETTE),
                tileColorIndex(row + BackdropScrollPeriodRows, column = 2, paletteSize = PALETTE),
                "row $row",
            )
        }
    }

    // Colours travel with the tiles rather than staying put while they move through. Keyed on
    // the absolute row for exactly that reason.
    @Test
    fun `each row is coloured differently from the one above it`() {
        val differing = (0 until 40).count { row ->
            (0 until 7).any { column ->
                tileColorIndex(row, column, PALETTE) != tileColorIndex(row + 1, column, PALETTE)
            }
        }

        assertTrue(differing >= 38, "only $differing of 40 row pairs differed")
    }

    @Test
    fun `each column is coloured differently from its neighbour`() {
        val differing = (0 until 40).count { column ->
            (0 until 7).any { row ->
                tileColorIndex(row, column, PALETTE) != tileColorIndex(row, column + 1, PALETTE)
            }
        }

        assertTrue(differing >= 38, "only $differing of 40 column pairs differed")
    }

    // A pattern that leaned on two or three of seven colours would read as a colour scheme
    // rather than as variety.
    @Test
    fun `the pattern uses the whole palette`() {
        val used = (0 until 60).flatMap { row ->
            (0 until 12).map { column -> tileColorIndex(row, column, PALETTE) }
        }.distinct()

        assertEquals(PALETTE, used.size, "only used $used of $PALETTE")
    }

    @Test
    fun `the colour index is always inside the palette`() {
        (-500 until 500).forEach { row ->
            (0 until 20).forEach { column ->
                val index = tileColorIndex(row, column, PALETTE)
                assertTrue(index in 0 until PALETTE, "row $row column $column gave $index")
            }
        }
    }

    @Test
    fun `an empty palette is rejected outright`() {
        assertFailsWith<IllegalArgumentException> { tileColorIndex(0, 0, 0) }
    }

    // The colour-pattern tests above deal only in indices, so on their own they would not notice
    // the field going back to a spread of unrelated hues. This is the assertion that the cards
    // are shades of one green.
    @Test
    fun `the tiles are shades of Cove's green`() {
        assertEquals(CoveColors.Seafoam.ramp, TilePalette)
        assertEquals(PALETTE, TilePalette.size, "the test's palette size drifted from the field's")
        TilePalette.forEach { shade ->
            assertTrue(shade.green > shade.red && shade.green > shade.blue, "$shade is not green")
        }
    }

    // ---- the highlight ---------------------------------------------------------------------

    @Test
    fun `a tile beyond the radius is not lit at all`() {
        assertEquals(0f, backdropGlow(distance = 100f, radius = 100f))
        assertEquals(0f, backdropGlow(distance = 400f, radius = 100f))
    }

    @Test
    fun `a tile under the pointer is fully lit`() {
        assertEquals(1f, backdropGlow(distance = 0f, radius = 100f))
    }

    // Light falls off; it does not step. Monotonicity is what stops the highlight reading as a
    // hard-edged disc following the cursor.
    @Test
    fun `the glow falls off with distance`() {
        val samples = (0..10).map { backdropGlow(distance = it * 10f, radius = 100f) }

        samples.zipWithNext { nearer, further ->
            assertTrue(nearer >= further, "glow rose with distance: $samples")
        }
    }

    // Smoothstep's whole point is a zero derivative at the edge, so a tile entering the radius
    // eases in instead of switching on. A linear ramp reads 0.1 here; smoothstep is well under.
    @Test
    fun `a tile entering the radius eases in rather than switching on`() {
        val justInside = backdropGlow(distance = 90f, radius = 100f)

        assertTrue(justInside < 0.05f, "was: $justInside")
    }

    // A degenerate radius must not divide by zero. Compose would take the resulting NaN into an
    // alpha, which throws on some backends and silently draws nothing on others.
    @Test
    fun `a zero radius yields no glow rather than NaN`() {
        assertEquals(0f, backdropGlow(distance = 0f, radius = 0f))
    }

    private companion object {
        /** Mirrors the tile palette's length — the green ramp in `CoveColors.Seafoam`. */
        const val PALETTE = 6
    }
}
