package com.coveninja.cove.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * mpv composes the picture into a buffer of exactly the size it is given, so the
 * render size has to track the surface exactly. A wrong height means mpv fits a
 * 16:9 picture into a target of some other shape and adds letterboxing of its
 * own, which no scaling mode can undo.
 *
 * Constructing the player touches no native code — start() is where libmpv is
 * loaded — so this runs anywhere.
 */
class SoftwarePlayerResizeTest {

    private fun player() = MpvSoftwarePlayer { }

    // The original used a single `w != x || h != y` expression, whose
    // short-circuit meant a changed width skipped the height store entirely.
    @Test
    fun `changing both dimensions stores both`() {
        val player = player()
        player.resize(1280, 720)
        assertEquals(1280 to 720, player.renderSize)

        player.resize(3440, 1440)

        assertEquals(3440 to 1440, player.renderSize)
    }

    // The case the short-circuit hid: width differs, height differs, and the
    // width comparison alone is enough to satisfy the condition.
    @Test
    fun `a width-only comparison still stores the new height`() {
        val player = player()
        player.resize(1920, 1080)

        player.resize(2560, 1080 + 1)

        assertEquals(2560 to 1081, player.renderSize)
    }

    @Test
    fun `degenerate sizes are clamped to something renderable`() {
        val player = player()

        player.resize(0, -10)

        assertEquals(1 to 1, player.renderSize)
    }

    @Test
    fun `absurd sizes are capped`() {
        val player = player()

        player.resize(100_000, 90_000)

        assertEquals(8192 to 8192, player.renderSize)
    }
}
