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

    @Test
    fun `a picture smaller than its surface renders at its own size when the GPU stretches it`() {
        val player = MpvSoftwarePlayer(upscaleOnGpu = true) { }
        player.resize(2560, 1600)

        player.videoSizeChanged(1920, 1080)

        // The panel's shape, at the film's resolution: mpv still letterboxes exactly as it would
        // at full size, and the surface stretches the whole frame uniformly.
        assertEquals(1920 to 1200, player.renderSize)
    }

    @Test
    fun `the surface size stands without a GPU to stretch or in a mode that crops`() {
        val software = MpvSoftwarePlayer(upscaleOnGpu = false) { }
        software.resize(2560, 1600)
        software.videoSizeChanged(1920, 1080)
        assertEquals(2560 to 1600, software.renderSize)

        // Fill crops the picture to the surface, so it is enlarged past its own size anyway.
        val fill = MpvSoftwarePlayer(upscaleOnGpu = true) { }
        fill.resize(2560, 1600)
        fill.videoSizeChanged(1920, 1080)
        fill.setScaling(keepAspect = true, panscan = 1.0, zoom = 0.0)
        assertEquals(2560 to 1600, fill.renderSize)

        fill.setScaling(keepAspect = true, panscan = 0.0, zoom = 0.0)
        assertEquals(1920 to 1200, fill.renderSize)
    }

    @Test
    fun `render size follows the picture and the surface`() {
        // 1080p on a 4K screen: a quarter of the pixels for mpv to fill.
        assertEquals(1920 to 1080, softwareRenderSize(3840, 2160, 1920, 1080, mayRenderSmaller = true))
        // 720p on a 4K screen: never fewer than 1080 lines, for the subtitles' sake.
        assertEquals(1920 to 1080, softwareRenderSize(3840, 2160, 1280, 720, mayRenderSmaller = true))
        // A scope film on an ultrawide: held at 1080 lines too.
        assertEquals(2580 to 1080, softwareRenderSize(3440, 1440, 1920, 800, mayRenderSmaller = true))
        // A surface under 1080 lines is rendered at its own size whatever plays in it.
        assertEquals(1280 to 720, softwareRenderSize(1280, 720, 640, 480, mayRenderSmaller = true))
        // Bigger pictures are still scaled down by mpv: a smaller frame is cheaper to hand over.
        assertEquals(2560 to 1440, softwareRenderSize(2560, 1440, 3840, 2160, mayRenderSmaller = true))
        // Until mpv knows the picture's size there is nothing to fit.
        assertEquals(2560 to 1600, softwareRenderSize(2560, 1600, 0, 0, mayRenderSmaller = true))
    }
}
