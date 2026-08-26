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
        assertEquals(true, skips(controlsVisible = false, skipAvailable = true))
        assertEquals(false, skips(controlsVisible = true, skipAvailable = true))
    }

    @Test
    fun `with nothing to skip centre is left alone`() {
        assertEquals(false, skips(controlsVisible = false, skipAvailable = false))
    }

    // The panel is a list of rows to walk, and it is the only thing on screen that can be
    // walked. Left seeking out from under it would move the picture the viewer is using to
    // judge the very setting they are changing — subtitle delay is unreadable otherwise.
    @Test
    fun `an open panel takes every arrow even with the bar gone`() {
        TvDirection.entries.forEach { direction ->
            assertEquals(
                TvPlayerArrowOutcome.Navigate,
                outcome(direction, panelOpen = true),
                "$direction should navigate the panel",
            )
        }
    }

    // The bar earns the arrows only once it holds focus; the panel does not have to wait,
    // because opening it is what hides the bar and there is nothing else the arrows could mean.
    @Test
    fun `the panel does not wait to hold focus the way the bar does`() {
        assertEquals(
            TvPlayerArrowOutcome.Seek,
            outcome(TvDirection.Right, controlsVisible = true, barHasFocus = false),
        )
        assertEquals(
            TvPlayerArrowOutcome.Navigate,
            outcome(
                TvDirection.Right,
                controlsVisible = true,
                barHasFocus = false,
                panelOpen = true,
            ),
        )
    }

    // Every row in the panel is activated with centre. An intro starting underneath must not
    // turn the next press into a skip instead of the track being selected.
    @Test
    fun `centre never skips a segment while the panel is open`() {
        assertEquals(
            false,
            skips(controlsVisible = false, skipAvailable = true, panelOpen = true),
        )
    }

    private fun skips(
        controlsVisible: Boolean,
        skipAvailable: Boolean,
        panelOpen: Boolean = false,
    ): Boolean = tvSelectSkipsSegment(controlsVisible, skipAvailable, panelOpen)

    private fun outcome(
        direction: TvDirection,
        controlsVisible: Boolean = false,
        barHasFocus: Boolean = false,
        panelOpen: Boolean = false,
    ): TvPlayerArrowOutcome =
        tvPlayerArrowOutcome(direction, controlsVisible, barHasFocus, panelOpen)
}
