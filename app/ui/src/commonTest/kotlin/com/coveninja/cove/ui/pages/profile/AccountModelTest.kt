package com.coveninja.cove.ui.pages.profile

import com.coveninja.cove.shared.data.SyncStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The sync line and the sign-in button — the only two things on the account page
 * that decide anything, and the only two testable without a renderer.
 */
class AccountModelTest {
    private val now = Instant.parse("2026-08-11T12:00:00Z")

    // Mutation applied to verify: returned the "Synced …" branch before checking
    // `running` → this failed with "Synced just now" while a sync was still in
    // flight, which claims success the moment one starts.
    @Test
    fun `a running sync says so rather than reporting the previous one`() {
        assertEquals(
            "Syncing…",
            syncSummary(SyncStatus(running = true, lastSyncedAt = now - 2.minutes), now),
        )
    }

    // Mutation applied to verify: made a null lastSyncedAt fall through to the
    // relative-time branch → this failed, since a device that has never synced
    // would otherwise claim to have synced at the epoch.
    @Test
    fun `never having synced is not the same as having just synced`() {
        assertEquals("Not synced yet", syncSummary(SyncStatus(), now))
        assertEquals(
            "Not synced",
            syncSummary(SyncStatus(lastError = "Network unreachable"), now),
        )
    }

    // Mutation applied to verify: dropped the minutes branch so everything under
    // an hour read "just now" → this failed at 5 minutes, which is the difference
    // between "up to date" and "stale" for someone deciding whether to press Sync.
    @Test
    fun `elapsed time is reported at the coarsest useful unit`() {
        assertEquals("Synced just now", syncSummary(synced(now - 20.seconds), now))
        assertEquals("Synced a minute ago", syncSummary(synced(now - 70.seconds), now))
        assertEquals("Synced 5 minutes ago", syncSummary(synced(now - 5.minutes), now))
        assertEquals("Synced an hour ago", syncSummary(synced(now - 65.minutes), now))
        assertEquals("Synced 3 hours ago", syncSummary(synced(now - 3.hours), now))
        assertEquals("Synced yesterday", syncSummary(synced(now - 30.hours), now))
        assertEquals("Synced 4 days ago", syncSummary(synced(now - 4.days), now))
    }

    // Mutation applied to verify: removed the `minutes < 1` guard → this failed
    // with "Synced -1 minutes ago" for a clock a little ahead of the server's.
    @Test
    fun `a timestamp slightly in the future reads as just now`() {
        assertEquals("Synced just now", syncSummary(synced(now + 10.seconds), now))
    }

    // Mutation applied to verify: dropped the awaitingToken branch → this failed,
    // because confirming a code would demand the password again and leave the
    // button dead with nothing left to fill in.
    @Test
    fun `confirming a code needs only the code`() {
        assertTrue(
            canSubmitAuth(
                mode = AuthMode.Code,
                awaitingToken = true,
                email = "a@b.c",
                password = "",
                profileName = "",
                token = "123456",
            ),
        )
        assertFalse(
            canSubmitAuth(
                mode = AuthMode.Code,
                awaitingToken = true,
                email = "a@b.c",
                password = "hunter2",
                profileName = "",
                token = "",
            ),
        )
    }

    // Mutation applied to verify: let Register through without a profile name →
    // this failed; the backend requires one and the request would be refused after
    // the round trip instead of before it.
    @Test
    fun `each sign-in path requires exactly its own fields`() {
        assertTrue(canSubmit(AuthMode.SignIn, password = "hunter2"))
        assertFalse(canSubmit(AuthMode.SignIn, password = ""))

        assertTrue(canSubmit(AuthMode.Register, password = "hunter2", profileName = "Cove"))
        assertFalse(canSubmit(AuthMode.Register, password = "hunter2", profileName = ""))

        // Emailing a code needs nothing but the address.
        assertTrue(canSubmit(AuthMode.Code))
    }

    // Mutation applied to verify: dropped the blank-email guard → this failed, and
    // every path would post an empty address the server can only reject.
    @Test
    fun `nothing submits without an email address`() {
        AuthMode.entries.forEach { mode ->
            assertFalse(
                canSubmit(mode, email = "", password = "hunter2", profileName = "Cove"),
                "$mode submitted with no email",
            )
        }
    }

    private fun synced(at: Instant) = SyncStatus(lastSyncedAt = at)

    private fun canSubmit(
        mode: AuthMode,
        email: String = "a@b.c",
        password: String = "",
        profileName: String = "",
        token: String = "",
    ) = canSubmitAuth(mode, awaitingToken = false, email, password, profileName, token)
}
