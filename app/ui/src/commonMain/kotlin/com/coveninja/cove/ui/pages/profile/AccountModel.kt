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
 * What the sync mark is showing.
 *
 * [Attention] is deliberately its own state rather than a shade of [Settled]: a
 * sync that finished but left rows behind is neither fine nor broken, and the
 * mark is the only thing on screen that says which.
 */
internal enum class SyncTone { Settled, Working, Attention, Idle }

/**
 * The headline of the sync card — the answer to "is this device in step?", in
 * three words or fewer, so the card can be read at a glance rather than parsed.
 */
internal fun syncHeadline(status: SyncStatus): String = when {
    status.running -> "Syncing…"
    status.failed -> "Sync failed"
    status.lastError != null -> "Partly synced"
    status.lastSyncedAt == null -> "Not synced yet"
    else -> "Up to date"
}

/**
 * The supporting line: when it last worked, or what went wrong.
 *
 * The error text comes from the server and is passed through verbatim — it says
 * which rows or which push, and inventing a friendlier sentence here would throw
 * away the only detail worth having.
 */
internal fun syncDetail(status: SyncStatus, now: Instant): String = when {
    status.lastError != null -> status.lastError!!
    status.running -> "Checking what changed on your other devices."
    status.lastSyncedAt == null -> "Nothing has reached your account from this device yet."
    else -> "Last synced ${relativeTime(status.lastSyncedAt!!, now)}."
}

internal fun syncTone(status: SyncStatus): SyncTone = when {
    status.running -> SyncTone.Working
    status.failed || status.lastError != null -> SyncTone.Attention
    status.lastSyncedAt != null -> SyncTone.Settled
    else -> SyncTone.Idle
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
