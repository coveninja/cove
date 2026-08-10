package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.MediaTimestamps
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.StreamSource

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
     */
    suspend fun streams(
        tmdbId: Int,
        type: MediaType,
        season: Int? = null,
        episode: Int? = null,
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
}

/** Thrown when a source list comes back but nothing in it can actually be played. */
class NoPlayableSourceException(message: String) : Exception(message)
