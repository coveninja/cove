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
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.MediaTimestamps
import com.coveninja.cove.shared.model.StreamSource
import com.coveninja.cove.shared.network.WatchProgressRequest
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.MediaEpisode
import com.coveninja.cove.ui.model.MediaType
import com.coveninja.cove.ui.model.MediaVideo
import com.coveninja.cove.ui.model.toDomainMedia
import com.coveninja.cove.ui.model.toDomainType
import com.coveninja.cove.ui.model.toUiEpisode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What is being played: a movie, one episode of a series, or an extra. */
data class PlaybackRequest(
    val media: Media,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    /**
     * Set when what is playing is a trailer or featurette rather than the title
     * itself. Everything that treats playback as watching — the resume point, the
     * source list, the next episode — is off while this is non-null.
     */
    val extra: MediaVideo? = null,
) {
    val label: String
        get() {
            val title = media.title ?: media.name ?: "Untitled"
            extra?.let { return "$title · ${it.title}" }
            if (season == null || episode == null) return title
            val suffix = episodeTitle?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            return "$title · S${season}E$episode$suffix"
        }
}

/**
 * How much of the window the video occupies.
 *
 * [Inline] is a real playback session drawn into a slot on the page it was started
 * from — the details sheet stays open around it. Only possible because the desktop
 * player reads frames back into Compose rather than embedding a native surface,
 * so the picture is an image the layout can size like any other.
 */
enum class PlaybackPresentation { Inline, Fullscreen }

sealed interface PlaybackPhase {
    /** Asking the backend which sources exist. Seconds, not milliseconds — addons are polled in fan-out. */
    data object Resolving : PlaybackPhase

    /** More than one candidate came back; the user picks. */
    data class Choosing(val sources: List<StreamChoice>) : PlaybackPhase

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

    /**
     * Extras start embedded in the sheet; the film itself always takes the window.
     * Reset by every open, so a session never inherits the last one's framing.
     */
    var presentation by mutableStateOf(PlaybackPresentation.Fullscreen)
        private set

    /** Sends an embedded video to the whole window. The page stays open behind it. */
    fun expandToFullscreen() {
        presentation = PlaybackPresentation.Fullscreen
    }

    /** Puts it back in the slot on the page it was started from. */
    fun collapseToInline() {
        presentation = PlaybackPresentation.Inline
    }

    /** Intro/recap/credits ranges for the seek bar; empty until they arrive. */
    var timestamps by mutableStateOf(MediaTimestamps.None)
        private set

    /**
     * Where playback was resumed from, if it was. Cleared once acknowledged so
     * the notice does not reappear on every recomposition.
     */
    var resumedFrom by mutableStateOf<Double?>(null)
        private set

    fun acknowledgeResume() {
        resumedFrom = null
    }

    /** Season the episode picker is showing, which need not be the one playing. */
    var browsingSeason by mutableStateOf<Int?>(null)
        private set

    /** Episodes of [browsingSeason]; empty while they load or for a film. */
    var browsingEpisodes by mutableStateOf<List<MediaEpisode>>(emptyList())
        private set

    /** Not named isActive: inside a coroutine that would collide with CoroutineScope.isActive. */
    val active: Boolean get() = request != null

    private var generation = 0
    private var progressJob: Job? = null

    // Kept so a source that dies can be stepped over without resolving again.
    // Named apart from the lambda parameter it would otherwise shadow.
    private var resolvedCandidates: List<StreamChoice> = emptyList()
    private var failedSources = mutableSetOf<String>()

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
        // Silences whatever is playing before the next thing is resolved. The
        // player's handle outlives the surface it was drawn on, so nothing else
        // does: without this the outgoing episode — or a trailer started from the
        // sheet — keeps talking over the "Finding sources" panel for as long as
        // the addons take.
        host.stop()
        resolvedCandidates = emptyList()
        failedSources = mutableSetOf()
        resumedFrom = null
        timestamps = MediaTimestamps.None
        browsingSeason = null
        browsingEpisodes = emptyList()
        request = PlaybackRequest(media, season, episode, episodeTitle)
        presentation = PlaybackPresentation.Fullscreen
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
                // Audio preference decides ranking before size does: a viewer
                // who asked for original audio should not be handed a dub just
                // because the dub is a bigger file.
                val settings = (graph.settings.settings.value as? SettingsState.Ready)?.settings

