package com.coveninja.cove.backend.storage

import com.coveninja.cove.shared.data.CacheKind
import java.nio.file.Path

/**
 * Where each cache lives on this host.
 *
 * Any of these may be null, and that is how a host says it does not have that cache at all rather
 * than that the cache is empty: Android bundles yt-dlp instead of downloading it, so it has no
 * tools directory, and the storage screen shows one row fewer rather than a permanent zero.
 *
 * Torrent metadata is not a separate parameter because it is not a separate choice — the engines
 * write it inside the torrent directory, and listing it apart from the content is a presentation
 * decision, not a wiring one.
 */
data class CacheDirectories(
    val torrents: Path? = null,
    val images: Path? = null,
    val tools: Path? = null,
) {
    val torrentMetadata: Path? get() = torrents?.resolve(METADATA_DIRECTORY)

    fun directoryFor(kind: CacheKind): Path? = when (kind) {
        CacheKind.TorrentDownloads -> torrents
        CacheKind.TorrentMetadata -> torrentMetadata
        CacheKind.Images -> images
        CacheKind.Tools -> tools
    }

    companion object {
        /** Matches the name both engines resolve when caching parsed `.torrent` files. */
        const val METADATA_DIRECTORY = "metadata"
    }
}
