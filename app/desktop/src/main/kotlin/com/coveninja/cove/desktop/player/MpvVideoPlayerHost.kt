package com.coveninja.cove.desktop.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.coveninja.cove.shared.network.CoveJson
import com.coveninja.cove.ui.state.MediaTrack
import com.coveninja.cove.ui.state.PlaybackStatus
import com.coveninja.cove.ui.state.TrackKind
import com.coveninja.cove.ui.state.VideoScaling
import com.coveninja.cove.ui.state.VideoPlayerHost
import java.awt.image.BufferedImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bridges libmpv to the shared [VideoPlayerHost] contract that :ui codes against.
 *
 * The mpv handle is owned by [Surface]'s composition rather than by this object:
 * it is created when the player screen mounts and destroyed when it leaves, so an
 * idle app holds no decoder threads. Commands that arrive before the surface
 * exists — [load] in particular, which races the recomposition that mounts it —
 * are queued and replayed on attach.
 */
class MpvVideoPlayerHost(
    // The in-app renderer is always software (see Surface), so --software-renderer
    // only has the decoder left to turn off. Kept as an escape hatch for drivers
    // where copy-back hardware decoding misbehaves.
    private val softwareDecoding: Boolean = false,
) : VideoPlayerHost {

    private val _status = MutableStateFlow(PlaybackStatus())
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var player: DesktopPlayer? = null
    private var mirrorJob: Job? = null

    private var pendingLoad: PendingLoad? = null
    private var pendingVolume: Double? = null
    private var pendingScaling: VideoScaling? = null

    // Produced on mpv's render thread and consumed by the composition; a flow is
    // the defined handoff, where a raw snapshot write from a foreign thread would
    // rely on apply-notification timing.
    private val frames = MutableStateFlow<BufferedImage?>(null)

    override fun load(url: String, startPositionSeconds: Double) {
        val active = player
        if (active == null) {
            pendingLoad = PendingLoad(url, startPositionSeconds)
        } else {
            active.load(url, startPositionSeconds)
        }
    }

    override fun setPaused(paused: Boolean) {
        player?.setPaused(paused)
    }

    override fun togglePause() {
        player?.togglePause()
    }

    override fun seek(seconds: Double) {
        player?.seek(seconds.coerceAtLeast(0.0))
    }

    override fun setVolume(volume: Double) {
        val clamped = volume.coerceIn(0.0, 100.0)
        val active = player
        if (active == null) {
            pendingVolume = clamped
        } else {
            active.setVolume(clamped)
            // mpv's property poll runs on a 200 ms timer; reflecting the change now
            // keeps the volume slider from snapping back under the pointer.
            _status.value = _status.value.copy(volume = clamped)
        }
    }

    override fun setScaling(scaling: VideoScaling) {
        pendingScaling = scaling
        player?.applyScaling(scaling)
    }

    override fun selectAudioTrack(id: Int) {
        player?.selectAudioTrack(id)
    }

    override fun selectSubtitleTrack(id: Int?) {
        player?.selectSubtitleTrack(id)
    }

    override fun stop() {
        pendingLoad = null
        player?.stop()
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
     * error would all sit behind an opaque black rectangle. Reading frames back
     * and drawing them as an ImageBitmap costs a copy per frame but keeps one
     * compositor in charge of the entire window. The decode itself stays on the
     * GPU via hwdec=auto-copy.
     *
     * The OpenGL path is still the right choice for --play, which is a bare
     * window with nothing drawn on top; see StandalonePlayerWindow.
     */
    @Composable
    override fun Surface(modifier: Modifier) {
        val player = remember {
            MpvSoftwarePlayer(
                hardwareDecoding = !softwareDecoding,
                frameConsumer = { frame -> frames.value = frame },
            ).also { it.start() }
        }

        DisposableEffect(player) {
            attach(player)
            onDispose {
                detach()
                player.close()
                frames.value = null
            }
        }

        val frame by frames.collectAsState()
        Box(
            // mpv renders at exactly the size it is told and letterboxes inside
            // it, so the surface size is the render size.
            modifier = modifier.onSizeChanged { player.resize(it.width, it.height) },
        ) {
            frame?.let {
                Image(
                    bitmap = it.toComposeImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    // FillBounds, not the default Fit: mpv has already composed
                    // the picture into a buffer the size of this surface, aspect
                    // and cropping included. Fit would apply a second aspect
                    // policy on top and add bars mpv never intended — bars no
                    // scaling mode could then remove, because they are added
                    // after mpv is done.
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
    }

    private fun attach(active: DesktopPlayer) {
        player = active
        mirrorJob?.cancel()
        mirrorJob = scope.launch {
            active.snapshot.collect { snapshot -> _status.value = snapshot.toPlaybackStatus() }
        }
        pendingVolume?.let { active.setVolume(it) }
        pendingVolume = null
        // Re-applied on attach: mpv resets these with the handle, so a mode
        // chosen before the surface existed would otherwise be forgotten.
        pendingScaling?.let(active::applyScaling)
        pendingLoad?.let { active.load(it.url, it.startPositionSeconds) }
        pendingLoad = null
    }

    private fun detach() {
        mirrorJob?.cancel()
        mirrorJob = null
        player = null
        _status.value = PlaybackStatus()
    }

    /** Releases the mirroring scope. The mpv handle itself follows [Surface]'s composition. */
    fun dispose() {
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

/** The one place the display modes become mpv's three knobs. */
private fun DesktopPlayer.applyScaling(scaling: VideoScaling) = when (scaling) {
    VideoScaling.Fit -> setScaling(keepAspect = true, panscan = 0.0, zoom = 0.0)
    VideoScaling.Fill -> setScaling(keepAspect = true, panscan = 1.0, zoom = 0.0)
    VideoScaling.Zoom -> setScaling(keepAspect = true, panscan = 0.0, zoom = 0.2)
    VideoScaling.Stretch -> setScaling(keepAspect = false, panscan = 0.0, zoom = 0.0)
}

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
    bufferingPercent = cacheBufferingPercent,
    waitingForData = pausedForCache,
    fileLoaded = fileLoaded,
    statusMessage = lastMessage,
    audioTracks = audio,
    subtitleTracks = subtitles,
    selectedAudioId = audio.firstOrNull { it.selected }?.id,
    selectedSubtitleId = subtitles.firstOrNull { it.selected }?.id,
    error = error,
)
