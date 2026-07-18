package com.coveninja.cove

import android.Manifest
import android.app.PendingIntent
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
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
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
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
import com.coveninja.cove.updater.ApkUpdater
import com.coveninja.cove.updater.PackageInstallerReceiver
import com.coveninja.cove.updater.UpdatePrefs
import com.coveninja.cove.updater.UpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class WebViewActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var isTV = false

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

    // ── Updater UI refs (non-null only while the updater screen is shown) ─────
    private var updaterStatusText: TextView? = null
    private var updaterProgressBar: ProgressBar? = null

    // Runtime receiver for ACTION_INSTALL_FAILED — registered in startInstallFlow,
    // unregistered in onDestroy (and on fallback to normal UI).
    private var installFailedReceiver: BroadcastReceiver? = null

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
                if (!isTV) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ctrl.show(WindowInsetsCompat.Type.systemBars())
                ctrl.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                if (!isTV) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
        // Guarded: the insets listener can fire mid-navigation, before the new
        // document has a documentElement — unguarded access throws a console
        // TypeError on every page (re)load.
        val js  = "var de=document.documentElement;" +
                  "if(de){de.style.setProperty('--cove-safe-top','${top}px');" +
                  "de.style.setProperty('--cove-safe-bottom','${bot}px');}"
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
                        or PlaybackStateCompat.ACTION_SEEK_TO
                        or PlaybackStateCompat.ACTION_FAST_FORWARD
                        or PlaybackStateCompat.ACTION_REWIND,
                )
                .build()
        )
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Detect Android TV upfront — used to suppress phone-only APIs below.
        isTV = (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager)
            .currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

        // Runtime permission for the persistent backend notification (API 33+).
        // Not requested on TV — notification prompts are not supported by Leanback.
        if (!isTV && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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

        // Back: if no WebView yet (including during the updater screen), background
        // the app immediately. Otherwise, always dispatch Escape into the page.
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

        // Start the update check concurrently with the backend startup ping so
        // we don't pay the 5 s timeout on top of the backend warm-up time.
        // Any exception (offline, rate-limited, malformed JSON) produces null
        // and the app launches normally — fail-open by design.
        val updateCheck = lifecycleScope.async(Dispatchers.IO) {
            if (!UpdatePrefs.isEnabled()) return@async null
            try {
                withTimeout(5_000) { ApkUpdater().checkForUpdate(BuildConfig.VERSION_CODE) }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Update check failed: ${e.message}")
                null
            }
        }

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

            // Backend is up — check update result then branch: updater or normal UI.
            // The inner withTimeout can't interrupt OkHttp's blocking execute()
            // (no suspension points), so bound the wait here too: a black-hole
            // network must never delay launch beyond ~5 s.
            val result = withTimeoutOrNull(5_000) { updateCheck.await() }
            withContext(Dispatchers.Main) {
                if (result != null) {
                    showUpdaterView()
                    startInstallFlow(result)
                } else {
                    buildNormalUI()
                }
            }
        }
    }

    // ── Normal UI builder ─────────────────────────────────────────────────────
    // Extracted from the original withContext(Dispatchers.Main) block so it can
    // be called either directly (no update) or as a fallback from startInstallFlow.
    // Must be called on the main thread.

    private fun buildNormalUI() {

        // ── M3: mpv surface (INVISIBLE until FILE_LOADED) ─────────────
        val mpv = MpvPlayerView(this).apply {
            visibility = View.INVISIBLE
        }
        mpvView = mpv

        // ── M3: bridge (JS shim + MPVLib adapter) ────────────────────
        val wv = WebView(this).apply {
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
        val session = MediaSessionCompat(this, "CovePlayer").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay()            { bridge?.resumeOnMain() }
                override fun onPause()           { bridge?.pauseOnMain() }
                override fun onSeekTo(pos: Long) { bridge?.seekOnMain(pos / 1000.0) }
                // FF/RW: ±30 s relative seek — sent by media remotes and lock-screen controls.
                override fun onFastForward()     { bridge?.seekRelativeOnMain(30.0) }
                override fun onRewind()          { bridge?.seekRelativeOnMain(-30.0) }
            })
            isActive = false
        }
        mediaSession = session

        // MpvBridge: create() registers addDocumentStartJavaScript (if
        // supported) and initialises mpv. Must happen before loadUrl.
        val br = MpvBridge(mpv, wv, platformListener,
            platformName = if (isTV) "androidtv" else "android")
        bridge = br
        br.create()

        // Register the JS interfaces BEFORE loadUrl so they are available
        // immediately when the page's JS runs.
        wv.addJavascriptInterface(br.jsInterface, "CoveMpv")
        wv.addJavascriptInterface(AppJsInterface(), "CoveApp")

        // A dark overlay placed above everything; hidden in onPageFinished
        // so there is no blank-white flash while the Svelte bundle loads.
        val pageOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            addView(
                ProgressBar(this@WebViewActivity).apply { isIndeterminate = true },
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER),
            )
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                // In-app navigations (the Svelte bundle / Vite dev server)
                // stay in the WebView; anything else — e.g. the Trakt
                // authorize link — opens in the system browser.
                val own = Uri.parse(BuildConfig.WEB_URL)
                val url = request.url
                if (url.host == own.host && url.port == own.port) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                } catch (e: Exception) {
                    // No browser on this device (common on TV) — never let the
                    // link navigate the app shell away.
                    android.util.Log.w(TAG, "no handler for external url $url", e)
                }
                return true
            }

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
                    // Same documentElement guard as injectSafeArea.
                    val js = "var de=document.documentElement;" +
                             "if(de){de.style.setProperty(" +
                             "'--cove-safe-top','${top}px');" +
                             "de.style.setProperty(" +
                             "'--cove-safe-bottom','${bot}px');}"
                    view.evaluateJavascript(js, null)
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail,
            ): Boolean {
                // Android reclaimed the renderer while backgrounded (or it
                // crashed) — the WebView surface is dead and must not be
                // reused. recreate() rebuilds mpv + bridge + WebView from
                // scratch. Returning false would kill the whole app process.
                android.util.Log.w(TAG,
                    "WebView renderer gone (crashed=${detail.didCrash()}); recreating activity")
                mainHandler.post { recreate() }
                return true
            }
        }

        setContentView(FrameLayout(this).apply {
            // Dark backdrop so surface gaps (renderer death, resume repaint)
            // never flash white through the transparent WebView.
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            // ── M3: mpv surface renders behind the transparent web layer ──
            addView(mpv,        FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(wv,         FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(pageOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        })

        wv.loadUrl(BuildConfig.WEB_URL)
    }

    // ── Updater screen ────────────────────────────────────────────────────────

    /**
     * Shows a blocking "Updating Cove…" screen while the download and install
     * proceed. No focusable elements — TV D-pad safe. Back press backgrounds
     * the app via the existing onBackPressedDispatcher callback (webView is
     * still null at this point so it falls through to moveTaskToBack).
     */
    private fun showUpdaterView() {
        val dp = resources.displayMetrics.density

        val titleView = TextView(this).apply {
            text = "Updating Cove…"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isFocusable = false
        }
        val statusView = TextView(this).apply {
            text = "Preparing…"
            textSize = 14f
            setTextColor(0xFFAAAAAA.toInt())
            gravity = Gravity.CENTER
            isFocusable = false
        }
        val progressView = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            isFocusable = false
        }

        updaterStatusText  = statusView
        updaterProgressBar = progressView

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F0F0F"))
            val pad = (24 * dp).toInt()
            setPadding(pad, pad, pad, pad)

            val marginBottom16 = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.bottomMargin = (16 * dp).toInt() }
            val marginBottom24 = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.bottomMargin = (24 * dp).toInt() }
            val noMargin = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

            addView(titleView,    marginBottom16)
            addView(statusView,   marginBottom24)
            addView(progressView, noMargin)
        })
    }

    /**
     * Downloads and installs [result]. Shows progress in the updater view.
     * Registers a runtime receiver for [PackageInstallerReceiver.ACTION_INSTALL_FAILED]
     * so that any failure falls back to [buildNormalUI] instead of leaving
     * the user stuck on the updater screen.
     *
     * Must be called on the main thread (registers a receiver and updates UI).
     */
    private fun startInstallFlow(result: UpdateResult) {
        // Register a runtime receiver to catch install failures from PackageInstallerReceiver
        // and fall back to the normal app UI. Unregistered in onDestroy.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                android.util.Log.w(TAG, "Install failed broadcast received — falling back to normal UI")
                unregisterInstallFailedReceiver()
                buildNormalUI()
            }
        }
        installFailedReceiver = receiver

        val filter = IntentFilter(PackageInstallerReceiver.ACTION_INSTALL_FAILED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        // Download on IO, post progress to the main thread via mainHandler.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val destFile = File(cacheDir, "cove-update.apk")
                val updater = ApkUpdater()
                updater.cleanStaleCacheFiles(cacheDir)

                mainHandler.post {
                    updaterStatusText?.text = "Downloading…"
                }

                updater.downloadAndVerify(result.apkUrl, result.shaUrl, destFile) { progress ->
                    mainHandler.post {
                        updaterProgressBar?.progress = progress
                        updaterStatusText?.text = "Downloading… $progress%"
                    }
                }

                // Download + verify succeeded — hand off to PackageInstaller.
                withContext(Dispatchers.Main) {
                    updaterStatusText?.text = "Installing…"
                    updaterProgressBar?.isIndeterminate = true
                }

                // FLAG_MUTABLE is mandatory on API 31+: PackageInstaller writes
                // EXTRA_STATUS and EXTRA_INTENT into the PendingIntent's extras.
                // An IMMUTABLE intent silently breaks the callback.
                val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val statusIntent = PendingIntent.getBroadcast(
                    this@WebViewActivity,
                    0,
                    Intent(this@WebViewActivity, PackageInstallerReceiver::class.java),
                    piFlags,
                )
                // Stays on the IO dispatcher: streaming the APK into the session
                // is heavy disk I/O and would ANR on the main thread.
                updater.installApk(this@WebViewActivity, destFile, statusIntent)
                // PackageInstaller will now fire PackageInstallerReceiver asynchronously.
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Update flow failed: ${e.message}", e)
                unregisterInstallFailedReceiver()
                withContext(Dispatchers.Main) { buildNormalUI() }
            }
        }
    }

    private fun unregisterInstallFailedReceiver() {
        installFailedReceiver?.let {
            try { unregisterReceiver(it) } catch (_: IllegalArgumentException) {}
            installFailedReceiver = null
        }
    }

    override fun onPause() {
        super.onPause()
        // Pause WebView JS timers while backgrounded — but only when nothing
        // is playing: mpv keeps running natively with the MediaSession active
        // (lock-screen/remote controls), and the player UI + progress-save
        // logic live in WebView JS and must stay alive alongside it.
        if (mediaSession?.isActive != true) webView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Counterpart to onPause() — safe to call even when never paused.
        webView?.onResume()
        // Notify the Svelte UI that the app has come to the foreground — mirrors
        // the desktop Qt shell's window-focus event so store-refresh logic fires.
        webView?.evaluateJavascript("window.dispatchEvent(new Event('focus'))", null)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterInstallFailedReceiver()
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

        /** Returns the current auto-update preference. Thread-safe (SharedPreferences read). */
        @JavascriptInterface
        fun getAutoUpdateEnabled(): Boolean = UpdatePrefs.isEnabled()

        /**
         * Persists the auto-update preference from the web settings UI.
         * SharedPreferences.apply() is async and thread-safe — no need to post.
         */
        @JavascriptInterface
        fun setAutoUpdateEnabled(enabled: Boolean) {
            UpdatePrefs.setEnabled(enabled)
        }
    }

    companion object {
        private const val TAG = "WebViewActivity"
    }
}
