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
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.application
import com.coveninja.cove.desktop.backend.SingleInstanceLock
import com.coveninja.cove.desktop.player.DesktopPlayer
import com.coveninja.cove.desktop.player.MpvOpenGlPanel
import com.coveninja.cove.desktop.player.MpvOpenGlPlayer
import com.coveninja.cove.desktop.player.MpvSoftwarePlayer
import com.coveninja.cove.desktop.player.MpvVideoPlayerHost
import com.coveninja.cove.desktop.player.YtDlpProvisioner
import com.coveninja.cove.backend.LocalBackendRuntime
import com.coveninja.cove.backend.LocalStoreGraph
import com.coveninja.cove.backend.platform.DesktopConfigPaths
import com.coveninja.cove.shared.data.AppGraph
import com.coveninja.cove.shared.data.createLiveAppGraph
import com.coveninja.cove.shared.fixture.FixtureAppGraph
import com.coveninja.cove.shared.network.CoveApiConfig
import com.coveninja.cove.ui.CoveApp
import com.coveninja.cove.ui.CoveTheme
import com.coveninja.cove.ui.icons.CoveLogoVector
import com.coveninja.cove.ui.tv.CoveTvApp
import com.coveninja.cove.ui.state.FullscreenController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
        System.setProperty("apple.awt.application.name", "Cove")
    }
    val parsedOptions = LaunchOptions.parse(args)
    val environmentMode = System.getenv("COVE_BACKEND_MODE")
        ?.takeIf(String::isNotBlank)
        ?.let(BackendMode::parse)
    val selectedMode = parsedOptions.backendMode ?: environmentMode ?: BackendMode.Kotlin
    val options = parsedOptions.copy(backendMode = selectedMode)

    if (options.exportLegacy) {
        LocalStoreGraph.open().use { it.exportLegacyFallback() }
        println("Exported the SQLite stores to legacy JSON sidecars.")
        return
    }

    // --play is a standalone probe of the player with no backend and no shared
    // state, so it must not contend for the single-instance lock.
    val lock = if (options.playFile == null) SingleInstanceLock() else null
    if (lock != null && !lock.acquired) {
        System.err.println("Cove is already running.")
        exitProcess(1)
    }

    // Before anything else can print, and after the lock rather than before it: a
    // packaged launcher has no console to print to, so without this every
    // diagnostic the run produces goes to a stream that discards it. The streams
    // are teed, so a terminal launch prints exactly as it always did. A second
    // launch that loses the lock installs nothing, or a double-clicked icon would
    // rotate the running instance's log out from under it and leave a bug report
    // carrying the empty file it started instead.
    DesktopLog.install(args)

    // Kotlin owns stores, integrations, and the compatibility media boundary
    // in-process. Fixtures and an explicit API URL remain available for UI work.
    val kotlinRuntime = if (
        options.backendMode == BackendMode.Kotlin &&
        options.apiBase == null &&
        options.playFile == null
    ) {
        LocalBackendRuntime.open()
    } else {
        null
    }
    val graph: AppGraph = when {
        options.apiBase != null                     -> createLiveAppGraph(CoveApiConfig(options.apiBase))
        options.backendMode == BackendMode.Fixtures -> FixtureAppGraph()
        kotlinRuntime != null                       -> kotlinRuntime.graph
        else                                        -> FixtureAppGraph()
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
                val playerHost = remember {
                    MpvVideoPlayerHost(
                        softwareDecoding = options.softwareRenderer,
                        // Beside the database and the mpv config, not in the
                        // installation: Cove never writes to where it is installed.
                        ytDlp = YtDlpProvisioner(
                            DesktopConfigPaths.dataDirectory().resolve("tools"),
                        ),
                    )
                }
                androidx.compose.runtime.DisposableEffect(playerHost) {
                    onDispose { playerHost.dispose() }
                }
                // Also honoured as an environment variable so `make hot` can enter the TV
                // shell: the hot-reload task owns its own process arguments.
                val tvShell = options.tv ||
                    System.getenv("COVE_UI").equals("tv", ignoreCase = true)
                val windowState = rememberWindowState()
                // A 16:9 window at television proportions, so the dev harness resolves the
                // same TvDimens a real panel would rather than a desktop aspect ratio.
                LaunchedEffect(tvShell) {
                    if (tvShell) windowState.size = DpSize(1280.dp, 720.dp)
                }
                // Window placement is the desktop window's business, so the
                // controller is built here and handed to :ui rather than :ui
                // reaching for a window it cannot see.
                val fullscreen = remember(windowState) { WindowFullscreenController(windowState) }
                Window(
                    onCloseRequest = ::exitApplication,
                    state = windowState,
                    title = if (tvShell) "Cove TV" else "Cove",
                    // The window's own icon — what a taskbar, a dock and an alt-tab switcher
                    // show. Separate from the packaged icon, and left as the stock Java one
                    // until now. Drawn from the same vector the UI uses, so there is no second
                    // copy of the mark to keep in step.
                    icon = rememberVectorPainter(CoveLogoVector),
                ) {
                    CoveTheme {
                        if (tvShell) {
                            CoveTvApp(
                                graph = graph,
                                videoPlayerHost = playerHost,
                                onUpdateExitRequested = ::exitApplication,
                                forceOnboarding = options.onboarding,
                            )
                        } else {
                            CoveApp(
                                graph,
                                videoPlayerHost = playerHost,
                                fullscreenController = fullscreen,
                                onUpdateExitRequested = ::exitApplication,
                                forceOnboarding = options.onboarding,
                            )
                        }
                    }
                }
            }
        }
    } finally {
        graph.close()
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

/**
 * Fullscreen backed by the Compose window's placement.
 *
 * Keeps its own flow rather than reading WindowState directly: :ui observes a
 * StateFlow, and WindowState.placement is Compose snapshot state that only a
 * composition can read.
 */
private class WindowFullscreenController(
    private val windowState: WindowState,
) : FullscreenController {
    private val _isFullscreen = MutableStateFlow(windowState.placement == WindowPlacement.Fullscreen)
    override val isFullscreen: StateFlow<Boolean> = _isFullscreen.asStateFlow()

    override fun toggle() {
        val next = if (windowState.placement == WindowPlacement.Fullscreen) {
            WindowPlacement.Floating
        } else {
            WindowPlacement.Fullscreen
        }
        windowState.placement = next
        _isFullscreen.value = next == WindowPlacement.Fullscreen
    }
}
