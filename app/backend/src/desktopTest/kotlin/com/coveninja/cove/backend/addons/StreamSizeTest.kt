package com.coveninja.cove.backend.addons

import kotlin.test.Test
import kotlin.test.assertEquals

class StreamSizeTest {

    private val gib = 1L shl 30

    // The reason the 💾 marker is tried before the bare pattern. Release names
    // carry sizes of their own, and the one the provider put behind the marker
    // is the file being offered — the one in the name is whatever the encoder
    // felt like mentioning. Mutation applied to verify: dropped the MARKED_SIZE
    // attempt → returned 2 GiB, the size in the release name, not 12.4 GiB.
    @Test
    fun `the marked size wins over one written into the release name`() {
        val title = "Movie.Name.2019.1080p.BluRay.x264-GRP [2GB]\n👤 51 💾 12.4 GB ⚙️ 1337x"

        assertEquals((12.4 * gib).toLong(), humanSizeToBytes(title))
    }

    // Verbatim from torrentio.strem.fun/stream/movie/tt0111161.json — the shape
    // this exists for at all.
    @Test
    fun `reads the size out of a real torrentio title`() {
        val title = "The.Shawshank.Redemption.1994.UHD.BluRay.2160p.DTS-HD.MA.5.1.DV.HEVC" +
            ".HYBRID.REMUX-FraMeSToR\n👤 109 💾 54.33 GB ⚙️ TorrentGalaxy"

        assertEquals((54.33 * gib).toLong(), humanSizeToBytes(title))
    }

    // The form Nuvio scrapers hand over in their own size field, which shares
    // this parser and predates the marker. Mutation applied to verify: dropped
    // the BARE_SIZE fallback → both returned 0.
    @Test
    fun `a plain size with no marker still parses`() {
        assertEquals(gib, humanSizeToBytes("1 GB"))
        assertEquals(1L shl 20, humanSizeToBytes("1MB"))
    }

    // Callers use `> 0` to decide whether a size is known at all, so a miss has
    // to stay a miss. Mutation applied to verify: made the no-match branch
    // return 1 → failed.
    @Test
    fun `text with no size at all yields zero`() {
        assertEquals(0, humanSizeToBytes("The.Matrix.1999.2160p.x265"))
    }
}
