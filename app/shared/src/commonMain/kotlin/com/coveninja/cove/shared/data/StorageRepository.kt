package com.coveninja.cove.shared.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One of the caches Cove keeps on disk, as the storage screen lists them. */
enum class CacheKind {
    /** Torrent content: by far the largest, and the only one that reaches gigabytes. */
    TorrentDownloads,

    /** Parsed `.torrent` files, kept so a replay skips the DHT lookup. Kilobytes. */
    TorrentMetadata,

    /** Proxied TMDB artwork. */
    Images,

    /** Binaries Cove downloaded for itself, currently yt-dlp. */
    Tools,
}

/** What one cache occupies right now. [items] is the entry count the screen reports. */
data class CacheEntry(val kind: CacheKind, val bytes: Long, val items: Int)

/**
 * A snapshot of everything Cove is storing.
 *
 * [entries] carries only the kinds this host actually has a directory for, so Android — which
 * bundles yt-dlp rather than downloading it — simply lists one row fewer, without a zero-byte
 * entry that reads as a cache waiting to fill up.
 */
data class StorageUsage(
    val entries: List<CacheEntry>,
    val totalBytes: Long,
    val freeDiskBytes: Long,
)

sealed interface StorageUsageState {
    data object Loading : StorageUsageState
    data class Ready(val usage: StorageUsage) : StorageUsageState
    data class Failed(val message: String) : StorageUsageState
}

/**
 * How long torrent downloads are kept, and how far past the playhead they are fetched.
 *
 * Zero means "no limit" in every field that carries one — one convention across bytes and days,
 * rather than three nullable types that each have to be unwrapped at the point of use.
 *
 * [downloadAheadBytes] is the one that changes playback rather than cleanup: libtorrent is asked
 * for pieces only this far ahead of what has actually been read, so quitting part-way through
 * stops the download instead of quietly finishing the file. Zero restores the older behaviour of
 * fetching the whole thing.
 */
data class TorrentCachePolicy(
    val limitBytes: Long = 0,
    val downloadAheadBytes: Long = 0,
    val deleteAfterWatching: Boolean = false,
    val maxAgeDays: Int = 0,
)

/**
 * The outcome of a manual clear.
 *
 * [keptInUse] is not an error: a torrent being streamed right now is skipped rather than deleted
 * out from under the player, and the screen says so instead of reporting a clear that silently
 * did less than it claimed.
 */
data class ClearResult(val freedBytes: Long, val keptInUse: Int)

/**
 * Disk retention for this installation.
 *
 * Deliberately not part of [SettingsRepository]: a disk budget belongs to the machine, not to the
 * person. Profile settings roam through Supabase sync, and a 20 GB allowance chosen on a desktop
 * would arrive on a phone as a promise its storage cannot keep. This sits beside
 * [DeviceRepository] for the same reason the mpv config does.
 */
interface StorageRepository {
    /** False where the caches are not on this machine — a remote backend over `--api-base`. */
    val available: Boolean

    val policy: StateFlow<TorrentCachePolicy>
    val usage: StateFlow<StorageUsageState>

    /** Re-walks the cache directories. Costs a directory scan, so it is called, not polled. */
    suspend fun refresh()

    /** Writes the policy and applies it immediately, so a lowered limit acts at once. */
    suspend fun setPolicy(policy: TorrentCachePolicy)

    suspend fun clear(kind: CacheKind): ClearResult
}

/** Stands in where none of this exists — see [UnavailableDeviceRepository]. */
object UnavailableStorageRepository : StorageRepository {
    override val available: Boolean = false
    override val policy = MutableStateFlow(TorrentCachePolicy())
    override val usage = MutableStateFlow<StorageUsageState>(StorageUsageState.Loading)

    override suspend fun refresh() = Unit
    override suspend fun setPolicy(policy: TorrentCachePolicy) = Unit
    override suspend fun clear(kind: CacheKind) = ClearResult(freedBytes = 0, keptInUse = 0)
}
