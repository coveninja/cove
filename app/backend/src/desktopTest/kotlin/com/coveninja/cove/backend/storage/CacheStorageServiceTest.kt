package com.coveninja.cove.backend.storage

import com.coveninja.cove.shared.data.CacheKind
import com.coveninja.cove.shared.data.TorrentCachePolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The half of the sweep that touches real files. Everything here deletes something irreversible
 * in production, so the cases are the ones where a wrong answer costs data: what counts towards
 * a cache, what is off limits, and what happens to a torrent that is still playing.
 */
class CacheStorageServiceTest {
    private val hashA = "a".repeat(40)
    private val hashB = "b".repeat(40)

    private fun cache(root: Path): CacheDirectories {
        val torrents = root.resolve("torrents")
        torrents.resolve(hashA).createDirectories()
        torrents.resolve(hashA).resolve("episode.mkv").writeBytes(ByteArray(4_000))
        torrents.resolve(hashB).createDirectories()
        torrents.resolve(hashB).resolve("episode.mkv").writeBytes(ByteArray(1_000))
        torrents.resolve(CacheDirectories.METADATA_DIRECTORY).createDirectories()
        torrents.resolve(CacheDirectories.METADATA_DIRECTORY).resolve("$hashA.torrent").writeBytes(ByteArray(30))
        torrents.resolve(CacheDirectories.METADATA_DIRECTORY).resolve("$hashB.torrent").writeBytes(ByteArray(20))
        root.resolve("image-cache/w500").createDirectories()
        root.resolve("image-cache/w500/poster.jpg").writeBytes(ByteArray(300))
        return CacheDirectories(
            torrents = torrents,
            images = root.resolve("image-cache"),
            tools = root.resolve("tools"),
        )
    }

    private fun temporaryRoot(): Path = Files.createTempDirectory("cove-cache-test")

    @Test
    fun `usage reports each cache separately and omits directories this host lacks`() = runTest {
        val root = temporaryRoot()
        val usage = CacheStorageService(cache(root)).usage()
        val byKind = usage.entries.associateBy { it.kind }

        // Downloads are counted per torrent over the hash directories only. Fails if the walk
        // starts at the torrents root, which would fold the metadata into the downloads figure
        // and then report the same bytes again under its own heading.
        assertEquals(5_000, byKind.getValue(CacheKind.TorrentDownloads).bytes)
        assertEquals(2, byKind.getValue(CacheKind.TorrentDownloads).items)
        assertEquals(50, byKind.getValue(CacheKind.TorrentMetadata).bytes)
        assertEquals(2, byKind.getValue(CacheKind.TorrentMetadata).items)
        assertEquals(300, byKind.getValue(CacheKind.Images).bytes)
        // The tools directory was never created. Fails if a missing cache is reported as an
        // empty one, which puts a permanent "0 bytes" row on a screen that has no such cache.
        assertFalse(CacheKind.Tools in byKind)
        assertEquals(5_350, usage.totalBytes)
    }

    @Test
    fun `clearing downloads leaves the torrent that is playing and its metadata alone`() = runTest {
        val root = temporaryRoot()
        val directories = cache(root)
        val service = CacheStorageService(directories, activeHashes = { setOf(hashA) })

        val result = service.clear(CacheKind.TorrentDownloads)

        // Only the idle torrent's bytes are freed, and the caller is told one was held back so
        // the screen can say so rather than appearing to have done nothing.
        assertEquals(1_000, result.freedBytes)
        assertEquals(1, result.keptInUse)
        assertTrue(Files.exists(directories.torrents!!.resolve(hashA)))
        assertFalse(Files.exists(directories.torrents!!.resolve(hashB)))
        // Metadata is its own row and its own decision, and this is asserted about the torrent
        // that was actually deleted — B, not the one held back. Fails if clearing content takes
        // the parsed torrent with it, which costs a DHT lookup on every future replay.
        assertTrue(Files.exists(directories.torrentMetadata!!.resolve("$hashB.torrent")))
        assertTrue(Files.exists(directories.torrentMetadata!!.resolve("$hashA.torrent")))
    }

    @Test
    fun `an active hash is matched whatever case it is reported in`() = runTest {
        val root = temporaryRoot()
        val directories = cache(root)
        val service = CacheStorageService(directories, activeHashes = { setOf(hashA.uppercase()) })

        service.clear(CacheKind.TorrentDownloads)

        // The engines canonicalise to lowercase but the hash arrives from an addon in whatever
        // case it was written. Fails on a raw set membership test, and the failure is the file
        // being streamed getting deleted mid-playback.
        assertTrue(Files.exists(directories.torrents!!.resolve(hashA)))
    }

    @Test
    fun `the sweep evicts by the journal's read order rather than by file times`() = runTest {
        val root = temporaryRoot()
        val directories = cache(root)
        // The journal says A was read first; the filesystem says the opposite. A sweep reading
        // modification times instead of read times evicts the wrong one, and that is the whole
        // reason the journal exists — libtorrent rewrites a sparse file as pieces land, so its
        // mtime tracks downloading rather than watching.
        var journalClock = 1_000L
        val journal = TorrentCacheJournal(directories.torrents!!) { journalClock }
        journal.touch(hashA)
        journalClock = 2_000L
        journal.touch(hashB)
        Files.setLastModifiedTime(
            directories.torrents!!.resolve(hashB),
            java.nio.file.attribute.FileTime.fromMillis(1_000),
        )
        Files.setLastModifiedTime(
            directories.torrents!!.resolve(hashA),
            java.nio.file.attribute.FileTime.fromMillis(2_000),
        )
        val service = CacheStorageService(directories, journal = journal, clock = { 3_000L })

        service.enforce(TorrentCachePolicy(limitBytes = 4_000))

        // 5,000 cached against a 4,000 limit: exactly one has to go, and it must be the one read
        // first. Fails if lastReadAt falls through to the directory mtime while a journal entry
        // exists, or if the eviction loop keeps going after the total is under the limit.
        assertFalse(Files.exists(directories.torrents!!.resolve(hashA)))
        assertTrue(Files.exists(directories.torrents!!.resolve(hashB)))
    }

