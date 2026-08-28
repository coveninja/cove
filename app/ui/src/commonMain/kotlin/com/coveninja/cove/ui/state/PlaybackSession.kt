package com.coveninja.cove.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.coveninja.cove.shared.data.AppGraph
import com.coveninja.cove.shared.data.LibraryState
import com.coveninja.cove.shared.data.PlaybackRepository
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.data.TrackMemory
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.MediaTimestamps
import com.coveninja.cove.shared.model.StreamSource
import com.coveninja.cove.shared.data.PluginPlaybackActivity
import com.coveninja.cove.shared.data.PluginTransportCommand
import com.coveninja.cove.shared.network.resolveTmdbImageUrl
import com.coveninja.cove.shared.network.WatchProgressRequest
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.displayImageUrl
import com.coveninja.cove.ui.model.MediaEpisode
import com.coveninja.cove.ui.model.MediaType
import com.coveninja.cove.ui.model.MediaVideo
import com.coveninja.cove.ui.model.toDomainMedia
import com.coveninja.cove.ui.model.toDomainType
import com.coveninja.cove.ui.model.toUiEpisode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    /** The title on its own, which is what leads everywhere the episode is shown separately. */
    val heading: String get() = media.title ?: media.name ?: "Untitled"

    /** "S2E4 · Episode name", or the extra's own title. Null for a film, which has neither. */
    val episodeSubtitle: String?
        get() {
            // Extras use the media title as the heading and the video title as context.
            extra?.let { return it.title }
            val season = season ?: return null
            val number = episode ?: return null
            return listOfNotNull(
                "S${season}E$number",
                episodeTitle?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
        }

    /** The two above on one line, for the places that have room for only one string. */
    val label: String
        get() = listOfNotNull(heading, episodeSubtitle).joinToString(" · ")

    /** What a lock screen, a notification or a window title should say. */
    fun nowPlaying(): NowPlaying = NowPlaying(
        title = heading,
        subtitle = episodeSubtitle,
        // Resolved rather than passed through: a stored poster may still be a bare TMDB path
        // or a loopback proxy URL from the retired app, neither of which any notification can
        // fetch. w342 is a lock-screen thumbnail, not a page of artwork.
        artworkUrl = displayImageUrl(media.posterUrl, "w342"),
    )
}

/**
 * A subtitle file the viewer supplied for what is playing now.
 *
 * Kept by the session rather than left to the player because mpv loses its external
 * tracks on every `loadfile`: a reconnect after a dead stream, or a step to another
 * source of the same episode, would otherwise silently take the viewer's file away at
 * the moment they are least inclined to go looking for it again.
 */
