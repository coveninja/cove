package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.WatchProgress
import com.coveninja.cove.shared.network.CoveApi
import com.coveninja.cove.shared.network.WatchProgressRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LiveLibraryRepository(
    private val api: CoveApi,
    private val scope: CoroutineScope,
) : LibraryRepository {

    private val _entries = MutableStateFlow<LibraryState>(LibraryState.Loading)
    override val entries: StateFlow<LibraryState> = _entries.asStateFlow()

    init {
        scope.launch { reload() }
    }

    private suspend fun reload() {
        try {
            _entries.value = LibraryState.Ready(api.library())
        } catch (e: Exception) {
            _entries.value = LibraryState.Failed(e.message ?: "Unknown error loading library")
        }
    }

    override suspend fun add(
        tmdbId: Int,
        mediaType: MediaType,
        title: String,
        posterPath: String,
        voteAverage: Double,
    ) {
        try {
            api.addToLibrary(tmdbId, mediaType, title, posterPath, voteAverage)
            reload()
        } catch (e: Exception) {
            _entries.value = LibraryState.Failed(e.message ?: "Unknown error adding to library")
        }
    }

    override suspend fun remove(tmdbId: Int, mediaType: MediaType) {
        try {
            api.deleteLibraryEntry(tmdbId, mediaType)
            reload()
        } catch (e: Exception) {
            _entries.value = LibraryState.Failed(e.message ?: "Unknown error removing from library")
        }
    }

    override suspend fun setStatus(tmdbId: Int, mediaType: MediaType, status: LibraryStatus) {
        try {
            api.patchLibraryStatus(tmdbId, mediaType, status)
            reload()
        } catch (e: Exception) {
            _entries.value = LibraryState.Failed(e.message ?: "Unknown error updating status")
        }
    }

    override suspend fun setRating(tmdbId: Int, mediaType: MediaType, rating: Double?) {
        try {
            api.patchLibraryRating(tmdbId, mediaType, rating)
            reload()
        } catch (e: Exception) {
            _entries.value = LibraryState.Failed(e.message ?: "Unknown error updating rating")
        }
    }

    override suspend fun setDismissed(tmdbId: Int, mediaType: MediaType, dismissed: Boolean) {
        try {
            api.setLibraryDismissed(tmdbId, mediaType, dismissed)
            reload()
        } catch (e: Exception) {
            _entries.value = LibraryState.Failed(e.message ?: "Unknown error updating recommendation")
        }
    }

    override suspend fun episodeWatchStates(
        tmdbId: Int,
        mediaType: MediaType,
    ): Map<Pair<Int, Int>, Boolean> = try {
        api.libraryDetail(tmdbId, mediaType).progress.mapNotNull { progress ->
            val season = progress.season ?: return@mapNotNull null
            val episode = progress.episode ?: return@mapNotNull null
            (season to episode) to progress.completed
        }.toMap()
    } catch (_: Exception) {
        // Episode metadata is still useful if watch history is temporarily
        // unavailable; render the season without watched badges.
        emptyMap()
    }

    override suspend fun setEpisodeWatched(
        tmdbId: Int,
        title: String,
        posterPath: String,
        voteAverage: Double,
        season: Int,
        episode: Int,
        runtimeMinutes: Int?,
        watched: Boolean,
    ) {
        try {
            val durationSeconds = runtimeMinutes
                ?.takeIf { it > 0 }
                ?.times(60)
                ?.toDouble()
                ?: 1.0
            api.postLibraryProgress(
                WatchProgressRequest(
                    tmdbId = tmdbId,
                    mediaType = MediaType.Tv,
                    title = title,
                    posterPath = posterPath,
                    voteAverage = voteAverage,
                    season = season,
                    episode = episode,
                    positionSeconds = if (watched) durationSeconds else 0.0,
                    durationSeconds = if (watched) durationSeconds else 0.0,
                    completed = watched,
                ),
            )
            reload()
        } catch (e: Exception) {
            _entries.value = LibraryState.Failed(
                e.message ?: "Unknown error updating episode progress",
            )
        }
    }

    override suspend fun progress(
        tmdbId: Int,
        mediaType: MediaType,
        season: Int?,
        episode: Int?,
    ): WatchProgress? = api.libraryProgress(tmdbId, mediaType, season, episode)

    // The HTTP API answers for one title at a time (/api/library/progress takes a
    // tmdb_id) and has no bulk route. Fanning out one request per saved title to
    // decorate a grid would cost more than the decoration is worth, so this path
    // reports nothing and callers draw no resume state.
    override suspend fun progressSnapshot(): List<WatchProgress> = emptyList()

    // Deliberately no reload(): this runs on a timer for the whole length of a
    // playback session, and refetching the entire library every few seconds to
    // refresh a resume point nobody is looking at yet is pure waste. The library
    // reloads when playback ends and on the next page load. Errors propagate —
    // the caller decides whether a failed save is worth interrupting playback for
    // (it is not).
    override suspend fun recordProgress(request: WatchProgressRequest): WatchProgress =
        api.postLibraryProgress(request)
}
