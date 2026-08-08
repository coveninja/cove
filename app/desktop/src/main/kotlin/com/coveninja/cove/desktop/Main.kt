package com.coveninja.cove.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.coveninja.cove.desktop.backend.BackendState
import com.coveninja.cove.desktop.backend.BackendSupervisor
import com.coveninja.cove.desktop.backend.SingleInstanceLock
import com.coveninja.cove.desktop.backend.SupervisorConfig
import com.coveninja.cove.desktop.player.DesktopPlayer
import com.coveninja.cove.desktop.player.MpvOpenGlPanel
import com.coveninja.cove.desktop.player.MpvOpenGlPlayer
import com.coveninja.cove.desktop.player.MpvSoftwarePlayer
import com.coveninja.cove.shared.data.AppGraph
import com.coveninja.cove.shared.data.createLiveAppGraph
import com.coveninja.cove.shared.fixture.FixtureAppGraph
import com.coveninja.cove.shared.network.CoveApiConfig
import com.coveninja.cove.ui.CoveApp
import com.coveninja.cove.ui.CoveTheme
import kotlinx.coroutines.delay
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val parsedOptions = LaunchOptions.parse(args)
    val options = if (parsedOptions.backendPath == null) {
        parsedOptions.copy(
            backendPath = System.getenv("COVE_BACKEND_PATH")
                ?.takeIf { it.isNotBlank() },
        )
    } else {
        parsedOptions
    }

    // --play is a standalone probe of the player with no backend and no shared
    // state, so it must not contend for the single-instance lock.
    val lock = if (options.playFile == null) SingleInstanceLock() else null
    if (lock != null && !lock.acquired) {
        System.err.println("Cove is already running.")
        exitProcess(1)
    }

    val supervisor = options.backendPath?.let {
        BackendSupervisor(SupervisorConfig(executable = Path.of(it))).apply { start() }
    }

    // Live only when told where to reach a backend; otherwise fixtures, so the
    // UI is workable without a running Go process.
    val graph: AppGraph = when {
        options.apiBase    != null -> createLiveAppGraph(CoveApiConfig(options.apiBase))
        options.backendPath != null -> createLiveAppGraph()
        else                        -> FixtureAppGraph()
    }

    try {
        application {
            if (options.smokeSeconds != null) {
                LaunchedEffect(Unit) {
                    delay(options.smokeSeconds * 1000L)
                    exitApplication()
                }
            }

            if (options.playFile != null) {
                // Standalone --play mode: open a single video window, no backend,
                // no graph, no navigation shell. Exits when the window is closed.
                StandalonePlayerWindow(
                    file             = options.playFile,
                    softwareRenderer = options.softwareRenderer,
                    onClose          = ::exitApplication,
                )
            } else {
                Window(onCloseRequest = ::exitApplication, title = "Cove") {
                    CoveTheme {
                        if (supervisor == null) {
                            CoveApp(graph)
                        } else {
                            val state by supervisor.state.collectAsState()
                            BackendGate(state, onRestartRequested = ::exitApplication) {
                                CoveApp(graph)
                            }
                        }
                    }
                }
            }
        }
    } finally {
        graph.close()
        supervisor?.stop()
        lock?.close()
    }
}

/**
 * A minimal single-window player for --play. Chooses OpenGL by default,
 * falls back to software automatically if GL context creation fails, or
 * unconditionally uses software when --software-renderer is set.
 */
@Composable
private fun StandalonePlayerWindow(
    file: String,
    softwareRenderer: Boolean,
    onClose: () -> Unit,
) {
    Window(onCloseRequest = onClose, title = "Cove — $file") {
        CoveTheme {
            if (softwareRenderer) {
                SoftwarePlayerSurface(file)
            } else {
                OpenGlPlayerSurface(file, fallbackToSoftware = true)
            }
        }
    }
}

/** Software render surface: frames arrive as BufferedImages → Compose ImageBitmap. */
@Composable
private fun SoftwarePlayerSurface(file: String) {
    var latestFrame by remember { mutableStateOf<java.awt.image.BufferedImage?>(null) }
    val player = remember {
        MpvSoftwarePlayer { frame -> latestFrame = frame }.also {
            it.start()
            it.load(file)
        }
    }
    // Free player resources when the composable leaves the composition.
    androidx.compose.runtime.DisposableEffect(player) { onDispose { player.close() } }

    Box(Modifier.fillMaxSize()) {
        latestFrame?.let {
            Image(
                bitmap      = it.toComposeImageBitmap(),
                contentDescription = null,
                modifier    = Modifier.fillMaxSize(),
            )
        }
        val snap by player.snapshot.collectAsState()
        snap.error?.let { err ->
            Text(err, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** OpenGL render surface: JOGL GLJPanel composited via SwingPanel. */
@Composable
private fun OpenGlPlayerSurface(file: String, fallbackToSoftware: Boolean) {
    val panel  = remember { MpvOpenGlPanel() }
    val player = remember {
        try {
            MpvOpenGlPlayer(panel).also {
                it.start()
                it.load(file)
            }
        } catch (error: Throwable) {
            if (fallbackToSoftware) null
            else throw error
        }
    }
    androidx.compose.runtime.DisposableEffect(player) { onDispose { player?.close() } }

    if (player == null) {
        // GL context creation failed; drop through to software.
        SoftwarePlayerSurface(file)
        return
    }

    Box(Modifier.fillMaxSize()) {
        SwingPanel(
            modifier  = Modifier.fillMaxSize(),
            factory   = { panel },
        )
        val snap by player.snapshot.collectAsState()
        snap.error?.let { err ->
            Text(err, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

// Holds the UI back until the sidecar answers /api/ping. Rendering the app
// against a backend that is not listening yet would surface every screen as a
// connection error on launch.
@Composable
private fun BackendGate(
    state: BackendState,
    onRestartRequested: () -> Unit,
    content: @Composable () -> Unit,
) {
    when (state) {
        BackendState.Ready   -> content()
        BackendState.Starting -> Centered("Starting backend…")
        is BackendState.Failed -> Centered("Backend failed: ${state.message}")
        // The backend replaced its own binary and exited 42. Re-exec is not
        // wired up yet, so exit cleanly rather than sitting on a dead sidecar.
        BackendState.RestartRequested -> {
            LaunchedEffect(Unit) { onRestartRequested() }
            Centered("Update applied — restart Cove.")
        }
    }
}

@Composable
private fun Centered(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(message) }
}
