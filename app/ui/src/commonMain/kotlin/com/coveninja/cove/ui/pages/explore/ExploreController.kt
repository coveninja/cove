package com.coveninja.cove.ui.pages.explore

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.coveninja.cove.shared.data.BrowseQuery
import com.coveninja.cove.shared.data.DiscoveryRepository
import com.coveninja.cove.shared.model.CatalogSort
import com.coveninja.cove.shared.model.MediaGenre
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.MediaType
import com.coveninja.cove.ui.model.toUiMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.coveninja.cove.shared.model.Media as DomainMedia
import com.coveninja.cove.shared.model.MediaType as DomainMediaType

/**
 * Everything Explore loads, and the order it loads it in.
 *
 * The staging is the point. `DiscoveryRepository` splits into a cheap impersonal half and
 * an expensive personal one — the latter sits behind a taste profile costing a metadata
 * request per saved title on a cold cache — so waiting for all of it before drawing
 * anything would leave the page blank for as long as the library is large. Instead:
 *
 *  0. **Seed.** Whatever the discover feed already holds, painted with no I/O at all.
 *  1. **Editorial.** Trending / top rated / new releases, plus the genre vocabulary.
 *  2. **Personal.** "Because you watched…" and the viewer's strongest genre.
 *  3. **Genres.** A rail per remaining genre.
 *
 * Each stage republishes, so rails appear as they resolve rather than in one late burst.
 * A stage that fails is simply absent: no rail is load-bearing, and failing the page over
 * an optional row would be a worse answer than the row not being there.
 */
