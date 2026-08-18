package com.coveninja.cove.desktop.player

import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit tests for the genuinely testable parts of the mpv integration.
 *
 * Rendering cannot be unit-tested without a display and a live libmpv.
 * These tests cover: LC_NUMERIC category selection per OS, library candidate
 * ordering, bgr0→RGB pixel conversion with a known byte pattern, and loadfile
 * argument construction.
 *
 * Each test is mutation-verified — see comments inside each test.
 */
class MpvTest {

    @Test
    fun `macOS searches both Homebrew libmpv prefixes`() {
        assertEquals(
            listOf(
                "/custom/mpv",
                "/Applications/Cove.app/Contents/Frameworks",
                "/opt/homebrew/opt/mpv/lib",
                "/usr/local/opt/mpv/lib",
            ),
            mpvLibrarySearchPaths(
                osName = "Mac OS X",
                environment = mapOf("COVE_MPV_LIBRARY_DIR" to "/custom/mpv"),
                javaHome = "/Applications/Cove.app/Contents/runtime/Contents/Home",
            ),
        )
    }

    // ---- lcNumericCategory ----

    @Test
    fun `LC_NUMERIC category is 1 on Linux`() {
        // Mutation: changing the return value to 4 makes this assertion fail.
        assertEquals(1, lcNumericCategory("Linux"))
    }

    @Test
    fun `LC_NUMERIC category is 4 on macOS`() {
        // Mutation: returning 1 for all OS strings makes this fail.
        assertEquals(4, lcNumericCategory("Mac OS X"))
    }

    @Test
    fun `LC_NUMERIC category is 4 on Windows`() {
        // Mutation: removing the Windows branch makes this fall through to the
        // else clause and return 1 — this assertion catches that.
        assertEquals(4, lcNumericCategory("Windows 10"))
    }

    @Test
    fun `LC_NUMERIC category is 1 for unrecognised OS strings`() {
        // Mutation: flipping the Linux/other branch to 4 makes this fail.
        assertEquals(1, lcNumericCategory("FreeBSD"))
        assertEquals(1, lcNumericCategory(""))
        assertEquals(1, lcNumericCategory("SunOS"))
    }

    // ---- configureMpvNumericLocale ----

    @Test
    fun `configureMpvNumericLocale calls setlocale with correct category and C locale`() {
        val calls = mutableListOf<Pair<Int, String>>()
        configureMpvNumericLocale(osName = "Linux") { category, locale ->
            calls += category to locale
            locale   // non-null return simulates success
        }
        // Mutation: passing the wrong category (4 for Linux) makes assertEquals fail.
        assertEquals(listOf(1 to "C"), calls)
    }

    @Test
    fun `configureMpvNumericLocale throws when setlocale returns null`() {
        // Mutation: removing the null check makes this test fail (no exception thrown).
        assertFailsWith<IllegalStateException> {
            configureMpvNumericLocale(osName = "Linux") { _, _ -> null }
        }
    }

    // ---- bgr0ToBufferedImage ----

    @Test
    fun `bgr0ToBufferedImage converts a known pixel to TYPE_INT_RGB correctly`() {
        // bgr0 byte pattern for a single pixel: [B=0x11, G=0x22, R=0x33, 0=0x00]
        // Expected TYPE_INT_RGB int: 0x00_33_22_11 = 0x00332211
        val buf = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x11.toByte())   // B
        buf.put(0x22.toByte())   // G
        buf.put(0x33.toByte())   // R
        buf.put(0x00.toByte())   // padding
        buf.rewind()

