package com.coveninja.cove.ui.pages.common

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageLayoutTest {
    @Test
    fun `portrait phone reserves the floating navigation without shrinking its hero`() {
        val viewport = PageViewport(
            width = 411.dp,
            height = 914.dp,
            hasBottomNavigation = true,
        )

        assertEquals(64.dp, viewport.bottomNavigationClearance)
        assertFalse(viewport.isShort)
        assertEquals(460.dp, viewport.heroMetrics(411.dp).height)
        assertTrue(viewport.heroMetrics(411.dp).compact)
    }

    @Test
    fun `landscape phone caps hero height from the short edge`() {
        val viewport = PageViewport(
            width = 914.dp,
            height = 411.dp,
            hasBottomNavigation = true,
        )
        val hero = viewport.heroMetrics(914.dp)

        assertTrue(hero.short)
        assertTrue(hero.compact)
        assertTrue(hero.height < 300.dp)
        assertTrue(hero.height <= viewport.height * 0.72f)
    }

    @Test
    fun `tablet and desktop keep expanded hero sizing`() {
        val tablet = PageViewport(1280.dp, 800.dp, hasBottomNavigation = true)
        val desktop = PageViewport(1280.dp, 800.dp, hasBottomNavigation = false)

        assertEquals(560.dp, tablet.heroMetrics(1280.dp).height)
        assertEquals(64.dp, tablet.bottomNavigationClearance)
        assertEquals(560.dp, desktop.heroMetrics(1280.dp).height)
        assertEquals(0.dp, desktop.bottomNavigationClearance)
    }
}
