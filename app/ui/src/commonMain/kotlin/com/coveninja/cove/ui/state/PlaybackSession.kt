package com.coveninja.cove.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.coveninja.cove.shared.data.AppGraph
import com.coveninja.cove.shared.data.LibraryState
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.StreamSource
import com.coveninja.cove.shared.network.WatchProgressRequest
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.MediaType
import com.coveninja.cove.ui.model.toDomainType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What is being played: a movie, or one specific episode of a series. */
data class PlaybackRequest(
    val media: Media,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
) {
    val label: String
        get() {
            val title = media.title ?: media.name ?: "Untitled"
            if (season == null || episode == null) return title
            val suffix = episodeTitle?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            return "$title · S${season}E$episode$suffix"
        }
}

sealed interface PlaybackPhase {
    /** Asking the backend which sources exist. Seconds, not milliseconds — addons are polled in fan-out. */
    data object Resolving : PlaybackPhase

    /** More than one candidate came back; the user picks. */
    data class Choosing(val sources: List<StreamSource>) : PlaybackPhase

    data class Playing(val source: StreamSource, val url: String) : PlaybackPhase

    data class Failed(val message: String) : PlaybackPhase
}

/**
 * Drives Watch → resolve sources → play, and keeps the resume point up to date.
 *
 * Everything is sequenced against a generation counter: opening a second title,
 * or closing the player, invalidates any in-flight resolve, so a slow addon
 * cannot revive a session the user already left.
 */
