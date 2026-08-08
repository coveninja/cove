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
    fun stop()
}
