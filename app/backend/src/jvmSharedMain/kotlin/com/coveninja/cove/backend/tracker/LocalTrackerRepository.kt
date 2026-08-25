package com.coveninja.cove.backend.tracker

import com.coveninja.cove.shared.data.TrackerRepository
import com.coveninja.cove.shared.data.TrackerState
import com.coveninja.cove.shared.model.TrackerProvider
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Tracker linking for the UI, in front of a [TrackerService].
 *
 * The device flow's own polling already lives in the service — it keeps its own job and a
 * flow state per profile — so this watches that state rather than running a second poll
 * loop against the tracker.
 */
class LocalTrackerRepository(
    private val service: TrackerService,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) : TrackerRepository {
    override val provider: TrackerProvider get() = service.provider

    private val _state = MutableStateFlow<TrackerState>(TrackerState.Loading)
    override val state: StateFlow<TrackerState> = _state.asStateFlow()

    private var watcher: Job? = null

    init {
        refresh()
    }

    override suspend fun startLink() {
        if (!service.isConfigured) return
        val code = runCatching { service.startDeviceFlow() }.getOrElse { error ->
            _state.value = TrackerState.Unlinked(
                error.message ?: "Could not reach ${provider.label}.",
            )
            return
        }
        _state.value = TrackerState.Pending(
            userCode = code.userCode,
            verificationUrl = code.verificationUrl,
            expiresAt = code.expiresIn.takeIf { it > 0 }?.let { clock.now() + it.seconds },
        )
        watchFlow()
    }

    override suspend fun cancelLink() {
        watcher?.cancel()
        watcher = null
        // The service cancels its polling job from unlink(); nothing is stored yet at this
        // point, so this clears the attempt rather than an account.
        service.unlink()
        refresh()
    }

    override suspend fun unlink() {
        watcher?.cancel()
        watcher = null
        service.unlink()
        refresh()
    }

    /**
     * Runs the sync and reports it, rather than queueing it and returning.
     *
     * `enqueueSync()` would be less code, but it answers a press with nothing at all: no
     * spinner, no landing, no reason when it declines. Awaiting the cycle is what lets the
     * card say which of those happened.
     */
    override suspend fun syncNow() {
        val linked = _state.value as? TrackerState.Linked ?: return
        if (linked.syncing) return
        _state.value = linked.copy(syncing = true, syncError = null)
        val outcome = runCatching { service.syncNow() }
        val failure = outcome.exceptionOrNull()?.message
            ?: outcome.getOrNull()?.takeIf { !it.completed }?.let(::declineReason)
        refresh(syncError = failure)
    }

    /**
     * `syncNow` reports a refusal as a reason code, not a message: it is answering the HTTP
     * route as well as this, so the wording belongs here where there is a reader.
     */
    private fun declineReason(result: TrackerSyncResult): String? = when (result.reason) {
        "disabled" -> "Turn on library sync below to reconcile with ${provider.label}."
        "not_connected", "unlinked_during_sync" -> "That account is no longer connected."
        else -> "${provider.label} did not finish the sync."
    }

    private fun refresh(syncError: String? = null) {
        if (!service.isConfigured) {
            _state.value = TrackerState.Unconfigured(
                "${provider.label} is not configured in this build (no client credentials).",
            )
            return
        }
        val status = service.status()
        _state.value = when {
            status.connected -> TrackerState.Linked(
                username = status.username.ifBlank { "your account" },
                lastSyncAt = status.lastSyncAt.toInstantOrNull(),
                syncError = syncError,
            )
            status.flowState == DeviceLinkCoordinator.DENIED ->
                TrackerState.Unlinked("${provider.label} declined the request.")
            status.flowState == DeviceLinkCoordinator.EXPIRED ->
                TrackerState.Unlinked("The code expired. Try again.")
            else -> TrackerState.Unlinked()
        }
    }

    private fun watchFlow() {
        watcher?.cancel()
        watcher = scope.launch {
            while (isActive && _state.value is TrackerState.Pending) {
                delay(POLL_INTERVAL_MILLIS)
                val status = service.status()
                if (status.connected || status.flowState != DeviceLinkCoordinator.PENDING) refresh()
            }
        }
    }

    private fun String.toInstantOrNull(): Instant? =
        takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 2_000L
    }
}
