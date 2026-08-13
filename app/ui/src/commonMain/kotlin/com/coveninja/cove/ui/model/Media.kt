package com.coveninja.cove.ui.model

import com.coveninja.cove.shared.data.ContentDetails
import com.coveninja.cove.shared.data.ContentArtwork
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.TvEpisode
import com.coveninja.cove.shared.network.resolveTmdbImageUrl
import com.coveninja.cove.shared.model.Media as DomainMedia
import com.coveninja.cove.shared.model.MediaType as DomainMediaType

enum class MediaType(val label: String) {
    Movie("Movie"),
    Series("Series"),
}

data class MediaVideo(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    /** TMDB's own label — "Trailer", "Behind the Scenes" — shown on the card as-is. */
    val type: String? = null,
    val duration: String? = null,
    /**
     * Where the video plays: a YouTube watch page, not a direct media URL. Null
     * for an entry on a site with no known page format, which nothing can open.
     */
    val url: String? = null,
    /** TMDB's flag for an upload from the studio rather than a channel mirroring it. */
    val official: Boolean = false,
    /** ISO-8601 UTC, or null. Compared as a string — see [sortedForDisplay]. */
    val publishedAt: String? = null,
) {
    val category: VideoCategory get() = videoCategoryOf(type)
}

data class MediaCastMember(
    /** TMDB person id — what the cast row needs to be able to open the person sheet. */
    val tmdbId: Int,
    val name: String,
    val character: String?,
    val profileUrl: String?,
)

data class MediaRecommendation(
    val id: String,
    val tmdbId: Int,
    val title: String,
    val posterUrl: String?,
    val type: MediaType?,
    val rating: Double?,
)

data class MediaEpisode(
    val id: String,
    val number: Int,
    val title: String,
    val overview: String? = null,
    val airDate: String? = null,
    val runtimeMinutes: Int? = null,
    val stillUrl: String? = null,
    val rating: Double? = null,
    val watched: Boolean = false,
)

data class MediaSeason(
    val number: Int,
    val title: String,
    val posterUrl: String? = null,
    val episodeCount: Int = 0,
    val episodes: List<MediaEpisode> = emptyList(),
)

data class Media(
    val id: String,
    val tmdbId: Int,
    val title: String?,
    val name: String?,
    val overview: String?,
    val released: String?,
    val firstAirDate: String?,
    val posterUrl: String?,
    val logoUrl: String?,
    val backdropUrl: String?,
    val rating: Double?,
    val type: MediaType?,
    val popularity: Double?,
    val adult: Boolean?,
    val originalLanguage: String?,
    val runtimeMinutes: Int? = null,
    val certification: String? = null,
    val status: String? = null,
    val tagline: String? = null,
    val genres: List<String> = emptyList(),
    /**
     * The same genres as ids. Kept alongside the names because filtering has to be done on
     * these: a name is localized and may have come from the baked-in fallback table, so two
     * spellings of one genre would split a filter that an id keeps whole.
     */
    val genreIds: List<Int> = emptyList(),
    /** Kept as people, not names, so the Details facts can open them like a cast card can. */
    val directors: List<Person> = emptyList(),
    val writers: List<Person> = emptyList(),
    val productionCompanies: List<String> = emptyList(),
    val originCountries: List<String> = emptyList(),
    val spokenLanguages: List<String> = emptyList(),
    val videos: List<MediaVideo> = emptyList(),
    val cast: List<MediaCastMember> = emptyList(),
    val moreLikeThis: List<MediaRecommendation> = emptyList(),
    val seasons: List<MediaSeason> = emptyList(),
)

fun DomainMedia.toUiMedia(): Media {
    val uiType = mediaType.toUiType()
    return Media(
        id = uiId(id, uiType),
        tmdbId = id,
        title = title,
        name = name,
        overview = overview,
        released = releaseDate?.take(4),
        firstAirDate = firstAirDate,
        posterUrl = displayImageUrl(posterPath, "w500"),
        logoUrl = null,
        backdropUrl = displayImageUrl(backdropPath, "w1280"),
        rating = voteAverage.takeIf { it > 0.0 },
        type = uiType,
        popularity = popularity,
        adult = adult,
        originalLanguage = originalLanguage,
        // List-level media carries genre ids and no names. Resolving them here is what
        // gives every card and every filter row real genres without a details fetch —
        // without it `genres` is empty for anything that never went through details(),
        // which is everything on Home, Explore and Search.
        genres = TmdbGenres.namesOf(genreIds, uiType),
        genreIds = genreIds,
    )
}

