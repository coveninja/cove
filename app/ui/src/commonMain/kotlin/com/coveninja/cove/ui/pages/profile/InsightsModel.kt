package com.coveninja.cove.ui.pages.profile

import com.coveninja.cove.shared.model.ActivityStats
import com.coveninja.cove.shared.model.ActivityTitle
import com.coveninja.cove.shared.model.DiscoveryInsights
import com.coveninja.cove.shared.model.DiscoveryTaste
import com.coveninja.cove.shared.model.InsightsRange
import com.coveninja.cove.shared.model.LanguageCount
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.WatchProgress
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.daysUntil
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.plus

// Everything in this file is pure: given the same numbers it produces the same answer, with
// no Compose, no clock and no repository behind it. That is what makes the insights page
// testable at all — nothing that renders is covered by a test in this repo, so the
// arithmetic has to be verifiable somewhere the tests can reach.

// ── Durations ────────────────────────────────────────────────────────────────

/**
 * Watch time as a person would say it: `"342h 18m"`, `"47m"`, `"6h"`.
 *
 * Hours never roll up into days. "14d 6h" sounds like a countdown, and the number people
 * want to quote about themselves is the hours one — that is the unit the whole page is
 * denominated in.
 *
 * Anything above zero but under a minute becomes `"<1m"` rather than `"0m"`: a resumed
 * title that played for ten seconds did happen, and rounding it to nothing makes the page
 * look broken to the person who just watched something.
 */
