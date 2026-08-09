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
data class PlaybackStatus(
    val hasMedia: Boolean = false,
    val paused: Boolean = true,
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    /** 0..100, matching mpv. Not the 0..1 scale AppSettings.defaultVolume uses. */
    val volume: Double = 100.0,
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
