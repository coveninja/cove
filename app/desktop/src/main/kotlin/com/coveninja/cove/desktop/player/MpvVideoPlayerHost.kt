package com.coveninja.cove.desktop.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.coveninja.cove.shared.network.CoveJson
import com.coveninja.cove.ui.state.MediaChapter
import com.coveninja.cove.ui.state.MediaTrack
import com.coveninja.cove.ui.state.PlaybackPreferences
import com.coveninja.cove.ui.state.PlaybackStatus
import com.coveninja.cove.ui.state.TrackKind
import com.coveninja.cove.ui.state.VideoScaling
import com.coveninja.cove.ui.state.VideoPlayerHost
import com.coveninja.cove.ui.state.classifyPlaybackTermination
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bridges libmpv to the shared [VideoPlayerHost] contract that :ui codes against.
 *
 * The mpv handle is owned by the session rather than by a composition: the first
 * [Surface] to mount creates it and [stop] destroys it, so an idle app holds no
 * decoder threads while a video can still move between mount points — inline in
 * the details sheet and fullscreen — without being torn down and reloaded on the
 * way. Commands that arrive before any surface exists — [load] in particular,
 * which races the recomposition that mounts one — are queued and replayed on attach.
 */
class MpvVideoPlayerHost(
    // The in-app renderer is always software (see Surface), so --software-renderer
    // only has the decoder left to turn off. Kept as an escape hatch for drivers
    // where copy-back hardware decoding misbehaves.
    private val softwareDecoding: Boolean = false,
    /**
     * Keeps a yt-dlp available for page URLs. Null leaves mpv searching PATH and
     * nothing else, which is what the tests want and all a headless run needs.
     */
    private val ytDlp: YtDlpProvisioner? = null,
) : VideoPlayerHost {

    private val _status = MutableStateFlow(PlaybackStatus())
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    /**
     * True when a page URL can be opened at all — either yt-dlp is already here or
     * one can be fetched. Whether it actually is ready is [prepareWebVideo]'s
     * answer, because that is the step that can fail and has something to say.
     */
    override val playsWebVideos: Boolean
        get() = ytDlp != null || streamExtractorInstalled()

    /**
     * A line of commentary while yt-dlp is being fetched, overlaid on whatever the
     * player is reporting. Held apart from [_status] because the status mirror
     * republishes the player's snapshot every 200 ms and would wipe it.
     */
    @Volatile
    private var provisioningMessage: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var player: DesktopPlayer? = null

    /**
     * The handle this host owns, as opposed to the one currently attached. Typed
     * concretely because the surface sizes it, and the render size is a property
     * of the software renderer rather than of playback.
     */
    private var owned: MpvSoftwarePlayer? = null
    private val surfaceSequence = AtomicInteger(0)
    private val activeSurface = AtomicInteger(0)
    private var mirrorJob: Job? = null
    /** Latched for the current load so a pre-open failure stays a startup error. */
    private var currentMediaOpened = false

    private var pendingLoad: PendingLoad? = null
    private var pendingVolume: Double? = null
    private var pendingScaling: VideoScaling? = null
    private var pendingPreferences: PlaybackPreferences? = null

    /**
     * Where the viewer has asked to be, until mpv reports having got there. Read by
     * [seekRelative] so repeated jumps stack instead of all starting from the same
     * 200 ms-old position, and by the status mirror so the bar tracks the input.
     */
    @Volatile
    private var pendingSeekSeconds: Double? = null

    /**
     * Conflated on purpose: a burst of targets collapses to its newest member, so a
     * dragged scrubber or a held arrow key costs mpv one exact seek per window rather
     * than one per event. See SEEK_COMMAND_INTERVAL_MILLIS.
     */
    private val seekRequests = Channel<Double>(Channel.CONFLATED)

    init {
        scope.launch {
            for (target in seekRequests) {
                player?.seek(target)
                delay(SEEK_COMMAND_INTERVAL_MILLIS)
            }
        }
    }

    // Produced on mpv's render thread and consumed by the composition; a flow is
    // the defined handoff, where a raw snapshot write from a foreign thread would
    // rely on apply-notification timing.
    private val frames = MutableStateFlow<SoftwareVideoFrame?>(null)

    override fun load(url: String, startPositionSeconds: Double) {
        // The new file starts wherever it starts; a target aimed at the old one would
        // otherwise survive and drag the playhead there.
        pendingSeekSeconds = null
        currentMediaOpened = false
        _status.value = _status.value.copy(
            hasMedia = false,
            fileLoaded = false,
            positionSeconds = startPositionSeconds.coerceAtLeast(0.0),
            endReached = false,
            interrupted = false,
            error = null,
            statusMessage = "Opening stream…",
        )
        val active = player
        if (active == null) {
            pendingLoad = PendingLoad(url, startPositionSeconds)
        } else {
            active.load(url, startPositionSeconds)
        }
    }

    override fun setPaused(paused: Boolean) {
        val active = player
        if (!paused && _status.value.endReached) {
            // mpv parked at the end ignores pause=no — it re-detects the end and pauses
            // again — so pressing play there does nothing at all until something seeks
            // away first. Issued straight at the player rather than through the
            // conflating channel: that route costs a coroutine hop, and the unpause
            // below would overtake the seek and be swallowed by the end all over again.
            val target = clampSeekTarget(0.0, _status.value.durationSeconds)
            pendingSeekSeconds = target
            _status.value = _status.value.copy(positionSeconds = target, endReached = false)
            active?.seek(target)
        }
        active?.setPaused(paused)
    }

    // Routed through setPaused rather than the player's own toggle so that resuming
    // from the end of a file gets the seek it needs.
    override fun togglePause() {
        setPaused(!_status.value.paused)
    }

    override fun seek(seconds: Double) {
        val target = clampSeekTarget(seconds, _status.value.durationSeconds)
        pendingSeekSeconds = target
        // Echoed immediately, exactly as setVolume does below: the poll is 200 ms
        // away, and a bar that ignores the input for that long reads as a dropped one.
        _status.value = _status.value.copy(positionSeconds = target, endReached = false)
        seekRequests.trySend(target)
    }

    override fun seekRelative(deltaSeconds: Double) {
        // Consecutive presses add up because seek publishes its target immediately;
        // this reads that back rather than the position mpv last reported, which is
        // up to a poll interval behind and is what made five taps of the key produce
        // a single step.
        //
        // The pending field is preferred over the published position, though the two
        // are equal whenever a seek is outstanding: it is written first, so it is the
        // one that survives the mirror coroutine publishing a status it computed
        // before this call.
        val base = pendingSeekSeconds ?: _status.value.positionSeconds
        seek(base + deltaSeconds)
    }

    override fun setVolume(volume: Double) {
        val clamped = volume.coerceIn(0.0, 100.0)
        val active = player
        if (active == null) {
            pendingVolume = clamped
        } else {
            active.setVolume(clamped)
            // Reaching for the volume means "I want to hear this", so a deliberate
            // raise clears the mute too. Without this, turning the slider up on a
            // player muted at load does nothing audible and offers no clue why.
            if (clamped > 0.0 && _status.value.muted) {
                active.setMuted(false)
            }
            // mpv's property poll runs on a 200 ms timer; reflecting the change now
            // keeps the volume slider from snapping back under the pointer.
            _status.value = _status.value.copy(
                volume = clamped,
                muted = if (clamped > 0.0) false else _status.value.muted,
            )
        }
    }

    override fun setMuted(muted: Boolean) {
        val active = player ?: return
        active.setMuted(muted)
        _status.value = _status.value.copy(muted = muted)
    }

    override fun setScaling(scaling: VideoScaling) {
        pendingScaling = scaling
        player?.applyScaling(scaling)
    }

    override fun setSpeed(speed: Double) {
        player?.setOption("speed", speed.coerceIn(0.25, 4.0).toString())
    }

    override fun applyPreferences(preferences: PlaybackPreferences) {
        pendingPreferences = preferences
        player?.applyPreferences(preferences)
    }

    override fun addSubtitle(url: String, title: String, language: String) {
        player?.addSubtitle(url, title, language)
    }

    override fun selectAudioTrack(id: Int) {
        player?.selectAudioTrack(id)
    }

    override fun selectSubtitleTrack(id: Int?) {
        player?.selectSubtitleTrack(id)
    }

    override fun stepChapter(delta: Int) {
        // mpv clamps this itself at either end of the list, and does nothing at all
        // in a file with no chapters, which is the behaviour the UI wants anyway.
        player?.command("add", "chapter", delta.toString())
    }

    override fun stepFrame(delta: Int) {
        player?.command(if (delta < 0) "frame-back-step" else "frame-step")
    }

    override fun setSubtitleDelay(seconds: Double) {
        val clamped = clampTrackDelay(seconds)
        player?.setOption("sub-delay", clamped.toString())
        // Echoed like the volume: the stepper would otherwise sit unchanged for a
        // fifth of a second per press, which reads as a control that missed the click.
        _status.value = _status.value.copy(subtitleDelaySeconds = clamped)
    }

    override fun setAudioDelay(seconds: Double) {
        val clamped = clampTrackDelay(seconds)
        player?.setOption("audio-delay", clamped.toString())
        _status.value = _status.value.copy(audioDelaySeconds = clamped)
    }

    override fun takeScreenshot() {
        // "subtitles" is the whole point of taking one from a player rather than
        // from the window manager.
        player?.command("screenshot", "subtitles")
    }

    /**
     * Makes sure mpv has a yt-dlp to hand before a page URL is loaded.
     *
     * A yt-dlp the viewer installed themselves is used as-is and nothing is
     * downloaded. Otherwise the managed copy is fetched once and refreshed in the
     * background when it goes stale — YouTube breaks extraction every few weeks,
     * and a stale copy fails in ways that look like a broken player.
     */
    override suspend fun prepareWebVideo(mayInstallHelper: Boolean): String? {
        val provisioner = ytDlp
        if (streamExtractorInstalled() || provisioner?.isInstalled() == true) {
            // Not awaited: what is already here plays now, and next time it is newer.
            if (mayInstallHelper && provisioner?.isStale() == true) {
                scope.launch { withContext(Dispatchers.IO) { provisioner.install() } }
            }
            return null
        }
        if (provisioner == null) {
            return "This build has no way to fetch yt-dlp, which YouTube videos need."
        }
        if (!mayInstallHelper) {
            return "yt-dlp is not installed, and Cove is set not to fetch it. " +
                "Install yt-dlp, or turn that setting back on."
        }

        return try {
            publishProvisioning("Getting yt-dlp, the helper YouTube videos need…")
            withContext(Dispatchers.IO) {
                provisioner.install { fraction ->
                    publishProvisioning(
                        fraction
                            ?.let { "Getting yt-dlp… ${(it * 100).toInt()}%" }
                            ?: "Getting yt-dlp…",
                    )
                }
            }.fold(
                onSuccess = { null },
                onFailure = { it.message ?: "yt-dlp could not be downloaded." },
            )
        } finally {
            publishProvisioning(null)
        }
    }

    private fun publishProvisioning(message: String?) {
        provisioningMessage = message
        _status.value = _status.value.copy(statusMessage = message.orEmpty())
    }

    override fun stop() {
        pendingLoad = null
        pendingSeekSeconds = null
        // Takes the handle with it, which is what the surface's disposal used to
        // do. The session is over here, so nothing is going to want it back.
        releasePlayer()
        _status.value = PlaybackStatus()
    }

    /**
     * Frames are drawn by Compose itself rather than embedded as an AWT panel.
     *
     * The OpenGL path renders into a Swing GLJPanel, and Compose can only
     * composite over an interop component where interop blending is supported —
     * which, per WindowSkiaLayerComponent.interopBlendingSupported, is Direct3D
     * and Metal only. On an OpenGL render API (every Linux desktop) the panel
     * paints over the whole scene, so the controls, the buffering spinner and any
     * error would all sit behind an opaque black rectangle. The software render
     * target keeps one compositor in charge of the entire window. mpv now writes
     * directly into one persistent Skia bitmap, avoiding the former AWT and
     * per-pixel Compose conversions; decoding stays on the GPU via hwdec=auto-copy.
     *
     * The OpenGL path is still the right choice for --play, which is a bare
     * window with nothing drawn on top; see StandalonePlayerWindow.
     */
    @Composable
    override fun Surface(modifier: Modifier) {
        // The handle belongs to the session, not to this composition. A video
        // started inline in the details sheet and then sent fullscreen is the same
        // playback moving between two mount points, and a handle owned here would
        // be destroyed and rebuilt on the way — which for a YouTube extra means
        // resolving the page through yt-dlp all over again. Closed by [stop], so
        // an idle app still holds no decoder threads.
        val player = remember { obtainPlayer() }
        // Both surfaces exist for the frame in which one replaces the other, and
        // they want different render sizes. The newer one wins; the older one's
        // last layout pass must not resize mpv on its way out.
        val surfaceId = remember { surfaceSequence.incrementAndGet() }
        DisposableEffect(surfaceId) {
            activeSurface.set(surfaceId)
            onDispose { }
        }

        val frame by frames.collectAsState()
        Canvas(
            // mpv renders at exactly the size it is told and letterboxes inside
            // it, so the surface size is the render size.
            modifier = modifier
                .fillMaxSize()
                .onSizeChanged {
                    if (activeSurface.get() == surfaceId) player.resize(it.width, it.height)
                },
        ) {
            frame?.draw(
                scope = this,
                destination = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            )
        }
    }

    /**
     * The session's mpv handle, created on the first surface that asks for one.
     *
     * Synchronized because a surface swap composes the new mount before disposing
     * the old one, and both arrive here.
     */
    @Synchronized
    private fun obtainPlayer(): MpvSoftwarePlayer = owned ?: MpvSoftwarePlayer(
        hardwareDecoding = !softwareDecoding,
        ytdlSearchPath = ytDlp?.let { ytdlSearchPath(it.managedPath, System.getProperty("os.name").orEmpty()) },
        ytdlFormat = ytDlp?.let { YTDL_FORMAT },
        ytdlRawOptions = ytDlp?.let { ytdlRawOptions(firstOnPath(JS_RUNTIMES)) },
        frameConsumer = { frame -> frames.value = frame },
    ).also {
        it.start()
        owned = it
        attach(it)
    }

    /** Ends the session's playback and the handle with it. */
    @Synchronized
    private fun releasePlayer() {
        val active = owned ?: return
        owned = null
        detach()
        frames.value = null
        active.close()
    }

    private fun attach(active: DesktopPlayer) {
        player = active
        mirrorJob?.cancel()
        mirrorJob = scope.launch {
            active.snapshot.collect { snapshot ->
                val previous = _status.value
                val raw = snapshot.toPlaybackStatus()
                if (raw.hasMedia) currentMediaOpened = true
                val terminalAware = when {
                    raw.endReached && currentMediaOpened -> {
                        val termination = classifyPlaybackTermination(
                            positionSeconds = raw.positionSeconds,
                            previousPositionSeconds = previous.positionSeconds,
                            durationSeconds = raw.durationSeconds,
                        )
                        raw.copy(
                            positionSeconds = termination.positionSeconds,
                            endReached = termination.ended,
                            interrupted = termination.interrupted,
                            statusMessage = if (termination.interrupted) {
                                "The stream stopped before the end."
                            } else {
                                raw.statusMessage
                            },
                        )
                    }
                    raw.endReached -> raw.copy(endReached = false, interrupted = false)
                    else -> raw.copy(interrupted = false)
                }
                val resolved = applyPendingSeek(terminalAware, pendingSeekSeconds)
                pendingSeekSeconds = resolved.pendingSeconds
                _status.value = provisioningMessage
                    ?.let { resolved.status.copy(statusMessage = it) }
                    ?: resolved.status
            }
        }
        pendingVolume?.let { active.setVolume(it) }
        pendingVolume = null
        // Re-applied on attach: mpv resets these with the handle, so a mode
        // chosen before the surface existed would otherwise be forgotten.
        pendingScaling?.let(active::applyScaling)
        // Track and subtitle options must be in place before loadfile, or mpv
        // picks its own defaults and the preference only takes effect next time.
        pendingPreferences?.let(active::applyPreferences)
        pendingLoad?.let { active.load(it.url, it.startPositionSeconds) }
        pendingLoad = null
    }

    private fun detach() {
        mirrorJob?.cancel()
        mirrorJob = null
        player = null
        pendingSeekSeconds = null
        currentMediaOpened = false
        _status.value = PlaybackStatus()
    }

    /** Releases the handle, if a session still holds one, and the mirroring scope. */
    fun dispose() {
        releasePlayer()
        scope.cancel()
    }

    private data class PendingLoad(val url: String, val startPositionSeconds: Double)
}

