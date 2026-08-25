package com.coveninja.cove.backend.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The pacing rules for automatic sync.
 *
 * Every case here is a real failure mode rather than a restatement of the code:
 * syncing on every library write, hammering a server that is refusing us, and
 * never syncing at all are the three ways this goes wrong.
 */
class SyncTriggerPolicyTest {
    private val policy = SyncTriggerPolicy(
        debounce = 30.seconds,
        minimumInterval = 2.minutes,
        period = 15.minutes,
    )
    private val start = Instant.parse("2026-08-11T12:00:00Z")

    @Test
    fun `syncs immediately when nothing has been attempted yet`() {
        assertEquals(SyncDecision.Now, decide(now = start))
    }

    @Test
    fun `does nothing while a sync is already running`() {
        assertEquals(
            SyncDecision.Idle,
            decide(now = start, running = true, lastAttemptAt = start - 1.minutes),
        )
    }

    @Test
    fun `does nothing when auto-sync is switched off`() {
        assertEquals(
            SyncDecision.Idle,
            decide(now = start, autoSyncEnabled = false, lastAttemptAt = null),
        )
    }

    @Test
    fun `does nothing while signed out`() {
        assertEquals(SyncDecision.Idle, decide(now = start, signedIn = false))
    }

    @Test
    fun `waits out the debounce after a local change`() {
        val decision = decide(
            now = start,
            lastAttemptAt = start - 10.minutes,
            lastSyncedAt = start - 10.minutes,
            pendingChangeAt = start - 10.seconds,
        )
        assertEquals(20.seconds, assertIs<SyncDecision.Wait>(decision).delay)
    }

    @Test
    fun `syncs once the change has settled`() {
        assertEquals(
            SyncDecision.Now,
            decide(
                now = start,
                lastAttemptAt = start - 10.minutes,
                lastSyncedAt = start - 10.minutes,
                pendingChangeAt = start - 31.seconds,
            ),
        )
    }

    @Test
    fun `holds the minimum interval even when a change is pending`() {
        val decision = decide(
            now = start,
            lastAttemptAt = start - 30.seconds,
            lastSyncedAt = start - 30.seconds,
            pendingChangeAt = start - 30.seconds,
        )
        assertEquals(90.seconds, assertIs<SyncDecision.Wait>(decision).delay)
    }

    @Test
    fun `paces retries after a failed sync`() {
        val decision = decide(
            now = start,
            lastAttemptAt = start - 30.seconds,
            lastSyncedAt = null,
            pendingChangeAt = start - 5.minutes,
        )
        assertEquals(90.seconds, assertIs<SyncDecision.Wait>(decision).delay)
    }

    @Test
    fun `syncs on the heartbeat with nothing pending`() {
        assertEquals(
            SyncDecision.Now,
            decide(
                now = start,
                lastAttemptAt = start - 16.minutes,
                lastSyncedAt = start - 16.minutes,
            ),
        )
        val early = decide(
            now = start,
            lastAttemptAt = start - 5.minutes,
            lastSyncedAt = start - 5.minutes,
        )
        assertEquals(10.minutes, assertIs<SyncDecision.Wait>(early).delay)
    }

    private fun decide(
        now: Instant,
        autoSyncEnabled: Boolean = true,
        signedIn: Boolean = true,
        running: Boolean = false,
        lastAttemptAt: Instant? = null,
        lastSyncedAt: Instant? = null,
        pendingChangeAt: Instant? = null,
    ) = policy.decide(
        now = now,
        autoSyncEnabled = autoSyncEnabled,
        signedIn = signedIn,
        running = running,
        lastAttemptAt = lastAttemptAt,
        lastSyncedAt = lastSyncedAt,
        pendingChangeAt = pendingChangeAt,
    )
}