fun formatWatchDuration(seconds: Long): String {
    if (seconds <= 0L) return "0m"
    if (seconds < 60L) return "<1m"
    val totalMinutes = seconds / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

/** Whole hours only, for axis ticks and tooltips where a two-part label will not fit. */
fun formatWatchHours(seconds: Long): String = "${seconds / 3600}h"

// ── Year over year ───────────────────────────────────────────────────────────

enum class TrendDirection { Up, Down, Flat }

/** A signed percentage change against the previous year, plus which way it points. */
data class YearDelta(val percent: Int, val direction: TrendDirection)

/**
 * This year against last year.
 *
 * Null when last year has no time on it at all. A first year of use has nothing to compare
 * against, and "+100%" against zero is arithmetic rather than information — the badge is
 * simply not shown in that case.
 */
fun yearOverYearDelta(thisYearSeconds: Long, lastYearSeconds: Long): YearDelta? {
    if (lastYearSeconds <= 0L) return null
    val ratio = (thisYearSeconds - lastYearSeconds).toDouble() / lastYearSeconds
    val percent = (ratio * 100).roundToInt()
    val direction = when {
        percent > 0 -> TrendDirection.Up
        percent < 0 -> TrendDirection.Down
        else -> TrendDirection.Flat
    }
    return YearDelta(percent, direction)
}

// ── Monthly bars ─────────────────────────────────────────────────────────────

/** One month of the year-over-year chart, already normalised for drawing. */
data class MonthBar(
    /** Single letter, for the axis, where twelve three-letter labels will not fit. */
    val label: String,
    /** The month spelled out, for the readout that names whichever bar is being pointed at. */
    val name: String,
    val thisYearSeconds: Long,
    val lastYearSeconds: Long,
    val thisYearFraction: Float,
    val lastYearFraction: Float,
)

/**
 * Twelve months, both years, scaled against a single shared maximum.
 *
 * Scaling each year to its own peak would be the obvious thing and would also be a lie:
 * two bars of equal height would mean two different amounts of time, which is the one
 * thing a reader assumes a bar chart will never do.
 *
 * Short input lists are tolerated rather than rejected — a host that answers with fewer
 * than twelve entries should leave the tail of the chart empty, not crash the page.
 */
fun monthBars(thisYear: List<Long>, lastYear: List<Long>): List<MonthBar> {
    val peak = ((thisYear + lastYear).maxOrNull() ?: 0L).coerceAtLeast(1L)
    return List(12) { index ->
        val current = thisYear.getOrElse(index) { 0L }
        val previous = lastYear.getOrElse(index) { 0L }
        MonthBar(
            label = MONTHS_INITIAL[index],
            name = MONTHS_FULL[index],
            thisYearSeconds = current,
            lastYearSeconds = previous,
            thisYearFraction = current.toFloat() / peak,
            lastYearFraction = previous.toFloat() / peak,
        )
    }
}

// ── Heatmap ──────────────────────────────────────────────────────────────────

/** One cell of the activity heatmap. */
data class HeatDay(val date: LocalDate, val seconds: Long, val level: Int)

/**
 * The trailing year as columns of weeks, Sunday at the top of each column.
 *
 * Columns are aligned to real week boundaries, not to "today minus a multiple of seven",
 * so the weekday rows stay meaningful across the whole grid. The final column is the
 * current, partial week: days after today are `null` and draw as empty, because a future
 * day with no watch time is not the same thing as a day the viewer skipped.
 *
 * Levels are computed against the maximum *inside the returned range* rather than all
 * history. A single enormous day two years ago would otherwise flatten the entire visible
 * year to level 1.
 */
fun heatmapWeeks(
    calendar: Map<String, Long>,
    today: LocalDate,
    weeks: Int = HEATMAP_WEEKS,
): List<List<HeatDay?>> {
    if (weeks <= 0) return emptyList()
    // isoDayNumber is Monday-first (Mon = 1 … Sun = 7); the grid is Sunday-first.
    val startOfThisWeek = today.plus(-(today.dayOfWeek.isoDayNumber % 7), DateTimeUnit.DAY)
    val firstSunday = startOfThisWeek.plus(-(weeks - 1) * 7, DateTimeUnit.DAY)

    val dates = List(weeks) { week ->
        List(7) { day ->
            val date = firstSunday.plus(week * 7 + day, DateTimeUnit.DAY)
            if (date > today) null else date
        }
    }
    val peak = dates.flatten().filterNotNull()
        .maxOfOrNull { calendar[it.toString()] ?: 0L }
        ?: 0L

    return dates.map { week ->
        week.map { date ->
            date?.let {
                val seconds = calendar[it.toString()] ?: 0L
                HeatDay(it, seconds, intensityLevel(seconds, peak))
            }
        }
    }
}

/**
 * The last day a heatmap should draw, and how many week-columns to draw back from it.
 *
 * A past year ends on its own 31 December rather than today, or the grid would spend half
 * its width on months the range does not cover. The current year is measured from 1 January
 * so the columns line up with the year rather than trailing arbitrarily into the previous
 * one; all-time keeps the rolling window, which is the only sensible reading when there is
 * no year to bound it.
 */
fun heatmapWindow(range: InsightsRange, today: LocalDate): Pair<LocalDate, Int> = when (range) {
    InsightsRange.ThisYear -> {
        val start = LocalDate(today.year, 1, 1)
        today to (start.daysUntil(today) / 7 + 2).coerceIn(1, HEATMAP_WEEKS)
    }
    InsightsRange.LastYear -> LocalDate(today.year - 1, 12, 31) to HEATMAP_WEEKS
    InsightsRange.AllTime -> today to HEATMAP_WEEKS
}

/**
 * Which of five shades a day gets, 0 meaning "nothing watched".
 *
 * Quartiles of the peak rather than of the distribution: the reader is comparing days to
 * the biggest day, which is the comparison the legend claims to describe.
 */
fun intensityLevel(seconds: Long, peak: Long): Int {
    if (seconds <= 0L || peak <= 0L) return 0
    val ratio = seconds.toDouble() / peak
    return when {
        ratio <= 0.25 -> 1
        ratio <= 0.50 -> 2
        ratio <= 0.75 -> 3
        else -> 4
    }
}

/**
 * Where each month name goes above the heatmap, as `weekIndex to label`.
 *
 * A label is emitted for the column that first contains a given month, which is what makes
 * the axis line up with the grid instead of being spaced evenly and drifting out of step
 * with the weeks underneath it.
 */
fun heatmapMonthLabels(weeks: List<List<HeatDay?>>): List<Pair<Int, String>> {
    val labels = mutableListOf<Pair<Int, String>>()
    var previousMonth = -1
    weeks.forEachIndexed { index, week ->
        val first = week.firstOrNull { it != null } ?: return@forEachIndexed
        val month = first.date.month.number
        if (month != previousMonth) {
            labels += index to MONTHS_SHORT[month - 1]
            previousMonth = month
        }
    }
    return labels
}

/**
 * How a heatmap cell names its own day: `"Today"`, or `"Sat 12 Aug"`.
 *
 * Written here rather than reusing the calendar page's `dayLabel` so the insights charts do
 * not take a dependency on another page's internals for four words. It also drops that
 * function's "Tomorrow", which cannot occur in a grid that stops at today.
 */
fun heatDayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    else -> "${WEEKDAYS_SHORT[date.dayOfWeek.isoDayNumber - 1]} " +
        "${date.day} ${MONTHS_SHORT[date.month.number - 1]}"
}