                // Probing costs a round trip but happens while the viewer is
                // already waiting, and it is far cheaper than picking a dead
                // link and finding out after the eight seconds mpv needs to open
                // one. Only direct URLs can be checked; torrents are left alone.
                val checked = if (settings?.probeStreams == true) {
                    val urls = playable.mapNotNull { it.url?.takeIf(String::isNotBlank) }
                    val alive = graph.playback.aliveUrls(urls)
                    playable.filter { it.url.isNullOrBlank() || it.url in alive }
                        // Never probe every candidate out of existence: if nothing
                        // survived, the check is more likely wrong than the sources.
                        .ifEmpty { playable }
                } else {
                    playable
                }
                if (token != generation) return@onSuccess

                val ranked = rankSources(
                    sources = checked,
                    preferredAudioLanguage = settings?.defaultAudioLang,
                    originalLanguage = resolved.media.originalLanguage,
                )
                // Keep the source-ranking order within each compatibility tier,
                // but never put a software-only or impossible stream ahead of a
                // source Android can hardware-decode (or whose codec is unknown).
                val choices = ranked
                    .map { source ->
                        StreamChoice(
                            source = source,
                            compatibility = source.compatibilityWith(playerCodecCapabilities()),
                        )
                    }
                    .sortedBy { choice -> choice.compatibility.selectionPriority() }
                resolvedCandidates = choices
                val automaticCandidates = choices.filter { it.compatibility.automaticallyEligible }
                when {
                    choices.isEmpty() -> phase = PlaybackPhase.Failed(
                        "No sources found. A fresh profile has no provider addons — " +
                            "add one in Settings before playing anything.",
                    )
                    // An explicit "choose a source" always asks, even for one result:
                    // the point of that entry point is to see what is on offer.
                    forcePicker -> phase = PlaybackPhase.Choosing(choices)
                    // A lone compatible candidate is not a choice. A lone
                    // software-only or unsupported one must still be explained
                    // in the picker rather than silently started.
                    automaticCandidates.size == 1 && choices.size == 1 ->
                        startPlayback(automaticCandidates.single().source, token)
                    autoSelectStream() && automaticCandidates.isNotEmpty() ->
                        startPlayback(automaticCandidates.first().source, token)
                    else -> phase = PlaybackPhase.Choosing(choices)
                }
            }
        }
    }

    /**
     * Plays a trailer or featurette from the details sheet.
     *
     * Nothing is resolved: an extra already carries the only URL it has, so this
     * goes straight to Playing rather than through the addon fan-out. The URL is a
     * web page rather than a media file, which is why the caller checks
     * [VideoPlayerHost.playsWebVideos] first and sends it to the browser otherwise.
     */
    fun openExtra(media: Media, video: MediaVideo) {
        val url = video.url
        val player = host
        if (url == null || player == null) {
            failExtra(
                media = media,
                video = video,
                message = if (url == null) {
                    "This video has no address that can be opened."
                } else {
                    "Playback is not available on this platform."
                },
            )
            return
        }

        val token = ++generation
        stopProgressTicker()
        resolvedCandidates = emptyList()
        failedSources = mutableSetOf()
        resumedFrom = null
        timestamps = MediaTimestamps.None
        browsingSeason = null
        browsingEpisodes = emptyList()
        request = PlaybackRequest(media, extra = video)
        // Embedded in the sheet the viewer started it from. Anyone who wants the
        // whole window can say so; opening there would have taken the title they
        // were reading off the screen without being asked.
        presentation = PlaybackPresentation.Inline
        // Synthetic, so the starting stage has something to name while the page is
        // being resolved into a stream. It is never offered as a choice.
        phase = PlaybackPhase.Playing(
            source = StreamSource(name = video.type ?: "Extra", title = video.title, url = url),
            url = url,
        )

        scope.launch {
            val settings = (graph.settings.settings.value as? SettingsState.Ready)?.settings
            if (token != generation) return@launch

            // A page URL is not a media file: the player has to have its extractor
            // in hand before the load, and fetching one is slow enough to report.
            val problem = player.prepareWebVideo(
                mayInstallHelper = settings?.manageYtDlp != false,
            )
            if (token != generation) return@launch
            if (problem != null) {
                failExtra(media, video, problem)
                return@launch
            }

            // The title's own language, not a fetched one: an extra is not worth a
            // details round trip to decide which dub of a trailer to prefer.
            settings?.let {
                player.applyPreferences(it.playbackPreferences(media.originalLanguage))
            }
            settings?.defaultVolume?.let { player.setVolume((it * 100.0).coerceIn(0.0, 100.0)) }
            // Always from the start: extras keep no resume point, by the same rule
            // that keeps them out of watch progress.
            player.load(url, 0.0)
        }
    }

    /**
     * Reports an extra that nothing could open, in the player's own failure panel.
     *
     * That panel is the one surface the app has for "this did not play", and the
     * caller's alternative — handing a browser a link it cannot take and letting
     * the exception out of a click handler — takes the window down with it.
     */
    fun failExtra(media: Media, video: MediaVideo, message: String) {
        generation++
        stopProgressTicker()
        request = PlaybackRequest(media, extra = video)
        // Reported in the slot on the page, not by blacking out the window: the
        // failure belongs next to the video that would not open.
        presentation = PlaybackPresentation.Inline
        phase = PlaybackPhase.Failed(message)
    }

    /**
     * Points the episode picker at a season. Independent of what is playing, so
     * looking ahead at the next season does not interrupt the current episode.
     */
    fun browseSeason(season: Int) {
        val current = request ?: return
        val token = generation
        browsingSeason = season
        browsingEpisodes = emptyList()
        scope.launch {
            val domainType = current.media.type.toDomainType()
            // Watch state lives in the library, not with the episode metadata, so
            // the picker has to merge the two to know what has been seen.
            val watched = if (domainType == null) {
                emptyMap()
            } else {
                runCatching {
                    graph.library.episodeWatchStates(current.media.tmdbId, domainType)
                }.getOrDefault(emptyMap())
            }
            val loaded = runCatching {
                graph.content.episodes(current.media.tmdbId, season)
                    .map { episode ->
                        episode.toUiEpisode(current.media.id, season).copy(
                            watched = watched[season to episode.episodeNumber] == true,
                        )
                    }
            }.getOrDefault(emptyList())
            // Guarded like every other async result here: a slow season fetch
            // must not repopulate the picker for a title that has been closed.
            if (token == generation && browsingSeason == season) browsingEpisodes = loaded
        }
    }

    /** Called from the source picker, and to switch source mid-session. */
    fun choose(choice: StreamChoice) {
        if (!choice.compatibility.selectable) return
        startPlayback(choice.source, generation)
    }

    /**
     * Steps to the next candidate after the current one failed.
     *
     * Failed sources are remembered so a list is walked rather than cycled, and
     * the walk stops rather than looping: if everything has been tried, saying so
     * is more use than starting again at the top.
     */
    fun failoverToNextSource(): Boolean {
        val playing = phase as? PlaybackPhase.Playing ?: return false
        failedSources += playing.source.identityKey()
        val next = resolvedCandidates.firstOrNull {
            it.compatibility.automaticallyEligible && it.source.identityKey() !in failedSources
        } ?: return false
        startPlayback(next.source, generation)
        return true
    }

    fun retry() {
        val current = request ?: return
        current.extra?.let { return openExtra(current.media, it) }
        open(current.media, current.season, current.episode, current.episodeTitle)
    }

    /** Back to the source list from an active or starting playback. */
    fun reopenSources() {
        val current = request ?: return
        // An extra has no source list behind it, and resolving one would replace
        // the trailer with the film itself.
        if (current.extra != null) return
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

    private fun playerCodecCapabilities(): VideoCodecCapabilities =
        host?.videoCodecCapabilities ?: VideoCodecCapabilities()

    fun close() {
        generation++
        stopProgressTicker()
        // Save before tearing down: the ticker's last write can be a full interval
        // stale, and the position at close is the one the viewer expects back.
        saveProgress()
        rememberVolume()
        host?.stop()
        request = null
        phase = null
    }

    /**
     * Carries the volume the viewer settled on into the next thing they play.
     *
     * Written back over defaultVolume, which is the field that seeds every session:
     * a second "last volume" alongside it would have to be kept in step with it, and
     * with the setting on there is no meaningful difference between the two.
     *
     * Read before write, per the whole-object replace rule — AppSettings has no
     * partial update, so anything not copied forward is silently reset.
     */
    private fun rememberVolume() {
        val settings = (graph.settings.settings.value as? SettingsState.Ready)?.settings ?: return
        if (!settings.rememberVolume) return
        val player = host ?: return
        val fraction = (player.status.value.volume / 100.0).coerceIn(0.0, 1.0)
        if (fraction == settings.defaultVolume) return
        scope.launch {
            runCatching { graph.settings.update(settings.copy(defaultVolume = fraction)) }
        }
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

        // The picker opens on whatever is playing, and is free to be pointed
        // elsewhere afterwards without disturbing playback.
        current.season?.let(::browseSeason)

        // Decoration, so it is fetched alongside playback rather than gating it.
        scope.launch {
            val fetched = graph.playback.timestamps(
                tmdbId = current.media.tmdbId,
                season = current.season,
                episode = current.episode,
            )
            if (token == generation) timestamps = fetched
        }

        scope.launch {
            val settings = (graph.settings.settings.value as? SettingsState.Ready)?.settings
            val resumeFrom = if (settings?.rememberPosition != false) resumePosition(current) else 0.0
            if (token != generation) return@launch

            // Applied before load: mpv chooses tracks while opening the file, so
            // a preference set afterwards would only take effect next time.
            settings?.let {
                player.applyPreferences(
                    it.playbackPreferences(originalLanguageFor(current, it)),
                )
            }
            // AppSettings.defaultVolume is a 0..1 fraction; mpv's volume property is
            // 0..100. Passing it through unscaled is near-silent audio.
            settings?.defaultVolume?.let { player.setVolume((it * 100.0).coerceIn(0.0, 100.0)) }
            resumedFrom = resumeFrom.takeIf { it > 0.0 }
            player.load(url, resumeFrom)
            startProgressTicker(token)

            // After load, so mpv attaches them to the file that is open. They then
            // appear alongside the embedded tracks in the subtitle menu.
            if (settings?.subtitlesEnabled != false) {
                val domainType = current.media.type.toDomainType()
                if (domainType != null) {
                    val external = graph.playback.subtitles(
                        tmdbId = current.media.tmdbId,
                        type = domainType,
                        season = current.season,
                        episode = current.episode,
                    )
                    if (token == generation) {
                        external.take(MAX_EXTERNAL_SUBTITLES).forEach { subtitle ->
                            player.addSubtitle(
                                url = subtitle.url,
                                title = subtitle.displayName,
                                language = subtitle.lang,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * The title's own language, fetching it if the copy in hand does not carry one.
     *
     * Several routes into playback hand over a thin Media — the library grid and the
     * continue-watching row build one from stored fields, and `originalLanguage` is
     * not among them. "Original" audio then resolves to nothing at all, so the
     * preference silently does not apply, and it does so precisely for the titles
     * you watch most: the ones already in your library.
     *
     * Only fetched when something actually asks for Original, and awaited here
     * because track selection happens while mpv opens the file — a language arriving
     * after that would apply to the next episode instead of this one. A failure
     * leaves it unknown, which is where this started.
     */
    private suspend fun originalLanguageFor(
        current: PlaybackRequest,
        settings: AppSettings,
    ): String? {
        val known = current.media.originalLanguage?.takeIf { it.isNotBlank() }
        if (known != null) return known
        val wantsOriginal = settings.defaultAudioLang == AUDIO_LANGUAGE_ORIGINAL ||
            settings.defaultSubtitleLang == AUDIO_LANGUAGE_ORIGINAL
        if (!wantsOriginal) return null

        return runCatching { graph.content.details(current.media.toDomainMedia()) }
            .getOrNull()
            ?.media
            ?.originalLanguage
            ?.takeIf { it.isNotBlank() }
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
        // Two minutes into a trailer is not two minutes into the film, and writing
        // it would drop a resume point onto a title nobody has started.
        if (current.extra != null) return
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

        // Nothing after it means the finished episode is the best available
        // answer; replaying beats refusing to play anything.
        val next = nextEpisodeAfter(media.seasons, lastSeason, lastEpisode)
            ?: return PlaybackRequest(media, lastSeason, lastEpisode)
        return PlaybackRequest(media, next.first, next.second)
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
        // Addons can return dozens; each is a fetch and a track in the menu, and
        // nobody scrolls past the first handful of a language.
        const val MAX_EXTERNAL_SUBTITLES = 12
        const val PROGRESS_SAVE_INTERVAL_MILLIS = 10_000L
        const val COMPLETED_FRACTION = 0.9
        const val RESUME_TAIL_SECONDS = 15.0
    }
}

@Composable
fun rememberPlaybackSession(): PlaybackSession {
    val graph = LocalAppGraph.current
    val host = LocalVideoPlayerHost.current
    val scope = rememberCoroutineScope()
    return remember(graph, host) { PlaybackSession(graph, scope, host) }
}

/** Identifies a candidate across retries; position in the list is not stable. */
internal fun StreamSource.identityKey(): String =
    url?.takeIf { it.isNotBlank() }
        ?: infoHash?.takeIf { it.isNotBlank() }
        ?: "${name.orEmpty()}|${title.orEmpty()}"
