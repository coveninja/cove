package com.coveninja.cove.backend.discovery

import com.coveninja.cove.backend.addons.AddonUrlPolicy
import com.coveninja.cove.backend.content.MediaCatalog
import com.coveninja.cove.backend.db.CoveDatabase
import com.coveninja.cove.backend.store.ActiveProfileSession
import com.coveninja.cove.backend.store.LocalSettingsRepository
import com.coveninja.cove.shared.model.ContributingTitle
import com.coveninja.cove.shared.model.DecadeCount
import com.coveninja.cove.shared.model.DiscoveryInsights
import com.coveninja.cove.shared.model.DiscoveryTaste
import com.coveninja.cove.shared.model.LanguageCount
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.Media
import com.coveninja.cove.shared.model.MediaDetails
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.StudioEntry
import com.coveninja.cove.shared.network.CoveJson
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.math.max
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Public Kotlin implementation of personalized discovery. Candidate generation
 * remains with TMDB; local taste only filters and re-ranks vetted candidates.
 *
 * Lives in commonMain so Android gets the same recommendations the desktop does. Like
 * [com.coveninja.cove.backend.calendar.CalendarService], the only thing that ever kept it
 * desktop-only was `java.time`; it needs nothing from the JVM that kotlin-stdlib does not
 * already provide. It depends on [MediaCatalog] rather than a concrete client so a fake
 * can drive it in tests without an HTTP engine.
 */
