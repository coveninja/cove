package com.coveninja.cove.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.view.TextureView
import dev.jdtech.mpv.MPVLib

/**
 * TextureView-based wrapper around MPVLib (dev.jdtech.mpv:libmpv:0.5.1).
 *
 * TextureView is used instead of SurfaceView to fix a compositing flicker
 * on real devices: transparent-WebView-over-SurfaceView triggers vendor
 * compositor quirks. TextureView is composited as a regular GL texture,
 * so the WebView above it is correctly blended with no tearing.
 *
 * 0.5.1 ships the classic static-method API: MPVLib.create(context),
 * MPVLib.init(), MPVLib.command([...]), MPVLib.observeProperty(name, fmt).
 * All constants (MPV_FORMAT_*, MPV_EVENT_*) are also static fields on MPVLib.
 *
 * Lifecycle:
 *   val view = MpvPlayerView(context)
 *   view.onSurfaceReady = { /* surface is live, safe to loadfile */ }
 *   view.onSurfaceDestroyed = { /* surface gone */ }
 *   view.create()                    // sets options + inits mpv; callbacks assigned before this
 *   MPVLib.addObserver(myObserver)
 *   // SurfaceTextureListener attaches/detaches the surface automatically
 *   view.destroy()                   // call from Activity.onDestroy()
 */
class MpvPlayerView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    /** Invoked on the main thread when the TextureView's surface is ready for rendering. */
    var onSurfaceReady: (() -> Unit)? = null

    /** Invoked on the main thread when the TextureView's surface is destroyed. */
    var onSurfaceDestroyed: (() -> Unit)? = null

    private var surface: Surface? = null

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

        // Network / cache: fast-start rationale — reduce readahead to 4 s so
        // playback begins quickly; disable pause-on-cache-empty at startup
        // (cache-pause-initial=no) and only pause when cache is critically low
        // (cache-pause-wait=2 s). keeps cache=yes and 32 MiB ceiling.
        MPVLib.setOptionString("cache", "yes")
        MPVLib.setOptionString("demuxer-max-bytes", "32MiB")
        MPVLib.setOptionString("demuxer-readahead-secs", "4")
        MPVLib.setOptionString("cache-pause-initial", "no")
        MPVLib.setOptionString("cache-pause-wait", "2")

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
        // track-list delivers audio+subtitle track metadata as a JSON string
        // so we can build the track-picker UI and perform language auto-select.
        MPVLib.observeProperty("track-list",       MPVLib.MPV_FORMAT_STRING)

        // TextureView fills the view with video; the WebView above provides UI
        // transparency. Marking opaque avoids a redundant alpha-compositing pass.
        isOpaque = true

        surfaceTextureListener = this
        Log.d(TAG, "MPV created and initialised")
    }

    /**
     * Queue a file for playback. Safe to call before the surface is ready —
     * mpv buffers the command and starts decoding once onSurfaceTextureAvailable fires.
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

    /** Set playback speed multiplier (0.5 … 2.0). */
    fun setSpeed(speed: Float) {
        MPVLib.setPropertyDouble("speed", speed.toDouble())
    }

    /** Release mpv. Must be called from Activity.onDestroy(). */
    fun destroy() {
        surfaceTextureListener = null
        try { MPVLib.detachSurface() } catch (_: Exception) {}
        surface?.release()
        surface = null
        MPVLib.destroy()
        Log.d(TAG, "MPV destroyed")
    }

    // ── TextureView.SurfaceTextureListener ───────────────────────────────────

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceTextureAvailable ${width}×${height}")
        surface = Surface(st)
        MPVLib.attachSurface(surface)
        MPVLib.setPropertyString("android-surface-size", "${width}x${height}")
        onSurfaceReady?.invoke()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceTextureSizeChanged ${width}×${height}")
        MPVLib.setPropertyString("android-surface-size", "${width}x${height}")
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        Log.d(TAG, "onSurfaceTextureDestroyed — detaching surface")
        onSurfaceDestroyed?.invoke()
        try { MPVLib.detachSurface() } catch (_: Exception) {}
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
        // no-op: frame updates are handled entirely by mpv's render thread
    }

    companion object {
        private const val TAG = "MpvPlayerView"
    }
}
