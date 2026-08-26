package com.coveninja.cove.ui.pages.profile

import com.coveninja.cove.shared.model.ActivityStats
import com.coveninja.cove.shared.model.ActivityTitle
import com.coveninja.cove.shared.model.DecadeCount
import com.coveninja.cove.shared.model.DiscoveryInsights
import com.coveninja.cove.shared.model.DiscoveryTaste
import com.coveninja.cove.shared.model.InsightsRange
import com.coveninja.cove.shared.model.LanguageCount
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.WatchMoment
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

// ── Headlines ────────────────────────────────────────────────────────────────
//
// Every section on the page is headed by a small-caps eyebrow and a headline. The eyebrow
// is the fixed label the section used to carry as its title ("ACROSS THE YEAR"); the
// headline is a sentence about the viewer, computed from their own numbers.
//
// The rule each of these obeys: state a fact or say nothing new. When the data is too thin
// to support a claim, the function returns the neutral description the section used to
// show rather than a hedged sentence or an overclaim. A page that tells someone the 2010s
// are "their decade" on the strength of four titles is worse than one that stays quiet.

/**
 * Which part of the day an hour belongs to.
 *
 * Four bands rather than the hour itself, because the sentence the page wants to say is
 * about a habit and an hour is about one evening. The night band deliberately wraps
 * midnight: someone watching at 1am is finishing a night, not starting a morning, and a
 * band that split at midnight would file the two halves of one sitting under opposite
 * words.
 */
enum class TimeBand { Morning, Afternoon, Evening, Night }

fun timeBand(hour: Int): TimeBand = when (((hour % 24) + 24) % 24) {
    in 5..11 -> TimeBand.Morning
    in 12..16 -> TimeBand.Afternoon
    in 17..21 -> TimeBand.Evening
    else -> TimeBand.Night
}

/** Reads after a weekday: "Sunday **evenings**". */
fun bandAfterWeekday(band: TimeBand): String = when (band) {
    TimeBand.Morning -> "mornings"
    TimeBand.Afternoon -> "afternoons"
    TimeBand.Evening -> "evenings"
    TimeBand.Night -> "nights"
}

/**
 * Reads on its own: "**late nights**".
 *
 * Differs from [bandAfterWeekday] in the night case only. "Sunday late nights" is not
 * English, while a bare "nights" in a list of habits is too vague to be worth the words.
 */
fun bandAlone(band: TimeBand): String = when (band) {
    TimeBand.Night -> "late nights"
    else -> bandAfterWeekday(band)
}

/**
 * The month chart's headline: which month of this year has the most time on it.
 *
 * [currentMonth] is 1-based and may be null when the chart is not showing the running
 * year. When the peak *is* the running month the claim is qualified with "so far" — a
 * fortnight into August, calling it the biggest month is a statement the rest of the month
 * can quietly overturn.
 */
fun monthlyHeadline(byMonthThisYear: List<Long>, currentMonth: Int?): String {
    val index = byMonthThisYear.indices.maxByOrNull { byMonthThisYear[it] }
        ?: return MONTHLY_FALLBACK
    if (byMonthThisYear[index] <= 0L) return MONTHLY_FALLBACK
    val name = MONTHS_FULL.getOrNull(index) ?: return MONTHLY_FALLBACK
    return if (currentMonth != null && currentMonth == index + 1) {
        "$name is your biggest month so far"
    } else {
        "$name was your biggest month"
    }
}

private const val MONTHLY_FALLBACK = "This year against last"

/**
 * The rhythm headline: the weekday and the part of the day the viewer actually watches in.
 *
 * Falls back to the neutral line when either half is unknown, matching [rhythmSummary] —
 * naming a weekday without a time, or a time without a weekday, is half a habit.
 */
fun rhythmHeadline(activity: ActivityStats): String {
    val hour = peakHour(activity.byHourOfDay) ?: return RHYTHM_FALLBACK
    val weekday = busiestWeekday(activity.byDayOfWeek) ?: return RHYTHM_FALLBACK
    return "${weekdayName(weekday)} ${bandAfterWeekday(timeBand(hour))}, " +
        "around ${formatHour(hour)}"
}

private const val RHYTHM_FALLBACK = "When your watching actually happens"

/**
 * The heatmap headline: how many days of the period had anything on them.
 *
 * The denominator differs by range and is the whole point of the sentence. A running year
 * is measured against the days that have actually elapsed — against 365 it would report a
 * worse figure every January and a better one every December, for no reason but the date.
 * All-time has no meaningful denominator at all, so it does not invent one.
 */
