package com.coveninja.cove

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.app.PictureInPictureParams
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
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.coveninja.cove.ui.CoveApp
import com.coveninja.cove.ui.CoveTheme
import com.coveninja.cove.ui.components.navigation.NavBarPlacement
import com.coveninja.cove.player.AndroidMpvVideoPlayerHost

class MainActivity : ComponentActivity() {
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    private var detailsOverlayVisible = false
    private var fullscreenPlaybackVisible = false
    private lateinit var playerHost: AndroidMpvVideoPlayerHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val mobileApplication = application as CoveMobileApplication
        playerHost = mobileApplication.playerHost()
        val graph = mobileApplication.backendRuntime().graph
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
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

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && fullscreenPlaybackVisible &&
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
        setPictureInPictureParams(pictureInPictureParams())
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
}
