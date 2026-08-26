package com.coveninja.cove.backend.activity

import com.coveninja.cove.shared.model.WatchMoment
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/**
 * The moment arithmetic, kept apart from the service that runs it.
 *
 * Pure on purpose. `ActivityService` needs a database, a clock and a profile before it can
 * answer anything, which makes every question about *the arithmetic* expensive to ask; these
 * take two lists and a year. Everything here is derived from the same `activity_hours` and
 * `activity_titles` rows `stats()` has already loaded, so the moments cost no extra query —
 * which is the only reason they are worth having at all, given the page they feed is already
 * one of the slower things in the app.
 *
 * Rows arrive as plain strings rather than SQLDelight types so this file has no dependency
 * on the generated schema, and no dependency on `java.time` either — dates are compared as
 * ISO text, which sorts correctly, and only the one place that genuinely needs to know what
 * tomorrow is reaches for a date at all.
 */

/** One `activity_hours` row, reduced to what the moments need. */
data class HourCell(val date: String, val hour: Int, val seconds: Long)

/** One `activity_titles` row, reduced to what the moments need. */
data class TitleCell(val date: String, val titleKey: String, val seconds: Long)

/** What the library knows a `title_key` by, when it still knows it at all. */
data class TitleName(val title: String, val posterPath: String)

/** Everything [buildMoments] produces, in one value so the caller destructures once. */
data class MomentSet(
    val biggestDay: WatchMoment? = null,
    val monthlyHeadliners: List<WatchMoment> = emptyList(),
    val longestSession: WatchMoment? = null,
    val firstWatch: WatchMoment? = null,
)

/**
 * Every dated moment the activity tables can support, for one range.
 *
 * [selectedYear] narrows to a single year, or covers everything when null. Filtering is done
 * on the ISO date's leading four characters rather than by parsing: these lists run to
 * thousands of rows on an established profile, and parsing every one of them to answer
 * "is this 2026?" is work with nothing to show for it.
 */
fun buildMoments(
    hours: List<HourCell>,
    titles: List<TitleCell>,
    names: Map<String, TitleName>,
    selectedYear: Int?,
): MomentSet {
    val prefix = selectedYear?.let { "$it-" }
    fun inRange(date: String) = prefix == null || date.startsWith(prefix)

    val hoursInRange = hours.filter { it.seconds > 0 && inRange(it.date) }
    val titlesInRange = titles.filter { it.seconds > 0 && inRange(it.date) }
    if (hoursInRange.isEmpty()) return MomentSet()

    val dayTotals = hoursInRange.groupingBy { it.date }.fold(0L) { sum, cell -> sum + cell.seconds }

    // Ties resolve to the earlier date. Any deterministic rule would do; what matters is
    // that the page does not name a different "biggest day" each time it is opened.
    val biggestDate = dayTotals.entries
        .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        .firstOrNull()
        ?.key
    val firstDate = dayTotals.keys.minOrNull()

    val topByDate = titlesInRange
        .groupBy { it.date }
        .mapValues { (_, cells) -> cells.topTitleKey() }

    fun moment(date: String?, seconds: Long): WatchMoment? {
        if (date == null || seconds <= 0L) return null
        return momentFor(date, seconds, topByDate[date], names)
    }

    return MomentSet(
        biggestDay = moment(biggestDate, dayTotals[biggestDate] ?: 0L),
        monthlyHeadliners = monthlyHeadliners(titlesInRange, names),
        longestSession = longestSession(hoursInRange, topByDate, names),
        firstWatch = moment(firstDate, dayTotals[firstDate] ?: 0L),
    )
}

/**
 * The title that took the most time in each month of the range, earliest month first.
 *
 * The flagship of the set: twelve posters, one per month, is a shape of the year that no
 * aggregate produces and that neither Trakt nor Simkl offers. Months with nothing in them
 * are simply absent — an empty slot in a row of posters reads as a loading failure, and a
 * viewer who watched nothing in February does not need to be shown a hole.
 */
private fun monthlyHeadliners(
    titles: List<TitleCell>,
    names: Map<String, TitleName>,
): List<WatchMoment> = titles
    .groupBy { it.date.take(7) }
    .toSortedMap()
    .mapNotNull { (month, cells) ->
        val (key, seconds) = cells.topTitleKey() ?: return@mapNotNull null
        // The first of the month: a month-long moment still has to carry a real date, and
        // inventing a `YYYY-MM` form would leave every reader of it parsing two shapes.
        momentFor("$month-01", seconds, key to seconds, names)
    }