fun LibraryEntry.toUiMedia(): Media {
    val uiType = mediaType.toUiType()
    return Media(
        id = uiId(tmdbId, uiType),
        tmdbId = tmdbId,
        title = title,
        name = title,
        overview = null,
        released = null,
        firstAirDate = null,
        posterUrl = displayImageUrl(posterPath, "w500"),
        logoUrl = null,
        backdropUrl = null,
        rating = voteAverage.takeIf { it > 0.0 },
        type = uiType,
        popularity = null,
        adult = null,
        originalLanguage = null,
    )
}

fun ContentDetails.toUiMedia(): Media {
    val base = media.toUiMedia()
    val metadata = details
    val logo = images.logos
        .sortedByDescending { it.language == "en" }
        .firstOrNull()
        ?.displayUrl("w500")
    val backdrop = images.backdrops.firstOrNull()?.displayUrl("w1280")
        ?: base.backdropUrl
    val poster = images.posters.firstOrNull()?.displayUrl("w500")
        ?: displayImageUrl(metadata.posterPath, "w500")
        ?: base.posterUrl
    val crew = metadata.credits.crew

    return base.copy(
        title = metadata.title.ifBlank { base.title.orEmpty() }.ifBlank { null },
        name = metadata.name.ifBlank { base.name.orEmpty() }.ifBlank { null },
        overview = metadata.overview.ifBlank { base.overview.orEmpty() }.ifBlank { null },
        released = metadata.releaseDate.take(4).ifBlank { base.released.orEmpty() }.ifBlank { null },
        posterUrl = poster,
        logoUrl = logo,
        backdropUrl = backdrop,
        runtimeMinutes = metadata.runtime.takeIf { it > 0 }
            ?: metadata.episodeRunTime.firstOrNull(),
        certification = metadata.certification.ifBlank { null },
        status = metadata.status.ifBlank { null },
        genres = metadata.genres.map { it.name }.filter { it.isNotBlank() },
        genreIds = metadata.genres.map { it.id }.filter { it > 0 },
        // distinctBy id, not name: one person credited twice (say "Screenplay" and
        // "Story") is one person, and two people can share a name.
        directors = crew.filter { it.job == "Director" }
            .distinctBy { it.id }
            .map { it.toUiPerson() },
        writers = crew.filter {
            it.job in setOf("Writer", "Screenplay", "Teleplay", "Story")
        }.distinctBy { it.id }.map { it.toUiPerson() },
        productionCompanies = metadata.productionCompanies.map { it.name },
        originCountries = metadata.originCountry,
        // Entries with nowhere to play are dropped rather than shown: every card
        // here is a play button, and one that cannot do anything is a dead end.
        videos = videos.results.mapNotNull { video ->
            val watchUrl = video.watchUrl() ?: return@mapNotNull null
            MediaVideo(
                id = "${media.id}-${video.key}",
                title = video.name.ifBlank { video.type.ifBlank { "Video" } },
                thumbnailUrl = video.key
                    .takeIf { it.isNotBlank() && video.site.equals("YouTube", ignoreCase = true) }
                    ?.let { "https://img.youtube.com/vi/$it/hqdefault.jpg" },
                type = video.type.ifBlank { null },
                url = watchUrl,
                official = video.official,
                publishedAt = video.publishedAt.ifBlank { null },
            )
        }.sortedForDisplay(),
        cast = metadata.credits.cast.sortedBy { it.order }.map { member ->
            MediaCastMember(
                tmdbId = member.id,
                name = member.name,
                character = member.character.ifBlank { null },
                profileUrl = displayImageUrl(member.profilePath, "w185"),
            )
        },
        moreLikeThis = similar.map { item ->
            val itemType = item.mediaType.toUiType()
            MediaRecommendation(
                id = uiId(item.id, itemType),
                tmdbId = item.id,
                title = item.displayTitle,
                posterUrl = displayImageUrl(item.posterPath, "w500"),
                type = itemType,
                rating = item.voteAverage.takeIf { it > 0.0 },
            )
        },
        seasons = metadata.seasons
            .filter { it.seasonNumber > 0 }
            .map { season ->
                MediaSeason(
                    number = season.seasonNumber,
                    title = season.name?.takeIf { it.isNotBlank() }
                        ?: "Season ${season.seasonNumber}",
                    posterUrl = displayImageUrl(season.posterPath, "w500"),
                    episodeCount = season.episodeCount,
                )
            },
    )
}

