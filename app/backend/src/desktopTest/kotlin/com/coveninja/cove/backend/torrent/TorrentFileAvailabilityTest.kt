package com.coveninja.cove.backend.torrent

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest

class TorrentFileAvailabilityTest {

    private val path: Path = Path.of("/torrents/hash/show/episode.mkv")

    /**
     * The read side must keep looking until libtorrent has actually created the
     * file. Mutation check: dropping the polling loop and returning after the
     * first probe leaves probes == 1 and fails this.
     */
    @Test
    fun `waits until the file appears`() = runTest {
        var probes = 0
        awaitTorrentFile(
            path = path,
            timeoutMillis = 10_000,
            exists = {
                probes += 1
                probes >= 3
            },
        )
        assertEquals(3, probes, "should have polled until the file existed")
    }

    /**
     * A file that never arrives has to end the read rather than hang the
     * response forever. Mutation check: removing withTimeout makes this loop
     * until the test framework gives up instead of throwing.
     */
    @Test
    fun `gives up when the file never appears`() = runTest {
        assertFailsWith<TimeoutCancellationException> {
            awaitTorrentFile(path = path, timeoutMillis = 5_000, exists = { false })
        }
    }
}
