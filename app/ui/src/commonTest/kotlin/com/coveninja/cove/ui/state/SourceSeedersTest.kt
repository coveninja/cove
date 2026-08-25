package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.model.StreamSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceSeedersTest {

    // ── Parsing ──────────────────────────────────────────────────────────────

    // Verbatim from torrentio.strem.fun/stream/movie/tt0111161.json.
    @Test
    fun `reads the count out of a real torrentio title`() {
        val title = "The.Shawshank.Redemption.1994.UHD.BluRay.2160p.DTS-HD.MA.5.1.DV.HEVC" +
            ".HYBRID.REMUX-FraMeSToR\n👤 109 💾 54.33 GB ⚙️ TorrentGalaxy"

        assertEquals(109, parseSeederCount(title))
    }

    // The whole point of anchoring on a marker. A release name is mostly
    // numbers, and any of them would be a plausible-looking peer count.
    @Test
    fun `a title with no marker yields nothing`() {
        val title = "The Matrix 1999 2160p BluRay x265 10bit HDR DTS-HD MA 5.1"

        assertNull(parseSeederCount(title))
    }

    // Zero is a real, useful answer and must survive as one rather than being
    // flattened to "unknown".
    @Test
    fun `zero seeders parses as zero, not absent`() {
        assertEquals(0, parseSeederCount("Some.Release.1080p\n👤 0 💾 2.1 GB ⚙️ 1337x"))
    }

    // Providers other than the emoji three.
    @Test
    fun `written-out forms are read too`() {
        assertEquals(42, parseSeederCount("Some.Release.720p | Seeders: 42"))
        assertEquals(7, parseSeederCount("Some.Release.720p | 7 seeders"))
    }

    // Guards the comma strip.
    @Test
    fun `thousands separators do not defeat the parse`() {
        assertEquals(1204, parseSeederCount("Big.Release.2160p\n👤 1,204 💾 60 GB"))
    }

    // The field the count actually arrives in varies by provider, so both are
    // searched.
    @Test
    fun `either field can carry the count`() {
        assertEquals(9, StreamSource(title = "Release\n👤 9 💾 1 GB").seederCount())
        assertEquals(9, StreamSource(name = "Provider 👤 9").seederCount())
    }

    // ── Health ───────────────────────────────────────────────────────────────

    // These boundaries determine the source-row health tint.
    @Test
    fun `health bands split dead from thin from healthy`() {
        assertEquals(SeederHealth.Dead, seederHealth(0))
        assertEquals(SeederHealth.Thin, seederHealth(1))
        assertEquals(SeederHealth.Thin, seederHealth(9))
        assertEquals(SeederHealth.Healthy, seederHealth(10))
    }
}
