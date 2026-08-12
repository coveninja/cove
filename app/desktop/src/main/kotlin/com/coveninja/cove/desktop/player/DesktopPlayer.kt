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
     * mpv's mute flag. Separate from [setVolume] because they are separate in mpv:
     * raising the volume of a muted handle changes nothing audible.
     */
    fun setMuted(muted: Boolean)

    /**
     * Sets an mpv option by name. Deliberately untyped: the caller is translating
     * user preferences into mpv's own vocabulary, and a typed method per option
     * would hide that without making it safer.
     */
    fun setOption(name: String, value: String)

    /** Loads an external subtitle file or URL alongside the current media. */
    fun addSubtitle(url: String, title: String, language: String)

    /**
     * mpv's keepaspect/panscan/video-zoom, which crop and scale properly rather
     * than letting the surface stretch a finished frame.
     */
    fun setScaling(keepAspect: Boolean, panscan: Double, zoom: Double)

    /** mpv's aid/sid. Null sid switches subtitles off. */
    fun selectAudioTrack(id: Int)
    fun selectSubtitleTrack(id: Int?)
    fun stop()

    /**
     * Runs a raw mpv command. Untyped for the same reason [setOption] is: chapter
     * stepping, frame stepping, screenshots and track cycling are each one word of
     * mpv's own vocabulary, and a method apiece would only restate them.
     */
    fun command(vararg args: String)
}
