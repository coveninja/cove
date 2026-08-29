package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.MediaTimestamps
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.StreamSource
import com.coveninja.cove.shared.model.SubtitleSource
import com.coveninja.cove.shared.model.TorrentProgress

/**
 * Resolves playable sources for a title and turns a chosen source into a URL the
 * player can open.
 *
 * Both halves must be served by the same backend boundary. `/api/play?url=` only
 * accepts a URL that a previous `/api/streams` call registered (see the stream
 * registry in the backend's MediaBoundary); resolving sources some other way and
 * handing the raw upstream URL to the player earns a 403.
 */
interface PlaybackRepository {
    /**
     * season and episode are required when [type] is [MediaType.Tv] — the backend
     * builds a Stremio `imdb:season:episode` id and rejects the request without them.
     *
     * @param refresh ask the providers again rather than answering from the addon
     *   listing cache. Reserved for a viewer who has explicitly asked to retry a stream
     *   that stopped: a link an addon mints per request is dead in the cache too, and
     *   the cached answer is exactly the one that just failed. Every other caller wants
     *   the cache — this is one fan-out across every enabled provider.
     */
    suspend fun streams(
        tmdbId: Int,
        type: MediaType,
        season: Int? = null,
        episode: Int? = null,
        refresh: Boolean = false,
    ): List<StreamSource>

    /**
     * @throws IllegalArgumentException if [source] carries neither a url nor an infoHash.
     */
    fun playUrl(
        source: StreamSource,
        season: Int? = null,
        episode: Int? = null,
    ): String

    /**
     * Intro/recap/credits ranges for the seek bar. Never throws — timestamps are
     * decoration, and a title without them is the normal case, not a failure.
     */
    suspend fun timestamps(
        tmdbId: Int,
        season: Int? = null,
        episode: Int? = null,
    ): MediaTimestamps

    /**
     * Subtitle files offered by addons, already pointed at the proxy that
     * converts and validates them. Never throws — a title with none is ordinary.
     */
    suspend fun subtitles(
        tmdbId: Int,
        type: MediaType,
        season: Int? = null,
        episode: Int? = null,
    ): List<SubtitleSource>

    /**
     * URLs from [urls] that still answer, checking at most [MAX_PROBED_URLS] of them. A failed
     * probe returns everything — losing a working source to a flaky check is worse than offering
     * a dead one.
     *
     * Callers must read the result as "these were reached", not "the rest are dead": a URL the
     * probe had no room for is simply unknown, and dropping those silently cut every source list
     * down to the probe's own budget.
     */
    suspend fun aliveUrls(urls: List<String>): Set<String>

    /** Null when the torrent is not active. */
    suspend fun torrentProgress(hash: String): TorrentProgress?

    companion object {
        /** How many URLs one probe covers. The HTTP boundary rejects a request beyond this. */
        const val MAX_PROBED_URLS = 10
    }
}

/** Thrown when a source list comes back but nothing in it can actually be played. */
class NoPlayableSourceException(message: String) : Exception(message)
