package com.coveninja.cove.ui.pages.profile

import com.coveninja.cove.shared.data.SyncStatus
import kotlin.time.Instant

/**
 * How to sign in. The three paths share one form; which fields it shows and what
 * the button does are the only differences.
 */
internal enum class AuthMode(val label: String) {
    SignIn("Sign in"),
    Register("Create account"),
    Code("Email a code"),
}

/**
 * The one line under "Cove account" that says whether things are in step.
 *
 * Pure so it can be tested without a clock or a Compose runtime: "synced 2
 * minutes ago" is the whole point of the feature and it should not be verifiable
 * only by looking at the screen.
 */
internal fun syncSummary(status: SyncStatus, now: Instant): String = when {
    status.running -> "Syncing…"
    status.lastSyncedAt == null && status.lastError != null -> "Not synced"
    status.lastSyncedAt == null -> "Not synced yet"
    else -> "Synced ${relativeTime(status.lastSyncedAt!!, now)}"
}

/**
 * Rounds down and stays vague on purpose — a sync time is context, not a
 * measurement, and "3 minutes ago" reads better than a timestamp nobody wants to
 * parse. A clock skewed into the future reads as "just now" rather than
 * something impossible.
 */
private fun relativeTime(then: Instant, now: Instant): String {
    val elapsed = now - then
    val minutes = elapsed.inWholeMinutes
    val hours = elapsed.inWholeHours
    val days = elapsed.inWholeDays
    return when {
        minutes < 1 -> "just now"
        minutes == 1L -> "a minute ago"
        hours < 1 -> "$minutes minutes ago"
        hours == 1L -> "an hour ago"
        days < 1 -> "$hours hours ago"
        days == 1L -> "yesterday"
        else -> "$days days ago"
    }
}

/**
 * Whether the sign-in form has enough to submit.
 *
 * [awaitingToken] means the emailed code is the outstanding field — the password
 * and profile name were already accepted at that point, so requiring them again
 * would leave the button dead with nothing left to type.
 */
internal fun canSubmitAuth(
    mode: AuthMode,
    awaitingToken: Boolean,
    email: String,
    password: String,
    profileName: String,
    token: String,
): Boolean {
    if (email.isBlank()) return false
    if (awaitingToken) return token.isNotBlank()
    return when (mode) {
        AuthMode.SignIn -> password.isNotBlank()
        AuthMode.Register -> password.isNotBlank() && profileName.isNotBlank()
        AuthMode.Code -> true
    }
}
