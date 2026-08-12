package com.coveninja.cove.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
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
import com.coveninja.cove.ui.state.VideoPlayerHost
import com.coveninja.cove.ui.state.VideoScaling
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dev.jdtech.mpv.MPVLib
import java.io.File
import java.util.Locale
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

    private var initialized = false
    private var destroyed = false
    private var surfaceReady = false
    private var stoppedByUser = false
    private var playbackRequested = false
    private var fileLoaded = false
    private var pendingLoad: PendingLoad? = null
    private var pendingPreferences: PlaybackPreferences? = null
    private var pendingVolume: Double? = null
    private var pendingScaling: VideoScaling = VideoScaling.Fit
    private var pendingSeekSeconds: Double? = null
    private var trackListJson = ""
    private var chapterListJson = ""

    private val dispatchSeek = Runnable {
        val target = pendingSeekSeconds ?: return@Runnable
        if (initialized) command("seek", formatNumber(target), "absolute", "exact")
    }

    override suspend fun prepareWebVideo(mayInstallHelper: Boolean): String? = withContext(Dispatchers.IO) {
        runCatching { YoutubeDL.getInstance().init(appContext) }
            .exceptionOrNull()
            ?.let { "Could not initialize the bundled yt-dlp runtime: ${it.message ?: "unknown error"}" }
    }

    override fun load(url: String, startPositionSeconds: Double) {
        val generation = loadGeneration.incrementAndGet()
        if (url.isWebVideoPage()) {
            resolveWebVideo(url, startPositionSeconds, generation)
        } else {
            loadResolved(url, startPositionSeconds, generation)
        }
    }

    private fun loadResolved(url: String, startPositionSeconds: Double, generation: Long) = onMain {
        if (generation != loadGeneration.get()) return@onMain
        if (!requestAudioFocus()) {
            _status.value = PlaybackStatus(error = "Another app currently owns audio playback.")
            return@onMain
        }
        stoppedByUser = false
        onPlaybackActiveChanged(true)
        playbackRequested = true
        fileLoaded = false
        pendingSeekSeconds = null
        pendingLoad = PendingLoad(url, startPositionSeconds.coerceAtLeast(0.0))
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
        MPVLib.setPropertyBoolean("pause", paused)
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
            MPVLib.setPropertyDouble("volume", clamped)
            if (clamped > 0.0 && _status.value.muted) MPVLib.setPropertyBoolean("mute", false)
        }
        _status.value = _status.value.copy(
            volume = clamped,
            muted = if (clamped > 0.0) false else _status.value.muted,
        )
    }

    override fun setMuted(muted: Boolean) = onMain {
        if (initialized) MPVLib.setPropertyBoolean("mute", muted)
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
        if (initialized) {
            MPVLib.removeObserver(this)
            MPVLib.removeLogObserver(this)
            runCatching { MPVLib.detachSurface() }
            MPVLib.destroy()
        }
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
            MPVLib.attachSurface(surface)
            MPVLib.setPropertyString(
                "android-surface-size",
                "${width.coerceAtLeast(1)}x${height.coerceAtLeast(1)}",
            )
            MPVLib.setPropertyString("vo", "gpu")
            surfaceReady = true
            performPendingLoad()
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
            MPVLib.setPropertyString("android-surface-size", "${width.coerceAtLeast(1)}x${height.coerceAtLeast(1)}")
        }
    }

    internal fun onSurfaceDestroyed() = onMain {
        if (!initialized || !surfaceReady) return@onMain
        surfaceReady = false
        runCatching { MPVLib.setPropertyString("vo", "null") }
        runCatching { MPVLib.detachSurface() }
    }

    private fun ensureInitialized() {
        if (initialized) return
        MPVLib.create(appContext)
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
        setInitialOption("terminal", "no")
        setInitialOption("msg-level", "all=warn")
        setInitialOption(
            "screenshot-directory",
            (appContext.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
                ?: appContext.filesDir).absolutePath,
        )
        MPVLib.init()
        MPVLib.addObserver(this)
        MPVLib.addLogObserver(this)
        observeProperties()
        initialized = true
        pendingPreferences?.let(::applyPreferencesNow)
        pendingVolume?.let { MPVLib.setPropertyDouble("volume", it) }
        applyScaling(pendingScaling)
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
                }
                YoutubeDL.getInstance().getInfo(request).url
                    ?.takeIf(String::isNotBlank)
                    ?: error("yt-dlp returned no playable URL")
            }
            if (generation != loadGeneration.get()) return@launch
            resolved.fold(
                onSuccess = { loadResolved(it, startPositionSeconds, generation) },
                onFailure = { error ->
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
        DOUBLE_PROPERTIES.forEach { MPVLib.observeProperty(it, MPVLib.MPV_FORMAT_DOUBLE) }
        FLAG_PROPERTIES.forEach { MPVLib.observeProperty(it, MPVLib.MPV_FORMAT_FLAG) }
        STRING_PROPERTIES.forEach { MPVLib.observeProperty(it, MPVLib.MPV_FORMAT_STRING) }
    }

    private fun performPendingLoad() {
        val load = pendingLoad ?: return
        if (!initialized || !surfaceReady) return
        pendingLoad = null
        fileLoaded = false
        MPVLib.command(buildMpvLoadCommand(load.url, load.startPositionSeconds).toTypedArray())
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
            "eof-reached" -> current.copy(endReached = value && !stoppedByUser)
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
            MPVLib.MPV_EVENT_START_FILE -> {
                fileLoaded = false
                _status.value = _status.value.copy(
                    hasMedia = false,
                    fileLoaded = false,
                    endReached = false,
                    error = null,
                    statusMessage = "Opening stream…",
                )
            }
            MPVLib.MPV_EVENT_FILE_LOADED -> {
                stoppedByUser = false
                fileLoaded = true
                _status.value = _status.value
                    .withTracks(parseMpvTracks(trackListJson))
                    .copy(
                        hasMedia = true,
                        fileLoaded = true,
                        error = null,
                        statusMessage = "",
                        chapters = parseMpvChapters(chapterListJson),
                    )
            }
            MPVLib.MPV_EVENT_PLAYBACK_RESTART -> {
                if (fileLoaded) _status.value = _status.value.copy(hasMedia = true, waitingForData = false)
            }
            MPVLib.MPV_EVENT_END_FILE -> {
                if (playbackRequested && !stoppedByUser && !fileLoaded) {
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
        val error = if (level <= MPVLib.MPV_LOG_LEVEL_ERROR && playbackRequested && !stoppedByUser) {
            message
        } else {
            _status.value.error
        }
        _status.value = _status.value.copy(statusMessage = message, error = error)
    }

    private fun applyPreferencesNow(preferences: PlaybackPreferences) {
        if (preferences.audioLanguages.isNotEmpty()) {
            MPVLib.setPropertyString("alang", preferences.audioLanguages.joinToString(","))
        }
        if (preferences.subtitleLanguages.isNotEmpty()) {
            MPVLib.setPropertyString("slang", preferences.subtitleLanguages.joinToString(","))
        }
        MPVLib.setPropertyString("sid", if (preferences.subtitlesEnabled) "auto" else "no")
        MPVLib.setPropertyBoolean("mute", preferences.startMuted)
        MPVLib.setPropertyDouble("sub-scale", preferences.subtitleScale)
        MPVLib.setPropertyInt("sub-pos", preferences.subtitlePosition)
        MPVLib.setPropertyString(
            "sub-border-style",
            if (preferences.subtitleBackground) "opaque-box" else "outline-and-shadow",
        )
        MPVLib.setPropertyString("hwdec", if (preferences.hardwareDecoding) "mediacodec" else "no")
    }

    private fun applyScaling(scaling: VideoScaling) {
        val (keepAspect, panscan, zoom) = when (scaling) {
            VideoScaling.Fit -> Triple(true, 0.0, 0.0)
            VideoScaling.Fill -> Triple(true, 1.0, 0.0)
            VideoScaling.Zoom -> Triple(true, 0.0, 0.2)
            VideoScaling.Stretch -> Triple(false, 0.0, 0.0)
        }
        MPVLib.setPropertyBoolean("keepaspect", keepAspect)
        MPVLib.setPropertyDouble("panscan", panscan)
        MPVLib.setPropertyDouble("video-zoom", zoom)
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

    private fun setInitialOption(name: String, value: String) {
        check(MPVLib.setOptionString(name, value) >= 0) { "mpv rejected option $name" }
    }

    private fun setStringProperty(name: String, value: String) = onMain {
        if (initialized) MPVLib.setPropertyString(name, value)
    }

    private fun setDoubleProperty(name: String, value: Double) = onMain {
        if (initialized) MPVLib.setPropertyDouble(name, value)
    }

    private fun command(vararg args: String) {
        if (initialized) MPVLib.command(arrayOf(*args))
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    private data class PendingLoad(val url: String, val startPositionSeconds: Double)

    private companion object {
        const val SEEK_DEBOUNCE_MILLIS = 40L
        val SYSTEM_FONTS = listOf(
            "/system/fonts/Roboto-Regular.ttf",
            "/system/fonts/NotoSans-Regular.ttf",
            "/system/fonts/DroidSans.ttf",
        )
        val DOUBLE_PROPERTIES = listOf(
            "time-pos", "duration", "volume", "cache-buffering-state",
            "demuxer-cache-time", "demuxer-cache-duration", "speed", "sub-delay",
            "audio-delay", "frame-drop-count", "estimated-vf-fps", "video-bitrate",
        )
        val FLAG_PROPERTIES = listOf("pause", "mute", "paused-for-cache", "eof-reached")
        val STRING_PROPERTIES = listOf(
            "track-list", "chapter-list", "video-codec", "hwdec-current", "current-vo",
        )
    }
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
