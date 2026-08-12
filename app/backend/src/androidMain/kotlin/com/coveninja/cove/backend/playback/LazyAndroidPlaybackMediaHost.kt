package com.coveninja.cove.backend.playback

import com.coveninja.cove.backend.addons.AddonStream
import com.coveninja.cove.backend.http.ProbeStreamsRequest
import com.coveninja.cove.backend.http.ProbeStreamsResponse
import com.coveninja.cove.backend.http.RouteMediaBoundary
import com.coveninja.cove.backend.torrent.TorrentProgress
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Defers the native playback boundary until a playback or compatibility-route
 * request actually needs it.
 *
 * Starting [AndroidPlaybackMediaHost] binds a loopback server and waits for its
 * connector. Doing that while assembling the application graph made every mobile
 * launch pay for playback even when the viewer only browsed the catalog.
 */
internal class LazyAndroidPlaybackMediaHost(
    private val factory: () -> AndroidPlaybackMediaHost,
) : RouteMediaBoundary, AutoCloseable {
    @Volatile
    private var instance: AndroidPlaybackMediaHost? = null
    private var closed = false

    private suspend fun host(): AndroidPlaybackMediaHost = instance ?: withContext(Dispatchers.IO) {
        createHost()
    }

    @Synchronized
    private fun createHost(): AndroidPlaybackMediaHost {
        check(!closed) { "playback media host is closed" }
        return instance ?: factory().also { instance = it }
    }

    fun directUrl(url: String): String = requireStarted().directUrl(url)

    fun torrentUrl(hash: String, season: Int?, episode: Int?, fileIndex: Int?): String =
        requireStarted().torrentUrl(hash, season, episode, fileIndex)

    suspend fun subtitleUrl(url: String): String = host().subtitleUrl(url)

    suspend fun aliveUrls(urls: List<String>): Set<String> = host().aliveUrls(urls)

    private fun requireStarted(): AndroidPlaybackMediaHost = checkNotNull(instance) {
        "list streams before requesting a playback URL"
    }

    override suspend fun registerStreams(candidates: List<AddonStream>): List<AddonStream> =
        host().registerStreams(candidates)

    override suspend fun playDirect(call: ApplicationCall, url: String) =
        host().playDirect(call, url)

    override suspend fun playTorrent(
        call: ApplicationCall,
        hash: String,
        season: Int?,
        episode: Int?,
        fileIndex: Int?,
    ) = host().playTorrent(call, hash, season, episode, fileIndex)

    override fun torrentProgress(hash: String): TorrentProgress? =
        instance?.torrentProgress(hash)

    override suspend fun probe(request: ProbeStreamsRequest): ProbeStreamsResponse =
        host().probe(request)

    override suspend fun subtitle(call: ApplicationCall, url: String) =
        host().subtitle(call, url)

    override suspend fun image(call: ApplicationCall, size: String, file: String) =
        host().image(call, size, file)

    override fun prefetchTorrent(
        hash: String,
        season: Int?,
        episode: Int?,
        fileIndex: Int?,
    ): Boolean = instance?.prefetchTorrent(hash, season, episode, fileIndex) == true

    override suspend fun speedTest(call: ApplicationCall) = host().speedTest(call)

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        instance?.close()
        instance = null
    }
}
