package com.coveninja.cove.ui.components.insights

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [waveAt] is the arithmetic behind every staggered reveal on the insights page — twelve
 * month columns, twenty-four clock spokes, three hundred and seventy-one heatmap cells. It
 * is the one piece of that animation that can be wrong in a way a build cannot catch, so it
 * is the piece worth pinning down.
 */
class ChartCommonTest {

    @Test
    fun `every element is complete once the drive finishes`() {
        repeat(12) { index ->
            assertEquals(1f, waveAt(progress = 1f, index = index, count = 12))
        }
    }

    @Test
    fun `nothing has started before the drive does`() {
        repeat(12) { index ->
            assertEquals(0f, waveAt(progress = 0f, index = index, count = 12))
        }
    }

    @Test
    fun `the first element leads and the last trails`() {
        val early = waveAt(progress = 0.3f, index = 0, count = 12)
        val late = waveAt(progress = 0.3f, index = 11, count = 12)

        assertTrue(early > late, "element 0 should be ahead of element 11 mid-drive")
        assertTrue(early > 0f, "the first element starts with the drive")
        assertEquals(0f, late, "the last element has not started a third of the way in")
    }

    @Test
    fun `a zero spread moves everything as one`() {
        assertEquals(0.4f, waveAt(progress = 0.4f, index = 0, count = 12, spread = 0f))
        assertEquals(0.4f, waveAt(progress = 0.4f, index = 11, count = 12, spread = 0f))
    }

    @Test
    fun `a single element chart does not divide by zero`() {
        assertEquals(0.5f, waveAt(progress = 0.5f, index = 0, count = 1))
        assertEquals(0.5f, waveAt(progress = 0.5f, index = 0, count = 0))
    }
}
