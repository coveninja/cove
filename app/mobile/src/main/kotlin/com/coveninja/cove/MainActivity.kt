package com.coveninja.cove

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Icon
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.app.UiModeManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.coveninja.cove.ui.CoveApp
import com.coveninja.cove.ui.CoveTheme
import com.coveninja.cove.ui.tv.CoveTvApp
import com.coveninja.cove.ui.components.common.AppBootstrapFailed
import com.coveninja.cove.ui.components.common.AppBootstrapLoading
import com.coveninja.cove.ui.components.navigation.NavBarPlacement
import com.coveninja.cove.player.AndroidMpvVideoPlayerHost
import com.coveninja.cove.shared.data.HomeState
import com.coveninja.cove.shared.data.AppUpdateState
import com.coveninja.cove.shared.fixture.FixtureAppGraph
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    private var detailsOverlayVisible = false
    private var fullscreenPlaybackVisible = false
    private lateinit var playerHost: AndroidMpvVideoPlayerHost

    /**
     * Which shell this device gets, decided once at startup.
     *
     * One APK serves both, because a television and a phone are the same installation of the
     * same app to everything underneath — one package identity, one update channel, one
     * database. What differs is entirely above the graph: a remote cannot hover, cannot drag a
     * card between library categories, and cannot reach anything the touch UI reveals on press.
     *
     * The leanback feature is the reliable half of the test — it is what the launcher itself
     * keys off — and the UI mode catches devices that report a television configuration
     * without declaring the feature, such as some set-top boxes and the emulator.
     */
    private val isTelevision: Boolean by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            (getSystemService(UI_MODE_SERVICE) as UiModeManager).currentModeType ==
            Configuration.UI_MODE_TYPE_TELEVISION
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val mobileApplication = application as CoveMobileApplication
        // Debug-only design harnesses, mirroring the desktop's --onboarding flag. Gated on
        // BuildConfig.DEBUG rather than shipped behind a preference: nothing here should be
        // reachable in a release build, and a flag that only exists in debug cannot be.
        val forceOnboarding = BuildConfig.DEBUG &&
            intent.getBooleanExtra(SHOW_ONBOARDING_EXTRA, false)
        val onboardingFixtures = BuildConfig.DEBUG &&
            intent.getBooleanExtra(ONBOARDING_FIXTURES_EXTRA, false)
        // Reuses the benchmark's fixture path so a preview needs no TMDB key baked into the
        // APK — the flow's poster wall wants artwork, not a live catalog.
        val fixtureMode = (
            BuildConfig.BENCHMARK_FIXTURE &&
                intent.getBooleanExtra(BENCHMARK_FIXTURE_EXTRA, false)
            ) || onboardingFixtures
        val fixtureLowPerformance = fixtureMode &&
            intent.getBooleanExtra(BENCHMARK_LOW_PERFORMANCE_EXTRA, false)
        if (!fixtureMode) mobileApplication.initializeBackend()
        setContent {
            CoveTheme {
                if (fixtureMode) {
                    val graph = remember { FixtureAppGraph() }
                    LaunchedEffect(fixtureLowPerformance) {
                        graph.device.setLowPerformanceMode(fixtureLowPerformance)
                        reportFullyDrawn()
                    }
                    if (isTelevision) {
                        CoveTvApp(graph = graph, forceOnboarding = forceOnboarding)
                    } else {
                        CoveApp(
                            graph = graph,
                            navBarPlacement = NavBarPlacement.Bottom,
                            forceOnboarding = forceOnboarding,
                        )
                    }
                } else {
                    val runtimeState by mobileApplication.runtimeState.collectAsState()
                    when (val state = runtimeState) {
                        MobileRuntimeState.Loading -> AppBootstrapLoading()
                        is MobileRuntimeState.Failed -> AppBootstrapFailed(
                            message = state.message,
                            onRetry = mobileApplication::initializeBackend,
                        )
                        is MobileRuntimeState.Ready -> {
                            val host = remember { mobileApplication.playerHost() }
                            playerHost = host
                            // The floating window's play/pause glyph and its shape both come
                            // from the params, and params are a snapshot — so they have to be
                            // re-issued whenever either changes. Collected off the flow rather
                            // than through composition: this is four updates a second of
                            // position data with two fields worth reacting to.
                            LaunchedEffect(host) {
                                host.status
                                    .map { it.paused to (it.renderWidth to it.renderHeight) }
                                    .distinctUntilChanged()
                                    .collect { refreshPictureInPictureParams() }
                            }
                            val homeState by state.runtime.graph.content.home.collectAsState()
                            LaunchedEffect(homeState) {
                                if (homeState !is HomeState.Loading) reportFullyDrawn()
                            }
                            if (isTelevision) {
                                CoveTvApp(
                                    graph = state.runtime.graph,
                                    videoPlayerHost = host,
                                    onFullscreenPlaybackVisibilityChanged =
                                        ::setFullscreenPlaybackVisible,
                                    forceOnboarding = forceOnboarding,
                                )
                            } else {
                                CoveApp(
                                    graph = state.runtime.graph,
                                    videoPlayerHost = host,
                                    navBarPlacement = NavBarPlacement.Bottom,
                                    onDetailsOverlayVisibilityChanged = ::setDetailsOverlayVisible,
                                    onFullscreenPlaybackVisibilityChanged = ::setFullscreenPlaybackVisible,
                                    forceOnboarding = forceOnboarding,
                                )
                            }
                        }
                    }
                }
            }
        }
        // Not on a television: the only notification Cove posts is the playback one a phone
        // uses from its lock screen, and a permission dialog on first launch is a poor way to
        // meet a viewer who has no use for the result.
        if (!fixtureMode && !isTelevision &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateSystemBars()
    }

    override fun onResume() {
        super.onResume()
        val mobileApplication = application as CoveMobileApplication
        val ready = mobileApplication.runtimeState.value as? MobileRuntimeState.Ready ?: return
        if (ready.runtime.graph.updates.state.value is AppUpdateState.PermissionRequired &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls())
        ) {
            lifecycleScope.launch { ready.runtime.graph.updates.resumePendingAction() }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Picture-in-picture is a phone gesture. Leaving Cove on a television means pressing
        // Home on a remote, where a shrinking window in the corner of someone's living room
        // is a surprise rather than a convenience.
        // `::playerHost.isInitialized` is not belt-and-braces: a fixture run never builds one,
        // and playback can still report itself fullscreen there because PlaybackSession does not
        // need a host to open. Reaching for `playerHost` then throws
        // UninitializedPropertyAccessException on the way out of the app. Previously that was
        // only reachable from a benchmark build; the onboarding preview brings the fixture path
        // into ordinary debug builds, so the guard has to be real.
        if (!isTelevision && Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            fullscreenPlaybackVisible && ::playerHost.isInitialized &&
            playerHost.status.value.hasMedia
        ) {
            enterPictureInPictureMode(pictureInPictureParams())
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        updateSystemBars()
        updateOrientation(isInPictureInPictureMode)
    }

    private fun setDetailsOverlayVisible(visible: Boolean) {
        detailsOverlayVisible = visible
        updateSystemBars()
    }

    private fun setFullscreenPlaybackVisible(visible: Boolean) {
        fullscreenPlaybackVisible = visible
        if (visible) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // Same reasoning as onUserLeaveHint: on a television, auto-enter would turn pressing
        // Home into a floating window rather than leaving the app.
        refreshPictureInPictureParams()
        updateSystemBars()
        updateOrientation()
    }

    private fun pictureInPictureParams(): PictureInPictureParams {
        val status = if (::playerHost.isInitialized) playerHost.status.value else null
        val (width, height) = pictureInPictureAspect(
            width = status?.renderWidth ?: 0,
            height = status?.renderHeight ?: 0,
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(width, height))
            // Without these the floating window is a picture and nothing else: no way to
            // pause, no way to skip, and no way to do either without restoring the app first.
            // They are pointed at the service actions that already existed for the
            // notification, so this is wiring rather than new behaviour.
            .setActions(pictureInPictureActions(status?.paused ?: true))
            .apply {
                // Where the window animates from. The video fills the window during
                // fullscreen playback, so the window's own bounds are the closest thing to
                // the picture available here — an exact hint would need the letterboxed
                // video rect, which lives inside the composition.
                runCatching {
                    val bounds = android.graphics.Rect()
                    window.decorView.getGlobalVisibleRect(bounds)
                    if (!bounds.isEmpty) setSourceRectHint(bounds)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(fullscreenPlaybackVisible)
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()
    }

    /**
     * Rewind, play/pause, forward — the three a floating window has room for.
     *
     * The middle one changes glyph and title with the transport, which is why the params have
     * to be re-issued when playback pauses rather than set once: a floating window showing a
     * play triangle over a playing film is worse than showing nothing.
     */
    private fun pictureInPictureActions(paused: Boolean): List<RemoteAction> = listOf(
        remoteAction(
            icon = android.R.drawable.ic_media_rew,
            title = "Rewind",
            action = PlaybackService.ACTION_REWIND,
            requestCode = 11,
        ),
        remoteAction(
            icon = if (paused) {
                android.R.drawable.ic_media_play
            } else {
                android.R.drawable.ic_media_pause
            },
            title = if (paused) "Play" else "Pause",
            action = PlaybackService.ACTION_PLAY_PAUSE,
            requestCode = 12,
        ),
        remoteAction(
            icon = android.R.drawable.ic_media_ff,
            title = "Forward",
            action = PlaybackService.ACTION_FORWARD,
            requestCode = 13,
        ),
    )

    private fun remoteAction(
        icon: Int,
        title: String,
        action: String,
        requestCode: Int,
    ): RemoteAction = RemoteAction(
        Icon.createWithResource(this, icon),
        title,
        title,
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
    )

    /** Safe to call at any time: a no-op on a television and before a player exists. */
    private fun refreshPictureInPictureParams() {
        if (isTelevision) return
        runCatching { setPictureInPictureParams(pictureInPictureParams()) }
    }

    /**
     * The picture-in-picture state is a parameter rather than a read of the activity's own
     * property because the one caller that changes it is handed the new value directly, and
     * taking it from the activity there would make this depend on the framework having already
     * written it. If that read were ever stale on the way *out* of a floating window, the
     * player would return to fullscreen and stay portrait, with nothing to say why.
     */
    private fun updateOrientation(inPictureInPicture: Boolean = isInPictureInPictureMode) {
        requestedOrientation = playerOrientation(
            fullscreenPlayback = fullscreenPlaybackVisible,
            inPictureInPicture = inPictureInPicture,
            isTelevision = isTelevision,
            smallestScreenWidthDp = resources.configuration.smallestScreenWidthDp,
        )
    }

    private fun updateSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (detailsOverlayVisible || fullscreenPlaybackVisible) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        const val BENCHMARK_FIXTURE_EXTRA = "com.coveninja.cove.BENCHMARK_FIXTURE"
        const val BENCHMARK_LOW_PERFORMANCE_EXTRA =
            "com.coveninja.cove.BENCHMARK_LOW_PERFORMANCE"

        /** Debug-only: re-open the first-run flow. See `make onboarding-mobile`. */
        const val SHOW_ONBOARDING_EXTRA = "com.coveninja.cove.SHOW_ONBOARDING"

        /** Debug-only: run that preview against fixtures, so no TMDB key is needed. */
        const val ONBOARDING_FIXTURES_EXTRA = "com.coveninja.cove.ONBOARDING_FIXTURES"
    }
}