        val image = bgr0ToBufferedImage(buf, 1, 1)
        val pixel = (image.raster.dataBuffer as DataBufferInt).data[0]
        // Mutation: swapping B and R in the byte buffer makes this fail because
        // the int becomes 0x00112233 instead of 0x00332211.
        assertEquals(0x00332211, pixel and 0x00FFFFFF)
    }

    @Test
    fun `bgr0ToBufferedImage handles non-trivial stride correctly`() {
        // 2x2 image: four pixels with distinct colours to prove all four are copied.
        // pixel(0,0) = B=0x10 G=0x20 R=0x30
        // pixel(1,0) = B=0x40 G=0x50 R=0x60
        // pixel(0,1) = B=0x70 G=0x80 R=0x90
        // pixel(1,1) = B=0xA0 G=0xB0 R=0xC0
        val buf = ByteBuffer.allocateDirect(16).order(ByteOrder.LITTLE_ENDIAN)
        val pixels = listOf(
            Triple(0x10, 0x20, 0x30),
            Triple(0x40, 0x50, 0x60),
            Triple(0x70, 0x80, 0x90),
            Triple(0xA0.toByte(), 0xB0.toByte(), 0xC0.toByte()),
        )
        for ((b, g, r) in pixels) {
            buf.put(b.toByte()); buf.put(g.toByte()); buf.put(r.toByte()); buf.put(0)
        }
        buf.rewind()

        val image = bgr0ToBufferedImage(buf, 2, 2)
        val data  = (image.raster.dataBuffer as DataBufferInt).data

        // Mutation: reading only pixel 0 is not enough — assert all four so that
        // an off-by-one stride error will fail on pixels 1, 2, or 3.
        assertEquals(0x302010, data[0] and 0xFFFFFF)
        assertEquals(0x605040, data[1] and 0xFFFFFF)
        assertEquals(0x908070, data[2] and 0xFFFFFF)
        assertEquals(0xC0B0A0.toInt() and 0xFFFFFF, data[3] and 0xFFFFFF)
    }

    @Test
    fun `bgr0ToBufferedImage rejects negative dimensions`() {
        val buf = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN)
        // Mutation: removing the require checks lets these return without
        // throwing, causing the test to fail.
        assertFailsWith<IllegalArgumentException> { bgr0ToBufferedImage(buf, 0, 1) }
        assertFailsWith<IllegalArgumentException> { bgr0ToBufferedImage(buf, 1, 0) }
    }

    @Test
    fun `bgr0ToBufferedImage rejects undersized buffer`() {
        // Declaring 2x2 but providing only 4 bytes (enough for 1 pixel).
        val buf = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN)
        // Mutation: removing the size check lets this crash with an
        // ArrayIndexOutOfBoundsException rather than IllegalArgumentException.
        assertFailsWith<IllegalArgumentException> { bgr0ToBufferedImage(buf, 2, 2) }
    }

    // ---- mpvLoadFileArgs / mpvStartOption ----

    /**
     * The previous version of this test asserted a four-element array carrying
     * `start=N`, and passed — because it only ever checked the shape of the array,
     * never that mpv accepts it. mpv 0.38 turned that fourth argument into an
     * `<index>`, so every resumed playback failed with "argument index can't be
     * parsed" while the test stayed green. Hence the size assertion below is
     * exact: three arguments, whatever the resume point.
     */
    // Mutation applied to verify: appended the start option again → test failed
    // on the argument count.
    @Test
    fun `loadfile is always three arguments`() {
        assertEquals(3, mpvLoadFileArgs("file.mkv").size)
        assertEquals(
            listOf("loadfile", "file.mkv", "replace"),
            mpvLoadFileArgs("file.mkv").toList(),
        )
    }

    // Mutation applied to verify: returned "0" unconditionally → test failed,
    // the resume point was dropped.
    @Test
    fun `a resume point becomes the start option`() {
        assertEquals("90", mpvStartOption(90.0))
    }

    // Mutation applied to verify: dropped the `> 0.0` guard so negatives passed
    // through → test failed with "-5" instead of "0".
    @Test
    fun `no resume point starts at the beginning`() {
        assertEquals("0", mpvStartOption(0.0))
        assertEquals("0", mpvStartOption(-5.0))
    }

    // Mutation applied to verify: switched toLong() to the raw double → test
    // failed with "90.7".
    @Test
    fun `fractional resume points truncate to whole seconds`() {
        assertEquals("90", mpvStartOption(90.7))
    }
}
