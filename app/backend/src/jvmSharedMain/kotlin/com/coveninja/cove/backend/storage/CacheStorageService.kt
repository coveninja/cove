package com.coveninja.cove.backend.storage

import com.coveninja.cove.shared.data.CacheEntry
import com.coveninja.cove.shared.data.CacheKind
import com.coveninja.cove.shared.data.ClearResult
import com.coveninja.cove.shared.data.StorageUsage
import com.coveninja.cove.shared.data.TorrentCachePolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Measures and deletes Cove's on-disk caches.
 *
 * Every deletion in here is irreversible and runs unattended, so the guards matter more than the
 * arithmetic: nothing outside a configured cache directory is ever touched, a torrent directory
 * must be named as an info hash to be eligible, and anything the engine reports as currently
 * streaming is skipped no matter which rule selected it.
 *
 * Failures are reported, never thrown. A file locked by another process — routine on Windows, and
 * possible for the yt-dlp binary mid-update — must not abort the rest of a sweep, and a cleanup
 * pass that cannot run is not a reason to fail playback or startup.
 */
class CacheStorageService(
    private val directories: CacheDirectories,
    private val journal: TorrentCacheJournal? = null,
    private val activeHashes: () -> Set<String> = { emptySet() },
    /**
     * Drops a torrent from the peer session, refusing while it is being read.
     *
     * Deleting the files of a torrent libtorrent still holds is not a deletion: the handle keeps
     * the file open and the session carries on writing pieces into it, so the space comes back
     * and then goes again. Defaulted to allowing the delete, for a host with no engine at all.
     */
    private val release: (String) -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun usage(): StorageUsage = withContext(Dispatchers.IO) {
        val entries = CacheKind.entries.mapNotNull { kind ->
            val directory = directories.directoryFor(kind) ?: return@mapNotNull null
            if (!Files.isDirectory(directory)) return@mapNotNull null
            when (kind) {
                // Counted per torrent rather than per file, and only over the hash directories:
                // "3 downloads" is what the viewer recognises, and it keeps the metadata row and
                // the journal from being counted twice under two headings.
                CacheKind.TorrentDownloads -> {
                    val torrents = listCachedTorrents()
                    CacheEntry(kind, torrents.sumOf { it.bytes }, torrents.size)
                }

                else -> {
                    val measured = measure(directory)
                    CacheEntry(kind, measured.bytes, measured.files)
                }
            }
        }
        StorageUsage(
            entries = entries,
            totalBytes = entries.sumOf { it.bytes },
            freeDiskBytes = freeSpace(),
        )
    }

    suspend fun clear(kind: CacheKind): ClearResult = withContext(Dispatchers.IO) {
        val directory = directories.directoryFor(kind) ?: return@withContext EMPTY
        if (!Files.isDirectory(directory)) return@withContext EMPTY
        when (kind) {
            CacheKind.TorrentDownloads -> {
                val active = canonicalActiveHashes()
                val (removable, kept) = listCachedTorrents().partition { it.hash !in active }
                val freed = delete(removable)
                // Counted from what is left rather than from what was planned, so a torrent the
                // engine refused at the last moment is reported as kept instead of vanishing
                // from both totals.
                val remaining = listCachedTorrents().size
                ClearResult(freedBytes = freed, keptInUse = maxOf(kept.size, remaining))
            }
            // The other three have no notion of being in use: an image or a tool binary is read
            // and closed, and the worst a clear during use can cost is one re-download.
            else -> ClearResult(freedBytes = deleteContents(directory), keptInUse = 0)
        }
    }

    /** Applies the retention policy. A no-op policy walks the directory and deletes nothing. */
    suspend fun enforce(policy: TorrentCachePolicy): ClearResult = withContext(Dispatchers.IO) {
        if (!policy.sweeps()) return@withContext EMPTY
        val root = directories.torrents ?: return@withContext EMPTY
        if (!Files.isDirectory(root)) return@withContext EMPTY
        val active = canonicalActiveHashes()
        val entries = listCachedTorrents()
        val removals = planTorrentSweep(entries, policy, active, clock())
        if (removals.isEmpty()) return@withContext EMPTY
        ClearResult(
            freedBytes = delete(removals),
            // Only counted when the limit is what could not be honoured. An untouched torrent
            // that is simply still being watched is not something to report as kept back.
            keptInUse = if (policy.limitBytes > 0 && entries.sumOf { it.bytes } - removals.sumOf { it.bytes } > policy.limitBytes) {
                entries.count { it.hash in active }
            } else {
                0
            },
        )
    }

    private fun delete(torrents: List<CachedTorrent>): Long {
        val root = directories.torrents ?: return 0
        var freed = 0L
        val removed = mutableListOf<String>()
        for (torrent in torrents) {
            if (!isDeletableTorrentDirectory(root, torrent.path)) continue
            // The last word on whether this is safe, and it is the engine's rather than the
            // plan's: a read can begin between the sweep choosing a torrent and reaching it.
            if (!runCatching { release(torrent.hash) }.getOrDefault(false)) continue
            freed += deleteTree(torrent.path)
            removed += torrent.hash
            // Metadata is a few kilobytes and saves a DHT lookup on the next play, so it outlives
            // the content it describes on purpose — clearing it is its own row on the screen.
        }
        journal?.forget(removed)
        return freed
    }

    private fun listCachedTorrents(): List<CachedTorrent> {
        val root = directories.torrents ?: return emptyList()
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { stream ->
            stream.toList()
                .filter { Files.isDirectory(it) && isDeletableTorrentDirectory(root, it) }
                .map { path ->
                    val hash = path.fileName.toString()
                    CachedTorrent(
                        hash = hash,
                        path = path,
                        bytes = measure(path).bytes,
                        lastReadAt = journal?.lastReadAt(hash) ?: firstSeen(hash),
                    )
                }
        }
    }

    private fun measure(directory: Path): Measured {
        var bytes = 0L
        var files = 0
        runCatching {
            Files.walk(directory).use { stream ->
                stream.forEach { path ->
                    if (!Files.isRegularFile(path)) return@forEach
                    bytes += runCatching { Files.size(path) }.getOrDefault(0L)
                    files += 1
                }
            }
        }
        return Measured(bytes, files)
    }

    private fun deleteContents(directory: Path): Long {
        var freed = 0L
        runCatching {
            Files.list(directory).use { stream -> stream.toList() }
        }.getOrDefault(emptyList()).forEach { freed += deleteTree(it) }
        return freed
    }

    /** Depth-first, so a directory is only removed once it is empty. Best effort throughout. */
    private fun deleteTree(path: Path): Long {
        var freed = 0L
        runCatching {
            Files.walk(path).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { entry ->
                    val size = if (Files.isRegularFile(entry)) {
                        runCatching { Files.size(entry) }.getOrDefault(0L)
                    } else {
                        0L
                    }
                    if (runCatching { Files.deleteIfExists(entry) }.getOrDefault(false)) freed += size
                }
            }
        }
        return freed
    }

    /**
     * Dates a torrent the journal has never seen, and remembers the answer.
     *
     * "Now" rather than the directory's modification time, and the difference is the first launch
     * after this feature arrives. Everything already cached predates the journal, and dating it
     * from the filesystem would make the age rule retroactive: a viewer who upgraded would lose
     * gigabytes to a thirty-day policy they had not yet been shown, for downloads that were
     * perfectly acceptable to keep under every previous version. Starting the clock here gives
     * those a full window, and costs only that their eviction order is arbitrary until each is
     * next played. The size limit still applies immediately — that one is bounded and asked for.
     */
    private fun firstSeen(hash: String): Long {
        val now = clock()
        journal?.touch(hash, at = now)
        return now
    }

    private fun canonicalActiveHashes(): Set<String> =
        runCatching { activeHashes() }.getOrDefault(emptySet()).mapTo(mutableSetOf()) { it.lowercase() }

    private fun freeSpace(): Long {
        val probe = CacheKind.entries
            .asSequence()
            .mapNotNull(directories::directoryFor)
            .firstOrNull { Files.isDirectory(it) }
            ?: return 0
        return runCatching { Files.getFileStore(probe).usableSpace }.getOrDefault(0L)
    }

    private data class Measured(val bytes: Long, val files: Int)

    private companion object {
        val EMPTY = ClearResult(freedBytes = 0, keptInUse = 0)
    }
}

/** True when the policy asks for anything at all, so an unrestricted one costs no directory walk. */
internal fun TorrentCachePolicy.sweeps(): Boolean =
    limitBytes > 0 || maxAgeDays > 0 || deleteAfterWatching
