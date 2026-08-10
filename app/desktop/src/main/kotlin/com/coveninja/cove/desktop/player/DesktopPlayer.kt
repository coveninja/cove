package com.coveninja.cove.desktop.player

import kotlinx.coroutines.flow.StateFlow

interface DesktopPlayer : AutoCloseable {
    val snapshot: StateFlow<PlayerSnapshot>

    fun start()
    fun load(source: String, startPositionSeconds: Double = 0.0)
    fun togglePause()
    fun setPaused(paused: Boolean)
    fun seek(seconds: Double)
    fun setVolume(volume: Double)

    /**
     * mpv's keepaspect/panscan/video-zoom, which crop and scale properly rather
     * than letting the surface stretch a finished frame.
     */
    fun setScaling(keepAspect: Boolean, panscan: Double, zoom: Double)

    /** mpv's aid/sid. Null sid switches subtitles off. */
    fun selectAudioTrack(id: Int)
    fun selectSubtitleTrack(id: Int?)
    fun stop()
}
