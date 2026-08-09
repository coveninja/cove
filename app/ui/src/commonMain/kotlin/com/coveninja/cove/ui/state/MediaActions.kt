package com.coveninja.cove.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.coveninja.cove.shared.data.AppGraph
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.MediaEpisode
import com.coveninja.cove.ui.model.MediaSeason
import com.coveninja.cove.ui.model.toDomainType
import com.coveninja.cove.ui.model.toUiEpisode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Stable
class MediaActions(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val index: LibraryIndex,
) {
    fun setListCategory(media: Media, category: MyListCategory) {
        val type = media.type.toDomainType() ?: return
        scope.launch {
            val existing = index.entryOf(media.id)
            if (category == MyListCategory.NotInterested) {
                // NotInterested removes from the list and marks dismissed; no status row needed.
                if (existing != null) graph.library.remove(media.tmdbId, type)
                graph.library.setDismissed(media.tmdbId, type, true)
                return@launch
            }

            if (existing == null) {
                graph.library.add(
                    tmdbId = media.tmdbId,
                    mediaType = type,
                    title = media.title ?: media.name ?: "Untitled",
                    posterPath = media.posterUrl.orEmpty(),
                    voteAverage = media.rating ?: 0.0,
                )
            }
            category.toLibraryStatus()?.let { status ->
                graph.library.setStatus(media.tmdbId, type, status)
            }
        }
    }

    fun removeFromList(media: Media) {
        val type = media.type.toDomainType() ?: return
        scope.launch { graph.library.remove(media.tmdbId, type) }
    }

    fun setRating(media: Media, rating: Int) {
        val type = media.type.toDomainType() ?: return
        scope.launch {
            if (index.entryOf(media.id) == null) {
                graph.library.add(
                    tmdbId = media.tmdbId,
                    mediaType = type,
                    title = media.title ?: media.name ?: "Untitled",
                    posterPath = media.posterUrl.orEmpty(),
                    voteAverage = media.rating ?: 0.0,
                )
            }
            graph.library.setRating(media.tmdbId, type, rating.toDouble())
        }
    }

    fun toggleWatched(media: Media) {
        val next = if (index.categoryOf(media.id) == MyListCategory.Finished) {
            MyListCategory.Watching
        } else {
            MyListCategory.Finished
        }
        setListCategory(media, next)
    }

    fun setEpisodeWatched(
        media: Media,
        season: MediaSeason,
        episode: MediaEpisode,
        watched: Boolean,
    ) {
        scope.launch {
            graph.library.setEpisodeWatched(
                tmdbId = media.tmdbId,
                title = media.title ?: media.name ?: "Untitled",
                posterPath = media.posterUrl.orEmpty(),
                voteAverage = media.rating ?: 0.0,
                season = season.number,
                episode = episode.number,
                runtimeMinutes = episode.runtimeMinutes,
                watched = watched,
            )
        }
    }

    suspend fun episodesFor(media: Media, season: MediaSeason): List<MediaEpisode> {
        val type = media.type.toDomainType()
        val watchStates = if (type == null) {
            emptyMap()
        } else {
            graph.library.episodeWatchStates(media.tmdbId, type)
        }
        return graph.content.episodes(media.tmdbId, season.number).map { episode ->
            episode.toUiEpisode(media.id, season.number).copy(
                watched = watchStates[season.number to episode.episodeNumber] == true,
            )
        }
    }
}

// remember(index) so the captured index is never stale after a library update.
@Composable
fun rememberMediaActions(index: LibraryIndex): MediaActions {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    return remember(index) { MediaActions(graph, scope, index) }
}