fun heatmapHeadline(activeDays: Int, range: InsightsRange, today: LocalDate): String {
    if (activeDays <= 0) return HEATMAP_FALLBACK
    return when (range) {
        InsightsRange.ThisYear ->
            "You watched on $activeDays of the year's ${today.dayOfYear} days so far"
        InsightsRange.LastYear ->
            "You watched on $activeDays of ${daysInYear(today.year - 1)} days"
        InsightsRange.AllTime ->
            "You watched on $activeDays separate " + if (activeDays == 1) "day" else "days"
    }
}

private const val HEATMAP_FALLBACK = "Every day you watched something"

/** 366 in a leap year, 365 otherwise — the heatmap denominator has to be the real one. */
fun daysInYear(year: Int): Int =
    if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 366 else 365

/** The library headline: what the saved titles are actually doing. */
fun compositionHeadline(breakdown: LibraryBreakdown): String {
    val finished = breakdown.statusCounts[LibraryStatus.Finished] ?: 0
    val watching = breakdown.statusCounts[LibraryStatus.Watching] ?: 0
    return when {
        finished > 0 && watching > 0 -> "$finished finished, $watching still going"
        finished > 0 -> "$finished of ${breakdown.total} finished"
        breakdown.total > 0 -> "${breakdown.total} " +
            (if (breakdown.total == 1) "title" else "titles") + " saved"
        else -> "What your library is made of"
    }
}

/**
 * The follow-through headline.
 *
 * Deliberately a plain percentage rather than a verdict. The page has no business telling
 * someone that abandoning things is a failing — the same reason [YearDelta] renders a fall
 * in grey rather than red — so this reports the number and stops.
 */
fun finishHeadline(stats: FinishStats): String {
    if (stats.started <= 0) return "How often you reach the end"
    if (stats.rate >= 0.95f) return "You finish almost everything you start"
    return "You finish ${(stats.rate * 100).roundToInt()}% of what you start"
}

/**
 * The taste headline: the three strongest genres, whichever side of the library they came
 * from.
 *
 * Movie and TV genres are merged by name and summed. They are weights from one profile on
 * one scale, so a viewer whose science fiction is split across both halves should see it
 * counted once and ranked accordingly, rather than losing to a genre that only ever
 * appears in one list.
 */
fun genreHeadline(movieGenres: List<DiscoveryTaste>, tvGenres: List<DiscoveryTaste>): String {
    val merged = (movieGenres + tvGenres)
        .filter { it.score > 0.0 }
        .groupBy { it.name }
        .map { (name, group) -> name to group.sumOf(DiscoveryTaste::score) }
        .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
        .take(3)
        .map { it.first }
    return when (merged.size) {
        0 -> "The genres your library argues for hardest"
        1 -> merged[0]
        2 -> "${merged[0]} and ${merged[1]}"
        else -> "${merged[0]}, ${merged[1]} and ${merged[2]}"
    }
}

/**
 * The crowd headline, on the ten-point scale both sides were converted to.
 *
 * A gap under [CROWD_NOISE] is reported as agreement rather than as a direction. Below
 * that the sign is decided by a couple of titles and would flip between visits, which
 * would make the page look like it was guessing — because it would be.
 */
fun crowdHeadline(comparison: RatingComparison): String {
    if (comparison.rated <= 0) return "How your scores sit against everyone else's"
    val delta = comparison.averageDelta
    if (abs(delta) < CROWD_NOISE) return "You rate about the same as everyone else"
    val amount = ((abs(delta) * 10).roundToInt() / 10.0)
    return if (delta > 0) {
        "You rate $amount above the crowd"
    } else {
        "You rate $amount below the crowd"
    }
}

private const val CROWD_NOISE = 0.25

/**
 * The decade headline.
 *
 * Claims a decade only when it holds [DOMINANT_SHARE] of the engaged set. Below that the
 * honest finding is the spread, not a favourite: naming the largest of five near-equal
 * decades as "your decade" is the page reading meaning into noise.
 */
