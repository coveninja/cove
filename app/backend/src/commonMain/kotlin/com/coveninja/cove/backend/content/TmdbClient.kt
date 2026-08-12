package com.coveninja.cove.backend.content

import com.coveninja.cove.backend.addons.AddonCatalogItem
import com.coveninja.cove.shared.model.CatalogSort
import com.coveninja.cove.shared.model.Media
import com.coveninja.cove.shared.model.MediaCastMember
import com.coveninja.cove.shared.model.MediaDetails
import com.coveninja.cove.shared.model.MediaGenre
import com.coveninja.cove.shared.model.MediaImage
import com.coveninja.cove.shared.model.MediaImages
import com.coveninja.cove.shared.model.MediaKeyword
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.MediaVideo
import com.coveninja.cove.shared.model.MediaVideos
import com.coveninja.cove.shared.model.PersonDetails
import com.coveninja.cove.shared.model.TvEpisode
import com.coveninja.cove.shared.model.TvSeason
import com.coveninja.cove.shared.network.SearchResultsDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable

class TmdbClient(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val localeProvider: () -> String = { "en" },
    private val baseUrl: String = "https://api.themoviedb.org/3",
) : MediaCatalog {
    init {
        require(apiKey.isNotBlank()) { "TMDB_API_KEY is required for the Kotlin backend" }
    }

    override suspend fun discover(type: MediaType, limit: Int): List<Media> {
        require(limit in 1..60) { "discover limit must be between 1 and 60" }
        val local = results("/discover/${type.wireName}") {
            parameter("include_adult", false)
            parameter("sort_by", "popularity.desc")
        }.withType(type)
        val merged = if (appLocale() == "en") local else {
            mergeMedia(local, results("/discover/${type.wireName}", "en-US") {
                parameter("include_adult", false)
                parameter("sort_by", "popularity.desc")
            }.withType(type))
        }
        return merged.filter { !it.posterPath.isNullOrBlank() }.take(limit)
    }

    override suspend fun searchMulti(query: String): SearchResultsDto = coroutineScope {
        require(query.isNotBlank()) { "search query is required" }
        val movies = async { search(query, MediaType.Movie) }
        val tv = async { search(query, MediaType.Tv) }
        val keywordMatches = async { runCatching { searchByKeywords(query) }.getOrDefault(emptyList()) }
        // People are an extra, not the answer: a failure here must not cost the viewer
        // the titles, which are what they were almost certainly searching for.
        val people = async { runCatching { searchPeople(query) }.getOrDefault(emptyList()) }
        val regularMovies = movies.await()
        val regularTv = tv.await()
        val keywordResults = keywordMatches.await()
        SearchResultsDto(
            people = people.await(),
            movies = (regularMovies + keywordResults.filter { it.mediaType == MediaType.Movie })
                .distinctBy(Media::id),
            tv = (regularTv + keywordResults.filter { it.mediaType == MediaType.Tv })
                .distinctBy(Media::id),
        )
    }

    override suspend fun media(id: Int, type: MediaType): Media {
        require(id > 0) { "media id must be positive" }
        val local = request<Media>("/${type.wireName}/$id").copy(mediaType = type)
        if (appLocale() == "en" || !local.needsFallback()) return local
        val english = request<Media>("/${type.wireName}/$id", "en-US").copy(mediaType = type)
        return local.withEnglishFallback(english)
    }

    override suspend fun details(id: Int, type: MediaType): MediaDetails {
        require(id > 0) { "media id must be positive" }
        val path = "/${type.wireName}/$id"
        val local = request<MediaDetails>(path) {
            parameter("append_to_response", "credits,release_dates,content_ratings,keywords")
        }
        if (appLocale() == "en" || !local.needsFallback()) return local
        val english = request<MediaDetails>(path, "en-US") {
            parameter("append_to_response", "credits,release_dates,content_ratings,keywords")
        }
        return local.withEnglishFallback(english)
    }

    override suspend fun images(id: Int, type: MediaType): MediaImages {
        require(id > 0) { "media id must be positive" }
        val locale = appLocale()
        val language = tmdbLocale(locale)
        val imageLanguages = if (locale == "en") "en,null" else "$locale,en,null"
        return request<MediaImages>("/${type.wireName}/$id/images", language) {
            parameter("include_image_language", imageLanguages)
        }.withUrls()
    }

    override suspend fun videos(id: Int, type: MediaType): MediaVideos {
        require(id > 0) { "media id must be positive" }
        val path = "/${type.wireName}/$id/videos"
        val local = request<MediaVideos>(path).withEmbedUrls()
        if (appLocale() == "en" || local.results.isNotEmpty()) return local
        return request<MediaVideos>(path, "en-US").withEmbedUrls()
    }

    override suspend fun similar(id: Int, type: MediaType): List<Media> {
        require(id > 0) { "media id must be positive" }
        val path = "/${type.wireName}/$id/recommendations"
        val local = results(path).withType(type)
        val merged = if (appLocale() == "en") local else {
            mergeMedia(local, results(path, "en-US").withType(type))
        }
        return merged.filter { !it.posterPath.isNullOrBlank() }.take(12)
    }

    override suspend fun episodes(id: Int, season: Int): List<TvEpisode> {
        require(id > 0) { "media id must be positive" }
        require(season >= 0) { "season must not be negative" }
        val path = "/tv/$id/season/$season"
        val local = request<TmdbEpisodes>(path).episodes
        if (appLocale() == "en" || local.none { it.needsFallback() }) return local
        val english = request<TmdbEpisodes>(path, "en-US").episodes.associateBy { it.episodeNumber }
        return local.map { episode ->
            english[episode.episodeNumber]?.let(episode::withEnglishFallback) ?: episode
        }
    }

    override suspend fun seasons(id: Int): List<TvSeason> {
        require(id > 0) { "media id must be positive" }
        val path = "/tv/$id"
        val local = request<TmdbSeasons>(path).seasons
        if (appLocale() == "en" || local.none { it.name.isNullOrBlank() }) return local
        val english = request<TmdbSeasons>(path, "en-US").seasons.associateBy { it.seasonNumber }
        return local.map { season ->
            if (!season.name.isNullOrBlank()) season
            else english[season.seasonNumber]?.let { season.copy(name = it.name) } ?: season
        }
    }

    override suspend fun imdbId(id: Int, type: MediaType): String {
        require(id > 0) { "media id must be positive" }
        val result = request<TmdbExternalIds>("/${type.wireName}/$id/external_ids", "en-US")
        return result.imdbId.takeIf(String::isNotBlank)
            ?: throw TmdbException("TMDB returned no IMDb id for /${type.wireName}/$id")
    }

    suspend fun keywordSuggestions(query: String): List<MediaKeyword> {
        require(query.isNotBlank()) { "keyword query is required" }
        return request<TmdbResults<MediaKeyword>>("/search/keyword") {
            parameter("query", query)
        }.results.take(20)
    }

    suspend fun searchTitles(query: String): List<Media> {
        val result = searchMulti(query)
        return (result.movies + result.tv).distinctBy { "${it.mediaType}:${it.id}" }
    }

    private suspend fun searchByKeywords(query: String): List<Media> {
        val ids = keywordSuggestions(query).take(3).map(MediaKeyword::id)
        if (ids.isEmpty()) return emptyList()
        val joined = ids.joinToString("|")
        return coroutineScope {
            listOf(MediaType.Movie, MediaType.Tv).map { type ->
                async {
                    runCatching {
                        results("/discover/${type.wireName}") {
                            parameter("with_keywords", joined)
                            parameter("sort_by", "popularity.desc")
                            parameter("include_adult", false)
                        }.withType(type).filter { !it.posterPath.isNullOrBlank() }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
    }

    /**
     * People matching a query, most prominent first.
     *
     * `/search/person` answers with a slim person — no biography, and `known_for` in place of
     * a filmography — which is all a result row needs; opening one fetches the rest. The
     * cutoff is deliberate: TMDB's person index reaches deep into uncredited extras and
     * duplicate records, and past the first handful the matches stop being anybody.
     */
    private suspend fun searchPeople(query: String, limit: Int = 12): List<PersonDetails> =
        request<TmdbResults<PersonDetails>>("/search/person") {
            parameter("query", query)
            parameter("include_adult", false)
        }.results
            .filter { it.id > 0 && it.name.isNotBlank() }
            .sortedByDescending(PersonDetails::popularity)
            .take(limit)

    override suspend fun person(id: Int): PersonDetails {
        require(id > 0) { "person id must be positive" }
        return request("/person/$id") { parameter("append_to_response", "combined_credits") }
    }

    suspend fun providerTitles(providerId: Int, limit: Int = 40): List<Media> = coroutineScope {
        require(providerId > 0) { "provider id must be positive" }
        val capped = limit.coerceIn(1, 100)
        val movies = async {
            results("/discover/movie") {
                parameter("watch_region", "US")
                parameter("with_watch_providers", providerId)
                parameter("sort_by", "popularity.desc")
                parameter("include_adult", false)
            }.withType(MediaType.Movie)
        }
        val shows = async {
            results("/discover/tv") {
                parameter("watch_region", "US")
                parameter("with_watch_providers", providerId)
                parameter("sort_by", "popularity.desc")
                parameter("include_adult", false)
            }.withType(MediaType.Tv)
        }
        (movies.await() + shows.await())
            .sortedByDescending(Media::popularity)
            .take(capped)
    }

    override suspend fun genres(type: MediaType): List<MediaGenre> =
        request<TmdbGenres>("/genre/${type.wireName}/list").genres

    override suspend fun discoverFiltered(
        type: MediaType,
        genreId: Int?,
        keywordId: Int?,
        personId: Int?,
        sort: CatalogSort,
        page: Int,
    ): List<Media> = results("/discover/${type.wireName}") {
        parameter("include_adult", false)
        parameter("sort_by", sort.tmdbSortBy(type))
        // The vote floor is what makes Rating usable at all: without it TMDB happily
        // returns titles sitting at 10.0 on three votes ahead of anything recognisable.
        // Every other order wants it too, to keep untouched entries out of the results.
        parameter("vote_count.gte", sort.voteFloor(type))
        parameter("page", page.coerceIn(1, TMDB_MAX_PAGE))
        genreId?.let { parameter("with_genres", it) }
        keywordId?.let { parameter("with_keywords", it) }
        personId?.let { parameter("with_people", it) }
    }.withType(type).filter { !it.posterPath.isNullOrBlank() }

    suspend fun resolveAddonMeta(meta: AddonCatalogItem): Media? {
        val type = when (meta.type) {
            "movie" -> MediaType.Movie
            "series", "tv" -> MediaType.Tv
            else -> return null
        }
        val direct = meta.id.removePrefix("tmdb:").substringBefore(':')
            .takeIf { meta.id.startsWith("tmdb:") }
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
        if (direct != null) return runCatching { media(direct, type) }.getOrNull()
        if (!meta.id.startsWith("tt")) return null
        val found = runCatching {
            request<TmdbFind>("/find/${meta.id}", "en-US") {
                parameter("external_source", "imdb_id")
            }
        }.getOrNull() ?: return null
        val id = when (type) {
            MediaType.Movie -> found.movieResults.firstOrNull()?.id
            MediaType.Tv -> found.tvResults.firstOrNull()?.id
        } ?: return null
        return runCatching { media(id, type) }.getOrNull()
    }

    private suspend fun search(query: String, type: MediaType): List<Media> {
        val path = "/search/${type.wireName}"
        val local = results(path) { parameter("query", query) }.withType(type)
        val merged = if (appLocale() == "en") local else {
            mergeMedia(local, results(path, "en-US") { parameter("query", query) }.withType(type))
        }
        return merged.filter { !it.posterPath.isNullOrBlank() }
    }

    private suspend fun results(
        path: String,
        language: String = tmdbLocale(appLocale()),
        configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): List<Media> = request<TmdbResults<Media>>(path, language, configure).results

    private suspend inline fun <reified T> request(
        path: String,
        language: String = tmdbLocale(appLocale()),
        noinline configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): T {
        val response: HttpResponse = httpClient.get("${baseUrl.trimEnd('/')}$path") {
            parameter("api_key", apiKey)
            parameter("language", language)
            configure()
        }
        if (!response.status.isSuccess()) {
            throw TmdbException("TMDB ${response.status.value} for $path")
        }
        return response.body()
    }

    private fun appLocale(): String = normalizeAppLocale(localeProvider())
}

class TmdbException(message: String) : RuntimeException(message)

/** TMDB refuses anything beyond this and answers 400 rather than an empty page. */
private const val TMDB_MAX_PAGE = 500

/**
 * TMDB spells the same ordering differently for films and series — a series has no
 * `primary_release_date` and no `title`, only `first_air_date` and `name` — so the
 * translation has to know which endpoint it is bound for.
 */
private fun CatalogSort.tmdbSortBy(type: MediaType): String = when (this) {
    CatalogSort.Popularity -> "popularity.desc"
    CatalogSort.Rating -> "vote_average.desc"
    CatalogSort.Newest -> if (type == MediaType.Movie) {
        "primary_release_date.desc"
    } else {
        "first_air_date.desc"
    }
    CatalogSort.Oldest -> if (type == MediaType.Movie) {
        "primary_release_date.asc"
    } else {
        "first_air_date.asc"
    }
    CatalogSort.Title -> if (type == MediaType.Movie) "title.asc" else "name.asc"
}

/**
 * Series are rated by far fewer people than films, so one floor applied to both would
 * empty the series catalogue. [CatalogSort.Rating] needs a much higher bar again: it is
 * the order that a handful of perfect scores would otherwise dominate outright.
 */
private fun CatalogSort.voteFloor(type: MediaType): Int {
    val base = if (type == MediaType.Movie) 100 else 40
    return if (this == CatalogSort.Rating) base * 5 else base
}

@Serializable
private data class TmdbResults<T>(val results: List<T> = emptyList())

@Serializable
private data class TmdbEpisodes(val episodes: List<TvEpisode> = emptyList())

@Serializable
private data class TmdbSeasons(val seasons: List<TvSeason> = emptyList())

@Serializable
private data class TmdbExternalIds(
    @kotlinx.serialization.SerialName("imdb_id") val imdbId: String = "",
)

@Serializable
private data class TmdbGenres(val genres: List<MediaGenre> = emptyList())

@Serializable
private data class TmdbFind(
    @kotlinx.serialization.SerialName("movie_results") val movieResults: List<Media> = emptyList(),
    @kotlinx.serialization.SerialName("tv_results") val tvResults: List<Media> = emptyList(),
)

private fun interleave(first: List<Media>, second: List<Media>, limit: Int): List<Media> {
    val result = ArrayList<Media>(limit)
    var left = 0
    var right = 0
    while (result.size < limit && (left < first.size || right < second.size)) {
        if (left < first.size) result += first[left++]
        if (result.size < limit && right < second.size) result += second[right++]
    }
    return result
}

internal fun normalizeAppLocale(value: String): String =
    value.trim().lowercase().substringBefore('-').substringBefore('_').let { base ->
        when (base) {
            "tr", "pt", "es", "it", "de", "ja" -> base
            else -> "en"
        }
    }

internal fun tmdbLocale(appLocale: String): String = when (appLocale) {
    "tr" -> "tr-TR"
    "pt" -> "pt-BR"
    "es" -> "es-ES"
    "it" -> "it-IT"
    "de" -> "de-DE"
    "ja" -> "ja-JP"
    else -> "en-US"
}

private fun List<Media>.withType(type: MediaType) = map { it.copy(mediaType = type) }

private fun mergeMedia(local: List<Media>, english: List<Media>): List<Media> {
    val fallback = english.associateBy(Media::id)
    return local.map { media -> fallback[media.id]?.let(media::withEnglishFallback) ?: media }
}

private fun Media.needsFallback() =
    title?.isBlank() == true ||
        name?.isBlank() == true ||
        overview.isNullOrBlank()

private fun Media.withEnglishFallback(english: Media) = copy(
    title = title?.takeIf(String::isNotBlank) ?: english.title,
    name = name?.takeIf(String::isNotBlank) ?: english.name,
    overview = overview?.takeIf(String::isNotBlank) ?: english.overview,
)

private fun MediaDetails.needsFallback() =
    displayTitle.isBlank() || overview.isBlank() || genres.any { it.name.isBlank() }

private fun MediaDetails.withEnglishFallback(english: MediaDetails): MediaDetails {
    val englishGenres = english.genres.associateBy(MediaGenre::id)
    val englishCast = english.credits.cast.associateBy(MediaCastMember::id)
    return copy(
        title = title.takeIf(String::isNotBlank) ?: english.title,
        name = name.takeIf(String::isNotBlank) ?: english.name,
        overview = overview.takeIf(String::isNotBlank) ?: english.overview,
        genres = genres.map { genre ->
            if (genre.name.isNotBlank()) genre else englishGenres[genre.id] ?: genre
        },
        credits = credits.copy(
            cast = credits.cast.map { member ->
                val fallback = englishCast[member.id] ?: return@map member
                member.copy(
                    name = member.name.takeIf(String::isNotBlank) ?: fallback.name,
                    character = member.character.takeIf(String::isNotBlank) ?: fallback.character,
                )
            },
        ),
    )
}

private fun MediaImages.withUrls() = copy(
    backdrops = backdrops.map { it.withUrl("original") },
    logos = logos.map { it.withUrl("w500") },
    posters = posters.map { it.withUrl("w500") },
)

private fun MediaImage.withUrl(size: String) = copy(
    url = filePath.takeIf(String::isNotBlank)?.let { "https://image.tmdb.org/t/p/$size$it" }.orEmpty(),
)

private fun MediaVideos.withEmbedUrls() = copy(
    results = results.map(MediaVideo::withEmbedUrl),
)

private fun MediaVideo.withEmbedUrl() = if (site == "YouTube" && key.isNotBlank()) {
    copy(embedUrl = "https://www.youtube.com/embed/$key")
} else {
    this
}

private fun TvEpisode.needsFallback() = name.isNullOrBlank() || overview.isNullOrBlank()

private fun TvEpisode.withEnglishFallback(english: TvEpisode) = copy(
    name = name?.takeIf(String::isNotBlank) ?: english.name,
    overview = overview?.takeIf(String::isNotBlank) ?: english.overview,
)