// ── Rhythm ───────────────────────────────────────────────────────────────────

/** The busiest hour of the day, or null when nothing has been watched. */
fun peakHour(byHourOfDay: List<Long>): Int? {
    val index = byHourOfDay.indices.maxByOrNull { byHourOfDay[it] } ?: return null
    return if (byHourOfDay[index] <= 0L) null else index
}

/** The busiest day of the week (0 = Sunday), or null when nothing has been watched. */
fun busiestWeekday(byDayOfWeek: List<Long>): Int? {
    val index = byDayOfWeek.indices.maxByOrNull { byDayOfWeek[it] } ?: return null
    return if (byDayOfWeek[index] <= 0L) null else index
}

/** `"9 pm"`, `"12 am"` — the clock face labels and the rhythm sentence both use this. */
fun formatHour(hour: Int): String {
    val normalised = ((hour % 24) + 24) % 24
    val suffix = if (normalised < 12) "am" else "pm"
    val display = when (normalised % 12) {
        0 -> 12
        else -> normalised % 12
    }
    return "$display $suffix"
}

/** `"Sunday"` … `"Saturday"`, indexed the way [ActivityStats.byDayOfWeek] is. */
fun weekdayName(index: Int): String = WEEKDAYS_FROM_SUNDAY[((index % 7) + 7) % 7]

/**
 * The one-line reading of the two rhythm charts.
 *
 * Null when there is not enough to say. A sentence that hedges ("you may watch at various
 * times") is worse than no sentence, so this only speaks when both halves are known.
 */
fun rhythmSummary(activity: ActivityStats): String? {
    val hour = peakHour(activity.byHourOfDay) ?: return null
    val weekday = busiestWeekday(activity.byDayOfWeek) ?: return null
    return "Most of your watching happens around ${formatHour(hour)}, " +
        "and ${weekdayName(weekday)} is your biggest day."
}

// ── Library composition ──────────────────────────────────────────────────────

/** The library counted up by status and shape, for the composition ring. */
data class LibraryBreakdown(
    val statusCounts: Map<LibraryStatus, Int>,
    val total: Int,
    val movies: Int,
    val shows: Int,
    val ratedCount: Int,
    /** Null when nothing is rated — distinct from an average that happens to be zero. */
    val averageRating: Double?,
)

/**
 * Counts every status, including the ones with nothing in them.
 *
 * The zero entries are deliberate: the ring's legend lists all four states every time, so
 * "Dropped 0" is a fact worth showing, and a legend whose rows appear and disappear as the
 * library changes is harder to read than a fixed one.
 */
fun libraryBreakdown(entries: List<LibraryEntry>): LibraryBreakdown {
    val counts = LibraryStatus.entries.associateWith { status ->
        entries.count { it.status == status }
    }
    val ratings = entries.mapNotNull(LibraryEntry::rating)
    return LibraryBreakdown(
        statusCounts = counts,
        total = entries.size,
        movies = entries.count { it.mediaType == MediaType.Movie },
        shows = entries.count { it.mediaType == MediaType.Tv },
        ratedCount = ratings.size,
        averageRating = if (ratings.isEmpty()) null else ratings.average(),
    )
}

// ── Taste ────────────────────────────────────────────────────────────────────

/** A ranked taste entry with its bar already sized, 0f..1f. */
data class TasteBar(val name: String, val score: Double, val fraction: Float)

/**
 * Scales a ranked list against its own strongest entry.
 *
 * Magnitude, not sign, sets the scale, so a list of disliked genres — every score negative
 * — still produces bars that grow with how strongly disliked each one is. The raw score is
 * carried through but never shown: it is a decayed profile weight with no unit, and
 * printing it would invite the reader to interpret a number that means nothing on its own.
 */
fun normalizeTaste(items: List<DiscoveryTaste>): List<TasteBar> {
    val peak = items.maxOfOrNull { abs(it.score) } ?: 0.0
    return items.map { taste ->
        TasteBar(
            name = taste.name,
            score = taste.score,
            fraction = if (peak <= 0.0) 0f else (abs(taste.score) / peak).toFloat(),
        )
    }
}

/** Share bars for the leaderboard, each relative to the most-watched title. */
fun titleShares(titles: List<ActivityTitle>): List<Float> {
    val peak = titles.maxOfOrNull { it.seconds } ?: 0L
    return titles.map { if (peak <= 0L) 0f else it.seconds.toFloat() / peak }
}

