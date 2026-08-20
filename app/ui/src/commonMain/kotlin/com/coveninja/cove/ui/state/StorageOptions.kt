package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.data.CacheKind

/**
 * The values the storage controls offer, and the words they are shown with.
 *
 * One list per setting, shared by both shells: the desktop draws them as pills and the television
 * cycles through them on centre-press, but a phone and a TV disagreeing about what "20 GiB" means
 * would be a bug nobody would find until two devices showed different numbers for the same
 * profile. Sizes are binary throughout, matching what the rest of the app reports.
 */
private const val GIB = 1024L * 1024 * 1024
private const val MIB = 1024L * 1024

/** Zero is "no limit", last so the list reads as increasing generosity. */
internal val CacheLimitChoices: List<Long> = listOf(5 * GIB, 10 * GIB, 20 * GIB, 50 * GIB, 100 * GIB, 0)

internal fun cacheLimitLabel(bytes: Long): String =
    if (bytes <= 0) "No limit" else "${bytes / GIB} GiB"

/**
 * How far past the playhead the download may run.
 *
 * The smallest option is deliberately well above any plausible buffer: this bounds background
 * fetching, not the read-ahead the player depends on, and a value tight enough to interfere with
 * seeking would look like a broken stream rather than a storage setting.
 */
internal val DownloadAheadChoices: List<Long> = listOf(256 * MIB, 512 * MIB, GIB, 2 * GIB, 0)

internal fun downloadAheadLabel(bytes: Long): String = when {
    bytes <= 0 -> "Whole file"
    bytes >= GIB -> "${bytes / GIB} GiB"
    else -> "${bytes / MIB} MiB"
}

internal val CacheAgeChoices: List<Int> = listOf(7, 14, 30, 90, 0)

internal fun cacheAgeLabel(days: Int): String = when {
    days <= 0 -> "Forever"
    days == 1 -> "1 day"
    else -> "$days days"
}

internal fun cacheKindLabel(kind: CacheKind): String = when (kind) {
    CacheKind.TorrentDownloads -> "Downloads"
    CacheKind.TorrentMetadata -> "Torrent details"
    CacheKind.Images -> "Artwork"
    CacheKind.Tools -> "Downloaded tools"
}

internal fun cacheKindDescription(kind: CacheKind): String = when (kind) {
    CacheKind.TorrentDownloads ->
        "Video files kept from what you have streamed. Anything playing right now is left alone."
    CacheKind.TorrentMetadata ->
        "A few kilobytes per title that let a rewatch start without searching for peers again."
    CacheKind.Images -> "Posters and backdrops. Clearing this means fetching them again."
    CacheKind.Tools -> "yt-dlp, which trailers need. Cove downloads it again when one is played."
}

/** The count beside a row, phrased for what that cache actually holds. */
internal fun cacheKindItems(kind: CacheKind, items: Int): String = when (kind) {
    CacheKind.TorrentDownloads -> if (items == 1) "1 download" else "$items downloads"
    CacheKind.TorrentMetadata -> if (items == 1) "1 title" else "$items titles"
    CacheKind.Images -> if (items == 1) "1 image" else "$items images"
    CacheKind.Tools -> if (items == 1) "1 file" else "$items files"
}

/**
 * Puts the option list in front of a value that is not on it.
 *
 * Reachable in ordinary use: the defaults differ between the desktop and Android, a build can add
 * an option a later one drops, and the policy is written per device rather than negotiated. A
 * control that silently showed the nearest neighbour would misreport the setting, so the real
 * value is added where it belongs in the ordering instead.
 */
internal fun <T : Comparable<T>> withCurrent(choices: List<T>, current: T, unlimited: T): List<T> {
    if (current in choices) return choices
    val (bounded, open) = choices.partition { it != unlimited }
    return (bounded + current).sorted() + open
}
