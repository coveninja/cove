package com.coveninja.cove.ui.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one parser both hosts now use, against what mpv actually emits.
 *
 * The payload below is not invented: it was read off `track-list` over mpv 0.41's JSON IPC
 * from a two-audio-track file, which is why it carries fields nobody would think to write by
 * hand — `main-selection`, `dependent`, `demux-channels` reading "unknown1" for a layout
 * FFmpeg could not name. Hand-written fixtures are how a parser ends up matching a shape mpv
 * does not send.
 */
class MpvTrackListTest {

    private val realTrackList = """
        [
          {"id":1,"type":"audio","src-id":1,"title":"Stereo Mix","lang":"eng",
           "audio-channels":2,"image":false,"albumart":false,"default":true,"forced":false,
           "dependent":false,"visual-impaired":false,"hearing-impaired":false,"external":false,
           "selected":true,"main-selection":0,"ff-index":0,"decoder":"ac3","decoder-desc":"AC-3",
           "codec":"ac3","codec-desc":"AC-3","demux-channel-count":2,"demux-channels":"stereo",
           "demux-samplerate":48000},
          {"id":2,"type":"audio","src-id":2,"lang":"jpn","audio-channels":6,"image":false,
           "albumart":false,"default":false,"forced":true,"dependent":false,
           "visual-impaired":false,"hearing-impaired":false,"external":false,"selected":false,
           "ff-index":1,"codec":"eac3","codec-desc":"E-AC-3","demux-channel-count":6,
           "demux-channels":"5.1(side)","demux-samplerate":48000}
        ]
    """.trimIndent()

    @Test
    fun `a real track list keeps identity, selection and language`() {
        val tracks = parseMpvTrackList(realTrackList)

        assertEquals(2, tracks.size)
        assertEquals(TrackKind.Audio, tracks[0].kind)
        assertEquals("Stereo Mix", tracks[0].title)
        assertEquals("eng", tracks[0].language)
        assertTrue(tracks[0].selected)
        assertEquals("jpn", tracks[1].language)
        assertFalse(tracks[1].selected)
    }

    // The point of the whole change: a track with no title used to read as "Track 2", because
    // everything that distinguishes it from the one above was parsed and thrown away.
    @Test
    fun `the technical detail that tells two tracks apart survives`() {
        val tracks = parseMpvTrackList(realTrackList)

        assertEquals("AC-3", tracks[0].codec)
        assertEquals("stereo", tracks[0].channels)
        assertEquals(48000, tracks[0].sampleRateHz)
        assertEquals("E-AC-3", tracks[1].codec)
        assertEquals("5.1(side)", tracks[1].channels)
    }

    // codec-desc is the readable name and codec the ffmpeg short one. A track still being
    // probed can carry the second without the first, and showing "eac3" beats showing nothing.
    @Test
    fun `a track with no codec description falls back to its codec`() {
        val tracks = parseMpvTrackList(
            """[{"id":1,"type":"audio","codec":"opus","selected":true}]""",
        )

        assertEquals("opus", tracks.single().codec)
    }

    // These are the flags a release encodes instead of writing them in the title. A forced
    // subtitle carrying only the alien dialogue looked identical to the full track beside it.
    @Test
    fun `the flags that say what a track is for are read`() {
        val tracks = parseMpvTrackList(
            """
            [
              {"id":1,"type":"sub","lang":"eng","forced":true,"default":true,"selected":false},
              {"id":2,"type":"sub","lang":"eng","hearing-impaired":true,"selected":false},
              {"id":3,"type":"sub","lang":"eng","image":true,"external":true,"selected":false},
              {"id":4,"type":"audio","lang":"eng","visual-impaired":true,"selected":true}
            ]
            """.trimIndent(),
        )

        assertTrue(tracks[0].forced)
        assertTrue(tracks[0].isDefault)
        assertTrue(tracks[1].hearingImpaired)
        assertFalse(tracks[1].forced)
        assertTrue(tracks[2].bitmap)
        assertTrue(tracks[2].external)
        assertTrue(tracks[3].visualImpaired)
    }

    // mpv omits what it does not know rather than sending nulls, so every field past the id
    // has to be optional. A subtitle track has no audio-channels at all.
    @Test
    fun `a track that declares almost nothing still parses`() {
        val track = parseMpvTrackList("""[{"id":7,"type":"sub"}]""").single()

        assertEquals(7, track.id)
        assertEquals(TrackKind.Subtitle, track.kind)
        assertEquals("", track.title)
        assertEquals("", track.codec)
        assertEquals(0, track.sampleRateHz)
        assertFalse(track.forced)
    }

    // Video tracks share the list and are not selectable here; an entry with no usable id
    // cannot be asked for at all. Both are dropped rather than turned into a broken menu row.
    @Test
    fun `entries that cannot become a menu row are dropped`() {
        val tracks = parseMpvTrackList(
            """
            [
              {"id":1,"type":"video","selected":true},
              {"type":"audio","lang":"eng"},
              {"id":3,"type":"audio","lang":"eng","selected":true}
            ]
            """.trimIndent(),
        )

        assertEquals(listOf(3), tracks.map { it.id })
    }

    // An unreadable track list must cost the track menus and nothing else. This runs on the
    // status path, so a throw here would take playback down with it.
    @Test
    fun `an unreadable track list costs the menus rather than playback`() {
        assertTrue(parseMpvTrackList("").isEmpty())
        assertTrue(parseMpvTrackList("not json").isEmpty())
        assertTrue(parseMpvTrackList("""{"not":"an array"}""").isEmpty())
        assertTrue(parseMpvTrackList("""[{"id":"not a number","type":"audio"}]""").isEmpty())
    }

    @Test
    fun `tracks are split into their two menus with the selection resolved`() {
        val status = PlaybackStatus().withTracks(
            parseMpvTrackList(
                """
                [
                  {"id":1,"type":"audio","lang":"eng","selected":true},
                  {"id":2,"type":"audio","lang":"jpn","selected":false},
                  {"id":3,"type":"sub","lang":"eng","selected":true}
                ]
                """.trimIndent(),
            ),
        )

        assertEquals(listOf(1, 2), status.audioTracks.map { it.id })
        assertEquals(listOf(3), status.subtitleTracks.map { it.id })
        assertEquals(1, status.selectedAudioId)
        assertEquals(3, status.selectedSubtitleId)
    }

    // Subtitles off is a state the file reports by selecting none, and it has to survive as
    // null rather than becoming the first track — which is what "off" would look like undone.
    @Test
    fun `nothing selected stays nothing selected`() {
        val status = PlaybackStatus().withTracks(
            parseMpvTrackList(
                """[{"id":1,"type":"sub","lang":"eng","selected":false}]""",
            ),
        )

        assertEquals(null, status.selectedSubtitleId)
    }
}
