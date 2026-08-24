package com.coveninja.cove.backend.playback

import com.coveninja.cove.backend.addons.AddonManager
import com.coveninja.cove.backend.addons.AddonStream
import com.coveninja.cove.backend.addons.TimestampData
import com.coveninja.cove.backend.content.MediaCatalog
import com.coveninja.cove.backend.nuvio.NuvioManager
import com.coveninja.cove.shared.data.PlaybackRepository
import com.coveninja.cove.shared.model.MediaTimestamps
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.StreamSource
import com.coveninja.cove.shared.model.SubtitleSource
import com.coveninja.cove.shared.model.TimestampSegment
import com.coveninja.cove.shared.model.TorrentProgress
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** In-process discovery plus loopback URLs consumable by Android libmpv. */
internal class AndroidPlaybackRepository(
    private val catalog: MediaCatalog,
    private val addons: AddonManager,
    private val media: LazyAndroidPlaybackMediaHost,
    private val nuvio: NuvioManager? = null,
) : PlaybackRepository {
    override suspend fun streams(
        tmdbId: Int,
        type: MediaType,
        season: Int?,
        episode: Int?,
    ): List<StreamSource> {
        val imdbId = catalog.imdbId(tmdbId, type)
        val stremioId = buildString {
            append(imdbId)
            if (type == MediaType.Tv) {
                require(season != null && season >= 0) { "season is required for tv streams" }
                require(episode != null && episode > 0) { "episode is required for tv streams" }
                append(":$season:$episode")
            }
        }
        // Concurrently, as the desktop route already does (CoreRoutes "/streams"). Awaiting the
        // addon fan-out first meant Android paid its fifteen-second timeout and the Nuvio budget
        // end to end, for two sets of requests that share nothing.
        val (stremio, scraped) = coroutineScope {
            val addonStreams = async { addons.streams(type, stremioId) }
            val scrapedStreams = async {
                nuvio?.let { manager ->
                    val title = catalog.media(tmdbId, type)
                    manager.streams(
                        mediaType = type,
                        tmdbId = tmdbId,
                        imdbId = imdbId,
                        title = title.displayTitle,
                        year = title.displayDate?.take(4)?.toIntOrNull() ?: 0,
                        season = season,
                        episode = episode,
                    )
                }.orEmpty()
            }
            addonStreams.await() to scrapedStreams.await()
        }
        return media.registerStreams(stremio + scraped).map(AddonStream::toShared)
    }

    override fun playUrl(source: StreamSource, season: Int?, episode: Int?): String {
        source.url?.takeIf(String::isNotBlank)?.let { return media.directUrl(it) }
        val hash = source.infoHash?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException(
                "stream \"${source.name ?: source.title ?: "unnamed"}\" has neither a url nor an infoHash",
            )
        return media.torrentUrl(hash, season, episode, source.fileIdx)
    }

    override suspend fun timestamps(tmdbId: Int, season: Int?, episode: Int?): MediaTimestamps =
        runCatching { addons.timestamps(tmdbId, season, episode).toShared() }
            .getOrDefault(MediaTimestamps.None)

    override suspend fun subtitles(
        tmdbId: Int,
        type: MediaType,
        season: Int?,
        episode: Int?,
    ): List<SubtitleSource> = runCatching {
        val stremioId = buildString {
            append(catalog.imdbId(tmdbId, type))
            if (type == MediaType.Tv && season != null && episode != null) append(":$season:$episode")
        }
        addons.subtitles(type, stremioId).filter { it.url.isNotBlank() }.map {
            SubtitleSource(it.id, media.subtitleUrl(it.url), it.lang)
        }
    }.getOrDefault(emptyList())

    override suspend fun aliveUrls(urls: List<String>): Set<String> =
        runCatching { media.aliveUrls(urls) }.getOrElse { urls.toSet() }

    override suspend fun torrentProgress(hash: String): TorrentProgress? =
        media.torrentProgress(hash)?.let {
            TorrentProgress(
                it.hash,
                it.fileIndex,
                it.downloadedBytes,
                it.totalBytes,
                it.downloadRate,
                it.peers,
                it.complete,
            )
        }
}

private fun AddonStream.toShared() = StreamSource(
    name = name,
    title = title,
    url = url.takeIf(String::isNotBlank),
    infoHash = infoHash.takeIf(String::isNotBlank),
    addonName = addonName,
    sizeBytes = sizeBytes.takeIf { it > 0 } ?: behaviorHints?.videoSize ?: 0,
    fileIdx = fileIdx,
    cached = cached,
)

private fun TimestampData.toShared() = MediaTimestamps(
    intro = intro.map { TimestampSegment(it.startMs, it.endMs) },
    recap = recap.map { TimestampSegment(it.startMs, it.endMs) },
    credits = credits.map { TimestampSegment(it.startMs, it.endMs) },
    preview = preview.map { TimestampSegment(it.startMs, it.endMs) },
)
