package com.coveninja.cove.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.coveninja.cove.shared.data.AppGraph
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.network.WatchProgressRequest
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.MediaEpisode
import com.coveninja.cove.ui.model.MediaSeason
import com.coveninja.cove.ui.model.toDomainType
import com.coveninja.cove.ui.model.toUiEpisode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.coveninja.cove.shared.model.MediaType as DomainMediaType

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

    /**
     * Puts a removed entry back exactly as it was — this is what Undo calls.
     *
     * `add` re-creates the row with a default status, so the status and rating have to be
     * reapplied afterwards; skipping that would silently downgrade a Finished title to
     * Watch Later on undo.
     */
    fun restore(entry: LibraryEntry) {
        scope.launch {
            graph.library.add(
                tmdbId = entry.tmdbId,
                mediaType = entry.mediaType,
                title = entry.title,
                posterPath = entry.posterPath,
                voteAverage = entry.voteAverage,
            )
            graph.library.setStatus(entry.tmdbId, entry.mediaType, entry.status)
            entry.rating?.let { graph.library.setRating(entry.tmdbId, entry.mediaType, it) }
        }
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
    ) = setEpisodeWatched(media, season.number, episode.number, watched, episode.runtimeMinutes)

    /**
     * The same write for callers holding numbers rather than the episode itself.
     *
     * Home's wide cards know which episode they are offering from the resume point and
     * nothing else about it, and fetching the season to tick one episode off would cost a
     * request for something the store does not need.
     */
    fun setEpisodeWatched(
        media: Media,
        season: Int,
        episode: Int,
        watched: Boolean,
        runtimeMinutes: Int? = null,
    ) {
        scope.launch {
            graph.library.setEpisodeWatched(
                tmdbId = media.tmdbId,
                title = media.title ?: media.name ?: "Untitled",
                posterPath = media.posterUrl.orEmpty(),
                voteAverage = media.rating ?: 0.0,
                season = season,
                episode = episode,
                runtimeMinutes = runtimeMinutes,
                watched = watched,
            )
        }
    }

    /**
     * Marks exactly what a card is offering as finished: one episode, or a film.
     *
     * Deliberately a progress write rather than a library status. The carry-on rail is built
     * from resume points, so `Finished` on the title would leave the card sitting there
     * advertising an episode the viewer has just said they are done with — and on a series it
     * would claim the whole show rather than the episode in hand.
     */
    fun markWatched(media: Media, season: Int?, episode: Int?, durationSeconds: Double?) {
        val type = media.type.toDomainType() ?: return
        val duration = durationSeconds?.takeIf { it > 0.0 }

        if (type == DomainMediaType.Tv) {
            if (season == null || episode == null) return
            setEpisodeWatched(
                media = media,
                season = season,
                episode = episode,
                watched = true,
                // The resume point measures seconds; the episode write wants whole minutes.
                runtimeMinutes = duration?.let { (it / 60.0).roundToInt() }?.takeIf { it > 0 },
            )
            return
        }

        scope.launch {
            graph.library.recordProgress(
                progressRequest(
                    media = media,
                    type = type,
                    positionSeconds = duration ?: 0.0,
                    durationSeconds = duration ?: 0.0,
                    completed = true,
                ),
            )
        }
    }

    /**
     * Drops the resume point for one episode or film without claiming it was watched.
     *
     * Zeroed rather than deleted, because the library exposes no delete and zero already
     * means "nothing to resume" everywhere that reads it: `watchFraction` returns null, so
     * the card leaves the rail while the title's status, rating and place in My List stay
     * exactly as they were. The row surviving is the point — a *missing* row would read as
     * never played, which is a second reason to be on that rail.
     */
    fun clearProgress(media: Media, season: Int?, episode: Int?) {
        val type = media.type.toDomainType() ?: return
        scope.launch {
            graph.library.recordProgress(
                progressRequest(
                    media = media,
                    type = type,
                    season = season,
                    episode = episode,
                    positionSeconds = 0.0,
                    durationSeconds = 0.0,
                    completed = false,
                ),
            )
        }
    }

    private fun progressRequest(
        media: Media,
        type: DomainMediaType,
        season: Int? = null,
        episode: Int? = null,
        positionSeconds: Double,
        durationSeconds: Double,
        completed: Boolean,
    ) = WatchProgressRequest(
        tmdbId = media.tmdbId,
        mediaType = type,
        title = media.title ?: media.name ?: "Untitled",
        posterPath = media.posterUrl.orEmpty(),
        voteAverage = media.rating ?: 0.0,
        // Never sent for a film: the store keys a movie's row on the title alone, and a
        // season on the request would write a row nothing looks for.
        season = season.takeIf { type == DomainMediaType.Tv },
        episode = episode.takeIf { type == DomainMediaType.Tv },
        positionSeconds = positionSeconds,
        durationSeconds = durationSeconds,
        completed = completed,
    )

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
