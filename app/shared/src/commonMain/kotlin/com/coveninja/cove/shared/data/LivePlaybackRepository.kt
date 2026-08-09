package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.StreamSource
import com.coveninja.cove.shared.network.CoveApi

/**
 * Playback over the HTTP boundary. Used for both the embedded Kotlin backend and
 * an external `--api-base`: the embedded runtime talks to its own loopback host
 * rather than reaching into the services in-process, because `/api/play` only
 * honours URLs that a prior `/api/streams` call registered.
 */
class LivePlaybackRepository(private val api: CoveApi) : PlaybackRepository {

    override suspend fun streams(
        tmdbId: Int,
        type: MediaType,
        season: Int?,
        episode: Int?,
    ): List<StreamSource> = api.streams(tmdbId, type, season, episode)

    override fun playUrl(source: StreamSource, season: Int?, episode: Int?): String {
        // A direct url wins over a torrent hash: an addon that supplies both is
        // offering an already-resolved file, and streaming it avoids spinning up
        // the torrent engine for no gain.
        val url = source.url?.takeIf { it.isNotBlank() }
        if (url != null) return api.playUrl(url = url)

        val hash = source.infoHash?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException(
                "stream \"${source.name ?: source.title ?: "unnamed"}\" has neither a url nor an infoHash",
            )
        return api.playUrl(
            hash = hash,
            season = season,
            episode = episode,
            fileIdx = source.fileIdx,
        )
    }
}

/**
 * Stands in when the backend runs without its HTTP host (tests, and any future
 * headless embedding). Playback fundamentally needs that host — the player opens
 * an HTTP URL — so failing here with the reason beats a connection-refused later.
 */
object UnavailablePlaybackRepository : PlaybackRepository {
    private const val REASON = "playback is unavailable: the backend HTTP host is not running"

    override suspend fun streams(
        tmdbId: Int,
        type: MediaType,
        season: Int?,
        episode: Int?,
    ): List<StreamSource> = throw IllegalStateException(REASON)

    override fun playUrl(source: StreamSource, season: Int?, episode: Int?): String =
        throw IllegalStateException(REASON)
}
