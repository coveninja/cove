package com.coveninja.cove.backend.storage

import com.coveninja.cove.shared.data.TorrentCachePolicy
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sweep decides what gets deleted and never gets a second chance, so every rule here was
 * checked by breaking the implementation first and confirming the assertion caught it.
 */
class TorrentCacheSweepTest {
    private val now = 1_800_000_000_000

    private fun torrent(name: String, bytes: Long, ageDays: Long) = CachedTorrent(
        hash = name.repeat(40).take(40),
        path = Path.of("/cache/torrents", name.repeat(40).take(40)),
        bytes = bytes,
        lastReadAt = now - ageDays * 24 * 60 * 60 * 1000,
    )

    private val recent = torrent("a", bytes = 4_000, ageDays = 1)
    private val middling = torrent("b", bytes = 3_000, ageDays = 10)
    private val ancient = torrent("c", bytes = 2_000, ageDays = 40)

    @Test
    fun `nothing is removed when the policy asks for nothing`() {
        // Fails if any rule runs on a zeroed field: an unlimited policy must be a no-op, not a
        // sweep with a limit of zero bytes that deletes everything.
        assertEquals(
            emptyList(),
            planTorrentSweep(listOf(recent, middling, ancient), TorrentCachePolicy(), emptySet(), now),
        )
    }

    @Test
    fun `age expiry removes only what is past the cutoff`() {
        val removed = planTorrentSweep(
            listOf(recent, middling, ancient),
            TorrentCachePolicy(maxAgeDays = 30),
            emptySet(),
            now,
        )
        // Fails on an off-by-one in the cutoff arithmetic, and on a comparison pointing the wrong
        // way — which would expire everything recent and keep the stale entries.
        assertEquals(listOf(ancient), removed)
    }

    @Test
    fun `the size cap evicts least recently read first and stops at the limit`() {
        val removed = planTorrentSweep(
            listOf(recent, middling, ancient),
            // 9,000 bytes cached, 5,000 allowed: the two oldest cover it, the newest must survive.
            TorrentCachePolicy(limitBytes = 5_000),
            emptySet(),
            now,
        )
        // Fails if the sort is reversed (the freshest download would be evicted first) and if the
        // running total is not decremented, which would empty the cache every time.
        assertEquals(listOf(ancient, middling), removed)
        assertFalse(recent in removed)
    }

    @Test
    fun `the cap counts what expiry already removed rather than evicting twice over`() {
        val removed = planTorrentSweep(
            listOf(recent, middling, ancient),
            // Expiry takes the 2,000-byte entry, leaving 7,000 against a 7,000 limit — so the cap
            // has nothing left to do and the other two stay.
            TorrentCachePolicy(limitBytes = 7_000, maxAgeDays = 30),
            emptySet(),
            now,
        )
        // Fails if the cap works from the pre-expiry total, which would evict a torrent to make
        // room that had already been freed.
        assertEquals(listOf(ancient), removed)
    }

    @Test
    fun `a torrent caught by two rules is listed once`() {
        val removed = planTorrentSweep(
            listOf(ancient),
            TorrentCachePolicy(limitBytes = 1, maxAgeDays = 30, deleteAfterWatching = true),
            emptySet(),
            now,
        )
        // Fails if removals are collected into a list rather than a set: the caller would delete
        // the same directory three times and count its bytes three times as freed.
        assertEquals(listOf(ancient), removed)
    }

    @Test
    fun `delete after watching spares a torrent still being read`() {
        val streaming = CachedTorrent(
            hash = recent.hash,
            path = recent.path,
            bytes = recent.bytes,
            lastReadAt = now - 30_000,
        )
        val removed = planTorrentSweep(
            listOf(streaming, ancient),
            TorrentCachePolicy(deleteAfterWatching = true),
            emptySet(),
            now,
        )
        // Fails without the grace window: a policy that acted on the last read instant would
        // delete the file out from under a player that had merely paused.
        assertEquals(listOf(ancient), removed)
    }

    @Test
    fun `an active torrent is never removed by any rule`() {
        val removed = planTorrentSweep(
            listOf(recent, middling, ancient),
            TorrentCachePolicy(limitBytes = 1, maxAgeDays = 1, deleteAfterWatching = true),
            active = setOf(recent.hash, middling.hash, ancient.hash),
            now = now,
        )
        // The one failure the policy must never cause. Fails if the active set is consulted by
        // only one of the three rules, or filtered after the cap arithmetic rather than before.
        assertEquals(emptyList(), removed)
    }

    @Test
    fun `active torrents count against the limit without being evicted`() {
        val removed = planTorrentSweep(
            listOf(recent, middling, ancient),
            TorrentCachePolicy(limitBytes = 5_000),
            active = setOf(recent.hash),
            now = now,
        )
        // The 4,000 being watched leaves 1,000 of headroom, so both others have to go. Fails if
        // the total is summed over candidates only, which would leave the cache over budget and
        // report success.
        assertEquals(listOf(ancient, middling), removed)
    }

    @Test
    fun `only info hash directories inside the root are deletable`() {
        val root = Path.of("/cache/torrents")
        assertTrue(isDeletableTorrentDirectory(root, root.resolve("a".repeat(40))))
        // Each of these has been a real shape on disk: the metadata folder the engines write
        // beside the downloads, the journal this very sweep keeps, an uppercase hash no engine
        // produces, a truncated name, and a traversal out of the cache entirely.
        assertFalse(isDeletableTorrentDirectory(root, root.resolve("metadata")))
        assertFalse(isDeletableTorrentDirectory(root, root.resolve(TorrentCacheJournal.JOURNAL_FILE)))
        assertFalse(isDeletableTorrentDirectory(root, root.resolve("A".repeat(40))))
        assertFalse(isDeletableTorrentDirectory(root, root.resolve("a".repeat(39))))
        assertFalse(isDeletableTorrentDirectory(root, root.resolve("../${"a".repeat(40)}")))
        assertFalse(isDeletableTorrentDirectory(root, root))
    }
}
