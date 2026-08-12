package com.coveninja.cove.backend.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TorrentFileSelectionTest {
    private val files = listOf(
        TorrentFile(0, "Show/sample.mkv", 10),
        TorrentFile(1, "Show/Show.S01E02.1080p.mkv", 1_000),
        TorrentFile(2, "Show/Show.1x03.1080p.mp4", 2_000),
        TorrentFile(3, "Show/readme.txt", 9_000),
    )

    @Test
    fun `explicit raw file index wins`() {
        assertEquals(0, selectTorrentFile(files, 1, 2, 0).index)
    }

    @Test
    fun `episode naming conventions select matching file`() {
        assertEquals(1, selectTorrentFile(files, 1, 2, null).index)
        assertEquals(2, selectTorrentFile(files, 1, 3, null).index)
    }

    @Test
    fun `largest supported video is fallback and non-video index is rejected`() {
        assertEquals(2, selectTorrentFile(files, null, null, null).index)
        assertFailsWith<IllegalArgumentException> { selectTorrentFile(files, null, null, 3) }
    }
}
