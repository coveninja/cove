package com.coveninja.cove.player

import com.coveninja.cove.ui.state.PlaybackStatus
import com.coveninja.cove.ui.state.TrackKind
import dev.jdtech.mpv.MPVLib
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class MpvMetadataParsingTest {
    @Test
    fun `resume option follows mpv loadfile playlist index`() {
        assertEquals(
            listOf("loadfile", "https://video.test/movie.mkv", "replace", "-1", "start=125.500"),
            buildMpvLoadCommand("https://video.test/movie.mkv", 125.5),
        )
        assertEquals(
            listOf("loadfile", "https://video.test/movie.mkv", "replace"),
            buildMpvLoadCommand("https://video.test/movie.mkv", 0.0),
        )
    }

    @Test
    fun `track metadata preserves selection language and type`() {
        val tracks = parseMpvTracks(
            """[
              {"id":1,"type":"video","selected":true},
              {"id":2,"type":"audio","title":"Original","lang":"jpn","selected":true},
              {"id":3,"type":"sub","title":"English","lang":"eng","selected":false}
            ]""",
        )
        assertEquals(2, tracks.size)
        assertEquals(TrackKind.Audio, tracks[0].kind)
        assertEquals("jpn", tracks[0].language)
        assertTrue(tracks[0].selected)
        assertEquals(TrackKind.Subtitle, tracks[1].kind)
        assertFalse(tracks[1].selected)
    }

    @Test
    fun `chapter metadata drops invalid entries without breaking playback`() {
        val chapters = parseMpvChapters(
            """[
              {"title":"Opening","time":0.0},
              {"title":"Broken"},
              {"title":"Finale","time":120.5}
            ]""",
        )
        assertEquals(listOf("Opening", "Finale"), chapters.map { it.title })
        assertEquals(120.5, chapters.last().startSeconds)
        assertTrue(parseMpvChapters("not json").isEmpty())
    }

    @Test
    fun `recoverable native diagnostics do not become playback errors`() {
        val messages = listOf(
            "mov, mp4, m4a, 3gp, 3g2, mj2: stream 1, offset 0x151f4741: partial file",
            "acquireLatestImage failed: -30001",
        )

        messages.forEach { message ->
            val status = PlaybackStatus(hasMedia = true).withMpvDiagnostic(message)

            assertEquals(message, status.statusMessage)
            assertNull(status.error)
        }
    }

    @Test
    fun `mpv bookkeeping never reaches the opening commentary`() {
        // Mutation check: both bounds were moved and the test failed each time —
        // relaxing to MPV_LOG_LEVEL_V trips the first assertion, tightening to
        // MPV_LOG_LEVEL_WARN trips the last.
        assertFalse(isViewableMpvDiagnostic(MPVLib.MpvLogLevel.MPV_LOG_LEVEL_V))
        assertFalse(isViewableMpvDiagnostic(MPVLib.MpvLogLevel.MPV_LOG_LEVEL_DEBUG))
        assertFalse(isViewableMpvDiagnostic(MPVLib.MpvLogLevel.MPV_LOG_LEVEL_TRACE))
        assertTrue(isViewableMpvDiagnostic(MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR))
        assertTrue(isViewableMpvDiagnostic(MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN))
        assertTrue(isViewableMpvDiagnostic(MPVLib.MpvLogLevel.MPV_LOG_LEVEL_INFO))
    }

    @Test
    fun `native diagnostics preserve an explicit playback failure`() {
        val status = PlaybackStatus(error = "The selected stream could not be opened.")
            .withMpvDiagnostic("demuxer diagnostic")

        assertEquals("demuxer diagnostic", status.statusMessage)
        assertEquals("The selected stream could not be opened.", status.error)
    }

    @Test
    fun `the outgoing file ending never fails the load that replaced it`() {
        // The replace-load window: stop() has already cleared stoppedByUser via the
        // new load, and mpv's end-of-file for the file being replaced arrives next.
        // Mutation check: dropping fileOpening from the conjunction fails here.
        assertFalse(
            mpvEndOfFileIsFailure(
                fileOpening = false,
                playbackRequested = true,
                stoppedByUser = false,
                fileLoaded = false,
            ),
        )
        // Mutation check: hardcoding false, or requiring fileLoaded, fails here.
        assertTrue(
            mpvEndOfFileIsFailure(
                fileOpening = true,
                playbackRequested = true,
                stoppedByUser = false,
                fileLoaded = false,
            ),
        )
        // Mutation check: dropping either guard below fails its own case.
        assertFalse(
            mpvEndOfFileIsFailure(
                fileOpening = true,
                playbackRequested = true,
                stoppedByUser = true,
                fileLoaded = false,
            ),
        )
        assertFalse(
            mpvEndOfFileIsFailure(
                fileOpening = true,
                playbackRequested = true,
                stoppedByUser = false,
                fileLoaded = true,
            ),
        )
    }

    @Test
    fun `the bundled yt-dlp is refreshed before it outlives youtube`() {
        val today = LocalDate.of(2026, 8, 18)

        // Never refreshed: the version preference is empty, so what is on disk is
        // the copy the AAR shipped. Mutation check: returning None or Background
        // for the null version fails here.
        assertEquals(
            YtDlpRefresh.Blocking,
            ytDlpRefreshFor(installedVersion = null, today = today, mayInstallHelper = true),
        )
        // Mutation check: dropping the mayInstallHelper guard fails here, and the
        // instrumented BundledYtDlpInstrumentedTest with it.
        assertEquals(
            YtDlpRefresh.None,
            ytDlpRefreshFor(installedVersion = null, today = today, mayInstallHelper = false),
        )
        // Mutation check: swapping the two thresholds fails both of these.
        assertEquals(
            YtDlpRefresh.Blocking,
            ytDlpRefreshFor("2025.11.12", today, mayInstallHelper = true),
        )
        assertEquals(
            YtDlpRefresh.Background,
            ytDlpRefreshFor("2026.07.10", today, mayInstallHelper = true),
        )
        // Mutation check: using > instead of >= for the stale bound fails here.
        assertEquals(
            YtDlpRefresh.Background,
            ytDlpRefreshFor("2026.07.19", today, mayInstallHelper = true),
        )
        assertEquals(
            YtDlpRefresh.None,
            ytDlpRefreshFor("2026.08.14", today, mayInstallHelper = true),
        )
    }

    @Test
    fun `a yt-dlp version is read as the release date it is`() {
        // Mutation check: requiring exactly three components, or reading the day
        // from the last one, fails on the nightly form below.
        assertEquals(LocalDate.of(2026, 8, 10), parseYtDlpReleaseDate("2026.08.10"))
        assertEquals(LocalDate.of(2026, 8, 1), parseYtDlpReleaseDate("2026.08.01.232946"))
        assertNull(parseYtDlpReleaseDate("2026.08"))
        assertNull(parseYtDlpReleaseDate("nightly"))
        assertNull(parseYtDlpReleaseDate(null))
    }

    @Test
    fun `the extractor headers reach mpv without duplicating the user agent`() {
        val headers = mapOf(
            "user-agent" to "Mozilla/5.0 (Android)",
            "Accept-Language" to "en-us,en;q=0.5",
            "Referer" to "https://www.youtube.com/",
            "Cookie" to "",
        )

        // Mutation check: a case-sensitive match returns "" here, since yt-dlp
        // lowercases the key.
        assertEquals("Mozilla/5.0 (Android)", mpvUserAgent(headers))
        // Mutation check: dropping the User-Agent filter puts it in this list too,
        // and dropping the blank-value filter adds the empty Cookie.
        assertEquals(
            listOf("Accept-Language: en-us,en;q=0.5", "Referer: https://www.youtube.com/"),
            mpvHeaderFields(headers),
        )
        // An ordinary stream clears both, so nothing a trailer set carries over.
        assertEquals("", mpvUserAgent(emptyMap()))
        assertEquals(emptyList(), mpvHeaderFields(emptyMap()))
    }

    @Test
    fun `programmatic stop never becomes completion or interruption`() {
        val status = PlaybackStatus(
            hasMedia = true,
            positionSeconds = 400.0,
            durationSeconds = 1000.0,
        ).withMpvEof(
            reached = true,
            stoppedByUser = true,
            fileLoaded = true,
            previousPositionSeconds = 399.0,
        )

        assertFalse(status.endReached)
        assertFalse(status.interrupted)
    }

    @Test
    fun `early eof becomes an interruption at the last real position`() {
        val status = PlaybackStatus(
            hasMedia = true,
            positionSeconds = 1000.0,
            durationSeconds = 1000.0,
        ).withMpvEof(
            reached = true,
            stoppedByUser = false,
            fileLoaded = true,
            previousPositionSeconds = 400.0,
        )

        assertFalse(status.endReached)
        assertTrue(status.interrupted)
        assertEquals(400.0, status.positionSeconds)
    }
}