fun decadesHeadline(decades: List<DecadeCount>): String {
    val total = decades.sumOf(DecadeCount::count)
    if (total <= 0) return "The eras your library comes from"
    val top = decades.maxByOrNull(DecadeCount::count) ?: return "The eras your library comes from"
    if (top.count.toDouble() / total >= DOMINANT_SHARE) {
        return "The ${decadeLabel(top.decade)} are your decade"
    }
    val oldest = decades.minOf(DecadeCount::decade)
    val newest = decades.maxOf(DecadeCount::decade)
    if (oldest == newest) return "The ${decadeLabel(oldest)}, almost entirely"
    return "You range from the ${decadeLabel(oldest)} to the ${decadeLabel(newest)}"
}

private const val DOMINANT_SHARE = 0.30

/**
 * The language headline: how much of the library is not in English.
 *
 * A percentage rather than a "1 in 4", which reads more warmly and is usually wrong — a
 * 40% share becomes "1 in 3" once it is rounded to a whole denominator, and the page would
 * be stating a number nobody can reproduce from the chart underneath it.
 */
fun languagesHeadline(languages: List<LanguageCount>): String {
    val named = namedLanguages(languages)
    val total = named.sumOf { it.second }
    if (total <= 0) return "The languages your library is in"
    val english = named.firstOrNull { it.first == "English" }?.second ?: 0
    val share = (total - english).toDouble() / total
    return when {
        share <= 0.0 -> "Everything you watch is in English"
        share < 0.05 -> "Almost everything you watch is in English"
        share >= 0.95 -> "Almost nothing you watch is in English"
        else -> "${(share * 100).roundToInt()}% of your titles aren't in English"
    }
}

/**
 * The one line under the hero total: the viewer's habits as three clauses at most.
 *
 * Null unless at least two clauses survive. One clause is not a portrait — "mostly series"
 * on its own says less than the number directly above it already did — and the line is
 * better absent than thin.
 */
fun identityLine(
    breakdown: LibraryBreakdown,
    activity: ActivityStats,
    decades: List<DecadeCount>,
): String? {
    val clauses = buildList {
        shapeClause(breakdown)?.let(::add)
        peakHour(activity.byHourOfDay)?.let { add(bandAlone(timeBand(it))) }
        dominantDecade(decades)?.let { add("the ${decadeLabel(it)}") }
    }
    return if (clauses.size < 2) null else clauses.joinToString(" · ")
}

/**
 * "Mostly series" or "mostly films", when the library actually leans one way.
 *
 * Null inside [SHAPE_LEAN] of even. Someone with 51 films and 49 shows watches both, and
 * telling them they are "mostly a film person" is a claim their own library contradicts.
 */
private fun shapeClause(breakdown: LibraryBreakdown): String? {
    val total = breakdown.movies + breakdown.shows
    if (total <= 0) return null
    val showShare = breakdown.shows.toDouble() / total
    return when {
        showShare >= SHAPE_LEAN -> "mostly series"
        showShare <= 1.0 - SHAPE_LEAN -> "mostly films"
        else -> null
    }
}

private const val SHAPE_LEAN = 0.6

/** The decade holding [DOMINANT_SHARE] of the set, or null when nothing dominates. */
private fun dominantDecade(decades: List<DecadeCount>): Int? {
    val total = decades.sumOf(DecadeCount::count)
    if (total <= 0) return null
    val top = decades.maxByOrNull(DecadeCount::count) ?: return null
    return top.decade.takeIf { top.count.toDouble() / total >= DOMINANT_SHARE }
}

/**
 * The qualifying line beside the hero total.
 *
 * Note what this deliberately does not say: how many *days* the total comes to.
 * [formatWatchDuration] documents why hours never roll up into days, and a second line
 * doing it anyway would undo that decision one label further down the same card.
 */
fun heroContext(activity: ActivityStats, range: InsightsRange, thisYear: Int): String? {
    if (activity.totalSeconds <= 0L) return null
    return when (range) {
        InsightsRange.AllTime -> activity.byYear.keys.size
            .takeIf { it > 1 }
            ?.let { "across $it years" }
        InsightsRange.ThisYear, InsightsRange.LastYear -> {
            val year = if (range == InsightsRange.ThisYear) thisYear else thisYear - 1
            val best = activity.byYear.maxByOrNull { it.value } ?: return null
            // Only worth saying once there is another year to have beaten.
            if (activity.byYear.size > 1 && best.key == year.toString()) {
                "your biggest year yet"
            } else {
                null
            }
        }
    }
}