data class UserSubtitle(val path: String, val title: String, val language: String)

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
    init {
        // Subtitle appearance is judged by eye, so a change to it has to reach the picture
        // that is already on screen rather than waiting for the next file. Only the style —
        // re-sending the whole preference set would put `sid` and `aid` back to what the
        // settings say and throw away whatever the viewer picked in the player's own menus.
        //
        // distinctUntilChanged is load-bearing rather than tidy: this flow republishes on
        // every settings write, and remembering the volume writes one every time playback
        // stops. Without it each of those would resend fourteen mpv properties.
        scope.launch {
            graph.settings.settings
                .map { (it as? SettingsState.Ready)?.settings?.subtitleStyle() }
                .distinctUntilChanged()
                .collect { style -> style?.let { host?.applySubtitleStyle(it) } }
        }
    }

    private var currentRequest by mutableStateOf<PlaybackRequest?>(null)

    /**
     * What the session is playing, or null for none.
     *
     * Written through a setter rather than a plain state field so the host is told on every
     * path — the six places this is assigned include the one that only upgrades the episode
     * title once it resolves, and the close that clears it. A host that has to be told
     * separately is a host that will eventually be told on five paths out of six.
     */
    var request: PlaybackRequest?
        get() = currentRequest
        private set(value) {
            currentRequest = value
            host?.setNowPlaying(value?.nowPlaying())
        }

    var phase by mutableStateOf<PlaybackPhase?>(null)
        private set

    /** True only while an automatic same-source reconnect is opening. */
    var reconnecting by mutableStateOf(false)
        private set

    /** The automatic reconnect, or a later manual retry, also failed. */
    var recoveryFailed by mutableStateOf(false)
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
    private var playbackMonitorJob: Job? = null
    private var reloadJob: Job? = null
    private var automaticRetriesUsed = 0
    private var interruptionPositionSeconds = 0.0

    /** Where the last automatic reconnect resumed from; the yardstick for [automaticRetryAllowed]. */
    private var lastRecoveryPositionSeconds = 0.0

    // Kept so a source that dies can be stepped over without resolving again.
    // Named apart from the lambda parameter it would otherwise shadow.
    private var resolvedCandidates: List<StreamChoice> = emptyList()
    private var failedSources = mutableSetOf<String>()

    /** Files the viewer supplied for the current request, in the order they arrived. */
    private var userSubtitles: List<UserSubtitle> = emptyList()

    /**
     * Set by `open(fromStart = true)`, and spent by the first position actually recorded.
     *
     * Not spent by the load, and not left standing for the session either — both are wrong at
     * one end. Every later load goes through the same resume block, so a flag that survived
     * would send a viewer back to zero on a source switch half an hour in; but a flag spent on
     * the attempt would lose the request entirely when the first source fails to open, and the
     * failover that follows would resume the very position the viewer asked to leave. What
     * settles it is whether anything played: once [saveProgress] has written a real position,
     * the resume point *is* this playthrough and every later load should honour it.
     */
    private var startFromBeginning = false

    /**
     * @param forcePicker show the source list even when the settings would have
     *   picked one, and even when only one came back. This is the "choose a
     *   source" entry point from the details overlay, not the Watch button.
     * @param fromStart ignore the resume point for this one load. "Play from
     *   beginning" is a different request from Watch, not a different setting.
     */
    fun open(
        media: Media,
        season: Int? = null,
        episode: Int? = null,
        episodeTitle: String? = null,
        forcePicker: Boolean = false,
        fromStart: Boolean = false,
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
        stopPlaybackMonitor()
        resetPlaybackRecovery()
        // Silences whatever is playing before the next thing is resolved. The
        // player's handle outlives the surface it was drawn on, so nothing else
        // does: without this the outgoing episode — or a trailer started from the
        // sheet — keeps talking over the "Finding sources" panel for as long as
        // the addons take.
        host.stop()
        resolvedCandidates = emptyList()
        failedSources = mutableSetOf()
        // A file chosen for one episode is the wrong file for the next, and a wrong
        // subtitle is worse than none.
        userSubtitles = emptyList()
        resumedFrom = null
        timestamps = MediaTimestamps.None
        browsingSeason = null
        browsingEpisodes = emptyList()
        request = PlaybackRequest(media, season, episode, episodeTitle)
        startFromBeginning = fromStart
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

                val ranked = rankSources(
                    sources = playable,
                    // The most-wanted language only. Ranking is a hint drawn from a release
                    // name, and a name that mentions the third choice says nothing useful
                    // about whether the first is in there.
                    preferredAudioLanguage = settings?.orderedAudioLanguages()?.firstOrNull(),
                    originalLanguage = resolved.media.originalLanguage,
                    mode = StreamSelectionMode.from(settings?.streamSelectionMode),
                )

                // Probing costs a round trip but happens while the viewer is
                // already waiting, and it is far cheaper than picking a dead
                // link and finding out after the eight seconds mpv needs to open
                // one. Only direct URLs can be checked; torrents are left alone.
                //
                // After ranking rather than before, and subtracting rather than
                // intersecting. A probe covers only a handful of candidates, so
                // ranking first is what puts the one about to be auto-played
                // inside that handful — checking an arbitrary slice left the
                // chosen source unverified. And keeping only what the probe
                // reached treated every candidate it had no room for as dead,
                // which cut the list to the probe's budget and then, when that
                // left nothing, handed back the rejects along with the rest.
                val checked = if (settings?.probeStreams == true) {
                    val probed = ranked.mapNotNull { it.url?.takeIf(String::isNotBlank) }
                        .take(PlaybackRepository.MAX_PROBED_URLS)
                    val dead = probed.toSet() - graph.playback.aliveUrls(probed)
                    ranked.filter { it.url.isNullOrBlank() || it.url !in dead }
                        // Never probe every candidate out of existence: if nothing
                        // survived, the check is more likely wrong than the sources.
                        // With the subtraction above this is now the rare case it was
                        // meant to be, rather than the usual one.
                        .ifEmpty { ranked }
                } else {
                    ranked
                }
                if (token != generation) return@onSuccess

                // Keep the source-ranking order within each compatibility tier,
                // but never put a software-only or impossible stream ahead of a
                // source Android can hardware-decode (or whose codec is unknown).
                val choices = checked
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
        stopPlaybackMonitor()
        resetPlaybackRecovery()
        resolvedCandidates = emptyList()
        failedSources = mutableSetOf()
        // A file chosen for one episode is the wrong file for the next, and a wrong
        // subtitle is worse than none.
        userSubtitles = emptyList()
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

            // Always from the start: extras keep no resume point, by the same rule
            // that keeps them out of watch progress.
            loadCurrentSource(
                current = request ?: return@launch,
                player = player,
                url = url,
                startPositionSeconds = 0.0,
                token = token,
                settings = settings,
                showResumeNotice = false,
            )
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
        stopPlaybackMonitor()
        resetPlaybackRecovery()
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

    /**
     * Records a track or speed the viewer chose, so the next episode opens the same way.
     *
     * Only ever called from an explicit choice — never from the automatic selection that
     * happens on load, which would write the settings' own answer back as though it were the
     * viewer's and make the memory impossible to clear. Extras are excluded for the same
     * reason they are excluded on the way in.
     */
    fun rememberAudioLanguage(language: String?) {
        updateMemory { it.copy(audioLanguage = language?.trim().orEmpty()) }
    }

    fun rememberSubtitleChoice(language: String?, off: Boolean) {
        updateMemory {
            it.copy(
                subtitleLanguage = if (off) "" else language?.trim().orEmpty(),
                subtitlesOff = off,
            )
        }
    }

    fun rememberSpeed(speed: Double) {
        updateMemory { it.copy(speed = speed) }
    }

    private fun updateMemory(change: (TrackMemory) -> TrackMemory) {
        val current = request ?: return
        if (current.extra != null) return
        val tmdbId = current.media.tmdbId
        scope.launch {
            runCatching {
                val existing = graph.trackMemory.read(tmdbId)
                graph.trackMemory.write(tmdbId, change(existing))
            }
        }
    }

    fun close() {
        generation++
        stopProgressTicker()
        stopPlaybackMonitor()
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
        // Clamped at 1.0 deliberately, now that the player goes to MAX_VOLUME: a boost is
        // compensation for one quiet mix, not a level to open every later title at, and
        // AppSettings.defaultVolume is a 0..1 fraction its own slider is drawn against.
        val fraction = (player.status.value.volume / NORMAL_VOLUME).coerceIn(0.0, 1.0)
        if (fraction == settings.defaultVolume) return
        scope.launch {
            runCatching { graph.settings.update(settings.copy(defaultVolume = fraction)) }
        }
    }

    private fun startPlayback(source: StreamSource, token: Int) {
        val current = request ?: return
        val player = host ?: return

        stopPlaybackMonitor()
        resetPlaybackRecovery()

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
            val resumeFrom = if (!startFromBeginning && settings?.rememberPosition != false) {
                resumePosition(current)
            } else {
                0.0
            }
            if (token != generation) return@launch
            loadCurrentSource(
                current = current,
                player = player,
                url = url,
                startPositionSeconds = resumeFrom,
                token = token,
                settings = settings,
                showResumeNotice = true,
            )
        }
    }

    /**
     * Reloads the selected URL without resolving or ranking sources again.
     *
     * Preferences go on before loadfile because mpv chooses tracks while opening;
     * external subtitles go on afterwards because replacing the file removes them.
     */
    private suspend fun loadCurrentSource(
        current: PlaybackRequest,
        player: VideoPlayerHost,
        url: String,
        startPositionSeconds: Double,
        token: Int,
        settings: AppSettings?,
        showResumeNotice: Boolean,
    ) {
        // An extra is a trailer, not the title: remembering that someone watched one with
        // German subtitles is not a fact about the film, and applying it would be worse.
        val memory = if (current.extra != null) {
            TrackMemory.None
        } else {
            runCatching { graph.trackMemory.read(current.media.tmdbId) }
                .getOrDefault(TrackMemory.None)
        }
        if (token != generation) return

        settings?.let {
            // An extra is not worth a details round trip merely to decide which
            // dub of a trailer to prefer; ordinary playback retains the richer lookup.
            val originalLanguage = if (current.extra != null) {
                current.media.originalLanguage
            } else {
                originalLanguageFor(current, it)
            }
            player.applyPreferences(
                it.playbackPreferences(originalLanguage).withMemory(memory),
            )
        }
        if (token != generation) return

        // After the preferences, and only when it is not the default: setting a speed the
        // viewer never chose would make every title open at whatever the last one used.
        if (memory.speed != 1.0) player.setSpeed(memory.speed)

        // AppSettings.defaultVolume is a 0..1 fraction; mpv's volume property is
        // 0..100. Passing it through unscaled is near-silent audio.
        settings?.defaultVolume?.let {
            player.setVolume((it * NORMAL_VOLUME).coerceIn(0.0, NORMAL_VOLUME))
        }
        resumedFrom = startPositionSeconds.takeIf { showResumeNotice && it > 0.0 }
        player.load(url, startPositionSeconds.coerceAtLeast(0.0))
        startPlaybackMonitor(token)
        startProgressTicker(token)

        // Ahead of both early returns below. loadfile dropped whatever was loaded, and
        // a viewer who turned fetched subtitles off in settings still means it when they
        // hand the player a file themselves — which is also why these are added with
        // select even though applyPreferences may just have set sid=no.
        userSubtitles.forEach { subtitle ->
            player.addSubtitle(
                url = subtitle.path,
                title = subtitle.title,
                language = subtitle.language,
                select = true,
            )
        }

        if (current.extra != null || settings?.subtitlesEnabled == false) return
        val domainType = current.media.type.toDomainType() ?: return
        val external = runCatching {
            graph.playback.subtitles(
                tmdbId = current.media.tmdbId,
                type = domainType,
                season = current.season,
                episode = current.episode,
            )
        }.getOrDefault(emptyList())
        if (token != generation) return

        val unnamedByLanguage = mutableMapOf<String, Int>()
        external.take(MAX_EXTERNAL_SUBTITLES).forEach { subtitle ->
            val title = subtitle.displayName ?: run {
                val language = subtitle.lang
                    .trim()
                    .substringBefore('-')
                    .lowercase()
                val ordinal = unnamedByLanguage.getOrDefault(language, 0) + 1
                unnamedByLanguage[language] = ordinal
                "Subtitle $ordinal"
            }
            player.addSubtitle(
                url = subtitle.url,
                title = title,
                language = subtitle.lang,
            )
        }
    }

    /**
     * Loads a subtitle file the viewer supplied and switches to it.
     *
     * Returns false for anything that is not a subtitle file, so the caller can say so
     * rather than leaving a drop that quietly did nothing. Accepted before playback
     * starts too: the file is recorded either way, and [loadCurrentSource] applies it
     * when the stream opens.
     */
    fun addUserSubtitle(path: String): Boolean {
        if (!isSubtitleFile(path)) return false
        val player = host ?: return false
        val entry = UserSubtitle(
            path = path,
            title = subtitleFileName(path),
            language = subtitleFileLanguage(path),
        )
        // Dropped twice — which is the natural response to a drop that appeared to do
        // nothing — must not list the same file twice. mpv has it already; this only
        // has to switch back to it.
        val existing = player.status.value.subtitleTracks.firstOrNull { it.title == entry.title }
        if (userSubtitles.any { it.path == entry.path } && existing != null) {
            player.selectSubtitleTrack(existing.id)
            return true
        }
        userSubtitles = userSubtitles + entry
        player.addSubtitle(
            url = entry.path,
            title = entry.title,
            language = entry.language,
            select = true,
        )
        return true
    }

    /** User-requested retry of the current source; never spends another automatic retry. */
    fun retryCurrentSource() {
        val status = host?.status?.value ?: return
        val start = status.positionSeconds
            .takeIf { it.isFinite() && it >= 0.0 }
            ?: interruptionPositionSeconds
        reloadCurrentSource(start, generation)
    }

    private fun reloadCurrentSource(startPositionSeconds: Double, token: Int) {
        val current = request ?: return
        val playing = phase as? PlaybackPhase.Playing ?: return
        val player = host ?: return
        reconnecting = true
        recoveryFailed = false
        reloadJob?.cancel()
        reloadJob = scope.launch {
            val settings = (graph.settings.settings.value as? SettingsState.Ready)?.settings
            loadCurrentSource(
                current = current,
                player = player,
                url = playing.url,
                startPositionSeconds = startPositionSeconds.coerceAtLeast(0.0),
                token = token,
                settings = settings,
                showResumeNotice = false,
            )
        }
    }

    private fun startPlaybackMonitor(token: Int) {
        if (playbackMonitorJob != null) return
        val player = host ?: return
        playbackMonitorJob = scope.launch {
            player.status.collect { status ->
                if (token != generation) return@collect
                when {
                    status.interrupted -> {
                        interruptionPositionSeconds = status.positionSeconds
                            .takeIf { it.isFinite() && it >= 0.0 }
                            ?: interruptionPositionSeconds
                        // Capture the rolled-back position before load() clears the
                        // terminal status for the reconnect attempt.
                        saveProgress()
                        val allowed = automaticRetryAllowed(
                            retriesUsed = automaticRetriesUsed,
                            positionSeconds = interruptionPositionSeconds,
                            lastRecoveryPositionSeconds = lastRecoveryPositionSeconds,
                        )
                        if (allowed && phase is PlaybackPhase.Playing) {
                            automaticRetriesUsed++
                            lastRecoveryPositionSeconds = interruptionPositionSeconds
                            reloadCurrentSource(interruptionPositionSeconds, token)
                        } else {
                            reconnecting = false
                            recoveryFailed = true
                        }
                    }
                    reconnecting && status.error != null -> {
                        reconnecting = false
                        recoveryFailed = true
                    }
                    reconnecting && status.hasMedia -> {
                        reconnecting = false
                        recoveryFailed = false
                    }
                }
            }
        }
    }

    private fun stopPlaybackMonitor() {
        playbackMonitorJob?.cancel()
        playbackMonitorJob = null
        reloadJob?.cancel()
        reloadJob = null
        reconnecting = false
        recoveryFailed = false
    }

    private fun resetPlaybackRecovery() {
        automaticRetriesUsed = 0
        interruptionPositionSeconds = 0.0
        lastRecoveryPositionSeconds = 0.0
        reconnecting = false
        recoveryFailed = false
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
        // Anywhere in either order, not just at the head: Original two entries down still
        // has to resolve, or the fallback silently stops working past the first choice.
        val wantsOriginal = AUDIO_LANGUAGE_ORIGINAL in settings.orderedAudioLanguages() ||
            AUDIO_LANGUAGE_ORIGINAL in settings.orderedSubtitleLanguages()
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
        return resumablePositionSeconds(progress) ?: 0.0
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
            // EOF is ambiguous for remote input. The host rolls an interruption
            // back to the last credible position, and this explicit guard keeps a
            // late disconnect from completing the title even if it crossed 90%.
            completed = !status.interrupted && !reconnecting && !recoveryFailed &&
                position / duration >= COMPLETED_FRACTION,
        )
        // Something has genuinely played, so the resume point from here on is this
        // playthrough rather than the one "play from beginning" was asked to ignore.
        startFromBeginning = false

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

        val entry = libraryEntry(media)
        val lastSeason = entry?.lastWatchedSeason
        val lastEpisode = entry?.lastWatchedEpisode
        val completed = lastSeason != null && lastEpisode != null &&
            episodeCompleted(media, lastSeason, lastEpisode)
        val target = defaultSeriesEpisode(media, entry, completed)
        return PlaybackRequest(media, target.first, target.second)
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
    }
}

