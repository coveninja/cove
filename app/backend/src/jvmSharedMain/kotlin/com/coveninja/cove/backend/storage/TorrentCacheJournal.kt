package com.coveninja.cove.backend.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * When each cached torrent was last read.
 *
 * This is what makes the whole retention policy work: it orders the LRU eviction, it dates the
 * age expiry, and the gap since the last entry is how "delete after watching" tells a quit from
 * a pause. libtorrent does not record it, and the filesystem is no help — access times are off on
 * most Linux mounts and meaningless inside a sparse file libtorrent rewrites as pieces land.
 *
 * One index file for every torrent rather than a marker inside each directory, so libtorrent's
 * save folders stay exactly as it left them and the sweep has one thing to keep consistent.
 *
 * [touch] sits on the byte-serving path, called once per chunk, so it only ever writes memory —
 * the file is flushed at most once a [FLUSH_INTERVAL_MILLIS] window. Losing up to a minute of
 * timestamps to a crash costs nothing: the entry is at worst a minute stale, and a torrent read
 * seconds before the process died is not one the next sweep should be evicting anyway.
 */
class TorrentCacheJournal(
    private val root: Path,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val file = root.resolve(JOURNAL_FILE).toAbsolutePath().normalize()
    private val entries = ConcurrentHashMap<String, Long>()
    private val loaded = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var flushedAt = 0L

    @Volatile
    private var dirty = false

    /** [at] is for the sweep dating a torrent it has just met; playback leaves it to the clock. */
    fun touch(hash: String, at: Long = clock()) {
        load()
        entries[hash.lowercase()] = at
        dirty = true
        if (clock() - flushedAt >= FLUSH_INTERVAL_MILLIS) flush()
    }

    /** Null for a torrent this journal has never seen — the caller falls back to the file times. */
    fun lastReadAt(hash: String): Long? {
        load()
        return entries[hash.lowercase()]
    }

    fun forget(hashes: Collection<String>) {
        if (hashes.isEmpty()) return
        load()
        hashes.forEach { entries.remove(it.lowercase()) }
        dirty = true
        flush()
    }

    @Synchronized
    fun flush() {
        if (!dirty) return
        val snapshot = entries.entries.sortedBy { it.key }
        val written = runCatching {
            Files.createDirectories(file.parent)
            val temporary = file.resolveSibling("${file.fileName}.tmp-${UUID.randomUUID()}")
            // java.io rather than Files.writeString: this file is compiled for Android too, and
            // its java.nio.file is the Java 8 subset — writeString and readString are not in it.
            temporary.toFile().writeText(
                buildString {
                    for ((hash, millis) in snapshot) {
                        append(hash).append('=').append(millis).append('\n')
                    }
                },
            )
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }.isSuccess
        // A journal that cannot be written is a cache that evicts by file timestamps instead of
        // by read order — degraded, not broken, and never a reason to fail a torrent read.
        if (written) {
            dirty = false
            flushedAt = clock()
        }
    }

    @Synchronized
    private fun load() {
        if (!loaded.compareAndSet(false, true)) return
        flushedAt = clock()
        if (!Files.isRegularFile(file)) return
        runCatching { file.toFile().readLines() }.getOrDefault(emptyList()).forEach { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            val hash = line.substring(0, separator).trim().lowercase()
            val millis = line.substring(separator + 1).trim().toLongOrNull() ?: return@forEach
            entries[hash] = millis
        }
    }

    companion object {
        /** Dot-prefixed so it sorts away from the hash directories and reads as Cove's own. */
        const val JOURNAL_FILE = ".cove-cache-index"
        private const val FLUSH_INTERVAL_MILLIS = 60_000L
    }
}
