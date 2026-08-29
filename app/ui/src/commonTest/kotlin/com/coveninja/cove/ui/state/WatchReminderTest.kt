package com.coveninja.cove.ui.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The watch reminder: how a sitting is counted, and when it is worth saying so.
 *
 * The failure everything here is guarding against is the same one: a nudge meant to arrive
 * twice an evening arriving once a second. Anything that leaves the state due after it has
 * been shown does exactly that, and none of it is visible from a green build.
 */
class WatchReminderTest {

    private val hour = 3_600

    @Test
    fun `a fresh sitting owes nothing`() {
        assertEquals(0, WatchReminder().watchedSeconds)
        assertFalse(watchReminderDue(WatchReminder(), intervalHours = 1))
    }

    // Mutation check: making the comparison strictly greater leaves the first reminder one
    // second late, which passes everywhere except the exact-interval assertion here.
    @Test
    fun `an interval of watching comes due exactly once`() {
        val short = advanceWatchReminder(WatchReminder(), hour - 1, sinceLastTickSeconds = 1)
        assertFalse(watchReminderDue(short, intervalHours = 1), "a second short of the hour")

        val due = advanceWatchReminder(short, 1, sinceLastTickSeconds = 1)
        assertTrue(watchReminderDue(due, intervalHours = 1))

        val shown = markWatchReminderShown(due, intervalHours = 1)
        assertFalse(watchReminderDue(shown, intervalHours = 1), "shown once, not once a second")
    }

    // Mutation check: dropping the +1 from the next-due point makes the reminder due again
    // immediately after being shown, which the assertion above catches, while dropping the
    // multiplication by remindersShown makes the *second* one never arrive — only this does.
    @Test
    fun `the next reminder waits a whole interval`() {
        var reminder = WatchReminder(watchedSeconds = hour, remindersShown = 1)
        reminder = advanceWatchReminder(reminder, hour - 1, sinceLastTickSeconds = 1)
        assertFalse(watchReminderDue(reminder, intervalHours = 1), "a second short of the second")

        reminder = advanceWatchReminder(reminder, 1, sinceLastTickSeconds = 1)
        assertTrue(watchReminderDue(reminder, intervalHours = 1))
    }

    // Mutation check: adding one to remindersShown rather than counting completed intervals
    // leaves this due at once, and it stays due for the next hour of ticks.
    @Test
    fun `shortening the interval mid-sitting does not fire every second`() {
        val threeHours = WatchReminder(watchedSeconds = 3 * hour, remindersShown = 1)
        assertTrue(watchReminderDue(threeHours, intervalHours = 1), "two hours went unremarked")

        val shown = markWatchReminderShown(threeHours, intervalHours = 1)
        assertEquals(3, shown.remindersShown, "caught up rather than incremented")
        assertFalse(watchReminderDue(shown, intervalHours = 1))
    }

    // Mutation check: comparing the gap with > rather than >= makes a gap of exactly the
    // window carry the old sitting forward.
    @Test
    fun `a gap with nothing playing starts the sitting again`() {
        val evening = WatchReminder(watchedSeconds = 2 * hour, remindersShown = 1)

        val resumed = advanceWatchReminder(evening, 1, sinceLastTickSeconds = SITTING_GAP_SECONDS)
        assertEquals(1, resumed.watchedSeconds, "counts from this tick, not from tonight")
        assertEquals(0, resumed.remindersShown, "and owes the viewer a reminder again")

        val continued = advanceWatchReminder(evening, 1, SITTING_GAP_SECONDS - 1)
        assertEquals(2 * hour + 1, continued.watchedSeconds, "a shorter gap is the same sitting")
        assertEquals(1, continued.remindersShown)
    }

    // Mutation check: dropping the guard makes an interval of zero due on every tick, which
    // is what a settings value of 0 would do if one ever reached here.
    @Test
    fun `an interval of zero is never due`() {
        val watched = WatchReminder(watchedSeconds = 5 * hour)
        assertFalse(watchReminderDue(watched, intervalHours = 0))
        assertEquals(watched, markWatchReminderShown(watched, intervalHours = 0))
    }

    @Test
    fun `the message counts whole hours`() {
        assertEquals(
            "You've been watching for 1 hour.",
            watchReminderMessage(hour, localHour = 14, hint = ""),
        )
        // Mutation check: rounding up rather than down makes 2h59m read as three hours.
        assertEquals(
            "You've been watching for 2 hours.",
            watchReminderMessage(2 * hour + 3_599, localHour = 14, hint = ""),
        )
    }

    // Mutation check: the interval is at least an hour, so a message about "0 hours" cannot
    // arrive from the player — but it can from a test or a future caller, and it reads as a
    // bug rather than as a nudge.
    @Test
    fun `a message never claims zero hours`() {
        assertEquals(
            "You've been watching for 1 hour.",
            watchReminderMessage(watchedSeconds = 12, localHour = 14, hint = ""),
        )
    }

    // Mutation check: an inclusive upper bound on the small hours, or a `> 23`, moves the
    // boundary by one and the wording is wrong for a whole hour of the night.
    @Test
    fun `the wording knows what time it is`() {
        val late = listOf(23, 0, 4)
        val ordinary = listOf(5, 12, 22)

        assertEquals(
            "It's getting late — 2 hours of watching.",
            watchReminderMessage(2 * hour, localHour = 23, hint = ""),
        )
        assertEquals(
            "It's past midnight — 2 hours of watching.",
            watchReminderMessage(2 * hour, localHour = 0, hint = ""),
        )
        assertTrue(
            late.all { !watchReminderMessage(hour, it, hint = "").startsWith("You've") },
            "23:00 through 04:59 is late",
        )
        assertTrue(
            ordinary.all { watchReminderMessage(hour, it, hint = "").startsWith("You've") },
            "05:00 through 22:59 is not",
        )
    }

    // The two shells name different places to find the sleep timer, which is the only reason
    // the hint is a parameter at all.
    @Test
    fun `the hint is appended when there is one`() {
        assertEquals(
            "You've been watching for 1 hour. Sleep timer's in the panel.",
            watchReminderMessage(hour, localHour = 14, hint = "Sleep timer's in the panel."),
        )
        assertFalse(
            watchReminderMessage(hour, localHour = 14, hint = "  ").endsWith(" "),
            "a blank hint leaves no trailing space",
        )
    }

    @Test
    fun `the settings intervals are the ones the model counts in`() {
        assertEquals(listOf(1, 2, 3, 4), WATCH_REMINDER_HOURS)
        assertTrue(WATCH_REMINDER_HOURS.all { it > 0 }, "an interval of zero would never fire")
    }
}
