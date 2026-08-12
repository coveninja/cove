package com.coveninja.cove.ui

import com.coveninja.cove.ui.components.navigation.NavDestination
import kotlin.test.Test
import kotlin.test.assertEquals

class MobileBackPolicyTest {
    @Test
    fun `back closes the topmost app-owned layer first`() {
        assertEquals(
            MobileBackAction.CollapseExtra,
            action(playback = true, extra = true, person = true, media = true, search = true),
        )
        assertEquals(
            MobileBackAction.ClosePlayback,
            action(playback = true, person = true, media = true, search = true),
        )
        assertEquals(
            MobileBackAction.ClosePerson,
            action(person = true, media = true, search = true),
        )
        assertEquals(
            MobileBackAction.CloseMedia,
            action(media = true, search = true),
        )
        assertEquals(MobileBackAction.CloseSearch, action(search = true))
    }

    @Test
    fun `back returns ordinary destinations home but lets profile own its drill down`() {
        assertEquals(
            MobileBackAction.GoHome,
            action(destination = NavDestination.Explore),
        )
        assertEquals(
            MobileBackAction.None,
            action(destination = NavDestination.Account),
        )
        assertEquals(
            MobileBackAction.None,
            action(destination = NavDestination.Home),
        )
    }

    private fun action(
        playback: Boolean = false,
        extra: Boolean = false,
        person: Boolean = false,
        media: Boolean = false,
        search: Boolean = false,
        destination: NavDestination = NavDestination.Search,
    ): MobileBackAction = resolveMobileBackAction(
        fullscreenPlayback = playback,
        fullscreenExtra = extra,
        personOverlay = person,
        mediaOverlay = media,
        searchMode = search,
        destination = destination,
    )
}