// ── Empty state ──────────────────────────────────────────────────────────────

/**
 * Whether the page has literally nothing to draw.
 *
 * All three sources have to be empty. A library with saved titles but no playback still
 * fills the composition ring and the taste sections, and showing "nothing to analyse" over
 * the top of real content would be wrong — the individual sections hide themselves.
 */
fun insightsAreEmpty(
    activity: ActivityStats,
    taste: DiscoveryInsights,
    libraryCount: Int,
): Boolean = activity.totalSeconds <= 0L &&
    activity.calendar.isEmpty() &&
    taste.signalsUsed == 0 &&
    libraryCount == 0

// ── Labels ───────────────────────────────────────────────────────────────────

/** Trailing weeks in the heatmap: a full year plus the current partial week. */
const val HEATMAP_WEEKS = 53

private val WEEKDAYS_FROM_SUNDAY = listOf(
    "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
)

/** Sunday-first initials for the weekday chart's axis. */
val WEEKDAY_INITIALS = listOf("S", "M", "T", "W", "T", "F", "S")

/** Monday-first, matching ISO day numbering, so an isoDayNumber indexes it directly. */
private val WEEKDAYS_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

private val MONTHS_SHORT = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

/** Single letters, because twelve three-letter labels do not fit a phone-width chart. */
private val MONTHS_INITIAL = listOf(
    "J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D",
)

private val MONTHS_FULL = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

// ── You against the crowd ────────────────────────────────────────────────────

/** One title where the viewer's score and the public score disagree. */
data class RatingGap(
    val title: String,
    val yours: Double,
    val crowd: Double,
) {
    /** Positive means the viewer liked it more than everyone else did. */
    val delta: Double get() = yours - crowd
}

/**
 * How the viewer's ratings sit against TMDB's.
 *
 * Both are put on the same ten-point scale first: the rating control writes stars out of
 * five, TMDB publishes a mean out of ten, and comparing them unconverted would make every
 * viewer look like a harsh critic.
 */
data class RatingComparison(
    val rated: Int,
    val averageDelta: Double,
    val higher: Int,
    val lower: Int,
    val gaps: List<RatingGap>,
)

/** Stars out of five as a score out of ten, so the two scales can be compared at all. */
fun starsToTenPoint(stars: Double): Double = stars * 2.0

/**
 * Compares every rated title against its public score, biggest disagreement first.
 *
 * Titles the crowd has not scored are skipped rather than treated as zero — an unrated
 * title would otherwise look like one everybody hated, and would drag the average with it.
 */
fun ratingComparison(entries: List<LibraryEntry>): RatingComparison {
    val gaps = entries.mapNotNull { entry ->
        val yours = entry.rating ?: return@mapNotNull null
        val crowd = entry.voteAverage.takeIf { it > 0.0 } ?: return@mapNotNull null
        RatingGap(entry.title, starsToTenPoint(yours), crowd)
    }
    if (gaps.isEmpty()) return RatingComparison(0, 0.0, 0, 0, emptyList())
    return RatingComparison(
        rated = gaps.size,
        averageDelta = gaps.sumOf(RatingGap::delta) / gaps.size,
        higher = gaps.count { it.delta > 0 },
        lower = gaps.count { it.delta < 0 },
        gaps = gaps.sortedByDescending { abs(it.delta) },
    )
}

// ── Finishing what you start ─────────────────────────────────────────────────

/** A title left part-watched, with how far in it was abandoned. */
data class StalledTitle(val tmdbId: Int, val mediaType: MediaType, val fraction: Float)

/** How often the viewer reaches the end of something they began. */
data class FinishStats(
    val started: Int,
    val finished: Int,
    val rate: Float,
    val stalled: List<StalledTitle>,
)

/**
 * Completion measured over resume points.
 *
 * Anything under [STALLED_FLOOR] of the way in is excluded from the stalled list: opening
 * something, watching two minutes and deciding against it is a browsing decision, not an
 * abandoned title, and counting it would fill the list with noise.
 */
fun finishStats(progress: List<WatchProgress>): FinishStats {
    if (progress.isEmpty()) return FinishStats(0, 0, 0f, emptyList())
    val finished = progress.count { it.completed }
    val stalled = progress
        .filterNot { it.completed }
        .mapNotNull { entry ->
            val duration = entry.durationSeconds.takeIf { it > 0.0 } ?: return@mapNotNull null
            val fraction = (entry.positionSeconds / duration).toFloat()
            if (fraction < STALLED_FLOOR || fraction > STALLED_CEILING) return@mapNotNull null
            StalledTitle(entry.tmdbId, entry.mediaType, fraction)
        }
        .sortedByDescending(StalledTitle::fraction)
    return FinishStats(
        started = progress.size,
        finished = finished,
        rate = finished.toFloat() / progress.size,
        stalled = stalled,
    )
}

