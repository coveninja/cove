package com.coveninja.cove

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.coveninja.cove.ui.CoveApp
import com.coveninja.cove.ui.CoveTheme
import com.coveninja.cove.ui.components.navigation.NavBarPlacement
import com.coveninja.cove.player.AndroidMpvVideoPlayerHost

class MainActivity : ComponentActivity() {
    private var detailsOverlayVisible = false
    private var fullscreenPlaybackVisible = false
    private lateinit var playerHost: AndroidMpvVideoPlayerHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        playerHost = AndroidMpvVideoPlayerHost(this)
        val graph = (application as CoveMobileApplication).backendRuntime().graph
        setContent {
            CoveTheme {
                CoveApp(
                    graph = graph,
                    videoPlayerHost = playerHost,
                    navBarPlacement = NavBarPlacement.Bottom,
                    onDetailsOverlayVisibilityChanged = ::setDetailsOverlayVisible,
                    onFullscreenPlaybackVisibilityChanged = ::setFullscreenPlaybackVisible,
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateSystemBars()
    }

    override fun onStop() {
        playerHost.onHostStopped()
        super.onStop()
    }

    override fun onDestroy() {
        playerHost.dispose()
        super.onDestroy()
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
        updateSystemBars()
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
}
