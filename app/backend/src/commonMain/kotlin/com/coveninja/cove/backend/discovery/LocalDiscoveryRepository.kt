package com.coveninja.cove.backend.discovery

import com.coveninja.cove.backend.content.MediaCatalog
import com.coveninja.cove.shared.data.BrowseQuery
import com.coveninja.cove.shared.data.DiscoveryRepository
import com.coveninja.cove.shared.data.FavoriteTitle
import com.coveninja.cove.shared.model.Media
import com.coveninja.cove.shared.model.MediaGenre
import com.coveninja.cove.shared.model.MediaType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-process discovery for both hosts. Nothing here goes over loopback: unlike playback
 * there is no per-request registry behind a route, so the UI graph calls the same objects
 * the HTTP boundary does.
 *
 * [service] is optional because personalization needs a database and a profile, and a host
 * without them should still be able to browse. When it is absent the personal half of the
 * contract answers empty, which is exactly what the interface says an empty taste profile
 * looks like — the caller needs no separate "unsupported" path.
 */
class LocalDiscoveryRepository(
    private val catalog: MediaCatalog,
    private val service: DiscoveryService? = null,
    private val localeProvider: () -> String = { "en" },
) : DiscoveryRepository {

    // Genre vocabularies change on the order of years and every miss is an upstream round
    // trip, so they are fetched once per type and then held for the process lifetime.
    private val genreCache = mutableMapOf<Pair<String, MediaType>, List<MediaGenre>>()
    private val genreMutex = Mutex()

    override suspend fun genres(type: MediaType): List<MediaGenre> {
        val key = localeProvider() to type
        genreCache[key]?.let { return it }
        return genreMutex.withLock {
            genreCache[key]?.let { return@withLock it }
            // A failed genre fetch must not be cached as "no genres" — leaving the map
            // empty means the next caller retries, and the UI has a static fallback table
            // to draw in the meantime.
            val fetched = runCatching { catalog.genres(type) }.getOrDefault(emptyList())
            if (fetched.isNotEmpty()) genreCache[key] = fetched
            fetched
        }
    }

    override suspend fun browse(query: BrowseQuery): List<Media> = catalog.discoverFiltered(
        type = query.type,
        genreId = query.genreId,
        sort = query.sort,
        page = query.page,
    )

    override suspend fun recommended(type: MediaType, limit: Int): List<Media> =
        service?.recommend(type.wireName, limit).orEmpty()

    override suspend fun topGenres(type: MediaType, limit: Int): List<MediaGenre> =
        service?.topGenres(type, limit)
            ?.map { MediaGenre(it.id, it.name) }
            .orEmpty()

    override suspend fun similarTo(type: MediaType, tmdbId: Int, limit: Int): List<Media> =
        service?.similarTo(type, tmdbId, limit).orEmpty()

    override suspend fun favorites(limit: Int): List<FavoriteTitle> =
        service?.favorites(limit)
            ?.mapNotNull { signal ->
                val type = signal.mediaType.toMediaTypeOrNull() ?: return@mapNotNull null
                FavoriteTitle(signal.tmdbId, type, signal.title)
            }
            .orEmpty()
}

private fun String.toMediaTypeOrNull(): MediaType? =
    MediaType.entries.firstOrNull { it.wireName == this }
