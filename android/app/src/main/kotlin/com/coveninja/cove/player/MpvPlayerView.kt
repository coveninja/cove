package com.coveninja.cove.player

import android.content.Context
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.jdtech.mpv.MPVLib
import java.io.File

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

        // Subtitle fonts: Android has no fontconfig, so libass starts with ZERO
        // fonts — text subtitles (SRT and most ASS) get selected and "rendered"
        // but no glyph ever appears. mpv loads font files from the `fonts/`
        // subdirectory of its config dir, so stage a system font there once and
        // point config-dir at it. (Copying ONE font, not sub-fonts-dir=/system/fonts:
        // libass reads memory fonts whole, and the full system set is >100 MB.)
        setupSubtitleFont()

        // Video: GPU path with Android context. hwdec is pinned to direct
        // mediacodec (surface output), NOT auto: auto prefers mediacodec-copy,
        // whose surfaceless ByteBuffer mode hard-fails on MediaTek decoders for
        // 10-bit HEVC ("MediaCodec 0x0 failed to start") and drops the whole
        // stream to software decode, which mid-range SoCs can't sustain. Direct
        // mediacodec still falls back to software automatically when the codec
        // is unavailable (e.g. the x86_64 emulator under Swiftshader).
        MPVLib.setOptionString("vo", "gpu")
        MPVLib.setOptionString("gpu-context", "android")
        // Dumb mode disables FBOs/PBOs and the advanced upload/render paths.
        // Budget Mali/MediaTek drivers (e.g. Mali-G52 on mt6768) mis-render
        // mpv's default pipeline to a black surface; dumb mode's plain
        // texture-and-blit path is what those drivers can actually run.
        MPVLib.setOptionString("gpu-dumb-mode", "yes")
        MPVLib.setOptionString("hwdec", "mediacodec")
        MPVLib.setOptionString("hwdec-codecs", "h264,hevc,vp8,vp9,av1")

        // Audio: audiotrack is Android's native path
        MPVLib.setOptionString("ao", "audiotrack")

        // Window / EOF: keep-open prevents the player closing on end-of-file
        MPVLib.setOptionString("force-window", "yes")
        MPVLib.setOptionString("keep-open", "yes")

        // Network / cache: start playback after ~4s of buffer instead of a
        // 20s pre-fill; the 32MiB demuxer buffer keeps filling ahead after
        // start, and cache-pause-wait bounds rebuffer pauses.
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

    /**
     * Stage a default subtitle font under mpv's config dir and enable it.
     * Runs between MPVLib.create() and MPVLib.init() — config-dir is an
     * init-time option. Best-effort: on failure mpv still plays video,
     * subtitles just stay glyph-less as before.
     */
    private fun setupSubtitleFont() {
        try {
            val configDir = File(context.filesDir, "mpv")
            val fontsDir = File(configDir, "fonts").apply { mkdirs() }
            // subfont.ttf at the config-dir ROOT is load-bearing: mpv passes
            // that exact file to libass as the default/fallback font, which is
            // what actually renders SRT-style "sans-serif" subs — memory fonts
            // from fonts/ are matched strictly by family name ("Roboto") and
            // provide no fallback, so alone they still render zero glyphs
            // ("fontselect: failed to find any fallback with glyph 0x0").
            // The fonts/ copy stays so ASS subs can match a real family too.
            val rootFont = File(configDir, "subfont.ttf")
            val familyFont = File(fontsDir, "subfont.ttf")
            if (!rootFont.exists() || rootFont.length() == 0L) {
                // Roboto ships on all AOSP-derived devices; DroidSans is a
                // legacy alias kept as fallback on some vendor skins.
                val source = listOf(
                    "/system/fonts/Roboto-Regular.ttf",
                    "/system/fonts/DroidSans.ttf",
                    "/system/fonts/NotoSans-Regular.ttf",
                ).map(::File).firstOrNull { it.canRead() }
                if (source == null) {
                    Log.w(TAG, "no readable system font found — subtitles will not render")
                    return
                }
                source.copyTo(rootFont, overwrite = true)
                source.copyTo(familyFont, overwrite = true)
                Log.d(TAG, "staged subtitle font from ${source.path}")
            }
            MPVLib.setOptionString("config", "yes")
            MPVLib.setOptionString("config-dir", configDir.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "subtitle font setup failed: ${e.message}")
        }
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
        holder.removeCallback(this)
        try { MPVLib.detachSurface() } catch (_: Exception) {}
        MPVLib.destroy()
        Log.d(TAG, "MPV destroyed")
    }

    // ── SurfaceHolder.Callback ────────────────────────────────────────────────

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceCreated — attaching surface")
        MPVLib.attachSurface(holder.surface)
        MPVLib.setPropertyString("android-surface-size",
            "${holder.surfaceFrame.width()}x${holder.surfaceFrame.height()}")
        // With hwdec=mediacodec (direct), the MediaCodec decoder is bound to the
        // Surface. After a background/resume cycle a new Surface arrives here; we
        // must re-assert the window and vo so mpv binds the decoder to the new one
        // rather than continuing to write into the previous (now-dead) surface,
        // which would cause black video with continuing audio until the next loadfile.
        try { MPVLib.setPropertyString("force-window", "yes") } catch (_: Exception) {}
        try { MPVLib.setPropertyString("vo", "gpu") } catch (_: Exception) {}
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "surfaceChanged ${width}×${height}")
        MPVLib.setPropertyString("android-surface-size", "${width}x${height}")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceDestroyed — detaching surface")
        // With hwdec=mediacodec (direct), the decoder output is bound to this Surface.
        // Setting vo=null tears down the vo_gpu path and releases the MediaCodec bound
        // to the dying surface *before* detachSurface() removes it from mpv's view.
        // Without this, mpv holds a reference to the dead surface and black video
        // results on resume until the next loadfile forces a decoder re-init.
        try { MPVLib.setPropertyString("vo", "null") } catch (_: Exception) {}
        try { MPVLib.detachSurface() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "MpvPlayerView"
    }
}
