package com.coveninja.cove.ui.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitleFilesTest {

    @Test
    fun `a subtitle file is recognised whatever its case`() {
        assertTrue(isSubtitleFile("/home/a/Movie.srt"))
        assertTrue(isSubtitleFile("/home/a/Movie.SRT"))
        assertTrue(isSubtitleFile("/home/a/Movie.AsS"))
    }

    @Test
    fun `anything else is refused`() {
        assertFalse(isSubtitleFile("/home/a/Movie.mkv"))
        assertFalse(isSubtitleFile("/home/a/Movie"))
        assertFalse(isSubtitleFile("/home/a/subtitles"))
        assertFalse(isSubtitleFile(""))
    }

    @Test
    fun `the name is the last segment of either kind of path`() {
        assertEquals("Movie.2024.en.srt", subtitleFileName("/home/a/Movie.2024.en.srt"))
        assertEquals("Movie.2024.en.srt", subtitleFileName("""C:\Films\Movie.2024.en.srt"""))
        assertEquals("Movie.2024.en.srt", subtitleFileName("Movie.2024.en.srt"))
    }

    @Test
    fun `a language is read only when the segment names one`() {
        assertEquals("en", subtitleFileLanguage("/a/Movie.2024.en.srt"))
        assertEquals("eng", subtitleFileLanguage("/a/Movie.2024.eng.srt"))
        assertEquals("pt-BR", subtitleFileLanguage("/a/Movie.2024.pt-BR.srt"))
        assertEquals("", subtitleFileLanguage("/a/Movie.2024.web.srt"))
        assertEquals("", subtitleFileLanguage("/a/Movie.1080p.srt"))
        assertEquals("", subtitleFileLanguage("/a/Movie.srt"))
    }

    @Test
    fun `the language is the segment nearest the extension`() {
        assertEquals("", subtitleFileLanguage("/a/Movie.en.forced.srt"))
        assertEquals("fr", subtitleFileLanguage("/a/Movie.en.fr.srt"))
        // A file named for nothing but its language still names it.
        assertEquals("en", subtitleFileLanguage("/a/en.srt"))
    }

    @Test
    fun `a mixed drop keeps the subtitles in the order they arrived`() {
        assertEquals(
            listOf("/a/Movie.en.srt", "/a/Movie.fr.ass"),
            subtitleFilesAmong(
                listOf("/a/Movie.mkv", "/a/Movie.en.srt", "/a/Extras", "/a/Movie.fr.ass"),
            ),
        )
    }

    @Test
    fun `the accepted extensions cover the formats mpv reads`() {
        listOf("srt", "ass", "ssa", "vtt", "sub", "idx", "sup", "smi", "mpl2", "ttml", "dfxp", "mks")
            .forEach { extension ->
                assertTrue(isSubtitleFile("/a/Movie.$extension"), "expected .$extension accepted")
            }
    }
}
