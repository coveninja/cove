package com.coveninja.cove.backend.torrent

import com.frostwire.jlibtorrent.TorrentInfo
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlinx.coroutines.delay

/**
 * Getting a torrent's metadata, which is the whole of a cold start.
 *
 * Shared rather than written twice because it already was: the desktop engine learned each of
 * these lessons and the Android one did not, so a phone kept paying a cold start the desktop
 * had stopped paying — the viewer saw "The selected stream could not be opened" on the first
 * play of anything and a working stream on the second.
 */

/**
 * Trackers appended to every magnet.
 *
 * A bare `magnet:?xt=urn:btih:` leaves DHT and peer exchange as the only way to find anybody,
 * which on a cold start means waiting out a DHT lookup before the first byte moves — and for a
 * poorly-seeded torrent it can mean never finding the peers a tracker would have handed over
 * immediately. These are the open trackers the public swarms already announce to, so this asks
 * the same servers the torrent's own announce list would have.
 */
internal val DEFAULT_TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.tracker.cl:1337/announce",
    "udp://tracker.openbittorrent.com:6969/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://tracker.torrent.eu.org:451/announce",
)

internal fun magnetUri(hash: String): String = buildString {
    append("magnet:?xt=urn:btih:").append(hash)
    for (tracker in DEFAULT_TRACKERS) {
        append("&tr=").append(URLEncoder.encode(tracker, StandardCharsets.UTF_8))
    }
}

/** How long the session is given to register a torrent that was just added. */
internal const val HANDLE_TIMEOUT_MILLIS = 15_000L

/** How long a torrent may find nobody at all before it is called dead. */
internal const val DEAD_SWARM_MILLIS = 20_000L

/** How often a wait long enough to be noticed reports what it is waiting on. */
internal const val PROGRESS_REPORT_MILLIS = 5_000L

/** Spacing between polls of a torrent handle that has not answered yet. */
internal const val TORRENT_POLL_MILLIS = 50L

/**
 * Reads metadata cached by an earlier play, or null if there is none to read.
 *
 * A corrupt or truncated file reads as "no cache" rather than as an error: the fetch that
 * follows is slow, not broken, and losing a torrent to a bad cache entry would be a far worse
 * trade than paying for the metadata again.
 */
internal fun readCachedMetadata(path: Path, hash: String): TorrentInfo? = runCatching {
    TorrentInfo(path.toFile()).takeIf { info ->
        // The info hash is checked rather than trusted from the file name: a cache entry that
        // decodes to a different torrent would otherwise send the player off downloading
        // something else entirely, and the find() that follows would search for a handle that
        // was never added.
        info.isValid && info.infoHashV1()?.toHex().equals(hash, ignoreCase = true)
    }
}.getOrNull()

/**
 * Waits for a torrent's metadata, saying out loud how it is going.
 *
 * Gives up early on a swarm that has produced nobody at all: a source with zero peers after
 * [DEAD_SWARM_MILLIS] is a dead link, not a slow one, and sitting on it for the full timeout
 * only delays the viewer finding that out. A swarm that has found peers keeps the whole
 * window — that one is working, just slowly.
 *
 * The handle is reached through lambdas rather than passed in, so the waiting itself can be
 * tested without a live session.
 */
internal suspend fun awaitMetadata(
    hash: String,
    timeoutMillis: Long,
    hasMetadata: () -> Boolean,
    peerCount: () -> Int,
    log: (String) -> Unit,
    diagnostics: () -> String = { "" },
    pollMillis: Long = TORRENT_POLL_MILLIS,
    nowMillis: () -> Long = System::currentTimeMillis,
) {
    val started = nowMillis()
    var reportedAt = started
    while (!hasMetadata()) {
        val elapsed = nowMillis() - started
        val peers = runCatching(peerCount).getOrDefault(0)
        if (elapsed >= timeoutMillis) {
            log("$hash: no metadata after ${elapsed / 1_000}s, $peers peers — giving up")
            throw IllegalStateException(
                "timed out fetching torrent metadata after ${elapsed / 1_000}s with $peers peers",
            )
        }
        if (elapsed >= DEAD_SWARM_MILLIS && peers == 0) {
            log("$hash: no peers at all after ${elapsed / 1_000}s — treating the source as dead")
            throw IllegalStateException(
                "no peers found for this torrent after ${elapsed / 1_000}s — the source looks dead",
            )
        }
        if (nowMillis() - reportedAt >= PROGRESS_REPORT_MILLIS) {
            reportedAt = nowMillis()
            // The session's own state alongside the peer count: zero peers with a dead DHT and
            // no trackers is a session that never got started properly, which looks identical
            // from the outside to a torrent nobody is seeding.
            log("$hash: waiting for metadata, ${elapsed / 1_000}s elapsed, $peers peers, ${diagnostics()}")
        }
        delay(pollMillis)
    }
    log("$hash: metadata in ${nowMillis() - started}ms")
}
