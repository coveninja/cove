package com.coveninja.cove.ui.model

import com.coveninja.cove.shared.data.ContentDetails
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.TvEpisode
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
    val type: String? = null,
    val duration: String? = null,
)

data class MediaCastMember(
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
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
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
        directors = crew.filter { it.job == "Director" }.map { it.name }.distinct(),
        writers = crew.filter {
            it.job in setOf("Writer", "Screenplay", "Teleplay", "Story")
        }.map { it.name }.distinct(),
        productionCompanies = metadata.productionCompanies.map { it.name },
        originCountries = metadata.originCountry,
        videos = videos.results.map { video ->
            MediaVideo(
                id = "${media.id}-${video.key}",
                title = video.name.ifBlank { video.type.ifBlank { "Video" } },
                thumbnailUrl = video.key.takeIf { it.isNotBlank() }?.let {
                    "https://img.youtube.com/vi/$it/hqdefault.jpg"
                },
                type = video.type.ifBlank { null },
            )
        },
        cast = metadata.credits.cast.sortedBy { it.order }.map { member ->
            MediaCastMember(
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

fun displayImageUrl(path: String?, size: String): String? {
    val value = path?.takeIf { it.isNotBlank() } ?: return null
    if (value.startsWith("http://") || value.startsWith("https://")) return value
    return "https://image.tmdb.org/t/p/$size$value"
}

fun tmdbImageSize(url: String?, size: String): String? = url
    ?.replace(Regex("/t/p/[^/]+/"), "/t/p/$size/")
    ?.replace(Regex("/api/img/[^/]+/"), "/api/img/$size/")

private fun com.coveninja.cove.shared.model.MediaImage.displayUrl(size: String): String? =
    url.takeIf { it.isNotBlank() } ?: displayImageUrl(filePath, size)

private fun uiId(id: Int, type: MediaType?): String =
    "${type?.name ?: "Media"}:$id"
