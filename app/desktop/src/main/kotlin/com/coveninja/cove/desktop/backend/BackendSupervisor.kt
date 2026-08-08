package com.coveninja.cove.desktop.backend

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** The Go backend exits with this code after applying a self-update so the
 *  shell can re-exec and load the new binary.  It is not a crash. */
private const val EXIT_UPDATE_SENTINEL = 42

data class SupervisorConfig(
    val executable: Path,
    val port: Int = 6969,
    val startupTimeoutMillis: Long = 20_000,
    val maxRestarts: Int = 3,
    val restartWindowMillis: Long = 60_000,
)

/**
 * Manages the Go backend sidecar: spawning, readiness probing, crash recovery
 * with a rolling-window budget, and update-triggered restarts (exit code 42).
 *
 * All state mutations happen inside [scope] coroutines; the public API is
 * safe to call from any thread.
 *
 * @param processFactory Injectable so tests drive the supervisor with a fake
 *   process that never touches the real filesystem or network.
 * @param probeFactory Injectable for the same reason; called once per spawn attempt.
 * @param portChecker Returns `true` if something is already bound on [port].
 *   Injectable so tests avoid the real network.
 * @param restartPolicy Injectable so tests control clock-dependent behavior
 *   without sleeping.
 */
class BackendSupervisor(
    private val config: SupervisorConfig,
    private val processFactory: BackendProcessFactory = RealBackendProcessFactory,
    private val probeFactory: (port: Int, timeoutMillis: Long) -> ReadinessProbe =
        { port, timeout -> HttpReadinessProbe(port, timeout) },
    private val portChecker: (Int) -> Boolean = ::isPortOccupied,
    private val restartPolicy: RestartPolicy = RestartPolicy(
        maxRestarts = config.maxRestarts,
        restartWindowMillis = config.restartWindowMillis,
    ),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private val _state = MutableStateFlow<BackendState>(BackendState.Starting)
    val state: StateFlow<BackendState> = _state.asStateFlow()

    @Volatile private var currentProcess: ManagedProcess? = null

    private val shutdownHook = Thread(::stopNow, "cove-backend-shutdown")

    fun start() {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        scope.launch { supervise() }
    }

    fun stop() {
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        scope.cancel()
        stopNow()
    }

    // May be called from the JVM shutdown hook — must work without a
    // coroutine context and must not throw.
    private fun stopNow() {
        currentProcess?.also { p ->
            p.destroy()
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    private suspend fun supervise() {
        while (true) {
            // Propagate scope cancellation (e.g. stop() call) before each attempt.
            currentCoroutineContext().ensureActive()
            _state.value = BackendState.Starting

            if (portChecker(config.port)) {
                _state.value = BackendState.Failed(
                    "port ${config.port} is already in use; refusing to spawn a second backend"
                )
                return
            }

            val parentPid = ProcessHandle.current().pid()
            val process = processFactory.spawn(config.executable, config.port, parentPid)
            currentProcess = process
            startOutputReader(process)

            val probe = probeFactory(config.port, config.startupTimeoutMillis)
            val action = startupPhase(process, probe)
            currentProcess = null

            when (action) {
                is LifecycleAction.Restart -> {
                    // Exit-42 update restart: no crash budget consumed, no backoff.
                    _state.value = BackendState.RestartRequested
                    // Yield so StateFlow observers get a chance to see RestartRequested
                    // before the loop immediately transitions back to Starting.  Without
                    // this yield the transition is effectively atomic from the collector's
                    // perspective and the state is conflated away.
                    yield()
                }
                is LifecycleAction.Backoff -> {
                    // Regular crash with budget remaining; stay in Starting while
                    // we wait — the UI can show "recovering…" based on Starting state.
                    delay(action.delayMillis)
                }
                is LifecycleAction.Stop -> {
                    _state.value = action.terminalState
                    return
                }
            }
        }
    }

    /**
     * Races the readiness probe against the process exiting prematurely.
     * Whichever resolves first determines the outcome.
     */
    private suspend fun startupPhase(
        process: ManagedProcess,
        probe: ReadinessProbe,
    ): LifecycleAction = coroutineScope {
        val exitDeferred = async { process.awaitExit() }
        val probeDeferred = async { probe.awaitReady() }

        select {
            exitDeferred.onAwait { code ->
                probeDeferred.cancel()
                if (code == EXIT_UPDATE_SENTINEL) LifecycleAction.Restart
                else LifecycleAction.Stop(
                    BackendState.Failed(
                        "backend exited before becoming ready (exit code $code)"
                    )
                )
            }
            probeDeferred.onAwait { ready ->
                if (!ready) {
                    // Probe timed out — kill the process that never answered.
                    exitDeferred.cancel()
                    process.destroy()
                    LifecycleAction.Stop(
                        BackendState.Failed(
                            "backend did not respond within ${config.startupTimeoutMillis} ms"
                        )
                    )
                } else {
                    _state.value = BackendState.Ready
                    postReadyPhase(exitDeferred)
                }
            }
        }
    }

    /**
     * Waits for the already-ready process to exit and maps the exit code to
     * the next loop action.  Exit-42 is never counted against the crash budget.
     */
    private suspend fun postReadyPhase(exitDeferred: Deferred<Int>): LifecycleAction {
        val code = exitDeferred.await()
        if (code == EXIT_UPDATE_SENTINEL) return LifecycleAction.Restart

        val delayMs = restartPolicy.recordCrash()
            ?: return LifecycleAction.Stop(
                BackendState.Failed(
                    "backend crashed too many times (last exit code $code)"
                )
            )

        return LifecycleAction.Backoff(delayMs)
    }

    /** Forwards merged process output to stdout with a `[go]` prefix.  The
     *  thread is daemon so it cannot prevent JVM exit after the app closes. */
    private fun startOutputReader(process: ManagedProcess) {
        Thread({
            runCatching {
                process.inputStream().bufferedReader().forEachLine { line ->
                    println("[go] $line")
                }
            }.onFailure { error ->
                // stop() closes the process stream while this daemon thread may
                // still be blocked in readLine(). That is an expected shutdown,
                // not a backend crash worth printing a stack trace for.
                val expectedClose = error is IOException && error.message == "Stream closed"
                if (scope.isActive && !expectedClose) {
                    System.err.println("[go] output reader stopped: ${error.message}")
                }
            }
        }, "cove-go-output").apply { isDaemon = true }.start()
    }
}

// ── Internal result type for what the main supervise loop should do next ─────

private sealed interface LifecycleAction {
    /** Exit-42 sentinel: restart immediately without consuming crash budget. */
    data object Restart : LifecycleAction
    /** Normal crash within budget: wait [delayMillis] before next spawn. */
    data class Backoff(val delayMillis: Long) : LifecycleAction
    /** Terminal condition: emit [terminalState] and stop the loop. */
    data class Stop(val terminalState: BackendState) : LifecycleAction
}

/** Returns `true` if something is already accepting TCP connections on [port]. */
private fun isPortOccupied(port: Int): Boolean =
    try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200); true }
    } catch (_: Exception) {
        false
    }
