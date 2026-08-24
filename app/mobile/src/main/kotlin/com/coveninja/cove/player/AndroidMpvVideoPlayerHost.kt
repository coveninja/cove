package com.coveninja.cove.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.coveninja.cove.shared.network.CoveJson
import com.coveninja.cove.ui.state.MediaChapter
import com.coveninja.cove.ui.state.MediaTrack
import com.coveninja.cove.ui.state.PlaybackPreferences
import com.coveninja.cove.ui.state.PlaybackStatus
import com.coveninja.cove.ui.state.TrackKind
import com.coveninja.cove.ui.state.VideoCodecCapabilities
import com.coveninja.cove.ui.state.VideoDecoderSupport
import com.coveninja.cove.ui.state.VideoPlayerHost
import com.coveninja.cove.ui.state.VideoScaling
import com.coveninja.cove.ui.state.classifyPlaybackTermination
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dev.jdtech.mpv.MPVLib
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

/** Native Android implementation of the shared player contract. */
class AndroidMpvVideoPlayerHost(
    context: Context,
    private val onPlaybackActiveChanged: (Boolean) -> Unit = {},
) : VideoPlayerHost, MPVLib.EventObserver,
    MPVLib.LogObserver {
    private val appContext = context.applicationContext
    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadGeneration = AtomicLong()
    /** One refresh attempt per process, however many extras are opened. */
    private val ytDlpRefreshed = AtomicBoolean()
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
        )
        .setOnAudioFocusChangeListener(::onAudioFocusChanged, main)
        .build()

    private val _status = MutableStateFlow(PlaybackStatus())
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()
    override val playsWebVideos: Boolean = true
    override val videoCodecCapabilities: VideoCodecCapabilities = probeVideoCodecCapabilities()

    /**
     * The mpv handle. libmpv 1.0.0 turned every call from a static into an instance
     * method, so the handle exists from [MPVLib.create] — before the option pass —
     * until [dispose] releases it. [initialized] still marks the later point where
     * `init()` has run and properties rather than options may be set.
     */
    private var mpv: MPVLib? = null
    private var initialized = false
    private var destroyed = false
    private var surfaceReady = false
    private var stoppedByUser = false
    private var playbackRequested = false
    private var fileLoaded = false
    /** True between mpv's start-of-file for the load we asked for and its end. */
    private var fileOpening = false
    private var pendingLoad: PendingLoad? = null
    private var pendingPreferences: PlaybackPreferences? = null
    private var pendingVolume: Double? = null
    private var pendingScaling: VideoScaling = VideoScaling.Fit
    private var pendingSeekSeconds: Double? = null
    /** Position before mpv's latest time-pos update, used to detect an EOF jump. */
    private var previousPositionSeconds = 0.0
    private var trackListJson = ""
    private var chapterListJson = ""

    private val dispatchSeek = Runnable {
        val target = pendingSeekSeconds ?: return@Runnable
        if (initialized) command("seek", formatNumber(target), "absolute", "exact")
    }

    override suspend fun prepareWebVideo(mayInstallHelper: Boolean): String? = withContext(Dispatchers.IO) {
        val failure = runCatching { YoutubeDL.getInstance().init(appContext) }.exceptionOrNull()
        if (failure != null) {
            // A minified build reports these as a bare class name, so the sentence below says
            // nothing on its own. The stack trace is the only way back to the real cause.
            Log.w(MPV_LOG_TAG, "Could not initialize the bundled yt-dlp runtime", failure)
            return@withContext "Could not initialize the bundled yt-dlp runtime: " +
                (failure.message ?: "unknown error")
        }
        val refresh = ytDlpRefreshFor(
            installedVersion = runCatching { YoutubeDL.getInstance().version(appContext) }.getOrNull(),
            today = LocalDate.now(),
            mayInstallHelper = mayInstallHelper,
        )
        when (refresh) {
            YtDlpRefresh.None -> Unit
            // What is already here plays now, and the next extra opens with a newer one.
            YtDlpRefresh.Background -> playerScope.launch { refreshYtDlp() }
            YtDlpRefresh.Blocking -> {
                onMain { _status.value = _status.value.copy(statusMessage = "Updating yt-dlp…") }
                refreshYtDlp()
            }
        }
        null
    }

    /**
     * Replaces the copy of yt-dlp the app shipped with the current release.
     *
     * Never fatal: a refresh that cannot reach GitHub leaves the copy already on
     * disk in place, and that copy is still the best chance the extra has. What
     * went wrong belongs in the log rather than in front of the viewer, because
     * the sentence that matters to them is the one [resolveWebVideo] writes when
     * the extraction itself fails.
     */
    private fun refreshYtDlp() {
        if (!ytDlpRefreshed.compareAndSet(false, true)) return
        runCatching { YoutubeDL.getInstance().updateYoutubeDL(appContext, YoutubeDL.UpdateChannel.STABLE) }
            .onFailure { error ->
                Log.w(MPV_LOG_TAG, "Could not refresh yt-dlp; keeping the copy already installed", error)
            }
    }

    override fun load(url: String, startPositionSeconds: Double) {
        val generation = loadGeneration.incrementAndGet()
        if (url.isWebVideoPage()) {
            resolveWebVideo(url, startPositionSeconds, generation)
        } else {
            loadResolved(url, startPositionSeconds, generation)
        }
    }

    private fun loadResolved(
        url: String,
        startPositionSeconds: Double,
        generation: Long,
        headers: Map<String, String> = emptyMap(),
    ) = onMain {
        if (generation != loadGeneration.get()) return@onMain
        if (!requestAudioFocus()) {
            _status.value = PlaybackStatus(error = "Another app currently owns audio playback.")
            return@onMain
        }
        stoppedByUser = false
        onPlaybackActiveChanged(true)
        playbackRequested = true
        fileLoaded = false
        // Not opening yet: mpv ends the outgoing file before it starts this one, and
        // that end-of-file must not be read as this load failing.
        fileOpening = false
        pendingSeekSeconds = null
        previousPositionSeconds = startPositionSeconds.coerceAtLeast(0.0)
        pendingLoad = PendingLoad(url, startPositionSeconds.coerceAtLeast(0.0), headers)
        _status.value = PlaybackStatus(
            paused = false,
            statusMessage = "Opening stream…",
            volume = pendingVolume ?: _status.value.volume,
        )
        if (initialized && surfaceReady) performPendingLoad()
    }

    override fun setPaused(paused: Boolean) = onMain {
        if (!initialized) return@onMain
        if (!paused && _status.value.endReached) seek(0.0)
        requireMpv().setPropertyBoolean("pause", paused)
        _status.value = _status.value.copy(paused = paused)
    }

    override fun togglePause() = setPaused(!_status.value.paused)

    override fun seek(seconds: Double) = onMain {
        val duration = _status.value.durationSeconds
        val target = when {
            !seconds.isFinite() -> 0.0
            duration > 0.0 -> seconds.coerceIn(0.0, (duration - 0.05).coerceAtLeast(0.0))
            else -> seconds.coerceAtLeast(0.0)
        }
        pendingSeekSeconds = target
        // A deliberate seek is trustworthy. If the viewer explicitly seeks near
        // the end, the following EOF must not look like a synthetic jump there.
        previousPositionSeconds = target
        _status.value = _status.value.copy(positionSeconds = target, endReached = false)
        main.removeCallbacks(dispatchSeek)
        main.postDelayed(dispatchSeek, SEEK_DEBOUNCE_MILLIS)
    }

    override fun seekRelative(deltaSeconds: Double) {
        seek((pendingSeekSeconds ?: _status.value.positionSeconds) + deltaSeconds)
    }

    override fun setVolume(volume: Double) = onMain {
        val clamped = volume.coerceIn(0.0, 100.0)
        pendingVolume = clamped
        if (initialized) {
            requireMpv().setPropertyDouble("volume", clamped)
            if (clamped > 0.0 && _status.value.muted) requireMpv().setPropertyBoolean("mute", false)
        }
        _status.value = _status.value.copy(
            volume = clamped,
            muted = if (clamped > 0.0) false else _status.value.muted,
        )
    }

    override fun setMuted(muted: Boolean) = onMain {
        if (initialized) requireMpv().setPropertyBoolean("mute", muted)
        _status.value = _status.value.copy(muted = muted)
    }

    override fun selectAudioTrack(id: Int) = setStringProperty("aid", id.toString())

    override fun setScaling(scaling: VideoScaling) = onMain {
        pendingScaling = scaling
        if (initialized) applyScaling(scaling)
    }

    override fun setSpeed(speed: Double) = setDoubleProperty("speed", speed.coerceIn(0.25, 4.0))

    override fun applyPreferences(preferences: PlaybackPreferences) = onMain {
        pendingPreferences = preferences
        if (initialized) applyPreferencesNow(preferences)
    }

    override fun addSubtitle(url: String, title: String, language: String) = onMain {
        if (initialized) command("sub-add", url, "select", title, language)
    }

    override fun selectSubtitleTrack(id: Int?) = setStringProperty("sid", id?.toString() ?: "no")

    override fun stepChapter(delta: Int) = onMain { command("add", "chapter", delta.toString()) }

    override fun stepFrame(delta: Int) = onMain {
        command(if (delta < 0) "frame-back-step" else "frame-step")
    }

    override fun setSubtitleDelay(seconds: Double) {
        val value = clampDelay(seconds)
        setDoubleProperty("sub-delay", value)
        _status.value = _status.value.copy(subtitleDelaySeconds = value)
    }

    override fun setAudioDelay(seconds: Double) {
        val value = clampDelay(seconds)
        setDoubleProperty("audio-delay", value)
        _status.value = _status.value.copy(audioDelaySeconds = value)
    }

    override fun takeScreenshot() = onMain { command("screenshot", "subtitles") }

    override fun stop() = onMain {
        loadGeneration.incrementAndGet()
        stoppedByUser = true
        playbackRequested = false
        fileOpening = false
        pendingLoad = null
        pendingSeekSeconds = null
        main.removeCallbacks(dispatchSeek)
        if (initialized) command("stop")
        fileLoaded = false
        abandonAudioFocus()
        onPlaybackActiveChanged(false)
        _status.value = PlaybackStatus(volume = pendingVolume ?: _status.value.volume)
    }

    /** Explicit host policy hook retained for callers that want foreground-only playback. */
    fun onHostStopped() {
        if (_status.value.hasMedia && !_status.value.paused) setPaused(true)
    }

    fun dispose() = onMain {
        if (destroyed) return@onMain
        destroyed = true
        loadGeneration.incrementAndGet()
        onPlaybackActiveChanged(false)
        playerScope.cancel()
        main.removeCallbacksAndMessages(null)
        abandonAudioFocus()
        mpv?.let { handle ->
            if (initialized) {
                handle.removeObserver(this)
                handle.removeLogObserver(this)
                runCatching { handle.detachSurface() }
            }
            handle.destroy()
        }
        mpv = null
        initialized = false
        surfaceReady = false
        _status.value = PlaybackStatus()
    }

    @Composable
    override fun Surface(modifier: Modifier) {
        AndroidView(
            modifier = modifier,
            factory = { AndroidMpvSurfaceView(it, this) },
        )
    }

    internal fun onSurfaceCreated(surface: Surface, width: Int, height: Int) = onMain {
        if (destroyed) return@onMain
        runCatching {
            ensureInitialized()
            requireMpv().attachSurface(surface)
            requireMpv().setPropertyString(
                "android-surface-size",
                "${width.coerceAtLeast(1)}x${height.coerceAtLeast(1)}",
            )
            _status.value = _status.value.copy(
                renderWidth = width.coerceAtLeast(1),
                renderHeight = height.coerceAtLeast(1),
            )
            requireMpv().setPropertyString("vo", "gpu")
            surfaceReady = true
            performPendingLoad()
            refreshStillFrame()
        }.onFailure { error ->
            surfaceReady = false
            _status.value = _status.value.copy(
                error = "Android player initialization failed: " +
                    (error.message ?: error::class.java.simpleName),
                statusMessage = "Playback unavailable",
            )
        }
    }

    internal fun onSurfaceChanged(width: Int, height: Int) = onMain {
        if (initialized && surfaceReady) {
            requireMpv().setPropertyString("android-surface-size", "${width.coerceAtLeast(1)}x${height.coerceAtLeast(1)}")
            _status.value = _status.value.copy(
                renderWidth = width.coerceAtLeast(1),
                renderHeight = height.coerceAtLeast(1),
            )
            refreshStillFrame()
        }
    }

    internal fun onSurfaceDestroyed() = onMain {
        if (!initialized || !surfaceReady) return@onMain
        surfaceReady = false
        runCatching { requireMpv().setPropertyString("vo", "null") }
        runCatching { requireMpv().detachSurface() }
    }

    /**
     * Put the paused picture back on screen after the surface geometry changes.
     *
     * A rotation hands the SurfaceView freshly allocated buffers whose contents are
     * undefined, and mpv writes into them only when it has a frame to show — which,
     * while paused, never happens. The image stays torn until playback resumes. A
     * rotation also arrives as several size changes in a row, hence the debounce.
     */
    private fun refreshStillFrame() {
        main.removeCallbacks(redrawStillFrame)
        main.postDelayed(redrawStillFrame, STILL_FRAME_DELAY_MILLIS)
    }

    /**
     * An exact seek of zero, which is the cheapest way to make mpv render again: it
     * re-displays the frame already on screen without moving the position, and the
     * demuxer answers it from the cache it has already read. Only while paused —
     * playback redraws itself, and a seek would interrupt it for nothing.
     */
    private val redrawStillFrame = Runnable {
        if (!initialized || !surfaceReady || !fileLoaded) return@Runnable
        if (!_status.value.paused || _status.value.endReached) return@Runnable
        command("seek", "0", "exact")
    }

    private fun ensureInitialized() {
        if (initialized) return
        // Null where 0.5.1 could only succeed or kill the process. onSurfaceCreated
        // turns this into "Android player initialization failed: …" on screen, which
        // is the whole reason 1.0.0 is worth having: a device that cannot start mpv
        // now says so instead of taking the app down with it.
        val handle = MPVLib.create(appContext)
            ?: error("libmpv could not create a player context")
        mpv = handle
        // init() likewise throws where 0.5.1 called exit(1), so for the first time a
        // handle can outlive a failed start. Release it before rethrowing —
        // onSurfaceCreated retries on the next surface, and create() would otherwise
        // leak the native context this attempt is holding.
        try {
            stageSubtitleFont()
            setInitialOption("vo", "gpu")
            setInitialOption("gpu-context", "android")
            setInitialOption("gpu-dumb-mode", "yes")
            setInitialOption("hwdec", "mediacodec")
            setInitialOption("hwdec-codecs", "h264,hevc,vp8,vp9,av1")
            setInitialOption("ao", "audiotrack")
            setInitialOption("force-window", "yes")
            setInitialOption("keep-open", "yes")
            setInitialOption("cache", "yes")
            setInitialOption("demuxer-max-bytes", "32MiB")
            setInitialOption("demuxer-readahead-secs", "4")
            setInitialOption("cache-pause-initial", "no")
            setInitialOption("cache-pause-wait", "2")
            // Explicit rather than left to mpv's default, because the number on the other side
            // of it is one of ours: the torrent engine waits up to its own pieceTimeoutMillis
            // for the pieces under a read, and every one of those waits happens with mpv already
            // blocked on the socket. Whichever of the two is shorter is the one that decides
            // what the viewer is told, and the engine's version of the story is the useful one —
            // it can say how many peers it had and how fast they were going, and the session can
            // reconnect from it. So this stays comfortably the larger of the pair.
            setInitialOption("network-timeout", "90")
            setInitialOption("terminal", "no")
            // mpv's ytdl_hook runs on load failure and shells out to a yt-dlp on PATH.
            // An app sandbox has no PATH to put one on, so it can only ever fail — and
            // when it does it replaces the real reason a stream would not open with
            // "youtube-dl failed: not found or not enough permissions". Android resolves
            // page URLs itself through youtubedl-android before mpv is handed anything,
            // so the hook has no work here beyond hiding errors. Not setInitialOption:
            // a libmpv built without the Lua script has no such option, and refusing to
            // start the player over that would be worse than leaving the hook on.
            if (requireMpv().setOptionString("ytdl", "no") < 0) {
                Log.w(MPV_LOG_TAG, "This libmpv has no ytdl option; its hook stays on")
            }
            // Lets ffmpeg re-issue the range request at the offset it had reached instead of
            // reporting a body that died mid-file as the end of the film. With keep-open=yes a
            // read error is otherwise permanent: mpv parks, PlaybackStatus.interrupted goes
            // true, and the viewer is told the stream stopped before the end — of a source that
            // would have answered a second request perfectly well.
            //
            // reconnect_at_eof is deliberately absent. It treats a clean end-of-file as an error
            // too, and the natural end is what decides whether the next episode plays; the two
            // flags here fire on errors only, and reconnect_delay_max bounds the retries so a
            // genuinely dead upstream still gives up rather than looping.
            //
            // Not setInitialOption: a stream option this libmpv does not recognise must cost the
            // reconnect, not the whole player, exactly as with ytdl above.
            if (requireMpv().setOptionString("stream-lavf-o", RECONNECT_STREAM_OPTIONS) < 0) {
                Log.w(MPV_LOG_TAG, "This libmpv rejected stream-lavf-o; streams will not reconnect")
            }
            // Caps mpv's own stdout only. The client API log the LogObserver reads is a
            // separate buffer whose level msg-level cannot lower — see
            // isViewableMpvDiagnostic for the filter that stands in for it.
            setInitialOption("msg-level", "all=warn")
            setInitialOption(
                "screenshot-directory",
                (appContext.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                    ?: appContext.filesDir).absolutePath,
            )
            handle.init()
            handle.addObserver(this)
            handle.addLogObserver(this)
            observeProperties()
            initialized = true
            pendingPreferences?.let(::applyPreferencesNow)
            pendingVolume?.let { handle.setPropertyDouble("volume", it) }
            applyScaling(pendingScaling)
        } catch (error: Throwable) {
            initialized = false
            mpv = null
            runCatching { handle.destroy() }
            throw error
        }
    }

    private fun resolveWebVideo(url: String, startPositionSeconds: Double, generation: Long) {
        onPlaybackActiveChanged(true)
        onMain {
            _status.value = PlaybackStatus(
                paused = false,
                statusMessage = "Resolving web video…",
                volume = pendingVolume ?: _status.value.volume,
            )
        }
        playerScope.launch {
            val resolved = runCatching {
                YoutubeDL.getInstance().init(appContext)
                val request = YoutubeDLRequest(url).apply {
                    addOption("-f", "best[protocol^=http][vcodec!=none][acodec!=none]/best")
                    addOption("--no-playlist")
                    addOption("--extractor-args", YOUTUBE_PLAYER_CLIENTS)
                }
                val info = YoutubeDL.getInstance().getInfo(request)
                val stream = info.url?.takeIf(String::isNotBlank)
                    ?: error("yt-dlp returned no playable URL")
                stream to info.httpHeaders.orEmpty()
            }
            if (generation != loadGeneration.get()) return@launch
            resolved.fold(
                onSuccess = { (stream, headers) ->
                    loadResolved(stream, startPositionSeconds, generation, headers)
                },
                onFailure = { error ->
                    Log.w(MPV_LOG_TAG, "Could not resolve the web video at $url", error)
                    onMain {
                        if (generation != loadGeneration.get()) return@onMain
                        onPlaybackActiveChanged(false)
                        _status.value = PlaybackStatus(
                            error = "Could not open this web video: ${error.message ?: "unknown error"}",
                            statusMessage = "Playback failed",
                            volume = pendingVolume ?: _status.value.volume,
                        )
                    }
                },
            )
        }
    }

    private fun observeProperties() {
        DOUBLE_PROPERTIES.forEach { requireMpv().observeProperty(it, MPVLib.MpvFormat.MPV_FORMAT_DOUBLE) }
        FLAG_PROPERTIES.forEach { requireMpv().observeProperty(it, MPVLib.MpvFormat.MPV_FORMAT_FLAG) }
        STRING_PROPERTIES.forEach { requireMpv().observeProperty(it, MPVLib.MpvFormat.MPV_FORMAT_STRING) }
    }

    private fun performPendingLoad() {
        val load = pendingLoad ?: return
        if (!initialized || !surfaceReady) return
        pendingLoad = null
        fileLoaded = false
        applyRequestHeaders(load.headers)
        requireMpv().command(buildMpvLoadCommand(load.url, load.startPositionSeconds).toTypedArray())
    }

    /**
     * Sends the extractor's headers with the stream it extracted.
     *
     * A yt-dlp URL is only half of what it returns: the CDN behind a web video
     * answers 403 to a request that arrives without the User-Agent and friends the
     * extraction was performed under. Desktop never had to think about this because
     * mpv's own ytdl_hook copies those headers into mpv as it resolves; Android runs
     * the extractor itself, so anything the resolver does not carry over is simply
     * lost, and every trailer dies on a forbidden that reads as a broken link.
     *
     * Appended one at a time rather than joined: http-header-fields is a list mpv
     * splits on commas, and Accept-Language alone routinely contains one.
     *
     * Always applied, with an empty map for ordinary streams — an addon's stream
     * must never inherit the User-Agent a trailer set before it.
     */
    private fun applyRequestHeaders(headers: Map<String, String>) {
        command("change-list", "http-header-fields", "clr", "")
        // mpv owns the User-Agent separately; ffmpeg would otherwise send both it
        // and the one in the header list.
        requireMpv().setPropertyString("user-agent", mpvUserAgent(headers))
        mpvHeaderFields(headers).forEach { field ->
            command("change-list", "http-header-fields", "append", field)
        }
    }

    override fun eventProperty(property: String) = Unit

    override fun eventProperty(property: String, value: Long) = Unit

    override fun eventProperty(property: String, value: Double) = onMain {
        if (destroyed || !value.isFinite()) return@onMain
        val current = _status.value
        _status.value = when (property) {
            "time-pos" -> {
                if (pendingSeekSeconds?.let { kotlin.math.abs(it - value) < 0.75 } == true) {
                    pendingSeekSeconds = null
                }
                previousPositionSeconds = current.positionSeconds
                current.copy(positionSeconds = pendingSeekSeconds ?: value.coerceAtLeast(0.0))
            }
            "duration" -> current.copy(durationSeconds = value.coerceAtLeast(0.0))
            "volume" -> current.copy(volume = value.coerceIn(0.0, 100.0))
            "cache-buffering-state" -> current.copy(bufferingPercent = value.toInt().coerceIn(0, 100))
            "demuxer-cache-time" -> current.copy(bufferedSeconds = value.coerceAtLeast(0.0))
            "demuxer-cache-duration" -> current.copy(bufferedAheadSeconds = value.coerceAtLeast(0.0))
            "speed" -> current.copy(speed = value)
            "sub-delay" -> current.copy(subtitleDelaySeconds = value)
            "audio-delay" -> current.copy(audioDelaySeconds = value)
            "frame-drop-count" -> current.copy(droppedFrames = value.coerceAtLeast(0.0).toInt())
            "decoder-frame-drop-count" -> current.copy(
                decoderDroppedFrames = value.coerceAtLeast(0.0).toInt(),
            )
            "mistimed-frame-count" -> current.copy(mistimedFrames = value.coerceAtLeast(0.0).toInt())
            "vo-delayed-frame-count" -> current.copy(delayedFrames = value.coerceAtLeast(0.0).toInt())
            "estimated-vf-fps" -> current.copy(estimatedFps = value.coerceAtLeast(0.0))
            "video-bitrate" -> current.copy(videoBitrate = value.coerceAtLeast(0.0))
            else -> current
        }
    }

    override fun eventProperty(property: String, value: Boolean) = onMain {
        if (destroyed) return@onMain
        val current = _status.value
        _status.value = when (property) {
            "pause" -> current.copy(paused = value)
            "mute" -> current.copy(muted = value)
            "paused-for-cache" -> current.copy(waitingForData = value)
            "eof-reached" -> current.withMpvEof(
                reached = value,
                stoppedByUser = stoppedByUser,
                fileLoaded = fileLoaded,
                previousPositionSeconds = previousPositionSeconds,
            )
            else -> current
        }
    }

    override fun eventProperty(property: String, value: String) = onMain {
        if (destroyed) return@onMain
        val current = _status.value
        _status.value = when (property) {
            "track-list" -> {
                trackListJson = value
                current.withTracks(parseMpvTracks(value))
            }
            "chapter-list" -> {
                chapterListJson = value
                current.copy(chapters = parseMpvChapters(value))
            }
            "video-codec" -> current.copy(videoCodec = value)
            "hwdec-current" -> current.copy(hardwareDecoder = value.takeUnless { it == "no" }.orEmpty())
            "current-vo" -> current.copy(renderBackend = value)
            else -> current
        }
    }

    override fun event(eventId: Int) = onMain {
        if (destroyed) return@onMain
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                fileLoaded = false
                fileOpening = true
                _status.value = _status.value.copy(
                    hasMedia = false,
                    fileLoaded = false,
                    endReached = false,
                    interrupted = false,
                    error = null,
                    statusMessage = "Opening stream…",
                )
            }
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                stoppedByUser = false
                fileLoaded = true
                _status.value = _status.value
                    .withTracks(parseMpvTracks(trackListJson))
                    .copy(
                        hasMedia = true,
                        fileLoaded = true,
                        endReached = false,
                        interrupted = false,
                        error = null,
                        statusMessage = "",
                        chapters = parseMpvChapters(chapterListJson),
                    )
            }
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                if (fileLoaded) _status.value = _status.value.copy(hasMedia = true, waitingForData = false)
            }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                val failed = mpvEndOfFileIsFailure(
                    fileOpening = fileOpening,
                    playbackRequested = playbackRequested,
                    stoppedByUser = stoppedByUser,
                    fileLoaded = fileLoaded,
                )
                fileOpening = false
                if (failed) {
                    _status.value = _status.value.copy(
                        error = "The selected stream could not be opened.",
                        statusMessage = "Playback failed",
                    )
                }
            }
        }
    }

    override fun logMessage(prefix: String, level: Int, text: String) = onMain {
        if (destroyed) return@onMain
        val message = text.trim().takeIf(String::isNotBlank) ?: return@onMain
        val source = prefix.takeIf(String::isNotBlank)?.let { "[$it] " }.orEmpty()
        when {
            level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR -> Log.e(MPV_LOG_TAG, source + message)
            level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN -> Log.w(MPV_LOG_TAG, source + message)
            else -> Log.d(MPV_LOG_TAG, source + message)
        }
        // Logcat takes everything; the screen only takes what reads as progress.
        if (isViewableMpvDiagnostic(level)) {
            _status.value = _status.value.withMpvDiagnostic(message)
        }
    }

    private fun applyPreferencesNow(preferences: PlaybackPreferences) {
        if (preferences.audioLanguages.isNotEmpty()) {
            requireMpv().setPropertyString("alang", preferences.audioLanguages.joinToString(","))
        }
        if (preferences.subtitleLanguages.isNotEmpty()) {
            requireMpv().setPropertyString("slang", preferences.subtitleLanguages.joinToString(","))
        }
        requireMpv().setPropertyString("sid", if (preferences.subtitlesEnabled) "auto" else "no")
        requireMpv().setPropertyBoolean("mute", preferences.startMuted)
        requireMpv().setPropertyDouble("sub-scale", preferences.subtitleScale)
        requireMpv().setPropertyInt("sub-pos", preferences.subtitlePosition)
        requireMpv().setPropertyString(
            "sub-border-style",
            if (preferences.subtitleBackground) "opaque-box" else "outline-and-shadow",
        )
        requireMpv().setPropertyString("hwdec", if (preferences.hardwareDecoding) "mediacodec" else "no")
    }

    private fun applyScaling(scaling: VideoScaling) {
        val (keepAspect, panscan, zoom) = when (scaling) {
            VideoScaling.Fit -> Triple(true, 0.0, 0.0)
            VideoScaling.Fill -> Triple(true, 1.0, 0.0)
            VideoScaling.Zoom -> Triple(true, 0.0, 0.2)
            VideoScaling.Stretch -> Triple(false, 0.0, 0.0)
        }
        requireMpv().setPropertyBoolean("keepaspect", keepAspect)
        requireMpv().setPropertyDouble("panscan", panscan)
        requireMpv().setPropertyDouble("video-zoom", zoom)
    }

    private fun stageSubtitleFont() {
        runCatching {
            val config = File(appContext.filesDir, "mpv").apply { mkdirs() }
            setInitialOption("config", "yes")
            setInitialOption("config-dir", config.absolutePath)
            val fonts = File(config, "fonts").apply { mkdirs() }
            val rootFont = File(config, "subfont.ttf")
            val familyFont = File(fonts, "subfont.ttf")
            if (!rootFont.isFile || rootFont.length() == 0L) {
                val systemFont = SYSTEM_FONTS.map(::File).firstOrNull(File::canRead) ?: return@runCatching
                systemFont.copyTo(rootFont, overwrite = true)
                systemFont.copyTo(familyFont, overwrite = true)
            }
        }
    }

    private fun requestAudioFocus(): Boolean =
        audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun abandonAudioFocus() {
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    private fun onAudioFocusChanged(change: Int) {
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        ) {
            setPaused(true)
        }
    }

    /**
     * The handle every call needs since libmpv 1.0.0 stopped being static.
     *
     * Errors rather than no-ops when there is none: every call site is already
     * behind [initialized], or runs inside [ensureInitialized] after the handle
     * exists, so a null here is a broken invariant and not a state to absorb.
     */
    private fun requireMpv(): MPVLib = mpv ?: error("mpv has not been created")

    private fun setInitialOption(name: String, value: String) {
        check(requireMpv().setOptionString(name, value) >= 0) { "mpv rejected option $name" }
    }

    private fun setStringProperty(name: String, value: String) = onMain {
        if (initialized) requireMpv().setPropertyString(name, value)
    }

    private fun setDoubleProperty(name: String, value: Double) = onMain {
        if (initialized) requireMpv().setPropertyDouble(name, value)
    }

    private fun command(vararg args: String) {
        if (initialized) requireMpv().command(arrayOf(*args))
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    private data class PendingLoad(
        val url: String,
        val startPositionSeconds: Double,
        val headers: Map<String, String> = emptyMap(),
    )

    private companion object {
        const val MPV_LOG_TAG = "CoveMpv"
        const val SEEK_DEBOUNCE_MILLIS = 40L

        /** Long enough for a rotation's burst of surface changes to settle. */
        const val STILL_FRAME_DELAY_MILLIS = 120L
        val SYSTEM_FONTS = listOf(
            "/system/fonts/Roboto-Regular.ttf",
            "/system/fonts/NotoSans-Regular.ttf",
            "/system/fonts/DroidSans.ttf",
        )
        val DOUBLE_PROPERTIES = listOf(
            "time-pos", "duration", "volume", "cache-buffering-state",
            "demuxer-cache-time", "demuxer-cache-duration", "speed", "sub-delay",
            "audio-delay", "frame-drop-count", "decoder-frame-drop-count",
            "mistimed-frame-count", "vo-delayed-frame-count", "estimated-vf-fps", "video-bitrate",
        )
        val FLAG_PROPERTIES = listOf("pause", "mute", "paused-for-cache", "eof-reached")
        val STRING_PROPERTIES = listOf(
            "track-list", "chapter-list", "video-codec", "hwdec-current", "current-vo",
        )
    }
}