/**
 * mpv publishes its tracks as a JSON string on the track-list property, so this
 * is where that string becomes typed data. Parsed defensively: an unreadable
 * track list must cost the track menus, not playback.
 */
private fun parseTracks(json: String): List<MediaTrack> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        CoveJson.parseToJsonElement(json).jsonArray.mapNotNull { element ->
            val track = element.jsonObject
            val kind = when (track["type"]?.jsonPrimitive?.contentOrNull) {
                "audio" -> TrackKind.Audio
                "sub" -> TrackKind.Subtitle
                else -> return@mapNotNull null
            }
            val id = track["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            MediaTrack(
                id = id,
                kind = kind,
                title = track["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                language = track["lang"]?.jsonPrimitive?.contentOrNull,
                selected = track["selected"]?.jsonPrimitive?.booleanOrNull == true,
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * The one place preferences become mpv options.
 *
 * alang/slang take an ordered list and mpv falls back to its own choice when
 * nothing matches, which is what an empty preference should do. sid=no is the
 * only way to say "no subtitles" — an empty slang would just mean "no preference".
 */
private fun DesktopPlayer.applyPreferences(preferences: PlaybackPreferences) {
    if (preferences.audioLanguages.isNotEmpty()) {
        setOption("alang", preferences.audioLanguages.joinToString(","))
    }
    if (preferences.subtitleLanguages.isNotEmpty()) {
        setOption("slang", preferences.subtitleLanguages.joinToString(","))
    }
    setOption("sid", if (preferences.subtitlesEnabled) "auto" else "no")
    setOption("mute", if (preferences.startMuted) "yes" else "no")
    setOption("sub-scale", preferences.subtitleScale.toString())
    setOption("sub-pos", preferences.subtitlePosition.toString())
    // opaque-box draws the shaded panel behind the text; the default outline
    // style has no background at all.
    setOption(
        "sub-border-style",
        if (preferences.subtitleBackground) "opaque-box" else "outline-and-shadow",
    )
    // auto-copy, not auto: this path reads finished frames back into system memory
    // for Compose to draw, and a decoder that keeps its output on the GPU has nothing
    // to hand over. mpv falls back to software on its own where copy-back is missing.
    setOption("hwdec", if (preferences.hardwareDecoding) "auto-copy" else "no")
}

/** The one place the display modes become mpv's three knobs. */
private fun DesktopPlayer.applyScaling(scaling: VideoScaling) = when (scaling) {
    VideoScaling.Fit -> setScaling(keepAspect = true, panscan = 0.0, zoom = 0.0)
    VideoScaling.Fill -> setScaling(keepAspect = true, panscan = 1.0, zoom = 0.0)
    VideoScaling.Zoom -> setScaling(keepAspect = true, panscan = 0.0, zoom = 0.2)
    VideoScaling.Stretch -> setScaling(keepAspect = false, panscan = 0.0, zoom = 0.0)
}

/**
 * mpv publishes chapters as a JSON array of `{ title, time }`, in file order.
 *
 * Parsed as defensively as the track list: an unreadable chapter list must cost the
 * ticks on the seek bar, not playback. A chapter with no timestamp is dropped rather
 * than defaulted to zero, which would put a tick at the start of the bar for no reason.
 */
private fun parseChapters(json: String): List<MediaChapter> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        CoveJson.parseToJsonElement(json).jsonArray.mapIndexedNotNull { index, element ->
            val chapter = element.jsonObject
            val start = chapter["time"]?.jsonPrimitive?.doubleOrNull ?: return@mapIndexedNotNull null
            if (!start.isFinite() || start < 0.0) return@mapIndexedNotNull null
            MediaChapter(
                index = index,
                title = chapter["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                startSeconds = start,
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * Whether one of the programs mpv's ytdl hook looks for is on the PATH. The names
 * are the hook's own defaults, in its order of preference.
 */
private fun streamExtractorInstalled(): Boolean = firstOnPath(STREAM_EXTRACTORS) != null

/**
 * The first of [names] that is on the PATH, or null if none of them is.
 *
 * PATH is read rather than a process being started: this runs on a click, and
 * spawning a program just to ask whether it exists costs more than the answer is
 * worth. The names are tried in their own order rather than the PATH's, so the
 * caller's order of preference is what decides.
 */
private fun firstOnPath(names: List<String>): String? {
    val entries = System.getenv("PATH")?.split(File.pathSeparatorChar).orEmpty()
        .filter(String::isNotBlank)
    return names.firstOrNull { name ->
        entries.any { entry ->
            val program = File(entry, name)
            program.isFile && program.canExecute()
        }
    }
}

private val STREAM_EXTRACTORS = listOf(
    "yt-dlp",
    "yt-dlp_x86",
    "youtube-dl",
    // Windows keeps its executables suffixed, and mpv finds them anyway.
    "yt-dlp.exe",
    "youtube-dl.exe",
)

private fun PlayerSnapshot.toPlaybackStatus(): PlaybackStatus {
    val tracks = parseTracks(trackListJson)
    val audio = tracks.filter { it.kind == TrackKind.Audio }
    val subtitles = tracks.filter { it.kind == TrackKind.Subtitle }
    return playbackStatus(audio, subtitles)
}

private fun PlayerSnapshot.playbackStatus(
    audio: List<MediaTrack>,
    subtitles: List<MediaTrack>,
) = PlaybackStatus(
    hasMedia = hasMedia && fileLoaded,
    paused = paused,
    positionSeconds = positionSeconds,
    durationSeconds = durationSeconds,
    volume = volume,
    muted = muted,
    bufferingPercent = cacheBufferingPercent,
    bufferedSeconds = cacheEndSeconds,
    waitingForData = pausedForCache,
    fileLoaded = fileLoaded,
    statusMessage = lastMessage,
    endReached = endReached,
    speed = speed,
    audioTracks = audio,
    subtitleTracks = subtitles,
    selectedAudioId = audio.firstOrNull { it.selected }?.id,
    selectedSubtitleId = subtitles.firstOrNull { it.selected }?.id,
    chapters = parseChapters(chapterListJson),
    subtitleDelaySeconds = subtitleDelaySeconds,
    audioDelaySeconds = audioDelaySeconds,
    videoCodec = videoCodec,
    hardwareDecoder = if (usingHardwareDecoding) hwdecCurrent else "",
    renderBackend = renderBackend,
    droppedFrames = frameDropCount,
    decoderDroppedFrames = decoderFrameDropCount,
    mistimedFrames = mistimedFrameCount,
    delayedFrames = delayedFrameCount,
    estimatedFps = estimatedFps,
    videoBitrate = videoBitrate,
    bufferedAheadSeconds = cacheDurationSeconds,
    renderWidth = renderWidth,
    renderHeight = renderHeight,
    renderTimeMillis = renderTimeMillis,
    // A load failure is reported once and then held; a transient error is whatever the last
    // state poll saw. Either is worth surfacing, and the sticky one must not be overwritten.
    error = error ?: loadError,
)
