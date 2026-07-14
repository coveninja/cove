package com.coveninja.cove.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.jdtech.mpv.MPVLib
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Bridges the Svelte web layer (player.svelte.ts) to native libmpv by
 * injecting a QWebChannel-compatible JS shim and forwarding MPVLib events
 * as signal dispatches into the WebView.
 *
 * Threading contract
 * ──────────────────
 * MPVLib observer callbacks arrive on an arbitrary native thread.
 * JavascriptInterface methods (JsInterface) arrive on a Binder thread.
 * Both are posted to mainHandler before touching MPVLib, View state,
 * or evaluateJavascript — all three require the main thread.
 *
 * JS contract (matches qt/src/MpvObject.h exactly)
 * ─────────────────────────────────────────────────
 * window.qt.webChannelTransport  — truthy sentinel
 * window.QWebChannel(t, cb)      — cb receives {objects:{mpv}} async (setTimeout 0)
 * window.__mpvEmit(name, ...args) — internal signal dispatcher
 *
 * mpv adapter methods:  play, pause, resume, stop, seek, setAudioTrack,
 *   setSubtitleTrack, addSubtitle, setVolume, setFullscreen, requestState,
 *   command, setOption, setMpvProperty
 * mpv adapter signals:  positionChanged, durationChanged, pausedChanged,
 *   volumeChanged, fileLoaded, endReached, tracksChanged
 */
