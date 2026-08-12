package com.coveninja.cove.backend.http

import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import com.coveninja.cove.shared.data.SettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LocalBackendHost(
    private val services: CoreRouteServices,
    private val host: String = "127.0.0.1",
    private val port: Int = 6969,
    private val remoteHost: String = "0.0.0.0",
    private val remotePort: Int = port + 1,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopbackServer: EmbeddedServer<*, *>? = null
    private var remoteServer: EmbeddedServer<*, *>? = null
    private var closed = false

    @Synchronized
    fun start() {
        check(!closed) { "backend host is closed" }
        check(loopbackServer == null) { "backend host is already running" }
        loopbackServer = embeddedServer(CIO, host = host, port = port) {
            configureCoreRoutes(services, ApiAccess.Loopback)
        }.also { it.start(wait = false) }
        reconcileRemoteAccess()
        scope.launch {
            services.settings.settings.collectLatest { state ->
                if (state is SettingsState.Ready) reconcileRemoteAccess()
            }
        }
    }

    @Synchronized
    private fun reconcileRemoteAccess() {
        if (closed) return
        val enabled = services.settings.current().remoteAccessEnabled
        if (enabled && remoteServer == null) {
            val candidate = embeddedServer(CIO, host = remoteHost, port = remotePort) {
                configureCoreRoutes(services, ApiAccess.Remote)
            }
            runCatching { candidate.start(wait = false) }
                .onSuccess { remoteServer = candidate }
                .onFailure {
                    candidate.stop(0, 0)
                    System.err.println("Cove remote-access listener failed on $remoteHost:$remotePort: ${it.message}")
                }
        } else if (!enabled && remoteServer != null) {
            remoteServer?.stop(500, 2_000)
            remoteServer = null
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        remoteServer?.stop(500, 2_000)
        remoteServer = null
        loopbackServer?.stop(500, 2_000)
        loopbackServer = null
    }
}
