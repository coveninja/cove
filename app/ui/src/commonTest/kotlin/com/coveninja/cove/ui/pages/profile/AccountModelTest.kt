package com.coveninja.cove.ui.pages.profile

import com.coveninja.cove.shared.data.AuthOutcome
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
 * The sync card's words and the sign-in button's enabled state — the two things
 * on the account screen that decide anything, and the only two testable without
 * a renderer.
 */
class AccountModelTest {
    private val now = Instant.parse("2026-08-11T12:00:00Z")

    // Mutation applied to verify: checked `failed` before `running` → this failed,
    // reporting a sync in flight as already broken because the previous attempt
    // had been.
    @Test
    fun `a running sync says so whatever the last one did`() {
        assertEquals("Syncing…", syncHeadline(SyncStatus(running = true)))
        assertEquals(
            "Syncing…",
            syncHeadline(SyncStatus(running = true, failed = true, lastError = "offline")),
        )
        assertEquals(SyncTone.Working, syncTone(SyncStatus(running = true, failed = true)))
    }

    // Mutation applied to verify: made the headline treat any lastError as a
    // failure → this failed on the partial case, which tells someone whose
    // library did sync that nothing did.
    @Test
    fun `a sync that finished with problems is not the same as one that failed`() {
        val partial = SyncStatus(lastSyncedAt = now - 1.minutes, lastError = "addons not merged")
        val failed = SyncStatus(lastSyncedAt = now - 1.minutes, lastError = "offline", failed = true)

        assertEquals("Partly synced", syncHeadline(partial))
        assertEquals("Sync failed", syncHeadline(failed))
        // Both want attention; only one of them lost everything.
        assertEquals(SyncTone.Attention, syncTone(partial))
        assertEquals(SyncTone.Attention, syncTone(failed))
    }

    // Mutation applied to verify: dropped the null-lastSyncedAt branch so it fell
    // through to "Up to date" → this failed; a device that has never synced would
    // claim to be current.
    @Test
    fun `never having synced is not being up to date`() {
        assertEquals("Not synced yet", syncHeadline(SyncStatus()))
        assertEquals(SyncTone.Idle, syncTone(SyncStatus()))
        assertEquals("Up to date", syncHeadline(SyncStatus(lastSyncedAt = now)))
        assertEquals(SyncTone.Settled, syncTone(SyncStatus(lastSyncedAt = now)))
    }

    // Mutation applied to verify: had the detail line prefer the timestamp over
    // the error → this failed, hiding the only text that says what went wrong.
    @Test
    fun `the detail line carries the error when there is one`() {
        assertEquals(
            "library: 409 duplicate key",
            syncDetail(
                SyncStatus(lastSyncedAt = now - 2.minutes, lastError = "library: 409 duplicate key"),
                now,
            ),
        )
    }

    // Mutation applied to verify: dropped the minutes branch so everything under
    // an hour read "just now" → this failed at 5 minutes, which is the difference
    // between "up to date" and "stale" for someone deciding whether to press Sync.
    @Test
    fun `elapsed time is reported at the coarsest useful unit`() {
        assertEquals("Last synced just now.", detail(now - 20.seconds))
        assertEquals("Last synced a minute ago.", detail(now - 70.seconds))
        assertEquals("Last synced 5 minutes ago.", detail(now - 5.minutes))
        assertEquals("Last synced an hour ago.", detail(now - 65.minutes))
        assertEquals("Last synced 3 hours ago.", detail(now - 3.hours))
        assertEquals("Last synced yesterday.", detail(now - 30.hours))
        assertEquals("Last synced 4 days ago.", detail(now - 4.days))
    }

    // Mutation applied to verify: removed the `minutes < 1` guard → this failed
    // with "-1 minutes ago" for a clock a little ahead of the server's.
    @Test
    fun `a timestamp slightly in the future reads as just now`() {
        assertEquals("Last synced just now.", detail(now + 10.seconds))
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

    // Mutation applied to verify: mapped every Success to SignedIn, which is what
    // the onboarding step used to do → this failed on the emailed code, where the
    // viewer would be told they had an account while the app stayed signed out and
    // the code that was sitting in their inbox was never asked for.
    @Test
    fun `an emailed code is only sent, not signed in`() {
        assertEquals(
            AuthFollowUp.AwaitToken,
            authFollowUp(AuthMode.Code, awaitingToken = false, outcome = AuthOutcome.Success),
        )
        // The same Success, once the code has come back, is the real thing.
        assertEquals(
            AuthFollowUp.SignedIn,
            authFollowUp(AuthMode.Code, awaitingToken = true, outcome = AuthOutcome.Success),
        )
    }

    // Mutation applied to verify: dropped the mode check, so any first Success
    // asked for a code → this failed; an ordinary password sign-in would sit there
    // waiting for a token nobody had been sent.
    @Test
    fun `a password sign-in is never waiting on a code`() {
        assertEquals(
            AuthFollowUp.SignedIn,
            authFollowUp(AuthMode.SignIn, awaitingToken = false, outcome = AuthOutcome.Success),
        )
        assertEquals(
            AuthFollowUp.SignedIn,
            authFollowUp(AuthMode.Register, awaitingToken = false, outcome = AuthOutcome.Success),
        )
    }

    // Mutation applied to verify: folded ConfirmationRequired in with Success →
    // this failed; a registration awaiting its emailed token would be reported as
    // a finished sign-in.
    @Test
    fun `a registration awaiting confirmation asks for the code whatever the mode`() {
        AuthMode.entries.forEach { mode ->
            assertEquals(
                AuthFollowUp.AwaitToken,
                authFollowUp(mode, awaitingToken = false, AuthOutcome.ConfirmationRequired),
                "$mode did not ask for the emailed code",
            )
        }
    }

    // Mutation applied to verify: had Failure carry a fixed string rather than the
    // server's → this failed, replacing "Invalid login credentials" with wording
    // that says nothing about what to change.
    @Test
    fun `a failure carries the server's own message and changes nothing else`() {
        assertEquals(
            AuthFollowUp.Failed("Invalid login credentials"),
            authFollowUp(
                AuthMode.SignIn,
                awaitingToken = false,
                AuthOutcome.Failure("Invalid login credentials"),
            ),
        )
        // A refused code leaves the form asking for it, rather than starting over.
        assertEquals(
            AuthFollowUp.Failed("Token has expired or is invalid"),
            authFollowUp(
                AuthMode.Code,
                awaitingToken = true,
                AuthOutcome.Failure("Token has expired or is invalid"),
            ),
        )
    }

    private fun detail(at: Instant) = syncDetail(SyncStatus(lastSyncedAt = at), now)

    private fun canSubmit(
        mode: AuthMode,
        email: String = "a@b.c",
        password: String = "",
        profileName: String = "",
        token: String = "",
    ) = canSubmitAuth(mode, awaitingToken = false, email, password, profileName, token)
}
