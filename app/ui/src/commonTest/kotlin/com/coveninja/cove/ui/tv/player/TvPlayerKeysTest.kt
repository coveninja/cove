package com.coveninja.cove.ui.tv.player

import com.coveninja.cove.ui.tv.focus.TvDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class TvPlayerKeysTest {

    @Test
    fun `with the controls away left and right move through the film`() {
        assertEquals(TvPlayerArrowOutcome.Seek, outcome(TvDirection.Left))
        assertEquals(TvPlayerArrowOutcome.Seek, outcome(TvDirection.Right))
    }

    // Up and down have nothing to steer while the bar is gone, so they summon it. Volume is
    // deliberately not bound here: a television's own remote owns volume, and a viewer who
    // presses up expecting the controls is not expecting the sound to change.
    @Test
    fun `with the controls away up and down bring them back`() {
        assertEquals(TvPlayerArrowOutcome.RevealControls, outcome(TvDirection.Up))
        assertEquals(TvPlayerArrowOutcome.RevealControls, outcome(TvDirection.Down))
    }

    // The window this closes: the bar has appeared but focus is still on the player behind it.
    // Checking visibility alone let a second press fall through to the page's focus search and
    // walk focus off the video entirely — the bug this condition was written for.
    @Test
    fun `a visible bar that has not taken focus yet still seeks`() {
        assertEquals(
            TvPlayerArrowOutcome.Seek,
            outcome(TvDirection.Right, controlsVisible = true, barHasFocus = false),
        )
        assertEquals(
            TvPlayerArrowOutcome.RevealControls,
            outcome(TvDirection.Up, controlsVisible = true, barHasFocus = false),
        )
    }

    @Test
    fun `once the bar holds focus every arrow belongs to it`() {
        TvDirection.entries.forEach { direction ->
            assertEquals(
                TvPlayerArrowOutcome.Navigate,
                outcome(direction, controlsVisible = true, barHasFocus = true),
                "$direction should navigate the control bar",
            )
        }
    }

    // The skip hint is not focusable — it appears mid-film and grabbing focus from the picture
    // would be worse than not offering the skip — so centre has to carry it while it is up.
    @Test
    fun `centre skips a segment only while the controls are away`() {
        assertEquals(true, tvSelectSkipsSegment(controlsVisible = false, skipAvailable = true))
        assertEquals(false, tvSelectSkipsSegment(controlsVisible = true, skipAvailable = true))
    }

    @Test
    fun `with nothing to skip centre is left alone`() {
        assertEquals(false, tvSelectSkipsSegment(controlsVisible = false, skipAvailable = false))
    }

    private fun outcome(
        direction: TvDirection,
        controlsVisible: Boolean = false,
        barHasFocus: Boolean = false,
    ): TvPlayerArrowOutcome = tvPlayerArrowOutcome(direction, controlsVisible, barHasFocus)
}
