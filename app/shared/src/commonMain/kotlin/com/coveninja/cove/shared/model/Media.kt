package com.coveninja.cove.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Backend sends `title` for movies and `name` for TV shows — both are present
// in the JSON (the other is just empty/missing). `displayTitle` picks whichever
// is non-null so call sites don't need to branch on media_type.
@Serializable
data class Media(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("poster_path")    val posterPath: String? = null,
    @SerialName("backdrop_path")  val backdropPath: String? = null,
    @SerialName("release_date")   val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average")   val voteAverage: Double = 0.0,
    @SerialName("media_type")     val mediaType: MediaType? = null,
    @SerialName("trailer_url")    val trailerUrl: String = "",
    @SerialName("clip_urls")      val clipUrls: String = "",
    val images: List<String> = emptyList(),
    val popularity: Double = 0.0,
    @SerialName("genre_ids")      val genreIds: List<Int> = emptyList(),
    val adult: Boolean = false,
    @SerialName("original_language") val originalLanguage: String = "",
) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: name ?: "Unknown"
    val displayDate: String? get() = releaseDate?.takeIf { it.isNotBlank() } ?: firstAirDate
}
