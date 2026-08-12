package com.coveninja.cove

import android.graphics.Color
import android.os.Bundle
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

class MainActivity : ComponentActivity() {
    private var detailsOverlayVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val graph = (application as CoveMobileApplication).backendRuntime().graph
        setContent {
            CoveTheme {
                CoveApp(
                    graph = graph,
                    navBarPlacement = NavBarPlacement.Bottom,
                    onDetailsOverlayVisibilityChanged = ::setDetailsOverlayVisible,
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateSystemBars()
    }

    private fun setDetailsOverlayVisible(visible: Boolean) {
        detailsOverlayVisible = visible
        updateSystemBars()
    }

    private fun updateSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (detailsOverlayVisible) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