    @Test
    fun `an old cache from before the journal is not expired on the first sweep`() = runTest {
        val root = temporaryRoot()
        val directories = cache(root)
        val ancient = java.nio.file.attribute.FileTime.fromMillis(1_000)
        Files.setLastModifiedTime(directories.torrents!!.resolve(hashA), ancient)
        Files.setLastModifiedTime(directories.torrents!!.resolve(hashB), ancient)
        val now = 1_800_000_000_000
        val journal = TorrentCacheJournal(directories.torrents!!) { now }

        CacheStorageService(directories, journal = journal, clock = { now })
            .enforce(TorrentCachePolicy(maxAgeDays = 30))

        // The upgrade case, and the one that would cost a viewer gigabytes without warning:
        // everything already on disk predates the journal, and dating it from the filesystem
        // makes a thirty-day policy retroactive the moment the app is first restarted. Fails if
        // an unknown torrent falls back to its modification time instead of being met now.
        assertTrue(Files.exists(directories.torrents!!.resolve(hashA)))
        assertTrue(Files.exists(directories.torrents!!.resolve(hashB)))
        // Met once and remembered, so the window runs from here rather than restarting on every
        // sweep — otherwise nothing pre-existing would ever expire at all.
        assertEquals(now, journal.lastReadAt(hashA))
    }

    @Test
    fun `a policy that asks for nothing walks nothing and deletes nothing`() = runTest {
        val root = temporaryRoot()
        val directories = cache(root)

        val result = CacheStorageService(directories).enforce(TorrentCachePolicy())

        // The default on a host that has never opened the storage screen. Fails if an unset
        // limit reads as a limit of zero, which would delete every download at first launch.
        assertEquals(0, result.freedBytes)
        assertTrue(Files.exists(directories.torrents!!.resolve(hashA)))
        assertTrue(Files.exists(directories.torrents!!.resolve(hashB)))
    }

    @Test
    fun `a torrent the engine refuses to release is left on disk`() = runTest {
        val root = temporaryRoot()
        val directories = cache(root)
        val refused = mutableListOf<String>()
        val service = CacheStorageService(
            directories = directories,
            // Nothing is reported as being read, but the engine declines when actually asked —
            // the race the gate exists for, where playback starts between the sweep choosing a
            // torrent and reaching it.
            activeHashes = { emptySet() },
            release = { hash -> (hash != hashA).also { if (!it) refused += hash } },
        )

        val result = service.clear(CacheKind.TorrentDownloads)

        // Fails if the delete runs regardless of the answer, which pulls the file out from under
        // a player mid-frame, and fails if the refusal is silently dropped from the count.
        assertEquals(listOf(hashA), refused)
        assertTrue(Files.exists(directories.torrents!!.resolve(hashA)))
        assertFalse(Files.exists(directories.torrents!!.resolve(hashB)))
        assertEquals(1_000, result.freedBytes)
        assertEquals(1, result.keptInUse)
    }

    @Test
    fun `the sweep releases a torrent from the session before deleting it`() = runTest {
        val root = temporaryRoot()
        val directories = cache(root)
        val released = mutableListOf<String>()
        val journal = TorrentCacheJournal(directories.torrents!!) { 1_000L }
        journal.touch(hashA)
        journal.touch(hashB)

        CacheStorageService(
            directories = directories,
            journal = journal,
            release = { released += it; true },
            clock = { 1_000L },
        ).enforce(TorrentCachePolicy(limitBytes = 1))

        // Both go, and both were released first. Fails if the sweep deletes without asking:
        // libtorrent keeps writing into a file whose handle it still holds, so the space comes
        // straight back and the cap never actually holds.
        assertEquals(setOf(hashA, hashB), released.toSet())
        assertFalse(Files.exists(directories.torrents!!.resolve(hashA)))
        assertFalse(Files.exists(directories.torrents!!.resolve(hashB)))
    }

    @Test
    fun `the journal forgets torrents it deleted and survives a reload`() = runTest {
        val root = temporaryRoot()
        val directories = cache(root)
        val journal = TorrentCacheJournal(directories.torrents!!)
        journal.touch(hashA)
        journal.touch(hashB)
        journal.flush()

        CacheStorageService(directories, journal = journal, activeHashes = { setOf(hashA) })
            .clear(CacheKind.TorrentDownloads)

        val reloaded = TorrentCacheJournal(directories.torrents!!)
        // The entry for a deleted torrent has to go, or the index grows without bound across
        // years of viewing. The surviving one has to stay, or every restart resets the eviction
        // order to "whatever the filesystem says".
        assertEquals(null, reloaded.lastReadAt(hashB))
        assertTrue((reloaded.lastReadAt(hashA) ?: 0) > 0)
    }
}
