package com.coveninja.cove.ui.pages.common

import androidx.compose.ui.unit.Dp
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
    fun `size class follows window width across both breakpoints`() {
        // Mutation check: widening MediumWidthBreakpoint to 601.dp fails the 600.dp case,
        // and narrowing ExpandedWidthBreakpoint to 839.dp fails the 839.dp case.
        assertEquals(WindowSizeClass.Compact, viewport(360.dp).sizeClass)
        assertEquals(WindowSizeClass.Compact, viewport(599.dp).sizeClass)
        assertEquals(WindowSizeClass.Medium, viewport(600.dp).sizeClass)
        assertEquals(WindowSizeClass.Medium, viewport(839.dp).sizeClass)
        assertEquals(WindowSizeClass.Expanded, viewport(840.dp).sizeClass)
        assertEquals(WindowSizeClass.Expanded, viewport(1280.dp).sizeClass)
    }

    @Test
    fun `gutter widens with the size class and never returns to zero on a phone`() {
        // The bug this replaces: mobile was given a 0.dp gutter, so content that honoured
        // it sat flush against the screen while neighbours that hardcoded padding did not.
        // Mutation check: returning 0.dp for Compact fails the first assertion.
        assertEquals(16.dp, viewport(360.dp).gutter)
        assertEquals(20.dp, viewport(700.dp).gutter)
        assertEquals(24.dp, viewport(1280.dp).gutter)
    }

    @Test
    fun `desktop keeps the gutter it had before the size class existed`() {
        // Guards the refactor itself: a desktop window must be a visual no-op, so its
        // gutter has to stay at the 24.dp the old constant provided.
        // Mutation check: changing ExpandedGutter to 20.dp fails here.
        assertEquals(24.dp, PageViewport(1280.dp, 800.dp, hasBottomNavigation = false).gutter)
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

    private fun viewport(width: Dp) =
        PageViewport(width = width, height = 900.dp, hasBottomNavigation = true)
}
