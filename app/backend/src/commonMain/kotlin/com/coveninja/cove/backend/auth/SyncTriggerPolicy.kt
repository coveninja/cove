package com.coveninja.cove.backend.auth

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** What the sync loop should do next. */
sealed interface SyncDecision {
    data object Now : SyncDecision

    /** Nothing to do until [delay] has passed, or until an input changes. */
    data class Wait(val delay: Duration) : SyncDecision

    /** Nothing to do at all until an input changes — signed out, or auto-sync off. */
    data object Idle : SyncDecision
}

/**
 * When an automatic sync is allowed to run.
 *
 * Pure on purpose: the loop that drives it needs a clock, a network and a
 * database, and none of those belong in a test of "does a burst of library
 * writes collapse into one sync".
 *
 * Two separate timestamps matter. [lastAttemptAt] paces retries — without it a
 * sync that fails while offline would be retried immediately, forever, because
 * [lastSyncedAt] never advances. [lastSyncedAt] paces the ordinary heartbeat.
 */
class SyncTriggerPolicy(
    /** How long local changes must settle before they are worth a sync. */
    val debounce: Duration = 30.seconds,
    /** Floor between two automatic syncs, whatever asked for them. */
    val minimumInterval: Duration = 2.minutes,
    /** Heartbeat when nothing local has changed. */
    val period: Duration = 15.minutes,
) {
    fun decide(
        now: Instant,
        autoSyncEnabled: Boolean,
        signedIn: Boolean,
        running: Boolean,
        lastAttemptAt: Instant?,
        lastSyncedAt: Instant?,
        /** When local data last changed, or null if nothing is pending. */
        pendingChangeAt: Instant?,
    ): SyncDecision {
        if (!autoSyncEnabled || !signedIn || running) return SyncDecision.Idle
        // Nothing has been attempted this session: sync at once, so the app comes
        // up already agreeing with the server.
        if (lastAttemptAt == null) return SyncDecision.Now

        val floor = lastAttemptAt + minimumInterval
        val target = if (pendingChangeAt != null) {
            maxOf(pendingChangeAt + debounce, floor)
        } else {
            maxOf((lastSyncedAt ?: lastAttemptAt) + period, floor)
        }
        return if (target <= now) SyncDecision.Now else SyncDecision.Wait(target - now)
    }
}