class DiscoveryService(
    private val database: CoveDatabase,
    private val session: ActiveProfileSession,
    private val settings: LocalSettingsRepository,
    private val catalog: MediaCatalog,
    private val customHttpClient: HttpClient,
    private val customUrlPolicy: AddonUrlPolicy,
    private val clock: Clock = Clock.System,
) {
    private val cacheMutex = Mutex()
    private var cached: CachedProfile? = null

    suspend fun recommend(type: String, limit: Int, kidProfile: Boolean = false): List<Media> {
        val capped = limit.coerceIn(1, 60)
        if (type == "all" || type == "both") {
            val profile = profile()
            val movieCount = (capped * profile.movieShare).toInt().coerceIn(0, capped)
            val movies = recommendType(MediaType.Movie, movieCount + 3, kidProfile, profile)
            val shows = recommendType(MediaType.Tv, capped - movieCount + 3, kidProfile, profile)
            return weightedMerge(movies, shows, profile.movieShare, capped)
        }
        val mediaType = type.toMediaType()
        return recommendType(mediaType, capped, kidProfile, profile())
    }

    suspend fun byGenre(type: MediaType, genreId: Int, limit: Int, kidProfile: Boolean = false): List<Media> =
        filterCandidates(
            catalog.discoverFiltered(type, genreId = genreId),
            type,
            profile(),
            kidProfile,
        ).take(limit.coerceIn(1, 60))

    suspend fun byKeyword(type: MediaType, keywordId: Int, limit: Int, kidProfile: Boolean = false): List<Media> =
        filterCandidates(
            catalog.discoverFiltered(type, keywordId = keywordId),
            type,
            profile(),
            kidProfile,
        ).take(limit.coerceIn(1, 60))

    suspend fun byPerson(type: MediaType, personId: Int, limit: Int, kidProfile: Boolean = false): List<Media> =
        filterCandidates(
            catalog.discoverFiltered(type, personId = personId),
            type,
            profile(),
            kidProfile,
        ).take(limit.coerceIn(1, 60))

    suspend fun similarTo(type: MediaType, tmdbId: Int, limit: Int, kidProfile: Boolean = false): List<Media> =
        filterCandidates(catalog.similar(tmdbId, type), type, profile(), kidProfile)
            .take(limit.coerceIn(1, 60))

    suspend fun topGenres(type: MediaType, limit: Int): List<DiscoveryTaste> {
        val p = profile()
        return rankedTaste(p.genreScores.getValue(type), p.genreNames).take(limit.coerceIn(1, 50))
    }

    suspend fun topKeywords(limit: Int): List<DiscoveryTaste> {
        val p = profile()
        return rankedTaste(p.keywordScores, p.keywordNames).take(limit.coerceIn(1, 50))
    }

    suspend fun topPeople(limit: Int): List<DiscoveryTaste> {
        val p = profile()
        return rankedTaste(p.personScores, p.personNames).take(limit.coerceIn(1, 50))
    }

    suspend fun favorites(limit: Int): List<FavoriteSignal> = profile().favorites.take(limit.coerceIn(1, 20))

    suspend fun insights(): DiscoveryInsights {
        val p = profile()
        val disliked = p.genreScores.values
            .flatMap(Map<Int, Double>::entries)
            .groupBy(Map.Entry<Int, Double>::key)
            .mapValues { (_, values) -> values.sumOf { it.value } }
            .filterValues { it < 0 }
            .entries.sortedBy { it.value }
            .take(8)
            .map { DiscoveryTaste(it.key, p.genreNames[it.key].orEmpty(), it.value) }
        return DiscoveryInsights(
            topMovieGenres = rankedTaste(p.genreScores.getValue(MediaType.Movie), p.genreNames).take(8),
            topTvGenres = rankedTaste(p.genreScores.getValue(MediaType.Tv), p.genreNames).take(8),
            dislikedGenres = disliked,
            topKeywords = rankedTaste(p.keywordScores, p.keywordNames).take(12),
            topPeople = rankedTaste(p.personScores, p.personNames).take(8),
            signalsUsed = p.contributors.size,
            topStudios = p.studioCounts.entries.sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key },
            ).take(12).map { StudioEntry(p.studioIds[it.key] ?: 0, it.key, it.value) },
            topContributors = p.contributors.filter { it.weight > 0 }.take(12),
            negativeContributors = p.contributors.filter { it.weight < 0 }.sortedBy { it.weight }.take(12),
            decades = p.decades,
            languages = p.languages.take(8),
            averageRuntimeMinutes = p.averageRuntimeMinutes,
        )
    }

    suspend fun testCustomAlgorithm(url: String): AlgorithmTestResult {
        val sampleProfile = TasteProfile(
            genreScores = mapOf(MediaType.Movie to mapOf(28 to 4.5, 12 to 2.0), MediaType.Tv to emptyMap()),
            genreNames = mapOf(28 to "Action", 12 to "Adventure"),
        )
        val candidates = listOf(
            Media(1, title = "Sample Movie One", voteAverage = 7.2, genreIds = listOf(28, 12), popularity = 42.0),
            Media(2, title = "Sample Movie Two", voteAverage = 6.5, genreIds = listOf(35), popularity = 18.0),
        )
        return runCatching { customScores(url, MediaType.Movie, sampleProfile, candidates) }
            .fold({ AlgorithmTestResult(true) }, { AlgorithmTestResult(false, it.message.orEmpty()) })
    }

    private suspend fun recommendType(
        type: MediaType,
        limit: Int,
        kidProfile: Boolean,
        taste: TasteProfile,
    ): List<Media> {
        if (limit <= 0) return emptyList()
        val topGenres = taste.genreScores.getValue(type).entries
            .filter { it.value > 0 }.sortedByDescending { it.value }.take(3).map { it.key }
        val generated = if (topGenres.isEmpty()) {
            catalog.discover(type, 60)
        } else {
            coroutineScope {
                topGenres.map { genre -> async { catalog.discoverFiltered(type, genreId = genre) } }
                    .awaitAll().flatten()
            }
        }
        val candidates = filterCandidates(generated, type, taste, kidProfile).toMutableList()
        if (candidates.size < limit) {
            candidates += filterCandidates(catalog.discover(type, 60), type, taste, kidProfile)
                .filter { fallback -> candidates.none { it.id == fallback.id } }
        }
        val algorithm = settings.current().discoveryAlgorithm
        if (algorithm != "popularity") {
            val smart = candidates.associate { it.id to smartScore(it, taste.genreScores.getValue(type)) }
            val scores = if (algorithm == "custom" && settings.current().customAlgorithmUrl.isNotBlank()) {
                runCatching {
                    customScores(settings.current().customAlgorithmUrl, type, taste, candidates)
                }.getOrDefault(smart)
            } else smart
            candidates.sortByDescending { scores[it.id] ?: 0.0 }
        }
        return candidates.distinctBy(Media::id).take(limit)
    }

    private fun filterCandidates(
        input: List<Media>,
        type: MediaType,
        profile: TasteProfile,
        kidProfile: Boolean,
    ): List<Media> = input.asSequence()
        .filter { !it.posterPath.isNullOrBlank() }
        .filter { !it.adult }
        .filter { "${type.wireName}:${it.id}" !in profile.excluded }
        .filter { !kidProfile || it.genreIds.none { genre -> genre in KID_BLOCKED_GENRES } }
        .distinctBy(Media::id)
        .toList()

    private suspend fun profile(): TasteProfile {
        val profileId = session.profileId.value
        val entries = database.coveQueries.selectLibraryEntries(profileId).executeAsList()
        val dismissals = database.coveQueries.selectDismissals(profileId).executeAsList()
        val removals = database.coveQueries.selectRemovals(profileId).executeAsList()
        val fingerprint = buildString {
            append(profileId).append('|')
            entries.forEach { append(it.id).append(it.updated_at).append(it.status).append(it.rating) }
            dismissals.forEach { append(it.tmdb_id).append(it.media_type).append(it.dismissed_at) }
            removals.forEach { append(it.tmdb_id).append(it.media_type).append(it.removed_at) }
        }.hashCode()
        cached?.takeIf { it.fingerprint == fingerprint && clock.now().toEpochMilliseconds() - it.createdAt < CACHE_TTL_MILLIS }
            ?.let { return it.profile }

        return cacheMutex.withLock {
            cached?.takeIf { it.fingerprint == fingerprint && clock.now().toEpochMilliseconds() - it.createdAt < CACHE_TTL_MILLIS }
                ?.let { return@withLock it.profile }
            val semaphore = Semaphore(6)
            val signals = coroutineScope {
                entries.map { entry ->
                    async {
                        semaphore.withPermit {
                            val type = if (entry.media_type == "movie") MediaType.Movie else MediaType.Tv
                            val details = runCatching { catalog.details(entry.tmdb_id.toInt(), type) }.getOrNull()
                            TasteSignal(
                                entry.tmdb_id.toInt(),
                                type,
                                entry.title,
                                entry.poster_path,
                                signalWeight(entry.status, entry.rating, entry.updated_at),
                                details,
                            )
                        }
                    }
                }.awaitAll()
            }
            val profile = buildProfile(signals, entries.mapTo(mutableSetOf()) { "${it.media_type}:${it.tmdb_id}" }.apply {
                dismissals.forEach { add("${it.media_type}:${it.tmdb_id}") }
                removals.forEach { add("${it.media_type}:${it.tmdb_id}") }
            })
            cached = CachedProfile(fingerprint, clock.now().toEpochMilliseconds(), profile)
            profile
        }
    }

    private fun buildProfile(signals: List<TasteSignal>, excluded: Set<String>): TasteProfile {
        val genreScores = mutableMapOf(MediaType.Movie to mutableMapOf<Int, Double>(), MediaType.Tv to mutableMapOf())
        val genreNames = mutableMapOf<Int, String>()
        val keywordScores = mutableMapOf<Int, Double>()
        val keywordNames = mutableMapOf<Int, String>()
        val personScores = mutableMapOf<Int, Double>()
        val personNames = mutableMapOf<Int, String>()
        val studioCounts = mutableMapOf<String, Int>()
        val studioIds = mutableMapOf<String, Int>()
        // Three more tallies off the same pass. The details behind each signal are already
        // fetched to score genres and people, so decade, language and runtime cost nothing
        // beyond the arithmetic — asking for them separately would double the request count
        // for a profile that is already the expensive part of this page.
        val decadeCounts = mutableMapOf<Int, Int>()
        val languageCounts = mutableMapOf<String, Int>()
        val runtimes = mutableListOf<Int>()
        var movieEngaged = 0
        var tvEngaged = 0
        signals.forEach { signal ->
            if (signal.weight > 0) {
                if (signal.type == MediaType.Movie) movieEngaged++ else tvEngaged++
            }
            signal.details?.genres?.forEach { genre ->
                genreScores.getValue(signal.type)[genre.id] =
                    (genreScores.getValue(signal.type)[genre.id] ?: 0.0) + signal.weight
                genreNames[genre.id] = genre.name
            }
            signal.details?.keywords?.let { keywords ->
                (keywords.keywords + keywords.results).distinctBy { it.id }.forEach { keyword ->
                    keywordScores[keyword.id] = (keywordScores[keyword.id] ?: 0.0) + signal.weight
                    keywordNames[keyword.id] = keyword.name
                }
            }
            signal.details?.credits?.cast?.take(5)?.forEach { person ->
                personScores[person.id] = (personScores[person.id] ?: 0.0) + signal.weight
                personNames[person.id] = person.name
            }
            signal.details?.credits?.crew?.filter { it.job == "Director" }?.take(2)?.forEach { person ->
                personScores[person.id] = (personScores[person.id] ?: 0.0) + signal.weight
                personNames[person.id] = person.name
            }
            (signal.details?.productionCompanies.orEmpty() + signal.details?.networks.orEmpty())
                .distinctBy { it.id }.forEach { studio ->
                    studioCounts[studio.name] = (studioCounts[studio.name] ?: 0) + 1
                    studioIds[studio.name] = studio.id
                }
            signal.details?.let { details ->
                // A film carries release_date and a series first_air_date; both answer the
                // same question here.
                val year = details.releaseDate.ifBlank { details.firstAirDate }
                    .take(4).toIntOrNull()
                if (year != null && year in 1870..2200) {
                    val decade = year / 10 * 10
                    decadeCounts[decade] = (decadeCounts[decade] ?: 0) + 1
                }
                details.originalLanguage.takeIf { it.isNotBlank() }?.let { code ->
                    languageCounts[code] = (languageCounts[code] ?: 0) + 1
                }
                // For a series the comparable figure is one episode, not the whole run —
                // averaging a 60-hour show against a 100-minute film says nothing.
                val minutes = if (signal.type == MediaType.Movie) {
                    details.runtime
                } else {
                    details.episodeRunTime.firstOrNull() ?: 0
                }
                if (minutes > 0) runtimes += minutes
            }
        }
        val contributors = signals.map {
            ContributingTitle(it.tmdbId, it.type.wireName, it.title, it.posterPath, it.weight)
        }.sortedByDescending { it.weight }
        val favorites = contributors.filter { it.weight > 0 }.map {
            FavoriteSignal(it.tmdbId, it.mediaType, it.weight, it.title)
        }
        val total = movieEngaged + tvEngaged
        return TasteProfile(
            genreScores = genreScores,
            genreNames = genreNames,
            keywordScores = keywordScores,
            keywordNames = keywordNames,
            personScores = personScores,
            personNames = personNames,
            studioCounts = studioCounts,
            studioIds = studioIds,
            contributors = contributors,
            favorites = favorites,
            excluded = excluded,
            movieShare = if (total == 0) 0.5 else movieEngaged.toDouble() / total,
            decades = decadeCounts.entries.sortedWith(
                compareByDescending<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key },
            ).map { DecadeCount(it.key, it.value) },
            languages = languageCounts.entries.sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key },
            ).map { LanguageCount(it.key, it.value) },
            averageRuntimeMinutes = if (runtimes.isEmpty()) 0 else runtimes.average().toInt(),
        )
    }

    private suspend fun customScores(
        url: String,
        type: MediaType,
        profile: TasteProfile,
        candidates: List<Media>,
    ): Map<Int, Double> {
        customUrlPolicy.validate(url)
        val response = withTimeout(5_000) {
            customHttpClient.post(url) {
                contentType(ContentType.Application.Json)
                setBody(CustomAlgorithmRequest(
                    type.wireName,
                    AlgorithmProfile(
                        rankedTaste(profile.genreScores.getValue(type), profile.genreNames).take(8),
                        profile.genreScores.values.flatMap { it.entries }.filter { it.value < 0 }
                            .sortedBy { it.value }.take(8)
                            .map { DiscoveryTaste(it.key, profile.genreNames[it.key].orEmpty(), it.value) },
                        rankedTaste(profile.keywordScores, profile.keywordNames).take(12),
                        rankedTaste(profile.personScores, profile.personNames).take(8),
                    ),
                    candidates,
                ))
            }
        }
        check(response.status.isSuccess()) { "custom algorithm returned HTTP ${response.status.value}" }
        return CoveJson.decodeFromString<CustomAlgorithmResponse>(response.bodyAsText()).scores
            .mapNotNull { (id, score) -> id.toIntOrNull()?.let { it to score } }.toMap()
    }

    private fun signalWeight(status: String, rating: Double?, updatedAt: String): Double {
        val base = when (status) {
            LibraryStatus.Finished.wireName -> 2.0
            LibraryStatus.Watching.wireName -> 1.0
            LibraryStatus.WatchLater.wireName -> 0.25
            LibraryStatus.Dropped.wireName -> -2.0
            else -> 0.0
        } + (rating?.let { (it - 2.5) * 1.5 } ?: 0.0)
        val ageDays = updatedAt.toInstantOrNull()?.let {
            max(0.0, (clock.now().epochSeconds - it.epochSeconds) / 86_400.0)
        } ?: 0.0
        return base * 0.5.pow(ageDays / 365.0)
    }

    private data class TasteSignal(
        val tmdbId: Int,
        val type: MediaType,
        val title: String,
        val posterPath: String,
        val weight: Double,
        val details: MediaDetails?,
    )

    private data class CachedProfile(val fingerprint: Int, val createdAt: Long, val profile: TasteProfile)

    private data class TasteProfile(
        val genreScores: Map<MediaType, Map<Int, Double>> = mapOf(
            MediaType.Movie to emptyMap(), MediaType.Tv to emptyMap(),
        ),
        val genreNames: Map<Int, String> = emptyMap(),
        val keywordScores: Map<Int, Double> = emptyMap(),
        val keywordNames: Map<Int, String> = emptyMap(),
        val personScores: Map<Int, Double> = emptyMap(),
        val personNames: Map<Int, String> = emptyMap(),
        val studioCounts: Map<String, Int> = emptyMap(),
        val studioIds: Map<String, Int> = emptyMap(),
        val contributors: List<ContributingTitle> = emptyList(),
        val favorites: List<FavoriteSignal> = emptyList(),
        val excluded: Set<String> = emptySet(),
        val movieShare: Double = 0.5,
        val decades: List<DecadeCount> = emptyList(),
        val languages: List<LanguageCount> = emptyList(),
        val averageRuntimeMinutes: Int = 0,
    )

    companion object {
        private const val CACHE_TTL_MILLIS = 5 * 60 * 1_000L
        private val KID_BLOCKED_GENRES = setOf(27, 53, 10_752, 80)
    }
}

