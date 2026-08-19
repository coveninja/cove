package com.coveninja.cove.backend.storage

import com.coveninja.cove.shared.data.TorrentCachePolicy
import java.nio.file.Path

/** One cached torrent, as the sweep sees it: what it costs and when it was last read. */
data class CachedTorrent(
    val hash: String,
    val path: Path,
    val bytes: Long,
    val lastReadAt: Long,
)

/**
 * Decides which cached torrents to delete. Pure — ordering and arithmetic, no filesystem.
 *
 * Three rules compose, and the order they are applied in matters: expiry and the
 * delete-after-watching sweep run first and shrink the total, so the size cap only evicts what
 * is still left over afterwards. Doing the cap first would evict recently-watched torrents to
 * make room for ones that were about to be dropped anyway.
 *
 * [active] is never touched. A torrent being streamed right now is occupying the disk and counts
 * against the limit, but deleting it would pull the file out from under the player mid-frame — so
 * the sweep goes over budget rather than break playback, and the caller reports how many it kept.
 */
internal fun planTorrentSweep(
    entries: List<CachedTorrent>,
    policy: TorrentCachePolicy,
    active: Set<String>,
    now: Long,
): List<CachedTorrent> {
    val candidates = entries.filter { it.hash !in active }
    // Insertion-ordered so a caller deleting in sequence sees expiry before eviction, and so a
    // torrent caught by two rules is listed once rather than deleted twice.
    val removals = LinkedHashSet<CachedTorrent>()

    if (policy.maxAgeDays > 0) {
        val cutoff = now - policy.maxAgeDays * MILLIS_PER_DAY
        candidates.filterTo(removals) { it.lastReadAt < cutoff }
    }

    // "Delete after watching" cannot mean "the instant the last byte is served": mpv closes and
    // reopens ranges throughout playback, and a pause would look identical to a quit. A grace
    // window after the final read is what tells them apart.
    if (policy.deleteAfterWatching) {
        val cutoff = now - WATCHED_GRACE_MILLIS
        candidates.filterTo(removals) { it.lastReadAt < cutoff }
    }

    if (policy.limitBytes > 0) {
        var total = entries.sumOf { it.bytes } - removals.sumOf { it.bytes }
        if (total > policy.limitBytes) {
            // Least recently read first: the torrent nobody has opened in weeks is the one whose
            // deletion costs the least if it turns out to have been wanted after all.
            for (entry in candidates.asSequence().filterNot(removals::contains).sortedBy { it.lastReadAt }) {
                if (total <= policy.limitBytes) break
                removals.add(entry)
                total -= entry.bytes
            }
        }
    }

    return removals.toList()
}

/**
 * True for a directory the sweep is allowed to delete.
 *
 * Deliberately strict, because this is the check standing between a cleanup pass and someone's
 * data: the name must be a 40-character info hash exactly as the engines write it, which excludes
 * `metadata`, the journal, and anything a user happened to leave in the folder. The resolved path
 * must also still be inside the torrent root, so a symlink or a crafted name cannot walk out of it.
 */
internal fun isDeletableTorrentDirectory(root: Path, candidate: Path): Boolean {
    if (!TORRENT_DIRECTORY_NAME.matches(candidate.fileName?.toString().orEmpty())) return false
    val normalizedRoot = root.toAbsolutePath().normalize()
    val normalized = candidate.toAbsolutePath().normalize()
    return normalized.startsWith(normalizedRoot) && normalized != normalizedRoot
}

/** Lowercase because both engines canonicalise the hash before using it as a directory name. */
private val TORRENT_DIRECTORY_NAME = Regex("^[a-f0-9]{40}$")

private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

/** How long after the last byte was read a torrent counts as no longer being watched. */
internal const val WATCHED_GRACE_MILLIS = 5L * 60 * 1000
