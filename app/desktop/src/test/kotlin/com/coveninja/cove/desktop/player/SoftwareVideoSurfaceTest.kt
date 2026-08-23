package com.coveninja.cove.desktop.player

import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SoftwareVideoSurfaceTest {

    @Test
    fun `mpv bgr0 pixels are visible to Skia without a conversion image`() {
        SoftwareVideoSurface().use { surface ->
            surface.render(width = 2, height = 1) { pixels, stride ->
                assertTrue(stride >= 2 * Int.SIZE_BYTES)
                // bgr0 bytes for #123456. OPAQUE makes Skia supply the alpha.
                pixels.setByte(0, 0x56.toByte())
                pixels.setByte(1, 0x34.toByte())
                pixels.setByte(2, 0x12.toByte())
                pixels.setByte(3, 0)
            }

            assertEquals(0x123456, surface.colorAt(0, 0) and 0x00ffffff)
        }
    }

    @Test
    fun `steady 4k playback reuses one bounded bitmap`() {
        SoftwareVideoSurface().use { surface ->
            repeat(120) {
                surface.render(width = 3840, height = 2160) { _, _ -> }
            }

            assertEquals(1, surface.allocationCount)
            assertEquals(IntSize(3840, 2304), surface.capacitySize)
            assertEquals(IntSize(3840, 2160), surface.contentSize)
        }
    }

    @Test
    fun `smaller frames reuse capacity and growth is geometric`() {
        SoftwareVideoSurface().use { surface ->
            surface.render(width = 1280, height = 720) { _, _ -> }
            assertEquals(IntSize(1280, 768), surface.capacitySize)

            surface.render(width = 960, height = 540) { _, _ -> }
            assertEquals(1, surface.allocationCount)

            surface.render(width = 1300, height = 800) { _, _ -> }
            assertEquals(2, surface.allocationCount)
            assertEquals(IntSize(1920, 1152), surface.capacitySize)
        }
    }

    @Test
    fun `capacity rounding is overflow checked and quantized`() {
        assertEquals(256, grownCapacity(current = 0, required = 1))
        assertEquals(1536, grownCapacity(current = 1024, required = 1025))
        assertEquals(3840, grownCapacity(current = 2560, required = 3440))
    }
}
