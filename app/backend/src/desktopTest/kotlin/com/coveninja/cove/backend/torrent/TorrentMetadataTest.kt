package com.coveninja.cove.backend.torrent

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class TorrentMetadataTest {
    private val hash = "3ae0584c0f02527a2e649be1b9fc487718625eea"

    @Test
    fun `a magnet carries the trackers a bare info hash would not`() {
        val magnet = magnetUri(hash)

        assertTrue(magnet.startsWith("magnet:?xt=urn:btih:$hash"))
        // Mutation check: dropping the tracker loop leaves only the xt parameter and fails
        // here. This is the difference between a cold start that can reach a tracker and one
        // that can only wait out a DHT lookup.
        assertEquals(DEFAULT_TRACKERS.size, magnet.split("&tr=").size - 1)
        // Percent-encoded, or the ':' and '/' in a tracker URL would terminate the parameter
        // early and every announce would go to a truncated address.
        assertContains(magnet, "udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce")
    }

    @Test
    fun `waiting ends as soon as the metadata lands`() = runTest {
        var polls = 0
        val lines = mutableListOf<String>()

        awaitMetadata(
            hash = hash,
            timeoutMillis = 45_000,
            hasMetadata = { polls++ >= 3 },
            peerCount = { 4 },
            log = lines::add,
            pollMillis = 1,
            nowMillis = { 0 },
        )

        // Mutation check: returning true immediately makes this 1, and inverting the
        // condition never terminates.
        assertEquals(4, polls)
        assertTrue(lines.single().contains("metadata in"))
    }

    @Test
    fun `a swarm with no peers at all is called dead before the full timeout`() = runTest {
        var clock = 0L
        val lines = mutableListOf<String>()

        val failure = assertFailsWith<IllegalStateException> {
            awaitMetadata(
                hash = hash,
                timeoutMillis = 45_000,
                hasMetadata = { false },
                peerCount = { 0 },
                log = lines::add,
                pollMillis = 1,
                nowMillis = { clock += 1_000; clock },
            )
        }

        // Mutation check: removing the dead-swarm branch makes this report the 45s timeout
        // instead, and raising DEAD_SWARM_MILLIS above the timeout does the same.
        assertContains(failure.message.orEmpty(), "the source looks dead")
        assertTrue(clock < 45_000, "gave up at ${clock}ms, which is not early")
    }

    @Test
    fun `a slow swarm that has found peers keeps the whole window`() = runTest {
        var clock = 0L
        val lines = mutableListOf<String>()

        val failure = assertFailsWith<IllegalStateException> {
            awaitMetadata(
                hash = hash,
                timeoutMillis = 45_000,
                hasMetadata = { false },
                peerCount = { 2 },
                log = lines::add,
                pollMillis = 1,
                nowMillis = { clock += 1_000; clock },
            )
        }

        // Mutation check: dropping `peers == 0` from the dead-swarm branch fails here, because
        // a torrent that is merely slow would be abandoned at 20s with peers in hand.
        assertContains(failure.message.orEmpty(), "timed out fetching torrent metadata")
        assertTrue(clock >= 45_000, "gave up at ${clock}ms, before the timeout")
        // The wait says what it is waiting on rather than going quiet for 45 seconds.
        assertTrue(lines.any { it.contains("waiting for metadata") })
    }
}
