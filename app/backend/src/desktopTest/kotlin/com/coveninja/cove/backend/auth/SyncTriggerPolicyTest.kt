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

    // Mutation applied to verify: returned Idle instead of Now for a null
    // lastAttemptAt → this failed, because a freshly launched app would then never
    // sync until something local changed.
    @Test
    fun `syncs immediately when nothing has been attempted yet`() {
        assertEquals(SyncDecision.Now, decide(now = start))
    }

    // Mutation applied to verify: dropped the `running` check → this failed with
    // Now, which is the overlapping-sync bug the mutex exists to prevent.
    @Test
    fun `does nothing while a sync is already running`() {
        assertEquals(
            SyncDecision.Idle,
            decide(now = start, running = true, lastAttemptAt = start - 1.minutes),
        )
    }

    // Mutation applied to verify: removed the autoSyncEnabled guard → this failed
    // with Now, i.e. the opt-out toggle would not actually opt out.
    @Test
    fun `does nothing when auto-sync is switched off`() {
        assertEquals(
            SyncDecision.Idle,
            decide(now = start, autoSyncEnabled = false, lastAttemptAt = null),
        )
    }

    // Mutation applied to verify: removed the signedIn guard → this failed with
    // Now, which would sync with no session and error on every tick.
    @Test
    fun `does nothing while signed out`() {
        assertEquals(SyncDecision.Idle, decide(now = start, signedIn = false))
    }

    // Mutation applied to verify: dropped the debounce and returned Now for any
    // pending change → this failed, because it is exactly the "sync on every
    // keystroke of a library edit" behaviour the debounce exists to stop.
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

    // Mutation applied to verify: used `period` in place of `debounce` for the
    // pending branch → this failed with a 14½-minute wait, i.e. an edit would sit
    // unsynced for a quarter of an hour.
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

    // Mutation applied to verify: dropped the `floor` term from the pending-change
    // branch → this failed with Now, meaning a busy library could sync every 30
    // seconds indefinitely.
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

    // Mutation applied to verify: used lastSyncedAt instead of lastAttemptAt for
    // the floor → this failed with Now and retried a failing sync on every wake-up,
    // which is the offline hot-loop.
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

    // Mutation applied to verify: removed the periodic branch (returning Idle with
    // no pending change) → this failed, and a device left alone would drift out of
    // date until something local changed.
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
