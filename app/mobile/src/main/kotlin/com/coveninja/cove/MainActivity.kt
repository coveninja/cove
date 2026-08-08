package com.coveninja.cove

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.coveninja.cove.ui.CoveApp
import com.coveninja.cove.ui.CoveTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val graph = (application as CoveMobileApplication).backendRuntime().graph
        setContent {
            CoveTheme {
                CoveApp(graph)
            }
        }
    }
}
