package com.coveninja.cove.backend.trakt

import com.coveninja.cove.shared.data.TraktRepository
import com.coveninja.cove.shared.data.TraktState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Trakt linking for the UI, in front of [TraktService].
 *
 * The device flow's own polling already lives in the service — it keeps its own
 * job and a flow state per profile — so this watches that state rather than
 * running a second poll loop against Trakt.
 */
class LocalTraktRepository(
    private val service: TraktService,
    private val scope: CoroutineScope,
) : TraktRepository {
    private val _state = MutableStateFlow<TraktState>(TraktState.Loading)
    override val state: StateFlow<TraktState> = _state.asStateFlow()

    private var watcher: Job? = null

    init {
        refresh()
    }

    override suspend fun startLink() {
        if (!service.isConfigured) return
        val code = runCatching { service.startDeviceFlow() }.getOrElse { error ->
            _state.value = TraktState.Unlinked(error.message ?: "Could not reach Trakt.")
            return
        }
        _state.value = TraktState.Pending(
            userCode = code.userCode,
            verificationUrl = code.verificationUrl,
        )
        watchFlow()
    }

    override suspend fun cancelLink() {
        watcher?.cancel()
        watcher = null
        // The service cancels its polling job from unlink(); nothing is stored
        // yet at this point, so this clears the attempt rather than an account.
        service.unlink()
        refresh()
    }

    override suspend fun unlink() {
        watcher?.cancel()
        watcher = null
        service.unlink()
        refresh()
    }

    override suspend fun syncNow() {
        service.enqueueSync()
    }

    private fun refresh() {
        if (!service.isConfigured) {
            _state.value = TraktState.Unconfigured(
                "Trakt is not configured in this build (no client credentials).",
            )
            return
        }
        val status = service.status()
        _state.value = when {
            status.connected -> TraktState.Linked(status.username.ifBlank { "your account" })
            status.flowState == "denied" -> TraktState.Unlinked("Trakt declined the request.")
            status.flowState == "expired" -> TraktState.Unlinked("The code expired. Try again.")
            else -> TraktState.Unlinked()
        }
    }

    private fun watchFlow() {
        watcher?.cancel()
        watcher = scope.launch {
            while (isActive && _state.value is TraktState.Pending) {
                delay(POLL_INTERVAL_MILLIS)
                val status = service.status()
                if (status.connected || status.flowState != "pending") refresh()
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 2_000L
    }
}
