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
    // Mutation applied to verify: returned true for a touch on hidden controls → test failed.
    @Test
    fun `a touch on a bare picture only summons the controls`() {
        assertFalse(tapTogglesPause(fromTouch = true, controlsShown = false))
    }

    // Mutation applied to verify: returned false whenever the tap came from a touch → test
    // failed, leaving a phone unable to pause by tapping at all.
    @Test
    fun `a touch with the controls up pauses like anywhere else`() {
        assertTrue(tapTogglesPause(fromTouch = true, controlsShown = true))
    }

    // A pointer can always wake the controls by moving, so the click stays on transport.
    // Mutation applied to verify: dropped the fromTouch term → the hidden case failed, which
    // is a desktop player that cannot pause by clicking the picture.
    @Test
    fun `a click pauses whatever the controls are doing`() {
        assertTrue(tapTogglesPause(fromTouch = false, controlsShown = true))
        assertTrue(tapTogglesPause(fromTouch = false, controlsShown = false))
    }
}
