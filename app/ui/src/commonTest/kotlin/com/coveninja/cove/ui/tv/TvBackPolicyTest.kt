package com.coveninja.cove.ui.tv

import com.coveninja.cove.ui.components.navigation.NavDestination
import com.coveninja.cove.ui.tv.focus.TvDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class TvBackPolicyTest {

    @Test
    fun `back closes the topmost layer first`() {
        assertEquals(
            TvBackAction.ClosePlayback,
            action(playback = true, person = true, details = true, railFocused = false),
        )
        // A person is reached *from* a title and the title stays selected underneath, so the
        // person has to close first — otherwise Back from an actor would drop both and land on
        // the page, losing the title the viewer was reading about.
        assertEquals(
            TvBackAction.ClosePerson,
            action(person = true, details = true, railFocused = false),
        )
        assertEquals(
            TvBackAction.CloseDetails,
            action(details = true, railFocused = false),
        )
    }

    // The step the phone does not have. Back from content lands in navigation rather than
    // jumping the viewer somewhere else entirely, which is also what stops the next press
    // from being an accidental exit.
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
    @Test
    fun `leaving the app takes the whole walk outward`() {
        assertEquals(
            TvBackAction.None,
            action(destination = NavDestination.Home, railFocused = true),
        )
    }

    // Left off the page's edge is the one move focus search cannot make on its own — the rail
    // is a sibling subtree over the page's gutter, not a neighbour inside the page — so it
    // reported failure and focus was left nowhere at all.
    @Test
    fun `left off the edge of a page hands focus to the rail`() {
        assertEquals(
            true,
            railTakesFocusAfterFailedMove(
                direction = TvDirection.Left,
                pageReachable = true,
                railFocused = false,
            ),
        )
    }

    @Test
    fun `the other three directions stop at the edge as they should`() {
        listOf(TvDirection.Right, TvDirection.Up, TvDirection.Down).forEach { direction ->
            assertEquals(
                false,
                railTakesFocusAfterFailedMove(
                    direction = direction,
                    pageReachable = true,
                    railFocused = false,
                ),
                "$direction should not reach for the rail",
            )
        }
    }

    // Repeating Left inside navigation would otherwise keep re-requesting the selected
    // destination's button, pinning focus there however far the viewer had walked down the rail.
    @Test
    fun `left inside the rail does not re-grab the selected destination`() {
        assertEquals(
            false,
            railTakesFocusAfterFailedMove(
                direction = TvDirection.Left,
                pageReachable = true,
                railFocused = true,
            ),
        )
    }

    // While a title or the player owns the screen the rail is behind them and unreachable by
    // design; Back is the way out. Reaching for it here would focus something invisible.
    @Test
    fun `left behind an open title does not reach the rail underneath`() {
        assertEquals(
            false,
            railTakesFocusAfterFailedMove(
                direction = TvDirection.Left,
                pageReachable = false,
                railFocused = false,
            ),
        )
    }

    // Closing a layer removes the node that held focus and Compose hands it nowhere, which on
    // a device with no pointer leaves the whole interface dead. The page has to reclaim it.
    @Test
    fun `the page reclaims focus only after a layer that was open has closed`() {
        assertEquals(
            true,
            pageReclaimsFocus(overlayOpen = false, overlayHasBeenOpen = true),
        )
        // Startup: nothing has ever covered the page, and Home is already focusing its hero.
        assertEquals(
            false,
            pageReclaimsFocus(overlayOpen = false, overlayHasBeenOpen = false),
        )
    }

    @Test
    fun `nothing is reclaimed while a layer is still on screen`() {
        assertEquals(
            false,
            pageReclaimsFocus(overlayOpen = true, overlayHasBeenOpen = true),
        )
    }

    private fun action(
        playback: Boolean = false,
        person: Boolean = false,
        details: Boolean = false,
        destination: NavDestination = NavDestination.Home,
        railFocused: Boolean = false,
    ): TvBackAction = resolveTvBackAction(
        fullscreenPlayback = playback,
        personOpen = person,
        detailsOpen = details,
        destination = destination,
        railFocused = railFocused,
    )
}