/** Below this it was a look, not a watch. */
private const val STALLED_FLOOR = 0.05f

/** Above this it is effectively finished, whatever the completed flag says. */
private const val STALLED_CEILING = 0.95f

// ── Library growth ───────────────────────────────────────────────────────────

/** How many titles were saved in one month of the trailing year. */
data class GrowthMonth(val label: String, val added: Int, val fraction: Float)

/**
 * Titles added per month over the trailing twelve, oldest first.
 *
 * Reads `addedAt` rather than anything about watching, which is the point: the gap between
 * what someone saves and what they get to is its own finding, and nothing else on the page
 * shows it.
 */
fun libraryGrowth(entries: List<LibraryEntry>, today: LocalDate): List<GrowthMonth> {
    val months = (11 downTo 0).map { back ->
        val month = today.minusMonths(back)
        month to entries.count { entry ->
            val added = entry.addedAt.take(7)
            added == "${month.year}-${month.month.number.toString().padStart(2, '0')}"
        }
    }
    val peak = months.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    return months.map { (month, count) ->
        GrowthMonth(
            label = MONTHS_INITIAL[month.month.number - 1],
            added = count,
            fraction = count.toFloat() / peak,
        )
    }
}

/** Steps back whole months, rolling the year over as needed. */
private fun LocalDate.minusMonths(count: Int): LocalDate {
    val zeroBased = (year * 12 + (month.number - 1)) - count
    return LocalDate(zeroBased / 12, zeroBased % 12 + 1, 1)
}

// ── All time ─────────────────────────────────────────────────────────────────

/** One year of the all-time chart. */
data class YearBar(val year: String, val seconds: Long, val fraction: Float)

/** The by-year series as bars, oldest first, scaled against the biggest year. */
fun yearBars(byYear: Map<String, Long>): List<YearBar> {
    val peak = (byYear.values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    return byYear.entries.sortedBy { it.key }
        .map { YearBar(it.key, it.value, it.value.toFloat() / peak) }
}

// ── Decades and languages ────────────────────────────────────────────────────

/**
 * A language's English name, or the bare code when it is not one this knows.
 *
 * Deliberately partial. Covering every ISO 639-1 code would be three hundred lines to name
 * languages no library here contains; the fallback prints the code, which is still readable
 * and is honest about not knowing.
 */
fun languageName(code: String): String = LANGUAGE_NAMES[code.lowercase()] ?: code.uppercase()

/** `1990` as `"1990s"`. */
fun decadeLabel(decade: Int): String = "${decade}s"

/**
 * Language counts merged by display name, biggest first.
 *
 * Several ISO codes legitimately share one name — TMDB reports Chinese as both `zh` and
 * `cn` — and grouping by code let the same language appear twice in the chart, splitting
 * its bar in half and looking like a duplicate row. Merging on the name the reader actually
 * sees is what makes the totals add up to what they expect.
 */
fun namedLanguages(languages: List<LanguageCount>): List<Pair<String, Int>> =
    languages.groupBy { languageName(it.code) }
        .map { (name, group) -> name to group.sumOf(LanguageCount::count) }
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })

private val LANGUAGE_NAMES = mapOf(
    "en" to "English", "ja" to "Japanese", "ko" to "Korean", "es" to "Spanish",
    "fr" to "French", "de" to "German", "it" to "Italian", "pt" to "Portuguese",
    "zh" to "Chinese", "cn" to "Chinese", "hi" to "Hindi", "ru" to "Russian",
    "sv" to "Swedish", "da" to "Danish", "no" to "Norwegian", "fi" to "Finnish",
    "nl" to "Dutch", "pl" to "Polish", "tr" to "Turkish", "ar" to "Arabic",
    "th" to "Thai", "he" to "Hebrew", "cs" to "Czech", "hu" to "Hungarian",
    "ro" to "Romanian", "el" to "Greek", "uk" to "Ukrainian", "id" to "Indonesian",
    "fa" to "Persian", "vi" to "Vietnamese", "ta" to "Tamil", "te" to "Telugu",
    "ml" to "Malayalam", "is" to "Icelandic", "ca" to "Catalan",
)
