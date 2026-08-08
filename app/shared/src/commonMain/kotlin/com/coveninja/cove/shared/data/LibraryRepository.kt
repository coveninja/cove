package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.MediaType
import kotlinx.coroutines.flow.StateFlow

sealed interface LibraryState {
    data object Loading : LibraryState
    data class Ready(val entries: List<LibraryEntry>) : LibraryState
    data class Failed(val message: String) : LibraryState
}

interface LibraryRepository {
    val entries: StateFlow<LibraryState>
    suspend fun add(
        tmdbId: Int,
        mediaType: MediaType,
        title: String,
        posterPath: String = "",
        voteAverage: Double = 0.0,
    )
    suspend fun remove(tmdbId: Int, mediaType: MediaType)
    suspend fun setStatus(tmdbId: Int, mediaType: MediaType, status: LibraryStatus)
    suspend fun setRating(tmdbId: Int, mediaType: MediaType, rating: Double?)
    suspend fun setDismissed(tmdbId: Int, mediaType: MediaType, dismissed: Boolean)
    suspend fun episodeWatchStates(
        tmdbId: Int,
        mediaType: MediaType,
    ): Map<Pair<Int, Int>, Boolean>
    suspend fun setEpisodeWatched(
        tmdbId: Int,
        title: String,
        posterPath: String,
        voteAverage: Double,
        season: Int,
        episode: Int,
        runtimeMinutes: Int?,
        watched: Boolean,
    )
}
