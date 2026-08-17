package com.coveninja.cove.ui.tv

import com.coveninja.cove.ui.components.navigation.NavDestination
import kotlin.test.Test
import kotlin.test.assertEquals

class TvBackPolicyTest {

    // Mutation applied to verify: moved the detailsOpen branch above fullscreenPlayback →
    // test failed, Back closed the sheet behind the player instead of the player.
    @Test
    fun `back closes the topmost layer first`() {
        assertEquals(
            TvBackAction.ClosePlayback,
            action(playback = true, details = true, railFocused = false),
        )
        assertEquals(
            TvBackAction.CloseDetails,
            action(details = true, railFocused = false),
        )
    }

    // The step the phone does not have. Back from content lands in navigation rather than
    // jumping the viewer somewhere else entirely, which is also what stops the next press
    // from being an accidental exit.
    // Mutation applied to verify: dropped the !railFocused branch → test failed, Back from
    // Explore content went straight Home.
    @Test
    fun `back out of content goes to the rail before it goes anywhere else`() {
        assertEquals(
            TvBackAction.FocusRail,
            action(destination = NavDestination.Explore, railFocused = false),
        )
        assertEquals(
            TvBackAction.FocusRail,
            action(destination = NavDestination.Home, railFocused = false),
        )
    }

    // Mutation applied to verify: returned None for every destination once the rail had focus
    // → test failed, Back from Explore's rail no longer reached Home.
    @Test
    fun `from the rail back returns home and only then leaves`() {
        assertEquals(
            TvBackAction.GoHome,
            action(destination = NavDestination.Explore, railFocused = true),
        )
        assertEquals(
            TvBackAction.GoHome,
            action(destination = NavDestination.Account, railFocused = true),
        )
    }

    // Only reachable from Home with navigation already focused — three deliberate presses from
    // anywhere in content. None is what hands Back to the system, which closes Cove; a
    // television remote's Back sits under the thumb and a one-press exit is a real annoyance.
    // Mutation applied to verify: made the Home case return GoHome → test failed, the app
    // could never be left at all.
    @Test
    fun `leaving the app takes the whole walk outward`() {
        assertEquals(
            TvBackAction.None,
            action(destination = NavDestination.Home, railFocused = true),
        )
    }

    private fun action(
        playback: Boolean = false,
        details: Boolean = false,
        destination: NavDestination = NavDestination.Home,
        railFocused: Boolean = false,
    ): TvBackAction = resolveTvBackAction(
        fullscreenPlayback = playback,
        detailsOpen = details,
        destination = destination,
        railFocused = railFocused,
    )
}
