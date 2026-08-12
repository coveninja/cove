package com.coveninja.cove.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MediaDetails(
    val title: String = "",
    val name: String = "",
    @SerialName("poster_path") val posterPath: String = "",
    val overview: String = "",
    val genres: List<MediaGenre> = emptyList(),
    val runtime: Int = 0,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @SerialName("release_date") val releaseDate: String = "",
    val credits: MediaCredits = MediaCredits(),
    @SerialName("release_dates") val releaseDates: MediaReleaseDates = MediaReleaseDates(),
    @SerialName("content_ratings") val contentRatings: MediaContentRatings = MediaContentRatings(),
    val keywords: MediaKeywords = MediaKeywords(),
    @SerialName("origin_country") val originCountry: List<String> = emptyList(),
    @SerialName("production_companies") val productionCompanies: List<MediaCompany> = emptyList(),
    val networks: List<MediaCompany> = emptyList(),
    val status: String = "",
    @SerialName("number_of_seasons") val numberOfSeasons: Int = 0,
    @SerialName("number_of_episodes") val numberOfEpisodes: Int = 0,
    val seasons: List<TvSeason> = emptyList(),
    @SerialName("last_episode_to_air") val lastEpisodeToAir: AiredEpisode? = null,
    @SerialName("next_episode_to_air") val nextEpisodeToAir: UpcomingEpisode? = null,
) {
    val displayTitle: String
        get() = title.takeIf { it.isNotBlank() } ?: name

    val certification: String
        get() {
            releaseDates.results
                .firstOrNull { it.countryCode == "US" }
                ?.releaseDates
                ?.firstOrNull { it.certification.isNotBlank() }
                ?.certification
                ?.let { return it }

            return contentRatings.results
                .firstOrNull { it.countryCode == "US" && it.rating.isNotBlank() }
                ?.rating
                .orEmpty()
        }
}

@Serializable
data class MediaGenre(
    val id: Int = 0,
    val name: String = "",
)

@Serializable
data class MediaCredits(
    val cast: List<MediaCastMember> = emptyList(),
    val crew: List<MediaCrewMember> = emptyList(),
)

@Serializable
data class MediaCastMember(
    val id: Int = 0,
    val name: String = "",
    val character: String = "",
    @SerialName("profile_path") val profilePath: String = "",
    val order: Int = 0,
)

@Serializable
data class MediaCrewMember(
    val id: Int = 0,
    val name: String = "",
    val job: String = "",
)

@Serializable
data class MediaReleaseDates(
    val results: List<CountryReleaseDates> = emptyList(),
)

@Serializable
data class CountryReleaseDates(
    @SerialName("iso_3166_1") val countryCode: String = "",
    @SerialName("release_dates") val releaseDates: List<ReleaseCertification> = emptyList(),
)

@Serializable
data class ReleaseCertification(
    val certification: String = "",
)

@Serializable
data class MediaContentRatings(
    val results: List<ContentRating> = emptyList(),
)

@Serializable
data class ContentRating(
    @SerialName("iso_3166_1") val countryCode: String = "",
    val rating: String = "",
)

@Serializable
data class MediaKeywords(
    val keywords: List<MediaKeyword> = emptyList(),
    val results: List<MediaKeyword> = emptyList(),
)

@Serializable
data class MediaKeyword(
    val id: Int = 0,
    val name: String = "",
)

@Serializable
data class MediaCompany(
    val id: Int = 0,
    val name: String = "",
)

@Serializable
data class AiredEpisode(
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("episode_number") val episodeNumber: Int = 0,
    @SerialName("air_date") val airDate: String = "",
)

@Serializable
data class UpcomingEpisode(
    val name: String = "",
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("episode_number") val episodeNumber: Int = 0,
    @SerialName("air_date") val airDate: String = "",
    @SerialName("still_path") val stillPath: String = "",
)

@Serializable
data class MediaImage(
    @SerialName("file_path") val filePath: String = "",
    val url: String = "",
    @SerialName("iso_639_1") val language: String = "",
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
)

@Serializable
data class MediaImages(
    val backdrops: List<MediaImage> = emptyList(),
    val logos: List<MediaImage> = emptyList(),
    val posters: List<MediaImage> = emptyList(),
)

@Serializable
data class MediaVideo(
    val name: String = "",
    val key: String = "",
    val site: String = "",
    val size: Int = 0,
    val type: String = "",
    val official: Boolean = false,
    @SerialName("published_at") val publishedAt: String = "",
    @SerialName("embed_url") val embedUrl: String = "",
)

@Serializable
data class MediaVideos(
    val results: List<MediaVideo> = emptyList(),
)
