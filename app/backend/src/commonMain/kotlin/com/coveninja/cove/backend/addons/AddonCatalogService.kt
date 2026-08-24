package com.coveninja.cove.backend.addons

import com.coveninja.cove.backend.content.MediaCatalog
import com.coveninja.cove.shared.model.AddonCatalogDescriptor
import com.coveninja.cove.shared.model.AddonCatalogPage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Clock

/**
 * Addon catalogs as rows of [com.coveninja.cove.shared.model.Media].
 *
 * [AddonManager] answers in the addons' own vocabulary — a list of metas naming titles by
 * IMDB or `tmdb:` id — which is not something any screen can draw. Turning those into the
 * app's media is the other half of the job, and it used to live inside the `GET /catalog`
 * route body. That put it out of reach of the in-process graph, which is how both hosts
 * actually read their data, so nothing but a compatibility HTTP client could ever show a
 * catalog. It lives here now and the route delegates.
 *
 * ## Why the cache is not optional
 *
 * Resolution costs one TMDB request per entry, or two when the entry names an IMDB id and
 * needs a /find first, and nothing downstream memoizes. A twenty-title row is therefore
 * twenty to forty requests, Home draws several such rows, and every return to the page
 * would pay again. [AddonManager.streams] has had a cache for this reason; catalogs are
 * the more expensive of the two and had none.
 */
class AddonCatalogService(
    private val addons: AddonManager,
    private val catalog: MediaCatalog,
) {
    private val cacheMutex = Mutex()
    private val cache = mutableMapOf<String, CachedCatalogPage>()

    /**
     * Every catalog the active profile can draw, across its own addons and any it
     * inherits. Ordered as [AddonManager.entries] orders addons, so the household's
     * shared providers lead.
     */
    suspend fun catalogs(): List<AddonCatalogDescriptor> =
        addons.enabledCatalogs().map(AddonCatalogRef::toDescriptor)

    /**
     * One page of a catalog, resolved and cached.
     *
     * Failure is deliberately not swallowed here — a caller drawing a row wants to omit it
     * rather than draw an empty one, and the repositories above decide that. What *is*
     * swallowed is a single entry failing to resolve: one unknown title must not cost the
     * other nineteen.
     */
    suspend fun page(
        addonId: String?,
        addonUrl: String?,
        type: String,
        catalogId: String,
        skip: Int,
        limit: Int,
    ): AddonCatalogPage {
        val key = listOf(
            addons.cacheToken(), addonId.orEmpty(), addonUrl.orEmpty(), type, catalogId, skip, limit,
        ).joinToString("|")
        val now = Clock.System.now().toEpochMilliseconds()
        cacheMutex.withLock {
            cache.entries.removeAll { it.value.expiresAt <= now }
            cache[key]?.let { return it.page }
        }

        val raw = addons.catalog(addonId, addonUrl, type, catalogId, skip)
        // Counted before resolution, and off the source list rather than the survivors:
        // an entry this app cannot key on is dropped, and paging past it is the only way
        // the next page is ever different from this one.
        val consumed = minOf(raw.size, limit)
        val medias = coroutineScope {
            val permits = Semaphore(RESOLVE_CONCURRENCY)
            raw.take(limit)
                .map { meta -> async { permits.withPermit { catalog.resolveAddonMeta(meta) } } }
                .map { it.await() }
                .filterNotNull()
        }
        val page = AddonCatalogPage(medias, skip + consumed)

        cacheMutex.withLock {
            cache[key] = CachedCatalogPage(
                page,
                now + if (medias.isEmpty()) EMPTY_CACHE_MILLIS else CACHE_MILLIS,
            )
        }
        return page
    }

    private companion object {
        // Enough to hide the per-request latency without opening twenty sockets to TMDB
        // for one row. Carried over from the route this replaced.
        const val RESOLVE_CONCURRENCY = 6

        // Matches the stream cache. A catalog is a curated list that turns over in days,
        // so this is conservative; the shorter empty window is what lets a row that
        // failed come back without the viewer waiting a quarter of an hour for it.
        const val CACHE_MILLIS = 15 * 60 * 1_000L
        const val EMPTY_CACHE_MILLIS = 2 * 60 * 1_000L
    }
}

private data class CachedCatalogPage(val page: AddonCatalogPage, val expiresAt: Long)

/** The shared shape of a catalog reference. Used by the HTTP route and the repository alike. */
fun AddonCatalogRef.toDescriptor(): AddonCatalogDescriptor = AddonCatalogDescriptor(
    addonId = addonId,
    addonName = addonName,
    addonUrl = addonUrl,
    type = catalogType,
    catalogId = catalogId,
    name = name,
)
