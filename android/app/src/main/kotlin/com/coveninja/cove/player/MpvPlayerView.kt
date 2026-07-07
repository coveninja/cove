package com.coveninja.cove.player

import android.content.Context
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.jdtech.mpv.MPVLib

/**
 * SurfaceView-based wrapper around MPVLib (dev.jdtech.mpv:libmpv:0.5.1).
 *
 * 0.5.1 ships the classic static-method API: MPVLib.create(context),
 * MPVLib.init(), MPVLib.command([...]), MPVLib.observeProperty(name, fmt).
 * All constants (MPV_FORMAT_*, MPV_EVENT_*) are also static fields on MPVLib.
 *
 * Lifecycle:
 *   val view = MpvPlayerView(context)
 *   view.create()                    // sets options + inits mpv
 *   MPVLib.addObserver(myObserver)
 *   // SurfaceHolder.Callback attaches/detaches the surface automatically
 *   view.loadFile(url, headers)      // mpv starts once surface attaches
 *   view.destroy()                   // call from Activity.onDestroy()
 */
class MpvPlayerView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    /** Create and initialise the MPV instance. Must be called before any other method. */
    fun create() {
        MPVLib.create(context)

        // Video: GPU path with Android context; hwdec=auto falls back to software
        // automatically — important for the x86_64 emulator (Swiftshader).
        MPVLib.setOptionString("vo", "gpu")
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("hwdec", "auto")
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,vp8,vp9,av1")

        // Audio: audiotrack is Android's native path
        MPVLib.setOptionString("ao", "audiotrack")

        // Window / EOF: keep-open prevents the player closing on end-of-file
        MPVLib.setOptionString("force-window", "yes")
        MPVLib.setOptionString("keep-open", "yes")

        // Network / cache: modest values for mobile
        MPVLib.setOptionString("cache", "yes")
        MPVLib.setOptionString("demuxer-max-bytes", "32MiB")
        MPVLib.setOptionString("demuxer-readahead-secs", "20")

        // Suppress verbose log output
        MPVLib.setOptionString("terminal", "no")
        MPVLib.setOptionString("msg-level", "all=warn")

        MPVLib.init()

        // Observe properties to drive the UI and progress saving.
        // Constants are static on MPVLib: MPV_FORMAT_DOUBLE, MPV_FORMAT_FLAG.
        MPVLib.observeProperty("time-pos",         MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("duration",         MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("pause",            MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("eof-reached",      MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("paused-for-cache", MPVLib.MPV_FORMAT_FLAG)

        holder.addCallback(this)
        Log.d(TAG, "MPV created and initialised")
    }

    /**
     * Queue a file for playback. Safe to call before the surface is ready —
     * mpv buffers the command and starts decoding once surfaceCreated fires.
     *
     * @param url     Direct HTTP URL, or the backend's /api/play?hash=… URL.
     * @param headers Optional "Key: Value\n…" string for http-header-fields.
     */
    fun loadFile(url: String, headers: String? = null) {
        if (!headers.isNullOrBlank()) {
            MPVLib.setPropertyString("http-header-fields", headers)
        }
        MPVLib.command(arrayOf("loadfile", url))
        Log.d(TAG, "loadfile → $url")
    }

    fun pause()       = MPVLib.setPropertyBoolean("pause", true)
    fun play()        = MPVLib.setPropertyBoolean("pause", false)

    fun togglePause() {
        val paused = MPVLib.getPropertyBoolean("pause") ?: false
        MPVLib.setPropertyBoolean("pause", !paused)
    }

    fun seekTo(seconds: Double) {
        MPVLib.command(arrayOf("seek", seconds.toLong().toString(), "absolute"))
    }

    /** Release mpv. Must be called from Activity.onDestroy(). */
    fun destroy() {
        holder.removeCallback(this)
        try { MPVLib.detachSurface() } catch (_: Exception) {}
        MPVLib.destroy()
        Log.d(TAG, "MPV destroyed")
    }

    // ── SurfaceHolder.Callback ────────────────────────────────────────────────

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceCreated — attaching surface")
        MPVLib.attachSurface(holder.surface)
        // Tell mpv to start rendering if it was waiting for a surface
        MPVLib.setPropertyString("android-surface-size",
            "${holder.surfaceFrame.width()}x${holder.surfaceFrame.height()}")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "surfaceChanged ${width}×${height}")
        MPVLib.setPropertyString("android-surface-size", "${width}x${height}")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceDestroyed — detaching surface")
        try { MPVLib.detachSurface() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "MpvPlayerView"
    }
}
