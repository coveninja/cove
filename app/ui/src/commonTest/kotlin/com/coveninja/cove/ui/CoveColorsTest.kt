package com.coveninja.cove.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Invariants the palette has to hold, as opposed to the exact hues, which are a design
 * choice and free to change.
 */
class CoveColorsTest {

    @Test
    fun `library category accents stay mutually distinguishable`() {
        // All five appear side by side as drag targets in the nav bar, so a duplicate makes
        // two drop targets indistinguishable rather than merely looking odd.
        // Mutation check: pointing Category.WatchLater at Status.Success fails this.
        val accents = listOf(
            CoveColors.Category.Watching,
            CoveColors.Category.WatchLater,
            CoveColors.Category.Finished,
            CoveColors.Category.Dropped,
            CoveColors.Category.NotInterested,
        )

        assertEquals(accents.size, accents.distinct().size)
    }

    @Test
    fun `player segment accents stay mutually distinguishable`() {
        // These sit adjacent on the seek bar; two matching segments read as one long segment.
        // Mutation check: pointing Segment.Preview at Segment.Credits fails this.
        val segments = listOf(
            CoveColors.Segment.Recap,
            CoveColors.Segment.Intro,
            CoveColors.Segment.Credits,
            CoveColors.Segment.Preview,
        )

        assertEquals(segments.size, segments.distinct().size)
    }

    @Test
    fun `one hue per meaning across categories and statuses`() {
        // A category and the status that means the same thing must not drift apart, or
        // "finished" is one green on the library page and a different green in a toast.
        // Mutation check: giving Category.Finished its own green fails this.
        assertEquals(CoveColors.Status.Success, CoveColors.Category.Finished)
        assertEquals(CoveColors.Status.Danger, CoveColors.Category.Dropped)
        assertEquals(CoveColors.Status.Info, CoveColors.Category.Watching)
        assertEquals(CoveColors.Status.Warning, CoveColors.Category.WatchLater)
    }

    @Test
    fun `the surface stack climbs`() {
        // The dark surfaces are separated by only a few points of lightness, and a card reads
        // as raised because each step is strictly lighter than the one under it. Equal steps
        // would flatten the stack with no error anywhere.
        // Mutation check: setting SurfaceHigh equal to Surface fails the strict ordering.
        val stack = listOf(
            CoveColors.Neutral.Background,
            CoveColors.Neutral.Surface,
            CoveColors.Neutral.SurfaceHigh,
            CoveColors.Neutral.SurfaceHighest,
        )

        stack.zipWithNext { lower, higher ->
            assertTrue(
                lower.lightness() < higher.lightness(),
                "expected $lower to be darker than $higher",
            )
        }
    }

    @Test
    fun `text outranks muted text which outranks the border`() {
        // Mutation check: swapping Muted and MutedDim fails the middle comparison.
        assertTrue(CoveColors.Neutral.Text.lightness() > CoveColors.Neutral.Muted.lightness())
        assertTrue(CoveColors.Neutral.Muted.lightness() > CoveColors.Neutral.MutedDim.lightness())
        assertTrue(CoveColors.Neutral.MutedDim.lightness() > CoveColors.Neutral.Border.lightness())
    }

    @Test
    fun `the android window background still matches the palette`() {
        // The one palette value duplicated outside Kotlin: Android applies
        // android:windowBackground from res/values/themes.xml before any Compose code runs,
        // so it cannot read this object. If this fails, the palette moved and
        // app/mobile/src/main/res/values/themes.xml has to move with it.
        // Mutation check: changing Neutral.Background fails this.
        assertEquals(Color(0xFF0A0A0A), CoveColors.Neutral.Background)
    }

    /** Greys only differ by channel here, so the red channel stands in for lightness. */
    private fun Color.lightness(): Float = red
}