fun ContentArtwork.toUiMedia(): Media {
    val base = media.toUiMedia()
    val logo = images.logos
        .sortedByDescending { it.language == "en" }
        .firstOrNull()
        ?.displayUrl("w500")
    val backdrop = images.backdrops.firstOrNull()?.displayUrl("w1280") ?: base.backdropUrl
    val poster = images.posters.firstOrNull()?.displayUrl("w500") ?: base.posterUrl
    return base.copy(posterUrl = poster, logoUrl = logo, backdropUrl = backdrop)
}

fun MediaRecommendation.toMedia(): Media = Media(
    id = id,
    tmdbId = tmdbId,
    title = title,
    name = title,
    overview = null,
    released = null,
    firstAirDate = null,
    posterUrl = posterUrl,
    logoUrl = null,
    backdropUrl = null,
    rating = rating,
    type = type,
    popularity = null,
    adult = null,
    originalLanguage = null,
)

fun Media.toDomainMedia(): DomainMedia = DomainMedia(
    id = tmdbId,
    title = title,
    name = name,
    overview = overview,
    posterPath = posterUrl,
    backdropPath = backdropUrl,
    releaseDate = released,
    firstAirDate = firstAirDate,
    voteAverage = rating ?: 0.0,
    mediaType = type.toDomainType(),
    popularity = popularity ?: 0.0,
    adult = adult ?: false,
    originalLanguage = originalLanguage.orEmpty(),
    genreIds = genreIds,
)

fun TvEpisode.toUiEpisode(mediaId: String, season: Int): MediaEpisode =
    MediaEpisode(
        id = "$mediaId-s${season}e$episodeNumber",
        number = episodeNumber,
        title = name?.takeIf { it.isNotBlank() } ?: "Episode $episodeNumber",
        overview = overview,
        airDate = airDate,
        runtimeMinutes = runtime.takeIf { it > 0 },
        stillUrl = displayImageUrl(stillPath, "w500"),
    )

fun DomainMediaType?.toUiType(): MediaType? = when (this) {
    DomainMediaType.Movie -> MediaType.Movie
    DomainMediaType.Tv -> MediaType.Series
    null -> null
}

fun MediaType?.toDomainType(): DomainMediaType? = when (this) {
    MediaType.Movie -> DomainMediaType.Movie
    MediaType.Series -> DomainMediaType.Tv
    null -> null
}

fun displayImageUrl(path: String?, size: String): String? = resolveTmdbImageUrl(path, size)

fun tmdbImageSize(url: String?, size: String): String? = resolveTmdbImageUrl(url, size)

private fun com.coveninja.cove.shared.model.MediaImage.displayUrl(size: String): String? =
    displayImageUrl(url.takeIf { it.isNotBlank() } ?: filePath, size)

/**
 * The page a video plays on. Preferred over the embed URL the backend fills in,
 * because that one exists to be framed and there is no browser here to frame it —
 * both the system browser and mpv's stream extractor want the watch page.
 */
private fun com.coveninja.cove.shared.model.MediaVideo.watchUrl(): String? {
    val id = key.takeIf { it.isNotBlank() }
    return when {
        id != null && site.equals("YouTube", ignoreCase = true) ->
            "https://www.youtube.com/watch?v=$id"

        id != null && site.equals("Vimeo", ignoreCase = true) -> "https://vimeo.com/$id"
        else -> embedUrl.takeIf { it.isNotBlank() }
    }
}

/**
 * The UI identity for a title known only by its TMDB id and domain type — watch progress
 * rows and calendar items, neither of which carries a [Media]. Shares [uiId] so those keys
 * cannot drift from the ones the cards are built with.
 */
fun uiMediaId(tmdbId: Int, mediaType: DomainMediaType?): String =
    uiId(tmdbId, mediaType.toUiType())

private fun uiId(id: Int, type: MediaType?): String =
    "${type?.name ?: "Media"}:$id"
