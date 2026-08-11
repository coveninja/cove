package com.coveninja.cove.ui.pages.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.coveninja.cove.shared.data.ContentRepository
import com.coveninja.cove.shared.data.DiscoveryRepository
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
 *  - **Hero art.** One `details()` call for the single title at the top, which is the only
 *    way to get its logo and a full-width backdrop. The hero draws immediately with whatever
 *    art it already has and upgrades in place when this lands.
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
    private val scope: CoroutineScope,
) {
    /** True while the taste profile is resolving, so the page can say it is still working. */
    var personalizing by mutableStateOf(false)
        private set

    var personalRails by mutableStateOf<List<HomeRail>>(emptyList())
        private set

    private var enrichedHero by mutableStateOf<Media?>(null)

    private var episodeStills by mutableStateOf<Map<String, String>>(emptyMap())

    private var heroJob: Job? = null
    private var heroId: String? = null

    private var personalJob: Job? = null
    private var personalStarted = false

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
            val details = runCatching { content.details(media.toDomainMedia()) }.getOrNull()
                ?: return@launch
            if (heroId != media.id) return@launch
            enrichedHero = details.toUiMedia()
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
     * Loads the rails that only mean anything for this viewer. Runs once per session; the
     * taste profile does not change fast enough to be worth refetching on every visit.
     */
    fun loadPersonal() {
        if (personalStarted) return
        personalStarted = true
        personalizing = true

        personalJob = scope.launch {
            try {
                personalRails = resolvePersonal()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Left empty on purpose — see the class doc.
            } finally {
                personalizing = false
            }
        }
    }

    private suspend fun resolvePersonal(): List<HomeRail> = coroutineScope {
        val favorite = runCatching { discovery.favorites(FAVORITE_CANDIDATES) }
            .getOrDefault(emptyList())
            .firstOrNull { it.title.isNotBlank() }

        // Both personal endpoints are typed, and Home is not. The strongest signal in the
        // library is the honest thing to follow; with no library at all there is no taste to
        // follow and films are the larger catalog.
        val type = favorite?.type ?: DomainMediaType.Movie

        val because = favorite?.let { signal ->
            async {
                runCatching { discovery.similarTo(signal.type, signal.tmdbId, RAIL_SIZE) }
                    .getOrDefault(emptyList())
                    .map { it.toUiMedia() }
                    .toRail(
                        id = "because-${signal.tmdbId}",
                        title = "Because you watched ${signal.title}",
                        subtitle = "Picked from what you finished and rated",
                        icon = "lucide:heart",
                    )
            }
        }

        val forYou = async {
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
                )
        }

        listOfNotNull(because?.await(), forYou.await())
    }

    private fun List<Media>.toRail(
        id: String,
        title: String,
        subtitle: String,
        icon: String,
    ): HomeRail? = takeIf { it.isNotEmpty() }?.let { media ->
        HomeRail(id = id, title = title, subtitle = subtitle, icon = icon, media = media)
    }

    private companion object {
        const val RAIL_SIZE = 20

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
): HomeController {
    val scope = rememberCoroutineScope()
    return remember(content, discovery) { HomeController(content, discovery, scope) }
}
