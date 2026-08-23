package com.coveninja.cove.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit tests for the genuinely testable parts of the mpv integration.
 *
 * Full libmpv rendering cannot be unit-tested without a display and live mpv.
 * These tests cover: LC_NUMERIC category selection per OS, library candidate
 * ordering, and loadfile argument construction. SoftwareVideoSurfaceTest owns
 * the bgr0/Skia pixel contract.
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
