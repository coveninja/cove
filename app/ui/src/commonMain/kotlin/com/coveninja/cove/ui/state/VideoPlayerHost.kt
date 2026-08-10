package com.coveninja.cove.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/**
 * What the player UI needs to know about the video, independent of how it is
 * decoded. Mirrors the desktop PlayerSnapshot; :ui is a Kotlin Multiplatform
 * module and cannot see :desktop, where libmpv lives.
 */
/**
 * How the picture is mapped onto the window.
 *
 * [Fit] is the default and the only one that shows the whole frame: the others
 * either crop or distort, which is a choice the viewer makes, never one made for
 * them.
 */
enum class VideoScaling(val label: String, val description: String) {
    Fit("Fit", "Whole picture, bars if the shapes differ"),
    Fill("Fill", "Fills the window, crops the overflow"),
    Zoom("Zoom", "Slightly enlarged"),
    Stretch("Stretch", "Fills the window, distorts the picture"),
}

/** One selectable audio or subtitle track reported by the player. */
data class MediaTrack(
    val id: Int,
    val kind: TrackKind,
    val title: String,
    val language: String?,
    val selected: Boolean,
) {
    val label: String
        get() = listOfNotNull(
            title.takeIf { it.isNotBlank() },
            language?.uppercase()?.takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifBlank { "Track $id" }
}

enum class TrackKind { Audio, Subtitle }

data class PlaybackStatus(
    val hasMedia: Boolean = false,
    val paused: Boolean = true,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    /** 0..100, matching mpv. Not the 0..1 scale AppSettings.defaultVolume uses. */
    val volume: Double = 100.0,
    /**
     * Independent of [volume]: a muted player at volume 100 is silent. The "start
     * muted" setting mutes at load, so the UI has to read this rather than infer
     * silence from the volume alone.
     */
    val muted: Boolean = false,
    /** How full the player's read-ahead buffer is, 0-100, while it is filling. */
    val bufferingPercent: Int = 0,
    /** The player has data to decode but is stalled waiting for more. */
    val waitingForData: Boolean = false,
    /** The file is open and its duration is known — not merely accepted for loading. */
    val fileLoaded: Boolean = false,
    /** Whatever the player last reported; the only detail available while opening. */
    val statusMessage: String = "",
    /** Playback ran to the end, as opposed to being paused near it. */
    val endReached: Boolean = false,
    /** 1.0 is normal speed. */
    val speed: Double = 1.0,
    val audioTracks: List<MediaTrack> = emptyList(),
    val subtitleTracks: List<MediaTrack> = emptyList(),
    /** Null when subtitles are switched off. */
    val selectedSubtitleId: Int? = null,
    val selectedAudioId: Int? = null,
    val error: String? = null,
) {
    val progressFraction: Float
        get() = if (durationSeconds > 0.0) {
            (positionSeconds / durationSeconds).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }
}

/**
 * A video surface plus transport controls, implemented per platform. The desktop
 * implementation wraps libmpv; there is no Android implementation yet.
 *
 * [Surface] is a composable rather than a plain view handle because the desktop
 * OpenGL path has to be hosted in a SwingPanel, which only exists inside a
 * composition.
 */
@Stable
interface VideoPlayerHost {
    val status: StateFlow<PlaybackStatus>

    /** Starts [url] from [startPositionSeconds]. Replaces whatever was playing. */
    fun load(url: String, startPositionSeconds: Double = 0.0)
    fun setPaused(paused: Boolean)
    fun togglePause()
    fun seek(seconds: Double)

    /** [volume] is 0..100, matching mpv. */
    fun setVolume(volume: Double)

    /** Toggles the player's own mute flag, which [setVolume] does not affect. */
    fun setMuted(muted: Boolean)

    fun selectAudioTrack(id: Int)

    fun setScaling(scaling: VideoScaling)

    fun setSpeed(speed: Double)

    /** Applied before loading, so the player picks the right tracks first time. */
    fun applyPreferences(preferences: PlaybackPreferences)

    /** Adds an external subtitle; it then appears in [PlaybackStatus.subtitleTracks]. */
    fun addSubtitle(url: String, title: String, language: String)

    /** Null switches subtitles off. */
    fun selectSubtitleTrack(id: Int?)
    fun stop()

    @Composable
    fun Surface(modifier: Modifier)
}

/**
 * Null wherever no player is available — currently every target except desktop.
 * The Watch button reports that playback is unavailable rather than crashing, so
 * :mobile keeps building against the same :ui module.
 */
val LocalVideoPlayerHost = staticCompositionLocalOf<VideoPlayerHost?> { null }

/**
 * Window-level fullscreen, which only a desktop window has. Null everywhere
 * else — mobile players are already fullscreen, so the control is simply absent
 * rather than present and inert.
 */
@Stable
interface FullscreenController {
    val isFullscreen: StateFlow<Boolean>
    fun toggle()
}

val LocalFullscreenController = staticCompositionLocalOf<FullscreenController?> { null }