private data class DecoderAvailability(
    var hardware: Boolean = false,
    var software: Boolean = false,
    var uncertain: Boolean = false,
) {
    fun record(isHardware: Boolean) {
        if (isHardware) hardware = true else software = true
    }

    fun support(): VideoDecoderSupport = when {
        hardware -> VideoDecoderSupport.Hardware
        software -> VideoDecoderSupport.SoftwareOnly
        uncertain -> VideoDecoderSupport.Unknown
        else -> VideoDecoderSupport.Unsupported
    }
}

private fun probeVideoCodecCapabilities(): VideoCodecCapabilities {
    val h264 = DecoderAvailability()
    val h264High10 = DecoderAvailability()
    val hevc = DecoderAvailability()
    val hevcMain10 = DecoderAvailability()
    val av1 = DecoderAvailability()
    val vp9 = DecoderAvailability()
    val dolbyVision = DecoderAvailability()

    return runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .asSequence()
            .filterNot(MediaCodecInfo::isEncoder)
            .forEach { info ->
                val hardware = info.isHardwareDecoder()
                info.supportedTypes.forEach { advertisedType ->
                    when (advertisedType.lowercase(Locale.US)) {
                        "video/avc" -> {
                            h264.record(hardware)
                            info.recordProfileSupport(
                                type = advertisedType,
                                profiles = setOf(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10),
                                hardware = hardware,
                                availability = h264High10,
                            )
                        }
                        "video/hevc" -> {
                            hevc.record(hardware)
                            info.recordProfileSupport(
                                type = advertisedType,
                                profiles = setOf(
                                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
                                    MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus,
                                ),
                                hardware = hardware,
                                availability = hevcMain10,
                            )
                        }
                        "video/av01" -> av1.record(hardware)
                        "video/x-vnd.on2.vp9" -> vp9.record(hardware)
                        "video/dolby-vision" -> dolbyVision.record(hardware)
                    }
                }
            }

        VideoCodecCapabilities(
            h264 = h264.support(),
            h264High10 = h264High10.support(),
            hevc = hevc.support(),
            hevcMain10 = hevcMain10.support(),
            av1 = av1.support(),
            vp9 = vp9.support(),
            dolbyVision = dolbyVision.support(),
        )
    }.getOrElse { error ->
        Log.w("CoveMpv", "Video codec capability probe failed", error)
        VideoCodecCapabilities()
    }
}

