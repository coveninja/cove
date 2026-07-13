package com.coveninja.cove

import android.Manifest
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewFeature
import com.coveninja.cove.player.MpvBridge
import com.coveninja.cove.player.MpvPlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class WebViewActivity : ComponentActivity() {

    private var webView: WebView? = null

    // ── M3/M4: mpv + bridge ───────────────────────────────────────────────────
    private var mpvView: MpvPlayerView? = null
    private var bridge:  MpvBridge?     = null

    // ── M4: MediaSession + audio focus ────────────────────────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wasPausedByFocusLoss = false
    private var mediaSession: MediaSessionCompat? = null

    // Cached playback state used by updateSessionPlaybackState()
    private var sessionPos    = 0.0
    private var sessionDur    = 0.0
    private var sessionPaused = true
    private var lastSessionPushMs = 0L   // throttle: push at most once per second

    // ── Safe-area insets (CSS px; −1 until the first inset callback fires) ────
    private var lastSafeTopCss    = -1f
    private var lastSafeBottomCss = -1f

    // ── Audio-focus listener (mirrors PlayerActivity exactly) ─────────────────
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        mainHandler.post {
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    wasPausedByFocusLoss = !sessionPaused
                    bridge?.pauseOnMain()
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (wasPausedByFocusLoss) {
                        bridge?.resumeOnMain()
                        wasPausedByFocusLoss = false
                    }
                }
            }
        }
    }

    // ── Platform listener (bridge → activity glue) ────────────────────────────
    private val platformListener = object : MpvBridge.PlatformListener {

        override fun onFileLoaded(mediaTitle: String) {
            // Activate the session, set metadata, request audio focus, keep screen on.
            val session = mediaSession ?: return
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE,
                        mediaTitle.ifBlank { "Cove" })
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION,
                        if (sessionDur > 0.0) (sessionDur * 1000.0).toLong() else -1L)
                    .build()
            )
            session.isActive = true
            audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            updateSessionPlaybackState(force = true)
        }

        override fun onPausedChanged(paused: Boolean) {
            sessionPaused = paused
            if (paused) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            updateSessionPlaybackState(force = true)
        }

        override fun onPositionChanged(seconds: Double) {
            sessionPos = seconds
            val now = System.currentTimeMillis()
            if (now - lastSessionPushMs > 1_000L) {
                lastSessionPushMs = now
                updateSessionPlaybackState(force = false)
            }
        }

        override fun onDurationChanged(seconds: Double) {
            sessionDur = seconds
            // Update duration in metadata without changing other fields
            val session = mediaSession ?: return
            val current = session.controller?.metadata
            val title   = current?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: "Cove"
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION,
                        (seconds * 1000.0).toLong())
                    .build()
            )
        }

        override fun onStopped() {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            mediaSession?.let { session ->
                session.setPlaybackState(
                    PlaybackStateCompat.Builder()
                        .setState(PlaybackStateCompat.STATE_STOPPED, 0L, 0f)
                        .build()
                )
                session.isActive = false
            }
        }

        override fun onFullscreenRequested(fullscreen: Boolean) {
            val ctrl = WindowCompat.getInsetsController(window, window.decorView)
            if (fullscreen) {
                ctrl.hide(WindowInsetsCompat.Type.systemBars())
                ctrl.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ctrl.show(WindowInsetsCompat.Type.systemBars())
                ctrl.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    // ── Safe-area helper ──────────────────────────────────────────────────────
    // Converts device-px insets to CSS px and injects --cove-safe-top/bottom
    // as inline custom properties on the document root. The mobile web shell
    // reads these via CSS vars; falls back to env() when not injected yet.
    private fun injectSafeArea(topPx: Int, bottomPx: Int) {
        val density = resources.displayMetrics.density
        lastSafeTopCss    = topPx    / density
        lastSafeBottomCss = bottomPx / density
        val wv  = webView ?: return
        val top = lastSafeTopCss
        val bot = lastSafeBottomCss
        val js  = "document.documentElement.style.setProperty('--cove-safe-top','${top}px');" +
                  "document.documentElement.style.setProperty('--cove-safe-bottom','${bot}px');"
        wv.evaluateJavascript(js, null)
    }

    // ── Playback-state helper ─────────────────────────────────────────────────

    private fun updateSessionPlaybackState(force: Boolean) {
        val session = mediaSession ?: return
        val state = if (sessionPaused) PlaybackStateCompat.STATE_PAUSED
                    else               PlaybackStateCompat.STATE_PLAYING
        val posMs  = (sessionPos * 1000.0).toLong()
        val speed  = if (sessionPaused) 0f else 1f
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, posMs, speed)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY
                        or PlaybackStateCompat.ACTION_PAUSE
                        or PlaybackStateCompat.ACTION_PLAY_PAUSE
                        or PlaybackStateCompat.ACTION_SEEK_TO,
                )
                .build()
        )
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Runtime permission for the persistent backend notification (API 33+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        // Allow the web layer to draw edge-to-edge behind system bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Listen for system-bar and display-cutout insets. Fires immediately
        // with current values, then again whenever bars hide/show (e.g. when
        // the player enters immersive fullscreen — insets become 0 so controls
        // can use the reclaimed space).
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout()
            )
            mainHandler.post { injectSafeArea(bars.top, bars.bottom) }
            insets
        }

        // Dark background (#0A0A0A) matches the Svelte app's root colour to
        // prevent a flash of white on first paint.
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            addView(
                ProgressBar(this@WebViewActivity).apply { isIndeterminate = true },
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER),
            )
        })

        // Back: if no WebView yet, fall back to backgrounding immediately.
        // Otherwise, always dispatch Escape into the page — the mobile web shell
        // is now the authority on when to background the app (via CoveApp.minimizeApp()).
        onBackPressedDispatcher.addCallback(this) {
            val wv  = webView ?: run { moveTaskToBack(true); return@addCallback }
            val esc = "document.dispatchEvent(new KeyboardEvent('keydown'," +
                      "{key:'Escape',bubbles:true,cancelable:true}))"
            // Always forward Escape; let the mobile web shell decide whether to
            // dismiss a modal or call CoveApp.minimizeApp() to background the app.
            wv.evaluateJavascript(esc, null)
        }

        // Initialise AudioManager early (safe before any playback starts).
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Poll /api/ping until the embedded Go backend is reachable.
        val pingUrl = BuildConfig.BACKEND_URL.replace("/api", "") + "/api/ping"
        val httpClient = OkHttpClient()

        lifecycleScope.launch(Dispatchers.IO) {
            var ready = false
            while (!ready) {
                try {
                    val resp = httpClient
                        .newCall(Request.Builder().url(pingUrl).build())
                        .execute()
                    if (resp.code == 200) {
                        resp.close()
                        ready = true
                    } else {
                        resp.close()
                    }
                } catch (_: Exception) {}
                if (!ready) delay(500)
            }

            // Backend is up — build the real UI on the main thread.
            withContext(Dispatchers.Main) {

                // ── M3: mpv surface (INVISIBLE until FILE_LOADED) ─────────────
                val mpv = MpvPlayerView(this@WebViewActivity).apply {
                    visibility = View.INVISIBLE
                }
                mpvView = mpv

                // ── M3: bridge (JS shim + MPVLib adapter) ────────────────────
                val wv = WebView(this@WebViewActivity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    setBackgroundColor(Color.TRANSPARENT)
                }
                webView = wv

                // Audio-focus request (created once; request/abandon per-session)
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(audioFocusListener)
                    .build()

                // MediaSession — inactive until onFileLoaded
                val session = MediaSessionCompat(this@WebViewActivity, "CovePlayer").apply {
                    setCallback(object : MediaSessionCompat.Callback() {
                        override fun onPlay()            { bridge?.resumeOnMain() }
                        override fun onPause()           { bridge?.pauseOnMain() }
                        override fun onSeekTo(pos: Long) { bridge?.seekOnMain(pos / 1000.0) }
                    })
                    isActive = false
                }
                mediaSession = session

                // MpvBridge: create() registers addDocumentStartJavaScript (if
                // supported) and initialises mpv. Must happen before loadUrl.
                val br = MpvBridge(mpv, wv, platformListener)
                bridge = br
                br.create()

                // Register the JS interfaces BEFORE loadUrl so they are available
                // immediately when the page's JS runs.
                wv.addJavascriptInterface(br.jsInterface, "CoveMpv")
                wv.addJavascriptInterface(AppJsInterface(), "CoveApp")

                // A dark overlay placed above everything; hidden in onPageFinished
                // so there is no blank-white flash while the Svelte bundle loads.
                val pageOverlay = FrameLayout(this@WebViewActivity).apply {
                    setBackgroundColor(Color.parseColor("#0A0A0A"))
                    addView(
                        ProgressBar(this@WebViewActivity).apply { isIndeterminate = true },
                        FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER),
                    )
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        // Fallback shim injection when DOCUMENT_START_SCRIPT is
                        // unavailable. When it IS supported, the shim was already
                        // registered in br.create() and this is a no-op.
                        if (!WebViewFeature.isFeatureSupported(
                                androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                            br.injectShimFallback()
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        // Svelte app has painted — remove the loading overlay.
                        pageOverlay.visibility = View.GONE
                        // Re-inject safe-area values so a reload / HMR navigation
                        // doesn't lose the custom properties set before page load.
                        val top = lastSafeTopCss
                        val bot = lastSafeBottomCss
                        if (top >= 0f) {
                            val js = "document.documentElement.style.setProperty(" +
                                     "'--cove-safe-top','${top}px');" +
                                     "document.documentElement.style.setProperty(" +
                                     "'--cove-safe-bottom','${bot}px');"
                            view.evaluateJavascript(js, null)
                        }
                    }
                }

                setContentView(FrameLayout(this@WebViewActivity).apply {
                    // ── M3: mpv surface renders behind the transparent web layer ──
                    addView(mpv,        FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                    addView(wv,         FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                    addView(pageOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                })

                wv.loadUrl(BuildConfig.WEB_URL)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Notify the Svelte UI that the app has come to the foreground — mirrors
        // the desktop Qt shell's window-focus event so store-refresh logic fires.
        webView?.evaluateJavascript("window.dispatchEvent(new Event('focus'))", null)
    }

    override fun onDestroy() {
        super.onDestroy()
        bridge?.destroy()
        bridge = null
        mpvView = null
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        mediaSession?.let { session ->
            session.isActive = false
            session.release()
        }
        mediaSession = null
        webView?.destroy()
        webView = null
    }

    // ── CoveApp JS interface ──────────────────────────────────────────────────
    // Registered as "CoveApp" so the mobile web shell can call native platform
    // actions. JavascriptInterface methods arrive on a Binder thread; post to
    // the main thread before touching any Android UI or Activity state.

    inner class AppJsInterface {
        @JavascriptInterface
        fun minimizeApp() {
            mainHandler.post { moveTaskToBack(true) }
        }
    }
}