/**
 * The leaderboard headline: whichever title took the most hours, named.
 *
 * The single most personal line the page can produce from activity alone, which is why it
 * heads the one card on the page drawn at feature weight. Falls back when the top entry has
 * no title — the activity tables key rows by tmdb id and only learn the name from the
 * library, so a title watched and then removed from the library reaches here nameless.
 */
fun leaderboardHeadline(titles: List<ActivityTitle>, range: InsightsRange): String {
    val top = titles.firstOrNull()?.title?.takeIf { it.isNotBlank() }
        ?: return "The titles you gave the most hours to"
    return when (range) {
        InsightsRange.ThisYear -> "$top led your year"
        InsightsRange.LastYear -> "$top led last year"
        InsightsRange.AllTime -> "$top is your most-watched"
    }
}

/**
 * The taste-signals headline, from whichever signal the profile actually has.
 *
 * Ordered people, then themes, then studios — least to most abstract from the reader's point
 * of view. A name is something someone recognises about themselves; a studio is a fact they
 * may never have noticed.
 */
fun signalsHeadline(profile: DiscoveryInsights): String {
    profile.topPeople.firstOrNull()?.name?.takeIf { it.isNotBlank() }?.let {
        return "$it shows up more than anyone"
    }
    profile.topKeywords.firstOrNull()?.name?.takeIf { it.isNotBlank() }?.let {
        return "You keep coming back to $it"
    }
    profile.topStudios.firstOrNull()?.name?.takeIf { it.isNotBlank() }?.let {
        return "$it made more of your library than anyone"
    }
    return "Themes, people and studios behind your recommendations"
}

/** The contributors headline: the title that moved the profile hardest, either way. */
fun contributorsHeadline(profile: DiscoveryInsights): String {
    profile.topContributors.firstOrNull()?.title?.takeIf { it.isNotBlank() }?.let {
        return "$it shaped your profile most"
    }
    profile.negativeContributors.firstOrNull()?.title?.takeIf { it.isNotBlank() }?.let {
        return "$it steered you away hardest"
    }
    return "The strongest pulls in each direction"
}

// ── Moments ──────────────────────────────────────────────────────────────────

/**
 * A moment's date, written the way someone would say it.
 *
 * The weekday is carried for a date in the current year and dropped for an older one: "the
 * Saturday you watched nine hours" is a thing a person can place, while "Saturday" about a
 * date eighteen months ago is a detail nobody can use, and the year is the part that
 * actually locates it. Null when the stored date will not parse, which is the same answer
 * every other reader of these strings gives.
 */
fun formatMomentDate(iso: String, today: LocalDate): String? {
    val date = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return null
    val month = MONTHS_FULL.getOrNull(date.month.number - 1) ?: return null
    if (date.year != today.year) return "${date.day} $month ${date.year}"
    val weekday = WEEKDAYS_FROM_SUNDAY[date.dayOfWeek.isoDayNumber % 7]
    return "$weekday ${date.day} $month"
}

/** A monthly headliner's month, three letters, for the label under its poster. */
fun momentMonthLabel(iso: String): String? {
    val date = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return null
    return MONTHS_SHORT.getOrNull(date.month.number - 1)
}

/** "9h 12m on Saturday 14 March" — the biggest single day of the range. */
fun biggestDayHeadline(moment: WatchMoment, today: LocalDate): String? {
    if (moment.isEmpty) return null
    val date = formatMomentDate(moment.date, today) ?: return null
    return "${formatWatchDuration(moment.seconds)} on $date"
}

/**
 * "4h 12m without stopping" — the longest unbroken run.
 *
 * The duration leads rather than the date, because the duration is the claim. Which evening
 * it happened on is the qualifier, and belongs in the support line underneath.
 */
fun longestSessionHeadline(moment: WatchMoment): String? {
    if (moment.isEmpty) return null
    return "${formatWatchDuration(moment.seconds)} without stopping"
}

/**
 * How the range opened.
 *
 * Names the title when the library still knows it and falls back to the bare date when it
 * does not — "You started the year with something on 2 January" would be worse than saying
 * nothing about what was on.
 */
fun firstWatchHeadline(moment: WatchMoment, today: LocalDate, range: InsightsRange): String? {
    if (moment.isEmpty) return null
    val date = formatMomentDate(moment.date, today) ?: return null
    val opened = if (range == InsightsRange.AllTime) "It all started" else "You started"
    val title = moment.title.takeIf { it.isNotBlank() }
        ?: return "$opened on $date"
    return "$opened with $title on $date"
}