@Composable
fun rememberPlaybackSession(): PlaybackSession {
    val graph = LocalAppGraph.current
    val host = LocalVideoPlayerHost.current
    val scope = rememberCoroutineScope()
    return remember(graph, host) { PlaybackSession(graph, scope, host) }
}

/**
 * Publishes a playback-source-free view of the active session to approved desktop
 * plugins and applies only the narrow transport commands the plugin contract permits.
 * Artwork is limited to TMDB's public CDN; provider and playback URLs stay private.
 */
@Composable
fun PluginPlaybackEffect(session: PlaybackSession) {
    val graph = LocalAppGraph.current
    val host = LocalVideoPlayerHost.current
    val status = host?.status?.collectAsState()?.value
    val request = session.request
    val phase = session.phase
    // Five-second buckets keep observers useful without turning mpv's 200 ms
    // status mirror into an extension-event firehose. Seeks still publish at once
    // when they land in a different bucket; pause and phase changes are independent.
    val positionBucket = status?.positionSeconds
        ?.takeIf { it.isFinite() && it >= 0.0 }
        ?.let { (it / 5.0).toInt() * 5.0 }
        ?: 0.0
    val activity = PluginPlaybackActivity(
        active = request != null,
        tmdbId = request?.media?.tmdbId,
        mediaType = request?.media?.type?.name?.lowercase(),
        title = request?.media?.title ?: request?.media?.name.orEmpty(),
        artworkUrl = pluginArtworkUrl(request?.media?.posterUrl),
        season = request?.season,
        episode = request?.episode,
        episodeTitle = request?.episodeTitle,
        extraTitle = request?.extra?.title,
        phase = when (phase) {
            PlaybackPhase.Resolving -> "resolving"
            is PlaybackPhase.Choosing -> "choosing"
            is PlaybackPhase.Playing -> if (status?.hasMedia == true) "playing" else "opening"
            is PlaybackPhase.Failed -> "failed"
            null -> "idle"
        },
        paused = status?.paused ?: true,
        positionSeconds = positionBucket,
        durationSeconds = status?.durationSeconds?.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0,
        speed = status?.speed?.takeIf(Double::isFinite)?.coerceIn(0.25, 4.0) ?: 1.0,
        reconnecting = session.reconnecting,
    )
    LaunchedEffect(graph.plugins, activity) {
        graph.plugins.publishPlayback(activity)
    }
    LaunchedEffect(graph.plugins, host, session) {
        graph.plugins.transportCommands.collect { command ->
            val player = host ?: return@collect
            when (command) {
                is PluginTransportCommand.SetPaused -> player.setPaused(command.paused)
                is PluginTransportCommand.SeekAbsolute -> player.seek(command.seconds.coerceAtLeast(0.0))
                is PluginTransportCommand.SeekRelative -> player.seekRelative(command.seconds)
                PluginTransportCommand.Stop -> session.close()
            }
        }
    }
    DisposableEffect(graph.plugins) {
        onDispose { graph.plugins.publishPlayback(PluginPlaybackActivity()) }
    }
}

