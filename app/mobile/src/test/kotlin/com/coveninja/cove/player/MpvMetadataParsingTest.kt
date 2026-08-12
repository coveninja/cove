package com.coveninja.cove.player

import com.coveninja.cove.ui.state.TrackKind
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