/**
 * The longest unbroken run of hours with real watching in them.
 *
 * Two details keep this honest. An hour must carry at least [SESSION_HOUR_FLOOR] to count,
 * or twenty minutes at nine and twenty minutes at ten would be reported as a two-hour
 * sitting — the rows say only that *something* played in that hour, never for how long it
 * ran unbroken. And runs are ranked by the time in them rather than by how many hours they
 * span, because the figure the page prints is the time; ranking by span could put "45m in
 * one sitting" above a genuine two-hour evening.
 *
 * Runs cross midnight. Walking `(date, hour)` pairs and refusing to step from 23:00 to the
 * next day's 00:00 would cut every late-night session in half, which is precisely the
 * session most worth naming.
 */
private fun longestSession(
    hours: List<HourCell>,
    topByDate: Map<String, Pair<String, Long>?>,
    names: Map<String, TitleName>,
): WatchMoment? {
    val cells = hours
        .filter { it.seconds >= SESSION_HOUR_FLOOR }
        .sortedWith(compareBy({ it.date }, { it.hour }))
    if (cells.isEmpty()) return null

    var bestStart: String? = null
    var bestSeconds = 0L
    var bestSpan = 0
    var runStart = cells.first().date
    var runSeconds = 0L
    var runSpan = 0
    var previous: HourCell? = null

    fun closeRun() {
        if (runSpan >= SESSION_MIN_HOURS && runSeconds > bestSeconds) {
            bestSeconds = runSeconds
            bestSpan = runSpan
            bestStart = runStart
        }
    }

    cells.forEach { cell ->
        val last = previous
        val continues = last != null && follows(last, cell)
        if (!continues) {
            closeRun()
            runStart = cell.date
            runSeconds = 0L
            runSpan = 0
        }
        runSeconds += cell.seconds
        runSpan += 1
        previous = cell
    }
    closeRun()

    val start = bestStart ?: return null
    if (bestSpan < SESSION_MIN_HOURS) return null
    // Attributed to whatever led the day the run began. The rows are per day, not per hour,
    // so nothing finer is available; naming the day's leader is the closest true statement.
    return momentFor(start, bestSeconds, topByDate[start], names)
}

/** Whether [next] is the very next hour after [cell], including across midnight. */
private fun follows(cell: HourCell, next: HourCell): Boolean {
    if (next.date == cell.date) return next.hour == cell.hour + 1
    if (cell.hour != 23 || next.hour != 0) return false
    val tomorrow = runCatching {
        LocalDate.parse(cell.date).plus(1, DateTimeUnit.DAY)
    }.getOrNull() ?: return false
    return next.date == tomorrow.toString()
}

/** The title with the most seconds against it, ties broken by key so the answer is stable. */
private fun List<TitleCell>.topTitleKey(): Pair<String, Long>? = groupingBy { it.titleKey }
    .fold(0L) { sum, cell -> sum + cell.seconds }
    .entries
    .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
    .firstOrNull()
    ?.let { it.key to it.value }

/**
 * Assembles one moment, resolving the title key against the library.
 *
 * A key of the form `"{tmdbId}:{mediaType}"`, which is what the activity tables store. A row
 * whose key does not parse is dropped down to a dateless id rather than discarded: the time
 * on that day is still true even when the thing that was watched can no longer be named.
 */
private fun momentFor(
    date: String,
    seconds: Long,
    top: Pair<String, Long>?,
    names: Map<String, TitleName>,
): WatchMoment {
    val key = top?.first
    val separator = key?.indexOf(':') ?: -1
    val tmdbId = if (key != null && separator > 0) {
        key.substring(0, separator).toIntOrNull() ?: 0
    } else {
        0
    }
    val mediaType = if (key != null && separator > 0) key.substring(separator + 1) else ""
    val name = key?.let(names::get)
    return WatchMoment(
        date = date,
        seconds = seconds,
        tmdbId = tmdbId,
        mediaType = mediaType,
        title = name?.title.orEmpty(),
        posterPath = name?.posterPath.orEmpty(),
    )
}

/**
 * Below this an hour is a row saying something played, not an hour spent watching.
 *
 * Fifteen minutes: long enough that an episode's tail crossing into the next hour does not
 * extend a session by an hour it did not fill, short enough that a genuine sitting is not
 * broken up by the hour it happened to start late in.
 */
private const val SESSION_HOUR_FLOOR = 15 * 60L

/** One hour on its own is not a sitting anybody would call a session. */
private const val SESSION_MIN_HOURS = 2
