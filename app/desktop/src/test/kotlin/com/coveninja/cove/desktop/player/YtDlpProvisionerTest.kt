package com.coveninja.cove.desktop.player

import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YtDlpProvisionerTest {

    // The names are yt-dlp's own release assets; getting one wrong means a 404 at
    // the moment someone clicks a trailer.
    @Test
    fun `each platform gets the standalone build published for it`() {
        assertEquals("yt-dlp_linux", assetName("Linux", "amd64"))
        assertEquals("yt-dlp_linux", assetName("Linux", "x86_64"))
        assertEquals("yt-dlp_linux_aarch64", assetName("Linux", "aarch64"))
        assertEquals("yt-dlp.exe", assetName("Windows 11", "amd64"))
        assertEquals("yt-dlp_arm64.exe", assetName("Windows 11", "aarch64"))
        assertEquals("yt-dlp_macos", assetName("Mac OS X", "aarch64"))
        assertEquals("yt-dlp_macos", assetName("Mac OS X", "x86_64"))
    }

    // Better to say so than to download a build that cannot run: the 32-bit and
    // armv7 Linux releases ship only as zips, which nothing here unpacks.
    @Test
    fun `an unsupported platform has no asset rather than a wrong one`() {
        assertNull(assetName("Linux", "arm"))
        assertNull(assetName("Linux", "i386"))
        assertNull(assetName("SunOS", "sparc"))
    }

    @Test
    fun `the managed copy is named for the platform that has to run it`() {
        assertEquals("yt-dlp.exe", managedFileName("Windows 11"))
        assertEquals("yt-dlp", managedFileName("Linux"))
        assertEquals("yt-dlp", managedFileName("Mac OS X"))
    }

    // A viewer's own yt-dlp has to win, or Cove downloads 40 MB nobody needed and
    // then runs its copy instead of the one their package manager updates.
    @Test
    fun `the search path prefers the managed copy but keeps the usual names`() {
        val unix = ytdlSearchPath(Path.of("/data/tools/yt-dlp"), "Linux")

        assertEquals("/data/tools/yt-dlp:yt-dlp:yt-dlp_x86:youtube-dl", unix)
    }

    // mpv splits this list on ; under Windows and : everywhere else, and a path
    // with a drive letter in it would split at the colon.
    @Test
    fun `the search path is separated the way the platform expects`() {
        val windows = ytdlSearchPath(Path.of("C:\\Users\\a\\cove\\tools\\yt-dlp.exe"), "Windows 11")

        assertTrue(windows.startsWith("C:\\Users\\a\\cove\\tools\\yt-dlp.exe;"), windows)
        assertEquals(4, windows.split(";").size)
    }

    // The published sums file is two columns; anything else in it is not a hash.
    @Test
    fun `checksums are read by file name`() {
        val body = """
            # a comment nobody promised would not be here
            495be29ff4d9d4e9be7eabdfef225221e5d5282e77f2f505abc6dca80349f3fd  yt-dlp
            6bbb3d314cde4febe36e5fa1d55462e29c974f63444e707871834f6d8cc210ae  yt-dlp_linux
        """.trimIndent()

        val sums = parseChecksums(body)

        assertEquals(
            "6bbb3d314cde4febe36e5fa1d55462e29c974f63444e707871834f6d8cc210ae",
            sums["yt-dlp_linux"],
        )
        assertEquals(2, sums.size)
    }

    // yt-dlp keeps up with YouTube by shipping constantly; a copy left alone for a
    // month fails in ways that read as a broken player.
    @Test
    fun `a copy is refreshed once it is a week old`() {
        val now = Instant.parse("2026-08-12T00:00:00Z")
        val week = Duration.ofDays(7)

        assertTrue(needsRefresh(now.minus(Duration.ofDays(7)), now, week))
        assertTrue(needsRefresh(now.minus(Duration.ofDays(40)), now, week))
        assertTrue(!needsRefresh(now.minus(Duration.ofDays(6)), now, week))
    }

    // The default separate-stream pick is what YouTube answers with 403; the mp4
    // family plays. Progressive is the last resort so something always plays.
    @Test
    fun `the format ladder prefers mp4 and always has a fallback`() {
        assertTrue(YTDL_FORMAT.startsWith("bv*[vcodec^=avc1]"), YTDL_FORMAT)
        assertTrue(YTDL_FORMAT.contains("ba[acodec^=mp4a]"), YTDL_FORMAT)
        assertTrue(YTDL_FORMAT.endsWith("/b"), YTDL_FORMAT)
    }

    // Naming no client at all is what leaves yt-dlp on ANDROID_VR, whose URLs 403
    // the open-ended range ffmpeg opens with. A client must always be asked for,
    // and it must be one that needs no JavaScript when there is none to run.
    @Test
    fun `a player client is always named, and needs no javascript unless there is some`() {
        val without = ytdlRawOptions(null)
        assertTrue(without.contains("youtube:player_client=android"), without)
        assertTrue(!without.contains("js-runtimes"), without)

        val with = ytdlRawOptions("deno")
        assertTrue(with.contains("youtube:player_client=web_embedded"), with)
        assertTrue(with.contains("js-runtimes=deno"), with)
    }

    // mpv splits ytdl-raw-options on commas, so a second pair may be added but a
    // client list may never be: yt-dlp merges the formats of every client named and
    // then picks on quality, letting a 403 stream outbid the one that plays.
    @Test
    fun `the options name one client and stay parseable as mpv key-value pairs`() {
        JS_RUNTIMES.map(::ytdlRawOptions).plus(ytdlRawOptions(null)).forEach { options ->
            val pairs = options.split(",")
            assertEquals(1, pairs.count { it.startsWith("extractor-args=") }, options)
            assertTrue(pairs.all { it.contains("=") }, options)
            assertEquals(1, Regex("player_client=").findAll(options).count(), options)
        }
    }

    // yt-dlp only accepts these four names, and the three kept here are the ones
    // whose runtime name is also the program to look for on the PATH.
    @Test
    fun `every js runtime offered is one yt-dlp accepts under that name`() {
        assertEquals(listOf("deno", "node", "bun"), JS_RUNTIMES)
    }
}