@Stable
class PlaybackSession(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val host: VideoPlayerHost?,
) {
    var request by mutableStateOf<PlaybackRequest?>(null)
        private set
    var phase by mutableStateOf<PlaybackPhase?>(null)
        private set

    /** Not named isActive: inside a coroutine that would collide with CoroutineScope.isActive. */
    val active: Boolean get() = request != null

    private var generation = 0
    private var progressJob: Job? = null

    /**
     * @param forcePicker show the source list even when the settings would have
     *   picked one, and even when only one came back. This is the "choose a
     *   source" entry point from the details overlay, not the Watch button.
     */
    fun open(
        media: Media,
        season: Int? = null,
        episode: Int? = null,
        episodeTitle: String? = null,
        forcePicker: Boolean = false,
    ) {
        val domainType = media.type.toDomainType()
        if (domainType == null) {
            request = PlaybackRequest(media)
            phase = PlaybackPhase.Failed("This title has no media type, so it cannot be played.")
            return
        }
        if (host == null) {
            request = PlaybackRequest(media, season, episode, episodeTitle)
            phase = PlaybackPhase.Failed("Playback is not available on this platform.")
            return
        }

        val token = ++generation
        stopProgressTicker()
        request = PlaybackRequest(media, season, episode, episodeTitle)
        phase = PlaybackPhase.Resolving

        scope.launch {
            // Episode resolution can hit the library, so it belongs here rather than
            // in the synchronous part above.
            val resolved = if (media.type == MediaType.Series) {
                resolveEpisode(media, season, episode, episodeTitle)
            } else {
                PlaybackRequest(media)
            }
            if (token != generation) return@launch
            request = resolved

            runCatching {
                graph.playback.streams(
                    tmdbId = resolved.media.tmdbId,
                    type = domainType,
                    season = resolved.season,
                    episode = resolved.episode,
                )
            }.onFailure { error ->
                if (token != generation) return@launch
                phase = PlaybackPhase.Failed(
                    error.message ?: "Could not reach the backend to look for sources.",
                )
            }.onSuccess { candidates ->
                if (token != generation) return@launch
                val playable = candidates.filter {
                    !it.url.isNullOrBlank() || !it.infoHash.isNullOrBlank()
                }
                val ranked = rankSources(playable)
                when {
                    playable.isEmpty() -> phase = PlaybackPhase.Failed(
                        "No sources found. A fresh profile has no provider addons — " +
                            "add one in Settings before playing anything.",
                    )
                    // An explicit "choose a source" always asks, even for one result:
                    // the point of that entry point is to see what is on offer.
                    forcePicker -> phase = PlaybackPhase.Choosing(ranked)
                    // One candidate is not a choice; skip the picker entirely.
                    playable.size == 1 -> startPlayback(ranked.single(), token)
                    autoSelectStream() -> startPlayback(ranked.first(), token)
                    else -> phase = PlaybackPhase.Choosing(ranked)
                }
            }
        }
    }

    /** Called from the source picker, and to switch source mid-session. */
    fun choose(source: StreamSource) = startPlayback(source, generation)

    fun retry() {
        val current = request ?: return
        open(current.media, current.season, current.episode, current.episodeTitle)
    }

    /** Back to the source list from an active or starting playback. */
    fun reopenSources() {
        val current = request ?: return
        if (phase is PlaybackPhase.Playing) {
            host?.setPaused(true)
            saveProgress()
        }
        open(
            media = current.media,
            season = current.season,
            episode = current.episode,
            episodeTitle = current.episodeTitle,
            forcePicker = true,
        )
    }

    // Settings may still be loading on a cold start; the default of "ask" is the
    // safer of the two, since it never silently plays the wrong thing.
    private fun autoSelectStream(): Boolean =
        (graph.settings.settings.value as? SettingsState.Ready)?.settings?.autoSelectStream == true

    fun close() {
        generation++
        stopProgressTicker()
        // Save before tearing down: the ticker's last write can be a full interval
        // stale, and the position at close is the one the viewer expects back.
        saveProgress()
        host?.stop()
        request = null
        phase = null
    }

    private fun startPlayback(source: StreamSource, token: Int) {
        val current = request ?: return
        val player = host ?: return

        val url = runCatching {
            graph.playback.playUrl(source, current.season, current.episode)
        }.getOrElse { error ->
            phase = PlaybackPhase.Failed(error.message ?: "This source cannot be played.")
            return
        }

        phase = PlaybackPhase.Playing(source, url)

        scope.launch {
            val settings = (graph.settings.settings.value as? SettingsState.Ready)?.settings
            val resumeFrom = if (settings?.rememberPosition != false) resumePosition(current) else 0.0
            if (token != generation) return@launch

            // AppSettings.defaultVolume is a 0..1 fraction; mpv's volume property is
            // 0..100. Passing it through unscaled is near-silent audio.
            settings?.defaultVolume?.let { player.setVolume((it * 100.0).coerceIn(0.0, 100.0)) }
            player.load(url, resumeFrom)
            startProgressTicker(token)
        }
    }

    private suspend fun resumePosition(current: PlaybackRequest): Double {
        val domainType = current.media.type.toDomainType() ?: return 0.0
        val progress = runCatching {
            graph.library.progress(
                tmdbId = current.media.tmdbId,
                mediaType = domainType,
                season = current.season,
                episode = current.episode,
            )
        }.getOrNull() ?: return 0.0

        // Replaying something already finished is the expected behaviour, and a
        // resume point in the last few seconds drops the viewer onto the credits.
        if (progress.completed) return 0.0
        val position = progress.positionSeconds
        val duration = progress.durationSeconds
        if (position <= 0.0) return 0.0
        if (duration > 0.0 && position >= duration - RESUME_TAIL_SECONDS) return 0.0
        return position
    }

    private fun startProgressTicker(token: Int) {
        stopProgressTicker()
        progressJob = scope.launch {
            while (token == generation) {
                delay(PROGRESS_SAVE_INTERVAL_MILLIS)
                if (token != generation) break
                saveProgress()
            }
        }
    }

    private fun stopProgressTicker() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun saveProgress() {
        val current = request ?: return
        val player = host ?: return
        val domainType = current.media.type.toDomainType() ?: return
        val status = player.status.value

        val position = status.positionSeconds
        val duration = status.durationSeconds
        // Nothing worth storing before mpv reports a duration, and a zero position
        // would overwrite a real resume point with the start of the file.
        if (duration <= 0.0 || position <= 0.0) return

        val media = current.media
        val payload = WatchProgressRequest(
            tmdbId = media.tmdbId,
            mediaType = domainType,
            title = media.title ?: media.name ?: "Untitled",
            posterPath = media.posterUrl.orEmpty(),
            voteAverage = media.rating ?: 0.0,
            season = current.season,
            episode = current.episode,
            positionSeconds = position,
            durationSeconds = duration,
            completed = position / duration >= COMPLETED_FRACTION,
        )
        // Fire-and-forget on the composition scope: a failed save must never
        // interrupt playback, and at close there is nothing left to await on.
        scope.launch { runCatching { graph.library.recordProgress(payload) } }
    }

    /**
     * Picks the season/episode Watch should play for a series.
     *
     * An explicit episode always wins. Otherwise pick up where the library left
     * off, stepping to the next episode when that one is already finished. The
     * step reads the season list from the details payload; with no list, or at the
     * end of the last season, it stays put rather than guessing at an episode that
     * may not exist.
     */
    private suspend fun resolveEpisode(
        media: Media,
        season: Int?,
        episode: Int?,
        episodeTitle: String?,
    ): PlaybackRequest {
        if (season != null && episode != null) {
            return PlaybackRequest(media, season, episode, episodeTitle)
        }

        val firstSeason = media.seasons.minOfOrNull { it.number } ?: 1
        val entry = libraryEntry(media)
        val lastSeason = entry?.lastWatchedSeason
        val lastEpisode = entry?.lastWatchedEpisode
        if (lastSeason == null || lastEpisode == null) {
            return PlaybackRequest(media, firstSeason, 1)
        }

        if (!episodeCompleted(media, lastSeason, lastEpisode)) {
            return PlaybackRequest(media, lastSeason, lastEpisode)
        }

        val episodesInSeason = media.seasons
            .firstOrNull { it.number == lastSeason }
            ?.episodeCount
            ?: 0
        val nextSeason = media.seasons
            .map { it.number }
            .filter { it > lastSeason }
            .minOrNull()

        return when {
            episodesInSeason > lastEpisode -> PlaybackRequest(media, lastSeason, lastEpisode + 1)
            nextSeason != null -> PlaybackRequest(media, nextSeason, 1)
            else -> PlaybackRequest(media, lastSeason, lastEpisode)
        }
    }

    // Same signal the episode browser ticks off.
    private suspend fun episodeCompleted(media: Media, season: Int, episode: Int): Boolean {
        val domainType = media.type.toDomainType() ?: return false
        return runCatching {
            graph.library.episodeWatchStates(media.tmdbId, domainType)
        }.getOrNull()?.get(season to episode) == true
    }

    private fun libraryEntry(media: Media): LibraryEntry? {
        val entries = (graph.library.entries.value as? LibraryState.Ready)?.entries.orEmpty()
        val domainType = media.type.toDomainType() ?: return null
        return entries.firstOrNull { it.tmdbId == media.tmdbId && it.mediaType == domainType }
    }

    private companion object {
        const val PROGRESS_SAVE_INTERVAL_MILLIS = 10_000L
        const val COMPLETED_FRACTION = 0.9
        const val RESUME_TAIL_SECONDS = 15.0
    }
}

/**
 * Ranks candidates so the picker's first row is the one most likely to just work:
 * already-cached debrid links first, then bigger files, which in practice track
 * higher bitrates.
 */
internal fun rankSources(sources: List<StreamSource>): List<StreamSource> =
    sources.sortedWith(
        compareByDescending<StreamSource> { it.cached }
            .thenByDescending { it.sizeBytes },
    )

@Composable
fun rememberPlaybackSession(): PlaybackSession {
    val graph = LocalAppGraph.current
    val host = LocalVideoPlayerHost.current
    val scope = rememberCoroutineScope()
    return remember(graph, host) { PlaybackSession(graph, scope, host) }
}
