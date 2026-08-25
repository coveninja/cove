package com.coveninja.cove.ui.components.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a tap on the video does.
 *
 * The rule exists because a finger has no hover: the tap that summons the controls cannot
 * also be the tap that pauses, while a mouse keeps click-to-pause everywhere.
 */
class SurfaceTapsTest {

    // The reported bug: on a phone the only way to reach the controls also paused the film.
    @Test
    fun `a touch on a bare picture only summons the controls`() {
        assertFalse(tapTogglesPause(fromTouch = true, controlsShown = false))
    }

    @Test
    fun `a touch with the controls up pauses like anywhere else`() {
        assertTrue(tapTogglesPause(fromTouch = true, controlsShown = true))
    }

    // A pointer can always wake the controls by moving, so the click stays on transport.
    @Test
    fun `a click pauses whatever the controls are doing`() {
        assertTrue(tapTogglesPause(fromTouch = false, controlsShown = true))
        assertTrue(tapTogglesPause(fromTouch = false, controlsShown = false))
    }
}
