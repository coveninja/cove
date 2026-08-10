package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.Media
import com.coveninja.cove.shared.model.MediaGenre
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.network.CoveApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Discovery over the HTTP boundary, for `--api-base` against a remote or compatibility
 * host. Both in-process hosts use `LocalDiscoveryRepository` instead.
 *
 * Every call is wrapped: a host that predates these routes answers 404, and Explore
 * treats that exactly as it treats an empty taste profile — the personalized rails simply
 * do not appear. Letting a missing optional rail fail the whole page would be worse than
 * the rail being absent.
 */
class LiveDiscoveryRepository(private val api: CoveApi) : DiscoveryRepository {

    private val genreCache = mutableMapOf<MediaType, List<MediaGenre>>()
    private val genreMutex = Mutex()

    override suspend fun genres(type: MediaType): List<MediaGenre> {
        genreCache[type]?.let { return it }
        return genreMutex.withLock {
            genreCache[type]?.let { return@withLock it }
            // Only a non-empty answer is cached, so a transient failure does not pin the
            // vocabulary to "none" for the rest of the session.
            val fetched = runCatching { api.genres(type) }.getOrDefault(emptyList())
            if (fetched.isNotEmpty()) genreCache[type] = fetched
            fetched
        }
    }

    override suspend fun browse(query: BrowseQuery): List<Media> =
        runCatching {
            api.browse(query.type, query.genreId, query.sort, query.page)
        }.getOrDefault(emptyList())

    override suspend fun recommended(type: MediaType, limit: Int): List<Media> =
        // /discover already routes through the taste profile when the host has one, which
        // is exactly what "recommended" means here.
        runCatching { api.discover(type.wireName, limit) }.getOrDefault(emptyList())

    override suspend fun topGenres(type: MediaType, limit: Int): List<MediaGenre> =
        runCatching {
            api.discoverTopGenres(type, limit).map { MediaGenre(it.id, it.name) }
        }.getOrDefault(emptyList())

    override suspend fun similarTo(type: MediaType, tmdbId: Int, limit: Int): List<Media> =
        runCatching { api.discoverSimilarTo(type, tmdbId, limit) }.getOrDefault(emptyList())

    override suspend fun favorites(limit: Int): List<FavoriteTitle> =
        runCatching {
            api.discoverFavorites(limit).map { FavoriteTitle(it.tmdbId, it.mediaType, it.title) }
        }.getOrDefault(emptyList())
}