private fun MediaCodecInfo.recordProfileSupport(
    type: String,
    profiles: Set<Int>,
    hardware: Boolean,
    availability: DecoderAvailability,
) {
    runCatching { getCapabilitiesForType(type).profileLevels }
        .onSuccess { levels ->
            if (levels.any { it.profile in profiles }) availability.record(hardware)
        }
        .onFailure { availability.uncertain = true }
}

/** API 29 exposes the authoritative flag; API 28 needs the standard codec-name fallback. */
private fun MediaCodecInfo.isHardwareDecoder(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isHardwareAccelerated
    } else {
        !name.startsWith("OMX.google.", ignoreCase = true) &&
            !name.startsWith("c2.android.", ignoreCase = true)
    }

private fun String.isWebVideoPage(): Boolean = runCatching {
    val host = java.net.URI(this).host?.lowercase().orEmpty()
    host == "youtu.be" || host.endsWith(".youtube.com") || host == "youtube.com" ||
        host.endsWith(".vimeo.com") || host == "vimeo.com"
}.getOrDefault(false)

private class AndroidMpvSurfaceView(
    context: Context,
    private val host: AndroidMpvVideoPlayerHost,
) : SurfaceView(context), SurfaceHolder.Callback {
    init {
        setZOrderOnTop(false)
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        host.onSurfaceCreated(holder.surface, holder.surfaceFrame.width(), holder.surfaceFrame.height())
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        host.onSurfaceChanged(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        host.onSurfaceDestroyed()
    }
}

internal fun parseMpvTracks(json: String): List<MediaTrack> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        CoveJson.parseToJsonElement(json).jsonArray.mapNotNull { element ->
            val track = element.jsonObject
            val kind = when (track["type"]?.jsonPrimitive?.contentOrNull) {
                "audio" -> TrackKind.Audio
                "sub" -> TrackKind.Subtitle
                else -> return@mapNotNull null
            }
            MediaTrack(
                id = track["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                kind = kind,
                title = track["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                language = track["lang"]?.jsonPrimitive?.contentOrNull,
                selected = track["selected"]?.jsonPrimitive?.booleanOrNull == true,
            )
        }
    }.getOrDefault(emptyList())
}

internal fun parseMpvChapters(json: String): List<MediaChapter> {
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

private fun PlaybackStatus.withTracks(tracks: List<MediaTrack>): PlaybackStatus {
    val audio = tracks.filter { it.kind == TrackKind.Audio }
    val subtitles = tracks.filter { it.kind == TrackKind.Subtitle }
    return copy(
        audioTracks = audio,
        subtitleTracks = subtitles,
        selectedAudioId = audio.firstOrNull { it.selected }?.id,
        selectedSubtitleId = subtitles.firstOrNull { it.selected }?.id,
    )
}

/**
 * mpv's log level describes a decoder or demuxer message, not the state of the
 * playback session. Recoverable FFmpeg reads and transient Android ImageReader
 * misses are both logged as errors while the next frame continues normally, so
 * only explicit player events may populate [PlaybackStatus.error].
 */
internal fun PlaybackStatus.withMpvDiagnostic(message: String): PlaybackStatus =
    copy(statusMessage = message)

/**
 * Whether an mpv log line is worth showing to the viewer as opening commentary.
 *
 * The Android binding fixes the client API log level at "v": its native create()
 * calls mpv_request_log_messages(ctx, "v") with the level hardcoded, and there is no
 * Java entry point to lower it afterwards. Verbose is mpv's internal bookkeeping —
 * a rotation alone emits "Set property: android-surface-size=1080x2400" once per size
 * change, and a rotation produces several — while PlayerLayer prints statusMessage
 * verbatim under the opening spinner, so those scroll past the viewer looking like
 * progress. Desktop never sees them because it asks libmpv for "info" and up
 * directly; this is the same cut, made after delivery rather than before it.
 */
internal fun isViewableMpvDiagnostic(level: Int): Boolean = level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_INFO

/**
 * Whether an mpv end-of-file means the stream the viewer asked for would not open.
 *
 * mpv ends the outgoing file before it starts the incoming one, so a load issued
 * while something is still loaded produces an end-of-file that belongs to the file
 * being replaced. stop() marks that case with stoppedByUser, but only until the next
 * load clears the flag — and the event crosses from mpv's thread to the main thread
 * by post, so a load that lands first turns the outgoing file's end into "The selected
 * stream could not be opened." on a stream that is opening perfectly well. That is
 * why the automatic path is the one that shows it: it starts playback the instant
 * sources resolve, where the picker spends the window waiting for a human.
 *
 * [fileOpening] closes the hole, because mpv's event order is guaranteed: the end of
 * the outgoing file arrives before the start of the incoming one, so an end seen
 * before that start belongs to the file being replaced and never to this load.
 */
internal fun mpvEndOfFileIsFailure(
    fileOpening: Boolean,
    playbackRequested: Boolean,
    stoppedByUser: Boolean,
    fileLoaded: Boolean,
): Boolean = fileOpening && playbackRequested && !stoppedByUser && !fileLoaded

/**
 * The YouTube clients whose URLs a media player can actually read.
 *
 * Left to itself yt-dlp answers from ANDROID_VR, and googlevideo serves those URLs
 * only to a *bounded* range request: `Range: bytes=0-0` and `bytes=0-1048575` come
 * back 206, while `bytes=0-` — what ffmpeg sends the moment it opens a stream — and
 * a request with no Range at all both come back 403. There is no mpv or ffmpeg option
 * that bounds the opening request, so a player can never read one of those URLs; the
 * "Playback failed" was that 403, reached through a URL yt-dlp had extracted perfectly.
 *
 * MWEB and TVHTML5_SIMPLY serve the same itag 18 — muxed avc1 + mp4a, which is what
 * the format selector above asks for and what mediacodec decodes — and answer an
 * open-ended range with 206. Both need a JavaScript runtime to expose any format at
 * all, which is exactly why youtubedl-android bundles QuickJS and passes it to yt-dlp
 * as --js-runtimes; on a host without one they return storyboards and nothing else.
 */
internal const val YOUTUBE_PLAYER_CLIENTS = "youtube:player_client=mweb,tv_simply"

/**
 * ffmpeg's HTTP reconnect flags, as one `stream-lavf-o` value.
 *
 * `reconnect_streamed` is included because a response without a length is not seekable as far
 * as ffmpeg is concerned, and those are exactly the addon streams that most need retrying.
 */
internal const val RECONNECT_STREAM_OPTIONS =
    "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=10"

/** The User-Agent mpv should send, or blank to leave mpv's own default alone. */
internal fun mpvUserAgent(headers: Map<String, String>): String =
    headers.entries
        .firstOrNull { it.key.equals(USER_AGENT_HEADER, ignoreCase = true) }
        ?.value
        ?.takeIf(String::isNotBlank)
        .orEmpty()

/**
 * The extractor's headers as mpv http-header-fields entries, User-Agent excluded.
 *
 * Excluded because [mpvUserAgent] sends that one through mpv's dedicated option,
 * and ffmpeg would put both on the wire if it appeared in each place. Blank values
 * are dropped rather than sent empty: yt-dlp emits a few of those, and a header with
 * no value is not the same request as no header at all.
 */
internal fun mpvHeaderFields(headers: Map<String, String>): List<String> =
    headers.entries
        .filterNot { it.key.equals(USER_AGENT_HEADER, ignoreCase = true) }
        .filter { it.key.isNotBlank() && it.value.isNotBlank() }
        .map { "${it.key}: ${it.value}" }

private const val USER_AGENT_HEADER = "User-Agent"

/** What to do about the copy of yt-dlp on disk before opening a page URL. */
internal enum class YtDlpRefresh { None, Background, Blocking }

/**
 * Whether the yt-dlp this app runs is current enough to extract a web video.
 *
 * Android runs yt-dlp itself, from a copy compiled into the youtubedl-android AAR;
 * desktop hands the job to mpv's ytdl_hook over a copy YtDlpProvisioner downloads
 * and keeps fresh. So the two hosts age differently: the desktop's yt-dlp is as new
 * as its last provisioning run, while Android's is frozen at whatever the dependency
 * shipped — 2025.11.12 for youtubedl-android 0.18.1 — and stays there for the life
 * of the release. YouTube breaks extraction faster than that, which is why extras
 * play on the desktop and not on the phone.
 *
 * [installedVersion] is null until the library's updater has run once, since it
 * reads the version from the preference that updater writes; that null is exactly
 * the "never refreshed, still on the bundled copy" case, so it is the one that
 * waits. Otherwise a yt-dlp version string is its own release date, and the copy
 * dates itself with nothing stored alongside it.
 */
internal fun ytDlpRefreshFor(
    installedVersion: String?,
    today: LocalDate,
    mayInstallHelper: Boolean,
): YtDlpRefresh {
    if (!mayInstallHelper) return YtDlpRefresh.None
    val released = parseYtDlpReleaseDate(installedVersion) ?: return YtDlpRefresh.Blocking
    val age = ChronoUnit.DAYS.between(released, today)
    return when {
        age >= YTDLP_UNUSABLE_AGE_DAYS -> YtDlpRefresh.Blocking
        age >= YTDLP_STALE_AGE_DAYS -> YtDlpRefresh.Background
        else -> YtDlpRefresh.None
    }
}

/** yt-dlp tags its releases by date: "2026.08.10", nightlies with a time after it. */
internal fun parseYtDlpReleaseDate(version: String?): LocalDate? {
    val parts = version?.trim()?.split('.') ?: return null
    if (parts.size < 3) return null
    return runCatching {
        LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
    }.getOrNull()
}

/** Old enough to be worth replacing, new enough that it probably still works. */
internal const val YTDLP_STALE_AGE_DAYS = 30L

/** Old enough that YouTube has almost certainly outrun it; wait for the new one. */
internal const val YTDLP_UNUSABLE_AGE_DAYS = 90L

/** Interprets Android's observed eof-reached flag without promoting stop() to EOF. */
internal fun PlaybackStatus.withMpvEof(
    reached: Boolean,
    stoppedByUser: Boolean,
    fileLoaded: Boolean,
    previousPositionSeconds: Double,
): PlaybackStatus {
    if (!reached || stoppedByUser || !fileLoaded) {
        return copy(endReached = false, interrupted = false)
    }
    val termination = classifyPlaybackTermination(
        positionSeconds = positionSeconds,
        previousPositionSeconds = previousPositionSeconds,
        durationSeconds = durationSeconds,
    )
    return copy(
        positionSeconds = termination.positionSeconds,
        endReached = termination.ended,
        interrupted = termination.interrupted,
        statusMessage = if (termination.interrupted) {
            "The stream stopped before the end."
        } else {
            statusMessage
        },
    )
}

private fun clampDelay(seconds: Double): Double =
    seconds.takeIf(Double::isFinite)?.coerceIn(-10.0, 10.0) ?: 0.0

private fun formatNumber(value: Double): String = String.format(Locale.US, "%.3f", value)

/** mpv 0.40 puts per-file options after its integer playlist-index argument. */
internal fun buildMpvLoadCommand(url: String, startPositionSeconds: Double): List<String> =
    buildList {
        add("loadfile")
        add(url)
        add("replace")
        if (startPositionSeconds > 0.0) {
            add("-1")
            add("start=${formatNumber(startPositionSeconds)}")
        }
    }
