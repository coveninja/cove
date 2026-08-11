package com.coveninja.cove.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WatchProgress(
    val id: String,
    @SerialName("profile_id")        val profileId: String? = null,
    // Nullable because it genuinely is, remotely: progress rows written before
    // this column existed — and rows whose library entry was later deleted —
    // carry null. Decoding those into a non-null String aborted the whole sync.
    //
    // Null rather than "": with explicitNulls = false a null is left out of the
    // wire payload entirely, so a push cannot write an empty string into a column
    // that may be typed uuid, nor clobber a value another device knows.
    @SerialName("library_entry_id")  val libraryEntryId: String? = null,
    @SerialName("tmdb_id")           val tmdbId: Int,
    @SerialName("media_type")        val mediaType: MediaType,
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("position_seconds")  val positionSeconds: Double = 0.0,
    @SerialName("duration_seconds")  val durationSeconds: Double = 0.0,
    val completed: Boolean = false,
    @SerialName("watched_at")        val watchedAt: String = "",
)
