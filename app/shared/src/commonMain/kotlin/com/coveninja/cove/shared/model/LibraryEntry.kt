package com.coveninja.cove.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LibraryEntry(
    val id: String,
    @SerialName("profile_id")   val profileId: String? = null,
    @SerialName("tmdb_id")      val tmdbId: Int,
    @SerialName("media_type")   val mediaType: MediaType,
    val title: String,
    @SerialName("poster_path")  val posterPath: String = "",
    val status: LibraryStatus,
    val rating: Double? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    // TV tracking — season/episode of the most recently watched episode
    @SerialName("last_air_date")        val lastAirDate: String = "",
    @SerialName("last_watched_at")      val lastWatchedAt: String? = null,
    @SerialName("last_watched_season")  val lastWatchedSeason: Int? = null,
    @SerialName("last_watched_episode") val lastWatchedEpisode: Int? = null,
    @SerialName("last_aired_season")    val lastAiredSeason: Int? = null,
    @SerialName("last_aired_episode")   val lastAiredEpisode: Int? = null,
    @SerialName("added_at")    val addedAt: String = "",
    @SerialName("updated_at")  val updatedAt: String = "",
)
