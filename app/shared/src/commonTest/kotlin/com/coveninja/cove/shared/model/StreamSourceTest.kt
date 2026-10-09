package com.coveninja.cove.shared.model

import com.coveninja.cove.shared.network.CoveJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which file a source is actually offering.
 *
 * The provider's display line answers this only sometimes: a torrent holding a whole season
 * names the release, not the episode, and several providers put nothing in the title but the
 * size and the seeder count. Every case here was confirmed to fail against a broken
 * implementation before its comment was written.
 */
class StreamSourceTest {
    @Test
    fun `the provider's own hint wins over the address`() {
        val source = StreamSource(
            url = "https://cdn.test/abc123/stream.mkv",
            behaviorHints = StreamBehaviorHints(filename = "Show.S02E04.1080p.WEB-DL.mkv"),
        )

        // Fails if the URL is read first: a debrid link's path is a token and a generic leaf,
        // which would hide the only field that names the episode.
        assertEquals("Show.S02E04.1080p.WEB-DL.mkv", source.fileName())
    }

    @Test
    fun `a direct link ending in a video file names itself`() {
        // What the Nuvio scrapers and most debrid links look like. Fails if the fallback is
        // dropped, which leaves the row blank for every provider that sets no hint.
        assertEquals(
            "Film.2024.2160p.BluRay.x265.mkv",
            StreamSource(url = "https://cdn.test/d/Film.2024.2160p.BluRay.x265.mkv?token=a%2Fb").fileName(),
        )
        assertEquals(
            "Film.2024.1080p.mp4",
            StreamSource(url = "https://cdn.test/d/Film.2024.1080p.mp4#t=0").fileName(),
        )
    }

    @Test
    fun `an address that names no file says nothing`() {
        // A guess here is worse than a blank: the row would claim to name the file and be wrong.
        assertNull(StreamSource(url = "https://cdn.test/playlist").fileName())
        assertNull(StreamSource(url = "https://cdn.test/stream/12345").fileName())
        assertNull(StreamSource(url = "https://cdn.test/a.m3u8").fileName())
        assertNull(StreamSource(url = "https://cdn.test/x.mkv").fileName())
        assertNull(StreamSource(infoHash = "a".repeat(40)).fileName())
        assertNull(StreamSource().fileName())
        // A blank hint is the absence of one, not a file called "".
        assertNull(StreamSource(behaviorHints = StreamBehaviorHints(filename = "  ")).fileName())
    }

    @Test
    fun `the described text carries every word a heuristic reads`() {
        val source = StreamSource(
            name = "Provider 1080p",
            title = "👤 48 💾 2.1 GB",
            behaviorHints = StreamBehaviorHints(filename = "Show.S02E04.1080p.WEB-DL.x265-NTb.mkv"),
        )

        // The codec and audio heuristics read this. Fails if the file name is left out, which is
        // how an x265 encode was offered to a device that cannot decode one: the title says only
        // the size, and the codec is named nowhere else.
        assertTrue("x265" in source.describedText())
        assertTrue("Provider" in source.describedText())
    }

    @Test
    fun `the nested hint survives the wire`() {
        // The desktop decodes this straight off /api/v1/streams, which answers with the backend's
        // own AddonStream shape. Fails if the field is flattened: Android would show a file name
        // and the desktop would not, for the same provider.
        val decoded = CoveJson.decodeFromString<StreamSource>(
            """{"name":"P","title":"t","behaviorHints":{"filename":"a.mkv","videoSize":12}}""",
        )

        assertEquals("a.mkv", decoded.fileName())
    }
}
