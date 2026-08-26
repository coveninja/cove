package com.coveninja.cove.ui.components.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a drag over the picture means.
 *
 * The classification is the whole of the gesture design: get it wrong and a swipe meant for
 * the volume seeks instead, which on a stream costs a rebuffer rather than a moment.
 */
class SurfaceGesturesTest {

    private val width = 1000f
    private val height = 500f

    // Mutation check: returning a mode unconditionally makes this fail.
    @Test
    fun `a drag shorter than the slop means nothing yet`() {
        assertNull(
            classifySurfaceDrag(
                totalDx = 10f,
                totalDy = 10f,
                startX = 500f,
                width = width,
                slop = SURFACE_DRAG_SLOP,
            ),
        )
    }

    // Mutation check: comparing the wrong axis sends this to Volume or Brightness.
    @Test
    fun `a mostly horizontal drag seeks, from either side`() {
        assertEquals(
            SurfaceDrag.Seek,
            classifySurfaceDrag(200f, 20f, startX = 10f, width = width, slop = SURFACE_DRAG_SLOP),
        )
        assertEquals(
            SurfaceDrag.Seek,
            classifySurfaceDrag(-200f, 20f, startX = 900f, width = width, slop = SURFACE_DRAG_SLOP),
        )
    }

    // Mutation check: swapping the halves fails this pair, and only this pair.
    @Test
    fun `a vertical drag is brightness on the left and volume on the right`() {
        assertEquals(
            SurfaceDrag.Brightness,
            classifySurfaceDrag(10f, -200f, startX = 100f, width = width, slop = SURFACE_DRAG_SLOP),
        )
        assertEquals(
            SurfaceDrag.Volume,
            classifySurfaceDrag(10f, -200f, startX = 900f, width = width, slop = SURFACE_DRAG_SLOP),
        )
    }

    /**
     * The side is decided by where the finger started, not where it is now — otherwise a
     * gesture that wandered across the midline would change what it was adjusting halfway
     * through, which is unusable.
     */
    @Test
    fun `a vertical drag that crosses the midline keeps the side it started on`() {
        assertEquals(
            SurfaceDrag.Brightness,
            classifySurfaceDrag(
                totalDx = 600f - 100f,
                totalDy = -700f,
                startX = 100f,
                width = width,
                slop = SURFACE_DRAG_SLOP,
            ),
        )
    }

    // Mutation check: flipping the comparison to `>` sends an exact diagonal to a vertical mode.
    @Test
    fun `an exactly diagonal drag seeks rather than adjusting anything`() {
        assertEquals(
            SurfaceDrag.Seek,
            classifySurfaceDrag(200f, 200f, startX = 100f, width = width, slop = SURFACE_DRAG_SLOP),
        )
    }

    // Mutation check: dropping the sign flip makes an upward swipe turn the volume down.
    @Test
    fun `dragging up increases and dragging down decreases`() {
        assertTrue(verticalDragFraction(-250f, height) > 0f)
        assertTrue(verticalDragFraction(250f, height) < 0f)
    }

    @Test
    fun `a drag of the full height covers the whole range`() {
        assertEquals(1f, verticalDragFraction(-height, height))
    }

    // A zero-sized surface is what a layout reports for one frame; dividing by it is a crash.
    @Test
    fun `a surface with no size yields no movement`() {
        assertEquals(0f, verticalDragFraction(100f, 0f))
        assertEquals(0.0, scrubSecondsFor(100f, 0f, durationSeconds = 3600.0))
        assertEquals(0.0, scrubSecondsFor(100f, width, durationSeconds = 0.0))
    }

    /**
     * Ten per cent of the running time, so the gesture means the same thing on a sitcom as on
     * a feature — a fixed span would cross a whole episode of the former in one swipe.
     */
    @Test
    fun `scrubbing scales with the running time`() {
        val hour = scrubSecondsFor(width, width, durationSeconds = 3600.0)
        val twoHours = scrubSecondsFor(width, width, durationSeconds = 7200.0)
        assertEquals(360.0, hour)
        assertEquals(600.0, twoHours)
    }

    // Mutation check: removing either bound breaks one of these two.
    @Test
    fun `the scrub span is bounded at both ends`() {
        // A three-minute clip: ten per cent would be eighteen seconds of unusable precision.
        assertEquals(60.0, scrubSecondsFor(width, width, durationSeconds = 180.0))
        // A ten-hour recording: ten per cent would be an hour in one swipe.
        assertEquals(600.0, scrubSecondsFor(width, width, durationSeconds = 36_000.0))
    }

    @Test
    fun `dragging left scrubs backwards`() {
        assertTrue(scrubSecondsFor(-width, width, durationSeconds = 3600.0) < 0.0)
    }
}