/**
 * Whether an interruption at [positionSeconds] earns another automatic reconnect.
 *
 * The budget used to be a single retry for the whole source, which was the right guard against
 * the wrong thing. What it prevents is a source that dies the moment it is opened being reopened
 * for ever, and that case is recognisable: playback never moves. It has nothing to say about a
 * film that plays for twenty minutes, stalls once and would come back on its own — and on a
 * phone, where a stall is an ordinary event, that was the common case. The second one ended the
 * session on a banner.
 *
 * So the renewal is evidence of playback rather than a clock: an interruption gets a retry if
 * the position has moved [RETRY_RENEWAL_SECONDS] past wherever the last reconnect resumed from.
 * A stream failing repeatedly at the same offset still stops after one attempt, because it never
 * clears that bar, and [MAX_AUTOMATIC_RETRIES] caps even a source that keeps limping forward so
 * a bad evening cannot become an unbounded reload loop.
 */
internal fun automaticRetryAllowed(
    retriesUsed: Int,
    positionSeconds: Double,
    lastRecoveryPositionSeconds: Double,
): Boolean {
    if (retriesUsed >= MAX_AUTOMATIC_RETRIES) return false
    if (retriesUsed == 0) return true
    if (!positionSeconds.isFinite() || !lastRecoveryPositionSeconds.isFinite()) return false
    return positionSeconds - lastRecoveryPositionSeconds >= RETRY_RENEWAL_SECONDS
}

/**
 * How far playback must advance past the last reconnect before another is earned. Long enough
 * that a source dying in its opening moments cannot keep qualifying, short enough that a stall
 * a few minutes into an episode still self-heals.
 */
internal const val RETRY_RENEWAL_SECONDS = 30.0

/** The ceiling whatever the progress: past here the viewer is told, and chooses. */
internal const val MAX_AUTOMATIC_RETRIES = 3

internal fun pluginArtworkUrl(value: String?): String? = resolveTmdbImageUrl(value, "w500")
    ?.takeIf { it.length <= 300 && it.startsWith("https://image.tmdb.org/t/p/") }

/** Identifies a candidate across retries; position in the list is not stable. */
internal fun StreamSource.identityKey(): String =
    url?.takeIf { it.isNotBlank() }
        ?: infoHash?.takeIf { it.isNotBlank() }
        ?: "${name.orEmpty()}|${title.orEmpty()}"
