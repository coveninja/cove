package com.coveninja.cove.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
        val segments = listOf(
            CoveColors.Segment.Recap,
            CoveColors.Segment.Intro,
            CoveColors.Segment.Credits,
            CoveColors.Segment.Preview,
        )

        assertEquals(segments.size, segments.distinct().size)
    }

    @Test
    fun `insight chapter accents stay mutually distinguishable`() {
        // The five chapter rules are what tell a reader they have moved from one part of
        // the insights page to another. Two matching hues merge two chapters into one.
        val chapters = listOf(
            CoveColors.Insight.Year,
            CoveColors.Insight.Moments,
            CoveColors.Insight.Rhythm,
            CoveColors.Insight.Library,
            CoveColors.Insight.Taste,
        )

        assertEquals(chapters.size, chapters.distinct().size)
    }

    @Test
    fun `insight chapter accents sit at one weight`() {
        // Chapters are peers. One markedly brighter than the rest reads as the important
        // one, which is a claim about the content that the palette has no business making.
        val luminances = listOf(
            CoveColors.Insight.Year,
            CoveColors.Insight.Moments,
            CoveColors.Insight.Rhythm,
            CoveColors.Insight.Library,
            CoveColors.Insight.Taste,
        ).map { it.luminance() }

        assertTrue(
            luminances.max() - luminances.min() < 0.42f,
            "chapter accents span too wide a luminance range: $luminances",
        )
    }

    @Test
    fun `one hue per meaning across categories and statuses`() {
        // A category and the status that means the same thing must not drift apart, or
        // "finished" is one green on the library page and a different green in a toast.
        assertEquals(CoveColors.Status.Success, CoveColors.Category.Finished)
        assertEquals(CoveColors.Status.Danger, CoveColors.Category.Dropped)
        assertEquals(CoveColors.Status.Info, CoveColors.Category.Watching)
        assertEquals(CoveColors.Status.Warning, CoveColors.Category.WatchLater)
    }

    @Test
    fun `the green ramp climbs`() {
        // A ramp that does not climb is not a ramp. Anything reaching for "two steps lighter"
        // would silently get something darker, and the onboarding backdrop leans on the spread
        // for the sense of depth in its wall of cards.
        CoveColors.Seafoam.ramp.zipWithNext { darker, lighter ->
            assertTrue(
                darker.luminance() < lighter.luminance(),
                "expected $darker to be darker than $lighter",
            )
        }
    }

    @Test
    fun `the green ramp stays one hue family`() {
        // The point of the ramp is that six shades read as one colour. Green has to dominate in
        // every step, or a "shade" has drifted into being a different hue and the backdrop is
        // back to looking like a colour test.
        CoveColors.Seafoam.ramp.forEach { shade ->
            assertTrue(shade.green > shade.red, "$shade is not green-dominant")
            assertTrue(shade.green > shade.blue, "$shade is not green-dominant")
        }
    }

    @Test
    fun `the green ramp reuses the greens Cove already has`() {
        // Two steps are the app's existing greens rather than near-misses of them. New values a
        // few points away would put three almost-identical greens in the palette with nothing to
        // say which was which — and the backdrop would drift away from the brand as either moved.
        assertEquals(CoveColors.Brand.Accent, CoveColors.Seafoam.Bright)
        assertEquals(CoveColors.Segment.Credits, CoveColors.Seafoam.Mid)
    }

    @Test
    fun `the surface stack climbs`() {
        // The dark surfaces are separated by only a few points of lightness, and a card reads
        // as raised because each step is strictly lighter than the one under it. Equal steps
        // would flatten the stack with no error anywhere.
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
        assertEquals(Color(0xFF0A0A0A), CoveColors.Neutral.Background)
    }

    /** Greys only differ by channel here, so the red channel stands in for lightness. */
    private fun Color.lightness(): Float = red
}
