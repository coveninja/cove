package com.coveninja.cove.ui.pages.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.coveninja.cove.shared.data.AddonRepository
import com.coveninja.cove.shared.data.ContentRepository
import com.coveninja.cove.shared.data.DiscoveryRepository
import com.coveninja.cove.shared.model.AddonCatalogDescriptor
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.displayImageUrl
import com.coveninja.cove.ui.model.toDomainMedia
import com.coveninja.cove.ui.model.toUiMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.coveninja.cove.shared.model.MediaType as DomainMediaType

/**
 * The three things Home cannot read straight out of memory.
 *
 * Everything else on the page — the library, watch progress, the discover feed, the cached
 * calendar — is already in a `StateFlow` the composition collects, so it paints with no I/O
 * at all. What is left is expensive and therefore deferred:
 *
 *  - **Hero art.** One artwork call for the single title at the top, which gets its logo and
 *    full-width backdrop without also loading cast, videos, or recommendations. The hero draws
 *    immediately with whatever art it already has and upgrades in place when this lands.
 *  - **Episode stills.** A season fetch per show on the carry-on rail. Library rows carry a
 *    poster and nothing else, so the frame the viewer stopped on has to be asked for.
 *  - **Personal rails.** Behind `favorites`/`topGenres` sits a taste profile costing a
 *    metadata request per saved title on a cold cache. These arrive last or not at all.
 *
 * Nothing here is load-bearing. A failure in any of them leaves the page exactly as it was
 * before the request, because a home screen that errors out over an optional rail — or over a
 * thumbnail — is a worse answer than one that quietly has fewer rails and plainer art.
 */