class MpvBridge(
    private val mpvView: MpvPlayerView,
    private val webView: WebView,
    private val listener: PlatformListener,
) : MPVLib.EventObserver {

    // ── Platform callbacks (invoked on main thread) ───────────────────────────

    interface PlatformListener {
        /** A new file finished loading; mediaTitle may be empty if mpv hasn't emitted it yet. */
        fun onFileLoaded(mediaTitle: String)
        fun onPausedChanged(paused: Boolean)
        /** Throttle-driven: called on every time-pos event. */
        fun onPositionChanged(seconds: Double)
        fun onDurationChanged(seconds: Double)
        /** JS called stop() — surface hidden, session should become stopped. */
        fun onStopped()
        /** JS called setFullscreen(). */
        fun onFullscreenRequested(fullscreen: Boolean)
    }

    // ── Public JS interface (registered as "CoveMpv" by the Activity) ─────────
    val jsInterface = JsInterface()

    // ── Cached state for requestState() re-emit ───────────────────────────────
    private var currentPos   = 0.0
    private var currentDur   = 0.0
    private var isPaused     = true
    private var currentVol   = 100.0
    private var tracksJson   = "[]"   // pre-serialised, safe for direct JS interpolation
    private var mediaTitle   = ""

    /**
     * Set in stop() and cleared in play() + MPV_EVENT_FILE_LOADED.
     * Guards endReached so a programmatic stop() never fires the signal
     * (only genuine EOF does).
     */
    private var stoppedByUser = false

    // ── Surface-readiness guard ───────────────────────────────────────────────
    // Unlike SurfaceView, a TextureView creates its SurfaceTexture regardless of
    // visibility. However we still gate loadfile on surface availability so that
    // play() issued before the first onSurfaceTextureAvailable callback defers
    // the command correctly. We still set the view INVISIBLE when stopped and
    // VISIBLE on play/FILE_LOADED to hide the video layer when not in use.
    private var surfaceReady   = false
    private var pendingPlayUrl: String? = null

    private var destroyed = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── JS shim ───────────────────────────────────────────────────────────────
    //
    // Injected via WebViewCompat.addDocumentStartJavaScript (preferred, runs
    // before any page script) or evaluateJavascript in onPageStarted (fallback).
    //
    // window.QWebChannel uses setTimeout(0) so that all mpv.*.connect() calls
    // in player.svelte.ts complete before the bridge fires requestState().

    private val SHIM_JS: String = """
window.__covePlatform='android';
window.__coveApp={minimizeApp:function(){CoveApp.minimizeApp();}};
(function(){
  var h={};
  window.__mpvEmit=function(name){
    var a=Array.prototype.slice.call(arguments,1);
    var c=h[name];if(!c)return;
    for(var i=0;i<c.length;i++){try{c[i].apply(null,a);}catch(e){}}
  };
  function sig(n){
    return{
      connect:function(cb){if(!h[n])h[n]=[];h[n].push(cb);},
      disconnect:function(cb){if(!h[n])return;var i=h[n].indexOf(cb);if(i>=0)h[n].splice(i,1);}
    };
  }
  var mpv={
    positionChanged:sig('positionChanged'),
    durationChanged:sig('durationChanged'),
    pausedChanged:sig('pausedChanged'),
    volumeChanged:sig('volumeChanged'),
    fileLoaded:sig('fileLoaded'),
    endReached:sig('endReached'),
    tracksChanged:sig('tracksChanged'),
    play:            function(u)     { CoveMpv.play(u); },
    pause:           function()      { CoveMpv.pause(); },
    resume:          function()      { CoveMpv.resume(); },
    stop:            function()      { CoveMpv.stop(); },
    seek:            function(s)     { CoveMpv.seek(s); },
    setAudioTrack:   function(id)    { CoveMpv.setAudioTrack(id); },
    setSubtitleTrack:function(id)    { CoveMpv.setSubtitleTrack(id); },
    addSubtitle:     function(u,t,l) { CoveMpv.addSubtitle(u,t,l); },
    setVolume:       function(v)     { CoveMpv.setVolume(v); },
    setFullscreen:   function(f)     { CoveMpv.setFullscreen(f); },
    requestState:    function()      { CoveMpv.requestState(); },
    command:         function(a)     { CoveMpv.command(JSON.stringify(a)); },
    setOption:       function(k,v)   { CoveMpv.setOption(k,v); },
    setMpvProperty:  function(k,v)   { CoveMpv.setMpvProperty(k,v); }
  };
  window.qt={webChannelTransport:{}};
  window.QWebChannel=function(t,cb){setTimeout(function(){cb({objects:{mpv:mpv}});},0);};
})();
""".trimIndent()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Initialise mpv and attach the JS shim. Must be called on the main thread
     * BEFORE [webView].loadUrl().
     *
     * MpvPlayerView already observes: time-pos, duration, pause, eof-reached,
     * paused-for-cache, track-list. We additionally observe volume and
     * media-title here — do NOT double-observe the ones above.
     */
    fun create() {
        mpvView.create()
        MPVLib.addObserver(this)
        MPVLib.observeProperty("volume",      MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("media-title", MPVLib.MPV_FORMAT_STRING)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, SHIM_JS, setOf("*"))
        }
        // Fallback: Activity calls injectShimFallback() from WebViewClient.onPageStarted
        // when DOCUMENT_START_SCRIPT is unavailable.

        // Wire MpvPlayerView's TextureView callbacks so we know when the surface
        // is ready for MPVLib commands. MpvPlayerView.create() has already called
        // MPVLib.attachSurface() in onSurfaceTextureAvailable before invoking
        // onSurfaceReady, so issuing MPVLib.command() here is safe.
        // Callbacks are invoked on the main thread by the TextureView machinery.
        mpvView.onSurfaceReady = {
            if (!destroyed) {
                surfaceReady = true
                pendingPlayUrl?.let { url ->
                    pendingPlayUrl = null
                    MPVLib.command(arrayOf("loadfile", url))
                }
            }
        }
        mpvView.onSurfaceDestroyed = {
            surfaceReady = false
        }
    }

    /** Fallback shim injection — called by the Activity's onPageStarted. */
    fun injectShimFallback() {
        webView.evaluateJavascript(SHIM_JS, null)
    }

    /** Release mpv and stop dispatching events. Safe to call multiple times. */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        MPVLib.removeObserver(this)
        mpvView.destroy()
    }

    // ── Main-thread helpers for MediaSession callbacks ────────────────────────

    fun resumeOnMain() {
        mainHandler.post { MPVLib.setPropertyBoolean("pause", false) }
    }

    fun pauseOnMain() {
        mainHandler.post { MPVLib.setPropertyBoolean("pause", true) }
    }

    fun seekOnMain(seconds: Double) {
        mainHandler.post {
            MPVLib.command(arrayOf("seek",
                String.format(Locale.US, "%.3f", seconds), "absolute"))
        }
    }

    // ── MPVLib.EventObserver ──────────────────────────────────────────────────

    override fun eventProperty(name: String) {}
    override fun eventProperty(name: String, value: Long) {}

    override fun eventProperty(name: String, value: Double) {
        mainHandler.post {
            if (destroyed) return@post
            when (name) {
                "time-pos" -> {
                    currentPos = value
                    emit("positionChanged", value.toString())
                    listener.onPositionChanged(value)
                }
                "duration" -> {
                    currentDur = value
                    emit("durationChanged", value.toString())
                    listener.onDurationChanged(value)
                }
                "volume" -> {
                    currentVol = value
                    emit("volumeChanged", value.toString())
                }
            }
        }
    }

    override fun eventProperty(name: String, value: Boolean) {
        mainHandler.post {
            if (destroyed) return@post
            when (name) {
                "pause" -> {
                    isPaused = value
                    emit("pausedChanged", if (value) "true" else "false")
                    listener.onPausedChanged(value)
                }
                "eof-reached" -> {
                    // Only genuine EOF — guard with stoppedByUser so programmatic
                    // stop() (which also triggers eof-reached) doesn't fire endReached.
                    if (value && !stoppedByUser) {
                        emitNoArgs("endReached")
                    }
                }
            }
        }
    }

    override fun eventProperty(name: String, value: String) {
        mainHandler.post {
            if (destroyed) return@post
            when (name) {
                "track-list" -> {
                    tracksJson = normalizeTrackList(value)
                    emitTracksChanged()
                }
                "media-title" -> {
                    mediaTitle = value
                }
            }
        }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MPV_EVENT_FILE_LOADED -> {
                mainHandler.post {
                    if (destroyed) return@post
                    stoppedByUser = false
                    mpvView.visibility = View.VISIBLE
                    emitNoArgs("fileLoaded")
                    emitTracksChanged()           // track-list may have arrived already
                    listener.onFileLoaded(mediaTitle)
                }
            }
        }
    }

    // ── Emit helpers (must be called on main thread) ──────────────────────────

    private fun emit(signal: String, jsArg: String) {
        webView.evaluateJavascript(
            "window.__mpvEmit&&window.__mpvEmit('$signal',$jsArg)", null)
    }

    private fun emitNoArgs(signal: String) {
        webView.evaluateJavascript(
            "window.__mpvEmit&&window.__mpvEmit('$signal')", null)
    }

    private fun emitTracksChanged() {
        // tracksJson is a pre-serialised JSON array — safe to interpolate directly
        // (contains only numeric/boolean/string values with no free-text injection risk).
        webView.evaluateJavascript(
            "window.__mpvEmit&&window.__mpvEmit('tracksChanged',$tracksJson)", null)
    }

    // ── Track-list normalisation ──────────────────────────────────────────────
    //
    // mpv's track-list JSON contains id/type/title/lang/selected plus many other
    // fields (codec, default, forced, …). We normalise to the minimal shape the
    // JS consumer expects, filling in defaults for absent fields.

    private fun normalizeTrackList(json: String): String {
        return try {
            val src = JSONArray(json)
            val out = JSONArray()
            for (i in 0 until src.length()) {
                val obj  = src.getJSONObject(i)
                val type = obj.optString("type")
                if (type != "video" && type != "audio" && type != "sub") continue
                out.put(JSONObject().apply {
                    put("id",       obj.optInt("id"))
                    put("type",     type)
                    put("title",    obj.optString("title", ""))
                    put("lang",     obj.optString("lang",  ""))
                    put("selected", obj.optBoolean("selected", false))
                })
            }
            out.toString()
        } catch (e: Exception) {
            Log.w(TAG, "normalizeTrackList failed: ${e.message}")
            "[]"
        }
    }

    // ── JavascriptInterface (Binder thread → mainHandler) ─────────────────────

    inner class JsInterface {

        @JavascriptInterface
        fun play(url: String) {
            mainHandler.post {
                stoppedByUser = false
                // Make the surface visible BEFORE issuing loadfile so that
                // surfaceCreated fires (an INVISIBLE SurfaceView never creates
                // its surface, which would prevent vo=gpu from initialising).
                mpvView.visibility = View.VISIBLE
                if (surfaceReady) {
                    MPVLib.command(arrayOf("loadfile", url))
                } else {
                    // Surface not yet ready — defer until surfaceCreated fires.
                    pendingPlayUrl = url
                }
            }
        }

        @JavascriptInterface
        fun pause() {
            mainHandler.post { MPVLib.setPropertyBoolean("pause", true) }
        }

        @JavascriptInterface
        fun resume() {
            mainHandler.post { MPVLib.setPropertyBoolean("pause", false) }
        }

        @JavascriptInterface
        fun stop() {
            mainHandler.post {
                stoppedByUser = true
                pendingPlayUrl = null
                MPVLib.command(arrayOf("stop"))
                mpvView.visibility = View.INVISIBLE
                listener.onStopped()
            }
        }

        @JavascriptInterface
        fun seek(seconds: Double) {
            mainHandler.post {
                MPVLib.command(arrayOf("seek",
                    String.format(Locale.US, "%.3f", seconds), "absolute"))
            }
        }

        @JavascriptInterface
        fun setAudioTrack(id: Int) {
            mainHandler.post { MPVLib.command(arrayOf("set", "aid", id.toString())) }
        }

        @JavascriptInterface
        fun setSubtitleTrack(id: Int) {
            mainHandler.post {
                if (id < 0) MPVLib.command(arrayOf("set", "sid", "no"))
                else        MPVLib.command(arrayOf("set", "sid", id.toString()))
            }
        }

        /**
         * Load and immediately select an external subtitle track.
         * Uses flag "select" (not "cached") so the added sub becomes active —
         * matches the desktop MpvObject::addSubtitle() behaviour.
         */
        @JavascriptInterface
        fun addSubtitle(url: String, title: String, lang: String) {
            mainHandler.post {
                MPVLib.command(arrayOf("sub-add", url, "select", title, lang))
            }
        }

        @JavascriptInterface
        fun setVolume(volume: Double) {
            mainHandler.post { MPVLib.setPropertyDouble("volume", volume) }
        }

        @JavascriptInterface
        fun setFullscreen(fullscreen: Boolean) {
            mainHandler.post { listener.onFullscreenRequested(fullscreen) }
        }

        /**
         * Re-emit cached position, duration, paused, volume, and tracks.
         * Called by the Svelte player right after connect() because the initial
         * property-change events predate the JS handshake.
         */
        @JavascriptInterface
        fun requestState() {
            mainHandler.post {
                if (destroyed) return@post
                emit("positionChanged", currentPos.toString())
                emit("durationChanged", currentDur.toString())
                emit("pausedChanged",   if (isPaused) "true" else "false")
                emit("volumeChanged",   currentVol.toString())
                emitTracksChanged()
            }
        }

        /** Generic mpv command — JS passes a JSON array, e.g. ["set","sub-delay","0.5"]. */
        @JavascriptInterface
        fun command(argsJson: String) {
            mainHandler.post {
                try {
                    val arr  = JSONArray(argsJson)
                    val args = Array(arr.length()) { i -> arr.getString(i) }
                    MPVLib.command(args)
                } catch (e: Exception) {
                    Log.w(TAG, "command() JSON parse failed: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun setOption(k: String, v: String) {
            mainHandler.post { MPVLib.setOptionString(k, v) }
        }

        @JavascriptInterface
        fun setMpvProperty(k: String, v: String) {
            mainHandler.post { MPVLib.setPropertyString(k, v) }
        }
    }

    companion object {
        private const val TAG = "MpvBridge"
    }
}
