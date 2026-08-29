package com.coveninja.cove.ui.state

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * How long the viewer has been watching, and how much of it they have been told about.
 *
 * A sitting rather than a title: it spans episodes, and survives closing the player and
 * starting something else, because "you have been watching for three hours" is a fact about
 * the evening and not about the file. What ends it is a gap with nothing playing, which is
 * why [advanceWatchReminder] is told how long it has been since the last tick.
 *
 * Seconds are carried rather than an instant, as in [SleepTimer]: the composition owns the
 * clock, and everything decided here is decided from numbers alone.
 */
data class WatchReminder(
    val watchedSeconds: Int = 0,
    /** Reminders already shown this sitting. The next is due one interval past the last. */
    val remindersShown: Int = 0,
)

/** The intervals offered in settings, in hours. */
val WATCH_REMINDER_HOURS = listOf(1, 2, 3, 4)

/** Nothing playing for this long ends the sitting; the next thing started begins a new one. */
const val SITTING_GAP_SECONDS = 30 * 60

private const val SECONDS_PER_HOUR = 3_600

/**
 * Adds [elapsedSeconds] to the sitting, or starts a fresh one when [sinceLastTickSeconds]
 * shows nothing has been playing for a while.
 *
 * The gap is passed in rather than measured here so the reset is testable without a clock —
 * and it is measured between *ticks*, which only happen while playback is actually running,
 * so a paused player and a closed one look the same to it. That is deliberate: an hour paused
 * is an hour not watching however the viewer spent it.
 */
fun advanceWatchReminder(
    reminder: WatchReminder,
    elapsedSeconds: Int,
    sinceLastTickSeconds: Int,
): WatchReminder = if (sinceLastTickSeconds >= SITTING_GAP_SECONDS) {
    WatchReminder(watchedSeconds = elapsedSeconds)
} else {
    reminder.copy(watchedSeconds = reminder.watchedSeconds + elapsedSeconds)
}

/** True once another [intervalHours] of watching has gone by unremarked. */
fun watchReminderDue(reminder: WatchReminder, intervalHours: Int): Boolean {
    if (intervalHours <= 0) return false
    val nextAt = (reminder.remindersShown + 1) * intervalHours * SECONDS_PER_HOUR
    return reminder.watchedSeconds >= nextAt
}

/**
 * Records that the viewer has been told, so the next reminder waits a whole interval.
 *
 * Counts intervals actually completed rather than simply adding one: a tick that lands past
 * two of them — a settings change shortening the interval mid-sitting — would otherwise leave
 * the state due again immediately and fire every second until it caught up.
 */
fun markWatchReminderShown(reminder: WatchReminder, intervalHours: Int): WatchReminder {
    if (intervalHours <= 0) return reminder
    val completed = reminder.watchedSeconds / (intervalHours * SECONDS_PER_HOUR)
    return reminder.copy(remindersShown = maxOf(reminder.remindersShown + 1, completed))
}

/**
 * The line the player shows, worded for [localHour] on a 24-hour clock.
 *
 * [hint] is supplied by the shell because the two disagree about where the sleep timer lives —
 * a menu on a pointer, a panel on a remote — and a model that knew that would have to be told
 * about every future shell as well.
 */
fun watchReminderMessage(watchedSeconds: Int, localHour: Int, hint: String): String {
    val hours = (watchedSeconds / SECONDS_PER_HOUR).coerceAtLeast(1)
    val spell = if (hours == 1) "1 hour" else "$hours hours"
    val sentence = when {
        localHour in 0..4 -> "It's past midnight — $spell of watching."
        localHour >= 23 -> "It's getting late — $spell of watching."
        else -> "You've been watching for $spell."
    }
    return if (hint.isBlank()) sentence else "$sentence $hint"
}

// Not part of the model above, and kept out of it on purpose: everything else in this file
// can be tested without a clock. Both shells need the same reading, so it lives here rather
// than twice.
fun currentLocalHour(): Int =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour
