package com.coveninja.cove.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WatchProgress(
    val id: String,
    @SerialName("profile_id")        val profileId: String? = null,
    @SerialName("library_entry_id")  val libraryEntryId: String,
    @SerialName("tmdb_id")           val tmdbId: Int,
    @SerialName("media_type")        val mediaType: MediaType,
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("position_seconds")  val positionSeconds: Double = 0.0,
    @SerialName("duration_seconds")  val durationSeconds: Double = 0.0,
    val completed: Boolean = false,
    @SerialName("watched_at")        val watchedAt: String = "",
)