@Stable
class ExploreController(
    private val discovery: DiscoveryRepository,
    private val scope: CoroutineScope,
) {
    var genres by mutableStateOf<List<MediaGenre>>(emptyList())
        private set

    /** Backend genre names by id, for [com.coveninja.cove.ui.model.resolveGenreName]. */
    var genreNames by mutableStateOf<Map<Int, String>>(emptyMap())
        private set

    var shelves by mutableStateOf<List<ExploreShelf>>(emptyList())
        private set

    /** True until the first stage that produces rails has finished. */
    var shelvesLoading by mutableStateOf(true)
        private set

    /** True while the personal stage is still resolving, so the page can say so. */
    var personalizing by mutableStateOf(false)
        private set

    var gridItems by mutableStateOf<List<Media>>(emptyList())
        private set

    var gridLoading by mutableStateOf(false)
        private set

    /** True once the catalog has no more pages to give for the current filter. */
    var gridExhausted by mutableStateOf(false)
        private set

    var gridError by mutableStateOf<String?>(null)
        private set

    private var shelvesJob: Job? = null
    private var gridJob: Job? = null

    private var loadedType: MediaType? = null
    private var activeKey: Triple<MediaType, Int?, ExploreSort>? = null
    private var loadedPages = 0

    /** The discover feed, kept as the grid's fallback when the catalog serves nothing. */
    private var seedMedia: List<Media> = emptyList()

    // Held separately rather than appended to one list so a later stage can republish
    // without disturbing what an earlier one produced.
    private var personalShelves: List<ExploreShelf> = emptyList()
    private var editorialShelves: List<ExploreShelf> = emptyList()
    private var genreShelves: List<ExploreShelf> = emptyList()

    // ── Shelves ─────────────────────────────────────────────────────────────

    /**
     * Loads every rail for [type]. Re-entrant: calling it again for the same type is a
     * no-op, and calling it for a different one abandons the previous load.
     *
     * @param seed titles already in memory from the discover feed, drawn immediately.
     */
    fun loadShelves(type: MediaType, seed: List<Media>) {
        // Kept even on the no-op path: the feed often arrives after the first call, and
        // the grid's fallback wants the latest version of it either way.
        if (seed.isNotEmpty()) seedMedia = seed
        if (loadedType == type) {
            // The discover feed regularly lands *after* this first ran with nothing. When
            // the catalog also gave nothing — no discovery wired, or a host without the
            // browse routes — that late seed is the only content the page will ever have,
            // so fold it in rather than letting the early empty result stick forever.
            if (shelves.isEmpty() && seed.isNotEmpty()) {
                editorialShelves = listOfNotNull(seedShelf(type, seed))
                shelvesLoading = false
                publish()
            }
            return
        }
        loadedType = type
        shelvesJob?.cancel()

        personalShelves = emptyList()
        genreShelves = emptyList()
        // Stage 0: the seed is already here, so the page is never blank while stage 1 runs.
        editorialShelves = listOfNotNull(seedShelf(type, seed))
        shelvesLoading = editorialShelves.isEmpty()
        publish()

        shelvesJob = scope.launch {
            val domainType = type.domain()
            loadEditorial(type, domainType, seed)
            if (loadedType != type) return@launch
            shelvesLoading = false

            personalizing = true
            try {
                loadPersonal(type, domainType)
            } finally {
                if (loadedType == type) personalizing = false
            }
            if (loadedType != type) return@launch
            loadGenreShelves(type, domainType)
        }
    }

    private fun seedShelf(type: MediaType, seed: List<Media>): ExploreShelf? {
        val forType = seed.filter { it.type == type }
        if (forType.isEmpty()) return null
        return ExploreShelf(
            // Deliberately the same id the real trending rail will use. The lazy column is
            // keyed on it, so when the ordered page arrives it replaces this rail's content
            // in place instead of remounting the row and throwing away its scroll position.
            id = trendingShelfId(type),
            title = "Trending now",
            subtitle = "What everyone is watching",
            icon = "lucide:flame",
            kind = ShelfKind.Trending,
            media = forType,
            sort = ExploreSort.Trending,
        )
    }

    private suspend fun loadEditorial(
        type: MediaType,
        domainType: DomainMediaType,
        seed: List<Media>,
    ) = coroutineScope {
        val vocabulary = async { runCatching { discovery.genres(domainType) }.getOrDefault(emptyList()) }
        val trending = async { browse(domainType, CatalogSort.Popularity) }
        val topRated = async { browse(domainType, CatalogSort.Rating) }
        val newest = async { browse(domainType, CatalogSort.Newest) }

        val vocabularyResult = vocabulary.await()
        if (loadedType != type) return@coroutineScope
        if (vocabularyResult.isNotEmpty()) {
            genres = vocabularyResult
            genreNames = vocabularyResult.associate { it.id to it.name }
        }

        // The seed stands in for trending only until the real, ordered page arrives.
        val trendingMedia = trending.await().ifEmpty { seed.filter { it.type == type } }
        if (loadedType != type) return@coroutineScope

        editorialShelves = listOfNotNull(
            trendingMedia.toShelf(
                id = trendingShelfId(type),
                title = "Trending now",
                subtitle = "What everyone is watching",
                icon = "lucide:flame",
                kind = ShelfKind.Trending,
                sort = ExploreSort.Trending,
            ),
            topRated.await().toShelf(
                id = "top-rated-${type.name}",
                title = "Top rated",
                subtitle = "The highest scores with the votes to back them",
                icon = "lucide:star",
                kind = ShelfKind.TopRated,
                sort = ExploreSort.TopRated,
            ),
            newest.await().toShelf(
                id = "newest-${type.name}",
                title = "New releases",
                subtitle = "Freshly out",
                icon = "lucide:calendar-clock",
                kind = ShelfKind.NewReleases,
                sort = ExploreSort.Newest,
            ),
        )
        publish()
    }

    private suspend fun loadPersonal(type: MediaType, domainType: DomainMediaType) = coroutineScope {
        val favorite = runCatching { discovery.favorites(FAVORITE_CANDIDATES) }
            .getOrDefault(emptyList())
            .firstOrNull { it.type == domainType && it.title.isNotBlank() }
        val topGenre = runCatching { discovery.topGenres(domainType, 1) }
            .getOrDefault(emptyList())
            .firstOrNull()

        val because = favorite?.let { signal ->
            async {
                runCatching { discovery.similarTo(domainType, signal.tmdbId, SHELF_SIZE) }
                    .getOrDefault(emptyList())
                    .toUi()
                    .toShelf(
                        id = "because-${signal.tmdbId}",
                        title = "Because you watched ${signal.title}",
                        subtitle = "Picked from what you finished and rated",
                        icon = "lucide:heart",
                        kind = ShelfKind.BecauseYouWatched,
                    )
            }
        }
        val yours = topGenre?.let { genre ->
            async {
                runCatching { discovery.recommended(domainType, SHELF_SIZE) }
                    .getOrDefault(emptyList())
                    .toUi()
                    .toShelf(
                        id = "for-you-${genre.id}",
                        title = "More ${genre.name.lowercase()}",
                        subtitle = "Your most-watched genre",
                        icon = "lucide:sparkles",
                        kind = ShelfKind.YourGenre,
                        genreId = genre.id,
                    )
            }
        }

        val resolved = listOfNotNull(because?.await(), yours?.await())
        if (loadedType != type) return@coroutineScope
        personalShelves = resolved
        publish()
    }

    private suspend fun loadGenreShelves(
        type: MediaType,
        domainType: DomainMediaType,
    ) = coroutineScope {
        val alreadyRailed = personalShelves.mapNotNull(ExploreShelf::genreId).toSet()
        val candidates = genres.filter { it.id !in alreadyRailed }.take(GENRE_SHELVES)
        if (candidates.isEmpty()) return@coroutineScope

        val loaded = candidates.map { genre ->
            async {
                genre to browse(domainType, CatalogSort.Popularity, genre.id)
            }
        }.map { it.await() }

        if (loadedType != type) return@coroutineScope
        genreShelves = loaded.mapNotNull { (genre, media) ->
            media.toShelf(
                id = "genre-${genre.id}-${type.name}",
                title = genre.name,
                subtitle = "Popular in ${genre.name.lowercase()}",
                icon = "lucide:layout-grid",
                kind = ShelfKind.Genre,
                genreId = genre.id,
            )
        }
        publish()
    }

    private fun publish() {
        shelves = buildShelves(personalShelves + editorialShelves + genreShelves)
    }

    // ── Grid ────────────────────────────────────────────────────────────────

    /** Points the grid at a filter, restarting paging when it actually changed. */
    fun setGridFilters(filters: ExploreFilters) {
        val key = filters.catalogKey
        if (key == activeKey) return
        activeKey = key
        gridJob?.cancel()
        gridItems = emptyList()
        gridExhausted = false
        gridError = null
        gridLoading = false
        loadedPages = 0
        loadNextPage()
    }

    /**
     * Appends the next page. Safe to call repeatedly from a scroll handler — it declines
     * while a page is already in flight, once the catalog is exhausted, and after a
     * failure until [retry] clears it.
     */
    fun loadNextPage() {
        val key = activeKey ?: return
        if (gridLoading || gridExhausted || gridError != null) return

        gridLoading = true
        gridJob = scope.launch {
            try {
                val page = discovery.browse(
                    BrowseQuery(key.first.domain(), key.second, key.third.catalog, loadedPages + 1),
                ).toUi()
                // A filter changed while this was in flight: its results belong to a page
                // nobody is looking at any more, and appending them would mix two catalogs.
                if (activeKey != key) return@launch

                val merged = (gridItems + page).distinctBy(Media::id)
                // Exhausted covers both "the catalog gave nothing" and "it gave only
                // duplicates" — the second happens when a popularity ordering shifts
                // under paging, and without it the scroll handler would loop forever
                // fetching pages that add nothing.
                if (merged.size == gridItems.size) {
                    gridExhausted = true
                    // Nothing at all on the very first page means browsing is not
                    // available here — no discovery wired, or a host that serves no
                    // /browse. The discover feed is still in hand, so narrow that
                    // instead of leaving the grid blank.
                    if (gridItems.isEmpty() && loadedPages == 0) {
                        gridItems = seedFor(key.first, key.second)
                    }
                } else {
                    loadedPages += 1
                    gridItems = merged
                }
                gridError = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (activeKey == key) {
                    gridError = error.message ?: "The catalog could not be reached"
                }
            } finally {
                if (activeKey == key) gridLoading = false
            }
        }
    }

    fun retry() {
        gridError = null
        loadNextPage()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun seedFor(type: MediaType, genreId: Int?): List<Media> =
        seedMedia.filter { it.type == type && (genreId == null || genreId in it.genreIds) }

    private suspend fun browse(
        type: DomainMediaType,
        sort: CatalogSort,
        genreId: Int? = null,
    ): List<Media> = runCatching {
        discovery.browse(BrowseQuery(type, genreId, sort)).toUi()
    }.getOrDefault(emptyList())

    private fun List<DomainMedia>.toUi(): List<Media> = map { it.toUiMedia() }

    private fun List<Media>.toShelf(
        id: String,
        title: String,
        subtitle: String,
        icon: String,
        kind: ShelfKind,
        genreId: Int? = null,
        sort: ExploreSort = ExploreSort.Trending,
    ): ExploreShelf? = takeIf { it.isNotEmpty() }?.let { media ->
        ExploreShelf(id, title, subtitle, icon, kind, media, genreId, sort)
    }

    private companion object {
        const val SHELF_SIZE = 20
        const val GENRE_SHELVES = 6

        // Favourites come back weighted, and the strongest may well be a film when the
        // page is showing series. Asking for a handful means there is usually one of the
        // right format among them rather than none.
        const val FAVORITE_CANDIDATES = 8
    }
}

private fun MediaType.domain(): DomainMediaType = when (this) {
    MediaType.Movie -> DomainMediaType.Movie
    MediaType.Series -> DomainMediaType.Tv
}

private fun trendingShelfId(type: MediaType): String = "trending-${type.name}"

@Composable
fun rememberExploreController(discovery: DiscoveryRepository): ExploreController {
    val scope = rememberCoroutineScope()
    return remember(discovery) { ExploreController(discovery, scope) }
}