@Serializable
data class FavoriteSignal(
    @SerialName("tmdb_id") val tmdbId: Int,
    @SerialName("media_type") val mediaType: String,
    val weight: Double,
    // Defaulted so an older client reading this payload is unaffected. Explore needs it to
    // title a rail after the thing that produced it — "Because you watched Fight Club"
    // cannot be assembled from an id alone without a second lookup.
    val title: String = "",
)

@Serializable
data class AlgorithmTestRequest(val url: String)

@Serializable
data class AlgorithmTestResult(val ok: Boolean, val error: String = "")

@Serializable
private data class AlgorithmProfile(
    @SerialName("top_genres") val topGenres: List<DiscoveryTaste>,
    @SerialName("disliked_genres") val dislikedGenres: List<DiscoveryTaste>,
    @SerialName("top_keywords") val topKeywords: List<DiscoveryTaste>,
    @SerialName("top_people") val topPeople: List<DiscoveryTaste>,
)

@Serializable
private data class CustomAlgorithmRequest(
    @SerialName("media_type") val mediaType: String,
    val profile: AlgorithmProfile,
    val candidates: List<Media>,
)

@Serializable
private data class CustomAlgorithmResponse(val scores: Map<String, Double> = emptyMap())

private fun rankedTaste(scores: Map<Int, Double>, names: Map<Int, String>): List<DiscoveryTaste> =
    scores.entries.filter { it.value > 0 }.sortedByDescending { it.value }
        .map { DiscoveryTaste(it.key, names[it.key].orEmpty(), it.value) }

