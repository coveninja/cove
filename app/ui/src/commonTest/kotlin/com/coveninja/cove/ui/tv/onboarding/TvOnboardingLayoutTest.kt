package com.coveninja.cove.ui.tv.onboarding

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The television onboarding layout, at the sizes real panels actually report.
 *
 * These exist because both numbers were constants first and both were wrong on a 1080p
 * television — 960×540 dp at density 2, which is the *smallest* viewport this shell serves
 * rather than an edge case. The wall overflowed its Row and was silently cropped, and nothing
 * in a 1280 dp desktop dev window showed it.
 *
 * Every assertion was mutation-checked before its comment was written.
 */
class TvOnboardingLayoutTest {

    /** What a 1080p television reports at density 2. The size that found the bug. */
    private val panelWidth = 960.dp
    private val gap = 16.dp
    private val posters = 5

    /** Horizontal padding the flow spends before the sidebar: overscan plus its own 24 dp. */
    private val chromeWidth = (48 + 24).dp * 2 + 48.dp

    // The regression this whole file exists for.
    @Test
    fun `the taste wall fits across a 1080p television`() {
        val available = panelWidth - tvSidebarWidthFor(panelWidth) - chromeWidth
        val poster = tvWallPosterWidth(available = available, gap = gap, count = posters)

        val used = poster * posters + gap * (posters - 1)
        assertTrue(used <= available, "wall needs $used but has $available")
    }

    // The same has to hold on the dev window, which is where the layout is actually looked at —
    // a fix that only works on the television would be invisible during design work.
    @Test
    fun `the taste wall fits in the desktop dev window`() {
        val width = 1280.dp
        val available = width - tvSidebarWidthFor(width) - (64 + 24).dp * 2 - 48.dp
        val poster = tvWallPosterWidth(available = available, gap = gap, count = posters)

        val used = poster * posters + gap * (posters - 1)
        assertTrue(used <= available, "wall needs $used but has $available")
    }

    // The lower clamp is deliberate and has to hold: a panel too narrow for five legible posters
    // gets five small ones, which someone can see and judge, rather than a Row that overflows
    // and is cropped with nothing on screen reporting it.
    @Test
    fun `an impossibly narrow panel still yields a usable poster`() {
        val poster = tvWallPosterWidth(available = 40.dp, gap = gap, count = posters)

        assertTrue(poster.value > 0f, "was: $poster")
    }

    @Test
    fun `a wall of no posters is rejected outright`() {
        assertFailsWith<IllegalArgumentException> {
            tvWallPosterWidth(available = 400.dp, gap = gap, count = 0)
        }
    }

    // The upper poster clamp is not about fitting — at 1920 dp there is room for 246 dp posters
    // and they would fit perfectly well. It is about a poster staying a poster: five cards a
    // quarter of the screen wide each read as a hero carousel, not as a row to choose from.
    @Test
    fun `a poster stops growing once it is big enough`() {
        val width = 1920.dp
        val available = width - tvSidebarWidthFor(width) - (64 + 24).dp * 2 - 48.dp

        val poster = tvWallPosterWidth(available = available, gap = gap, count = posters)

        assertTrue(poster <= 150.dp, "was: $poster")
    }

    @Test
    fun `the sidebar does not run away on a 4K panel`() {
        val width = 3840.dp

        assertTrue(tvSidebarWidthFor(width) <= width * 0.15f, "was: ${tvSidebarWidthFor(width)}")
    }

    @Test
    fun `the sidebar stays readable on a small panel`() {
        assertTrue(tvSidebarWidthFor(640.dp) >= 260.dp)
    }

    // The sidebar must never take so much that nothing is left — the guarantee that makes the
    // fit assertions above meaningful at every size rather than only at the two tested.
    @Test
    fun `the sidebar always leaves the step the larger share`() {
        listOf(640, 960, 1280, 1920, 3840).forEach { size ->
            val width = size.dp
            val sidebar = tvSidebarWidthFor(width)
            assertTrue(sidebar < width / 2, "sidebar $sidebar of $width")
        }
    }
}
