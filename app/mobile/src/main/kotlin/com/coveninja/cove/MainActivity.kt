package com.coveninja.cove

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.app.PictureInPictureParams
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
        val fixtureMode = BuildConfig.BENCHMARK_FIXTURE &&
            intent.getBooleanExtra(BENCHMARK_FIXTURE_EXTRA, false)
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
                        CoveTvApp(graph = graph)
                    } else {
                        CoveApp(
                            graph = graph,
                            navBarPlacement = NavBarPlacement.Bottom,
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
                                )
                            } else {
                                CoveApp(
                                    graph = state.runtime.graph,
                                    videoPlayerHost = host,
                                    navBarPlacement = NavBarPlacement.Bottom,
                                    onDetailsOverlayVisibilityChanged = ::setDetailsOverlayVisible,
                                    onFullscreenPlaybackVisibilityChanged = ::setFullscreenPlaybackVisible,
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
        if (!isTelevision && Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            fullscreenPlaybackVisible && playerHost.status.value.hasMedia
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
        if (!isTelevision) setPictureInPictureParams(pictureInPictureParams())
        updateSystemBars()
    }

    private fun pictureInPictureParams(): PictureInPictureParams = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setAutoEnterEnabled(fullscreenPlaybackVisible)
                setSeamlessResizeEnabled(true)
            }
        }
        .build()

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
    }
}