@Stable
class HomeController(
    private val content: ContentRepository,
    private val discovery: DiscoveryRepository,
    private val addons: AddonRepository,
    private val scope: CoroutineScope,
) {
    /** True while the taste profile is resolving, so the page can say it is still working. */
    var personalizing by mutableStateOf(false)
        private set

    var personalRails by mutableStateOf<List<HomeRail>>(emptyList())
        private set

    /**
     * Rails drawn from third-party addon catalogs. Kept apart from [personalRails] rather
     * than appended to it so neither waits on the other: resolving a catalog costs a
     * metadata request per title, and folding these in would hold every personal rail
     * behind the slowest addon on the list.
     */
    var catalogRails by mutableStateOf<List<HomeRail>>(emptyList())
        private set

    private var enrichedHero by mutableStateOf<Media?>(null)

    private var episodeStills by mutableStateOf<Map<String, String>>(emptyMap())

    private var heroJob: Job? = null
    private var heroId: String? = null

    private var personalJob: Job? = null

    /** Which personal rails the current [personalRails] were resolved for. */
    private var personalSelectionUsed: Set<HomeSectionKind>? = null

    private var catalogJob: Job? = null

    /** The catalog selection the current [catalogRails] were resolved for. */
    private var catalogSelectionUsed: String? = null

    /** Seasons already fetched or already failed, so neither is asked for twice. */
    private val attemptedSeasons = mutableSetOf<String>()

    // ── Hero ────────────────────────────────────────────────────────────────

    /**
     * The best version of [media] currently known.
     *
     * Returns the argument untouched until the details fetch for that exact title resolves,
     * which is what lets the hero render on the first frame rather than after a round trip.
     */
    fun heroArt(media: Media): Media =
        enrichedHero?.takeIf { it.id == media.id } ?: media

    /** Fetches the hero's logo and backdrop. Re-entrant: asking twice for one title is free. */
    fun enrichHero(media: Media) {
        if (heroId == media.id) return
        heroId = media.id
        heroJob?.cancel()
        // Dropped rather than kept: it belongs to the title that just stopped being the
        // hero, and showing one title's logo over another's backdrop is worse than plain art.
        enrichedHero = null

        heroJob = scope.launch {
            val artwork = runCatching { content.artwork(media.toDomainMedia()) }.getOrNull()
                ?: return@launch
            if (heroId != media.id) return@launch
            enrichedHero = artwork.toUiMedia()
        }
    }

    // ── Episode stills ──────────────────────────────────────────────────────

    /**
     * The frame the viewer stopped on, for the rows that have one.
     *
     * Keyed by [EpisodeRef.cacheKey] rather than by title, so a show whose resume point moves
     * to the next episode fetches that episode's still instead of keeping the old one.
     */
    fun stillFor(row: ContinueRow): String? {
        val ref = row.thumbnailEpisode() ?: return null
        return episodeStills[ref.cacheKey(row.media.tmdbId)]
    }

    /**
     * Fetches stills for the visible part of the carry-on rail.
     *
     * A library row carries a poster and nothing else, so the still is the one image that has
     * to be asked for — and it costs a season fetch per show, which is why this is capped at
     * [STILL_FETCH_LIMIT] and why every row draws immediately with its backdrop and upgrades
     * only when its own request lands.
     *
     * Idempotent and cheap to call again: a season already fetched or already tried is
     * skipped, so the progress ticks that rebuild the rows mid-playback cost nothing.
     */
    fun loadEpisodeStills(rows: List<ContinueRow>) {
        val wanted = rows.asSequence()
            .take(STILL_FETCH_LIMIT)
            .mapNotNull { row -> row.thumbnailEpisode()?.let { row to it } }
            // One request serves a whole season, so shows with several rows in flight — or a
            // rail listing two episodes of one show — must not fetch it twice.
            .filter { (row, ref) -> "${row.media.tmdbId}:${ref.season}" !in attemptedSeasons }
            .distinctBy { (row, ref) -> "${row.media.tmdbId}:${ref.season}" }
            .toList()

        if (wanted.isEmpty()) return

        wanted.forEach { (row, ref) ->
            attemptedSeasons += "${row.media.tmdbId}:${ref.season}"
        }

        scope.launch {
            val resolved = coroutineScope {
                wanted.map { (row, ref) ->
                    async {
                        val episodes = runCatching {
                            content.episodes(row.media.tmdbId, ref.season)
                        }.getOrNull().orEmpty()

                        // The whole season came back, so cache every still in it: the next
                        // episode is exactly where this row is heading, and it is already paid
                        // for.
                        episodes.mapNotNull { episode ->
                            val url = displayImageUrl(episode.stillPath, "w500") ?: return@mapNotNull null
                            EpisodeRef(ref.season, episode.episodeNumber)
                                .cacheKey(row.media.tmdbId) to url
                        }
                    }
                }.flatMap { it.await() }
            }

            if (resolved.isNotEmpty()) episodeStills = episodeStills + resolved
        }
    }

    // ── Personal rails ──────────────────────────────────────────────────────

    /**
     * Loads the rails that only mean anything for this viewer.
     *
     * Effectively once per session — the taste profile does not change fast enough to be
     * worth refetching on every visit — but keyed on *which* of the two rails is wanted
     * rather than on having run at all. A viewer who un-hides one would otherwise not see it
     * until they restarted, having watched the other appear immediately.
     *
     * Skipped outright when both are hidden. That is the expensive one to get wrong: behind
     * `favorites`/`topGenres` sits a metadata request per saved title, and spending a cold
     * cache's worth of them on two rows nobody asked to see is the whole reason the layout
     * reaches this far down.
     */
    fun loadPersonal(layout: HomeLayout = HomeLayout.Default) {
        val wanted = personalSelection(layout)
        if (wanted.isEmpty()) return
        if (personalSelectionUsed == wanted) return
        personalSelectionUsed = wanted
        personalJob?.cancel()
        personalizing = true

        personalJob = scope.launch {
            try {
                personalRails = resolvePersonal(layout)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Left empty on purpose — see the class doc.
            } finally {
                personalizing = false
            }
        }
    }

    /** The personal rails this layout actually wants drawn. */
    private fun personalSelection(layout: HomeLayout): Set<HomeSectionKind> =
        setOf(HomeSectionKind.BecauseYouWatched, HomeSectionKind.PickedForYou)
            .filterNot(layout::isHidden)
            .toSet()

    private suspend fun resolvePersonal(layout: HomeLayout): List<HomeRail> = coroutineScope {
        val wantsBecause = !layout.isHidden(HomeSectionKind.BecauseYouWatched)
        val wantsForYou = !layout.isHidden(HomeSectionKind.PickedForYou)

        val favorite = runCatching { discovery.favorites(FAVORITE_CANDIDATES) }
            .getOrDefault(emptyList())
            .firstOrNull { it.title.isNotBlank() }

        // Both personal endpoints are typed, and Home is not. The strongest signal in the
        // library is the honest thing to follow; with no library at all there is no taste to
        // follow and films are the larger catalog.
        val type = favorite?.type ?: DomainMediaType.Movie

        val because = favorite?.takeIf { wantsBecause }?.let { signal ->
            async {
                runCatching { discovery.similarTo(signal.type, signal.tmdbId, RAIL_SIZE) }
                    .getOrDefault(emptyList())
                    .map { it.toUiMedia() }
                    .toRail(
                        id = "because-${signal.tmdbId}",
                        title = "Because you watched ${signal.title}",
                        subtitle = "Picked from what you finished and rated",
                        icon = "lucide:heart",
                        section = HomeSectionKind.BecauseYouWatched.key,
                    )
            }
        }

        val forYou = if (!wantsForYou) {
            null
        } else {
            async {
                val topGenre = runCatching { discovery.topGenres(type, 1) }
                    .getOrDefault(emptyList())
                    .firstOrNull()
                runCatching { discovery.recommended(type, RAIL_SIZE) }
                    .getOrDefault(emptyList())
                    .map { it.toUiMedia() }
                    .toRail(
                        id = "for-you-${topGenre?.id ?: "all"}",
                        title = "Picked for you",
                        subtitle = topGenre
                            ?.let { "You keep coming back to ${it.name.lowercase()}" }
                            ?: "From the titles you have saved",
                        icon = "lucide:sparkles",
                        section = HomeSectionKind.PickedForYou.key,
                    )
            }
        }

        listOfNotNull(because?.await(), forYou?.await())
    }

    // ── Addon catalog rails ─────────────────────────────────────────────────

    /**
     * Draws the catalogs the profile's addons offer, in the viewer's order.
     *
     * Capped at [HomeLayout.catalogRows] deliberately. A viewer with several catalog addons
     * can easily have a dozen enabled catalogs, each one a fan-out of metadata requests to
     * resolve, and a home screen is not the place to spend that — the rest are reachable on
     * Explore, which loads them for the format being browsed rather than all at once. The
     * cap is applied *after* the ordering, so which catalogs make the page is the viewer's
     * choice rather than the order their addons happened to be installed in.
     *
     * Not filtered by type: Home is not a typed page, so a film catalog and a series
     * catalog are equally at home here.
     *
     * Re-resolves when the selection changes, rather than running once a session like the
     * personal rails do. Reordering catalogs and then finding Home unchanged until the next
     * launch would read as the setting not working at all.
     */
    fun loadCatalogs(layout: HomeLayout = HomeLayout.Default) {
        val selection = catalogSelection(layout)
        if (catalogSelectionUsed == selection) return
        catalogSelectionUsed = selection
        catalogJob?.cancel()

        catalogJob = scope.launch {
            try {
                catalogRails = resolveCatalogs(layout)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Left empty on purpose — see the class doc.
            }
        }
    }

    /**
     * The part of the layout that changes which catalogs are fetched.
     *
     * Deliberately not the whole [HomeLayout]: moving *another* section, or widening the
     * upcoming horizon, would otherwise throw away resolved catalog rails and refetch every
     * one of them for nothing.
     *
     * Read off [HomeLayout.savedOrder] rather than the reconciled [HomeLayout.order], and
     * that part is load-bearing. The page builds its layout from the catalog rails this
     * controller produced, so the reconciled order gains those keys the moment the first
     * fetch lands — keying on it means the arrival of the answer invalidates the question,
     * and every catalog page is fetched twice on every cold start. The stored order does not
     * move underneath us that way.
     */
    private fun catalogSelection(layout: HomeLayout): String =
        layout.savedOrder.filter { it.startsWith(CATALOG_KEY_PREFIX) }
            .joinToString(",") + "|" + layout.hidden.filter { it.startsWith(CATALOG_KEY_PREFIX) }
            .sorted().joinToString(",") + "|" + layout.catalogRows

    private suspend fun resolveCatalogs(layout: HomeLayout): List<HomeRail> = coroutineScope {
        val catalogs = runCatching { addons.catalogs() }.getOrDefault(emptyList())
        layout.catalogsToDraw(catalogs).map { descriptor ->
            async {
                runCatching {
                    addons.catalogPage(
                        addonId = descriptor.addonId,
                        type = descriptor.type,
                        catalogId = descriptor.catalogId,
                        skip = 0,
                        limit = RAIL_SIZE,
                    ).medias
                }.getOrDefault(emptyList())
                    .map { it.toUiMedia() }
                    .toRail(
                        id = "addon-${descriptor.addonId}-${descriptor.key}",
                        title = descriptor.name.ifBlank { descriptor.addonName },
                        subtitle = "From ${descriptor.addonName}",
                        icon = "lucide:blocks",
                        // The addon chose this order, and that ordering is the whole
                        // content of the row — so it earns its place even where the
                        // titles have already appeared above.
                        ordered = true,
                        catalog = descriptor,
                        section = catalogSectionKey(descriptor),
                    )
            }
        }.mapNotNull { it.await() }
    }

    private fun List<Media>.toRail(
        id: String,
        title: String,
        subtitle: String,
        icon: String,
        ordered: Boolean = false,
        catalog: AddonCatalogDescriptor? = null,
        section: String = id,
    ): HomeRail? = takeIf { it.isNotEmpty() }?.let { media ->
        HomeRail(
            id = id,
            title = title,
            subtitle = subtitle,
            icon = icon,
            media = media,
            ordered = ordered,
            catalog = catalog,
            section = section,
        )
    }

    private companion object {
        const val RAIL_SIZE = 20

        /** How every catalog section key starts — see `catalogSectionKey`. */
        const val CATALOG_KEY_PREFIX = "catalog:"

        // Roughly what fits on screen before the viewer scrolls. Each one costs a season
        // fetch, so the rest of the rail keeps its backdrop rather than paying for art
        // nobody has looked at yet.
        const val STILL_FETCH_LIMIT = 8

        // Favourites come back weighted; asking for a handful means a blank or untitled
        // strongest signal does not cost the rail entirely.
        const val FAVORITE_CANDIDATES = 8
    }
}

@Composable
fun rememberHomeController(
    content: ContentRepository,
    discovery: DiscoveryRepository,
    addons: AddonRepository,
): HomeController {
    val scope = rememberCoroutineScope()
    return remember(content, discovery, addons) {
        HomeController(content, discovery, addons, scope)
    }
}
