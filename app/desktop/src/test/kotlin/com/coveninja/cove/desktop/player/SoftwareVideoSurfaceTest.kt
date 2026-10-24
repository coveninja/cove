package com.coveninja.cove.desktop.player

import androidx.compose.ui.unit.IntSize
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `steady 4k playback reuses two bounded bitmaps`() {
        SoftwareVideoSurface().use { surface ->
            repeat(120) {
                surface.render(width = 3840, height = 2160) { _, _ -> }
            }

            assertEquals(2, surface.allocationCount)
            assertEquals(IntSize(3840, 2304), surface.capacitySize)
            assertEquals(IntSize(3840, 2160), surface.contentSize)
        }
    }

    @Test
    fun `smaller frames reuse capacity and growth is geometric`() {
        SoftwareVideoSurface().use { surface ->
            repeat(2) { surface.render(width = 1280, height = 720) { _, _ -> } }
            assertEquals(IntSize(1280, 768), surface.capacitySize)

            surface.render(width = 960, height = 540) { _, _ -> }
            assertEquals(2, surface.allocationCount)

            surface.render(width = 1300, height = 800) { _, _ -> }
            assertEquals(3, surface.allocationCount)
            assertEquals(IntSize(1920, 1152), surface.capacitySize)
        }
    }

    @Test
    fun `a timed native render does not block reading the completed frame`() {
        SoftwareVideoSurface().use { surface ->
            surface.render(2, 1) { pixels, _ -> pixels.setInt(0, 0x00123456) }
            val rendering = CountDownLatch(1)
            val finishRender = CountDownLatch(1)
            val workers = Executors.newFixedThreadPool(2)
            try {
                val writer = workers.submit {
                    surface.render(3, 2) { pixels, _ ->
                        pixels.setInt(0, 0x00654321)
                        rendering.countDown()
                        check(finishRender.await(5, TimeUnit.SECONDS))
                    }
                }
                assertTrue(rendering.await(5, TimeUnit.SECONDS))
                val reader = workers.submit<Int> {
                    assertEquals(IntSize(2, 1), surface.contentSize)
                    surface.colorAt(0, 0) and 0x00ffffff
                }
                assertEquals(0x123456, reader.get(1, TimeUnit.SECONDS))
                finishRender.countDown()
                writer.get(5, TimeUnit.SECONDS)
                assertEquals(IntSize(3, 2), surface.contentSize)
                assertEquals(0x654321, surface.colorAt(0, 0) and 0x00ffffff)
            } finally {
                finishRender.countDown()
                workers.shutdownNow()
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `a failed render leaves the completed frame visible`() {
        SoftwareVideoSurface().use { surface ->
            surface.render(2, 1) { pixels, _ -> pixels.setInt(0, 0x00123456) }
            assertFailsWith<IllegalStateException> {
                surface.render(3, 2) { pixels, _ ->
                    pixels.setInt(0, 0x00654321)
                    error("Native render failed")
                }
            }
            assertEquals(IntSize(2, 1), surface.contentSize)
            assertEquals(0x123456, surface.colorAt(0, 0) and 0x00ffffff)
        }
    }

    @Test
    fun `capacity rounding is overflow checked and quantized`() {
        assertEquals(256, grownCapacity(current = 0, required = 1))
        assertEquals(1536, grownCapacity(current = 1024, required = 1025))
        assertEquals(3840, grownCapacity(current = 2560, required = 3440))
    }
}
