package com.coveninja.cove.shared.network

import com.coveninja.cove.shared.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// DTOs for response shapes that don't map 1:1 to a domain model.
// Domain models (Media, LibraryEntry, AppSettings, etc.) are already @Serializable
// with the correct @SerialName annotations and are deserialized directly by CoveApi.

// /api/search/multi wraps movies and tv in separate arrays; the UI collapses them.
@Serializable
data class SearchResultsDto(
    val movies: List<Media> = emptyList(),
    val tv: List<Media> = emptyList(),
)

// /api/library/{id}/{type} bundles entry + all progress records + dismissed flag.
@Serializable
data class LibraryDetailDto(
    val entry: LibraryEntry? = null,
    val progress: List<WatchProgress> = emptyList(),
    val dismissed: Boolean = false,
)

// /api/profiles wraps the list with an active profile pointer.
@Serializable
data class ProfilesResponseDto(
    val profiles: List<Profile> = emptyList(),
    @SerialName("active_profile_id") val activeProfileId: String = "",
)

// /api/update/check
@Serializable
data class UpdateCheckDto(
    val available: Boolean = false,
    @SerialName("current_version") val currentVersion: String = "",
    @SerialName("latest_version") val latestVersion: String = "",
    @SerialName("release_name") val releaseName: String = "",
)

// Request bodies

@Serializable
data class AddLibraryRequest(
    @SerialName("tmdb_id")           val tmdbId: Int,
    @SerialName("media_type")        val mediaType: MediaType,
    val title: String,
    @SerialName("poster_path")       val posterPath: String = "",
    val status: LibraryStatus = LibraryStatus.WatchLater,
    @SerialName("vote_average")      val voteAverage: Double = 0.0,
    @SerialName("last_air_date")     val lastAirDate: String = "",
    @SerialName("last_aired_season") val lastAiredSeason: Int? = null,
    @SerialName("last_aired_episode")val lastAiredEpisode: Int? = null,
)

@Serializable
data class PatchStatusRequest(val status: String)

@Serializable
data class PatchRatingRequest(val rating: Double?)

@Serializable
data class DismissLibraryRequest(
    @SerialName("tmdb_id") val tmdbId: Int,
    @SerialName("media_type") val mediaType: MediaType,
)

@Serializable
data class WatchProgressRequest(
    @SerialName("tmdb_id")            val tmdbId: Int,
    @SerialName("media_type")         val mediaType: MediaType,
    val title: String = "",
    @SerialName("poster_path")        val posterPath: String = "",
    @SerialName("vote_average")       val voteAverage: Double = 0.0,
    @SerialName("last_air_date")      val lastAirDate: String = "",
    @SerialName("last_aired_season")  val lastAiredSeason: Int? = null,
    @SerialName("last_aired_episode") val lastAiredEpisode: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("position_seconds")   val positionSeconds: Double = 0.0,
    @SerialName("duration_seconds")   val durationSeconds: Double = 0.0,
    val completed: Boolean = false,
)

// Both the addon and Nuvio "add" routes take the same single-field body.
@Serializable
data class AddAddonRequest(val url: String)

@Serializable
data class ToggleEnabledRequest(val enabled: Boolean)
