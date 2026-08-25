package com.coveninja.cove.ui.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitleFilesTest {

    // Mutation applied to verify: dropped the lowercase() from the extension read
    // → test failed on the .SRT case, which is what a Windows drop often carries.
    @Test
    fun `a subtitle file is recognised whatever its case`() {
        assertTrue(isSubtitleFile("/home/a/Movie.srt"))
        assertTrue(isSubtitleFile("/home/a/Movie.SRT"))
        assertTrue(isSubtitleFile("/home/a/Movie.AsS"))
    }

    // Mutation applied to verify: made isSubtitleFile return true for an empty
    // extension → test failed on the video and on the extensionless name.
    @Test
    fun `anything else is refused`() {
        assertFalse(isSubtitleFile("/home/a/Movie.mkv"))
        assertFalse(isSubtitleFile("/home/a/Movie"))
        assertFalse(isSubtitleFile("/home/a/subtitles"))
        assertFalse(isSubtitleFile(""))
    }

    // Mutation applied to verify: split on '/' only → test failed on the Windows
    // path, which came back with the whole thing as the track title.
    @Test
    fun `the name is the last segment of either kind of path`() {
        assertEquals("Movie.2024.en.srt", subtitleFileName("/home/a/Movie.2024.en.srt"))
        assertEquals("Movie.2024.en.srt", subtitleFileName("""C:\Films\Movie.2024.en.srt"""))
        assertEquals("Movie.2024.en.srt", subtitleFileName("Movie.2024.en.srt"))
    }

    // Mutation applied to verify: returned the segment without asking
    // knownLanguageTag about it → test failed on the release-name cases, which came
    // back as languages called WEB and 1080P.
    @Test
    fun `a language is read only when the segment names one`() {
        assertEquals("en", subtitleFileLanguage("/a/Movie.2024.en.srt"))
        assertEquals("eng", subtitleFileLanguage("/a/Movie.2024.eng.srt"))
        assertEquals("pt-BR", subtitleFileLanguage("/a/Movie.2024.pt-BR.srt"))
        assertEquals("", subtitleFileLanguage("/a/Movie.2024.web.srt"))
        assertEquals("", subtitleFileLanguage("/a/Movie.1080p.srt"))
        assertEquals("", subtitleFileLanguage("/a/Movie.srt"))
    }

    // Mutation applied to verify: took the stem's first segment rather than its last
    // → test failed, reading the language of Movie.en.forced.srt as "movie".
    @Test
    fun `the language is the segment nearest the extension`() {
        assertEquals("", subtitleFileLanguage("/a/Movie.en.forced.srt"))
        assertEquals("fr", subtitleFileLanguage("/a/Movie.en.fr.srt"))
        // A file named for nothing but its language still names it.
        assertEquals("en", subtitleFileLanguage("/a/en.srt"))
    }

    // Mutation applied to verify: returned the whole list instead of filtering
    // → test failed, keeping the video and the folder alongside the subtitle.
    @Test
    fun `a mixed drop keeps the subtitles in the order they arrived`() {
        assertEquals(
            listOf("/a/Movie.en.srt", "/a/Movie.fr.ass"),
            subtitleFilesAmong(
                listOf("/a/Movie.mkv", "/a/Movie.en.srt", "/a/Extras", "/a/Movie.fr.ass"),
            ),
        )
    }

    // Mutation applied to verify: dropped "sup" from the extension list → test failed
    // on the PGS case. The list is the contract with mpv; an entry lost here is a file
    // the viewer is told Cove cannot read when it can.
    @Test
    fun `the accepted extensions cover the formats mpv reads`() {
        listOf("srt", "ass", "ssa", "vtt", "sub", "idx", "sup", "smi", "mpl2", "ttml", "dfxp", "mks")
            .forEach { extension ->
                assertTrue(isSubtitleFile("/a/Movie.$extension"), "expected .$extension accepted")
            }
    }
}