private fun smartScore(media: Media, genreScores: Map<Int, Double>): Double =
    2.0 * media.genreIds.sumOf { genreScores[it] ?: 0.0 } +
        (media.voteAverage - 5.0) + 0.05 * media.popularity

private fun weightedMerge(
    movies: List<Media>,
    shows: List<Media>,
    movieShare: Double,
    limit: Int,
): List<Media> {
    val result = mutableListOf<Media>()
    var movieIndex = 0
    var showIndex = 0
    var moviesUsed = 0
    while (result.size < limit && (movieIndex < movies.size || showIndex < shows.size)) {
        val takeMovie = when {
            movieIndex >= movies.size -> false
            showIndex >= shows.size -> true
            else -> {
                val slots = result.size + 1.0
                kotlin.math.abs(movieShare - (moviesUsed + 1) / slots) <=
                    kotlin.math.abs(movieShare - moviesUsed / slots)
            }
        }
        if (takeMovie) {
            result += movies[movieIndex++]
            moviesUsed++
        } else result += shows[showIndex++]
    }
    return result
}

private fun String.toMediaType(): MediaType = when (this) {
    "movie" -> MediaType.Movie
    "tv" -> MediaType.Tv
    else -> throw IllegalArgumentException("type must be movie, tv, or all")
}

private fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()
