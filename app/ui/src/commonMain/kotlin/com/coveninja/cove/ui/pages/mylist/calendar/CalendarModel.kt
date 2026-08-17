package com.coveninja.cove.ui.pages.mylist.calendar

import com.coveninja.cove.shared.model.CalendarItem
import com.coveninja.cove.ui.model.displayImageUrl
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.yearMonth

/** One day's worth of the schedule, ready to render as a section. */
data class CalendarDay(
    val date: LocalDate,
    val label: String,
    val relative: String,
    val items: List<CalendarItem>,
) {
    val key: String get() = date.toString()
}

/**
 * Items that are watchable right now.
 *
 * Kept apart from the day sections because their dates are backwards-looking and can be
 * arbitrarily old — an episode that aired months ago is still "available", and burying it
 * in the month it aired would hide the very thing the viewer opened the calendar for.
 */
fun availableNow(items: List<CalendarItem>): List<CalendarItem> =
    items.filter { it.available }.sortedWith(
        compareByDescending<CalendarItem> { it.date }.thenBy { it.title.lowercase() },
    )

/** The scheduled (not-yet-available) items falling inside [month]. */
fun itemsInMonth(items: List<CalendarItem>, month: YearMonth): List<CalendarItem> =
    items.filter { item -> !item.available && item.parsedDate()?.yearMonth == month }

/**
 * Groups into day sections, oldest first. Items whose date will not parse are dropped:
 * a schedule entry with no usable day has nothing to say on a calendar.
 */
fun groupByDay(items: List<CalendarItem>, today: LocalDate): List<CalendarDay> =
    items
        .mapNotNull { item -> item.parsedDate()?.let { date -> date to item } }
        .groupBy({ it.first }, { it.second })
        .toSortedMap()
        .map { (date, dayItems) ->
            CalendarDay(
                date = date,
                label = dayLabel(date, today),
                relative = countdownLabel(date, today),
                items = dayItems.sortedBy { it.title.lowercase() },
            )
        }

/** "Today", "Tomorrow", "Yesterday", or "Sat 12 Aug". */
fun dayLabel(date: LocalDate, today: LocalDate): String = when (today.daysUntil(date)) {
    0 -> "Today"
    1 -> "Tomorrow"
    -1 -> "Yesterday"
    else -> "${WEEKDAYS[date.dayOfWeek.isoDayNumber - 1]} ${date.day} ${MONTHS_SHORT[date.month.number - 1]}"
}

/** "today", "in 3 days", "3 days ago" — the countdown beside a row. */
fun countdownLabel(date: LocalDate, today: LocalDate): String {
    val days = today.daysUntil(date)
    return when {
        days == 0 -> "today"
        days == 1 -> "tomorrow"
        days == -1 -> "yesterday"
        days > 0 -> "in $days days"
        else -> "${-days} days ago"
    }
}

/** "August 2026", for the month header. */
fun monthLabel(month: YearMonth): String =
    "${MONTHS_FULL[month.month.number - 1]} ${month.year}"

/**
 * "Aug 2026", for the month header on a phone.
 *
 * The bar carries three 48 dp touch targets and the Today chip alongside the label, which on a
 * 360 dp screen leaves the label under 60 dp — not enough for a full month name, so it would
 * ellipsize to "Aug…" and lose the year anyway. Abbreviating keeps the whole date readable.
 */
fun monthLabelShort(month: YearMonth): String =
    "${MONTHS_SHORT[month.month.number - 1]} ${month.year}"

fun CalendarItem.parsedDate(): LocalDate? =
    runCatching { LocalDate.parse(date) }.getOrNull()

/**
 * The thumbnail for a calendar row, as a URL Coil can actually load.
 *
 * Calendar items carry TMDB's own paths (`/abc.jpg`), not the absolute proxied URLs the
 * rest of the UI passes around, so they need [displayImageUrl] — which prefixes a bare
 * path and leaves an already-absolute URL alone. Handing them to `tmdbImageSize` instead
 * yields the raw path back unchanged and silently renders nothing.
 *
 * The poster comes first because every title has one; an episode still is the fallback for
 * the rare item with no poster, so the row is never blank when any art exists.
 */
fun calendarImageUrl(item: CalendarItem, size: String = "w185"): String? =
    displayImageUrl(item.posterPath.ifBlank { item.stillPath }, size)

/**
 * Episode identity as `S3 E7`, or null for movies. Separate from the row composable so the
 * formatting is testable and cannot differ between the calendar and My List.
 */
fun CalendarItem.episodeMarker(): String? {
    val season = seasonNumber ?: return null
    val episode = episodeNumber ?: return null
    return "S$season E$episode"
}

// The app has no message catalog wired up yet (see i18n/messages/README), so these are
// plain English tables rather than a formatter that would imply localization it lacks.
private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

private val MONTHS_SHORT = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

private val MONTHS_FULL = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)
