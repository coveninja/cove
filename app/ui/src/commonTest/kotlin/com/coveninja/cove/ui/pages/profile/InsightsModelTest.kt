package com.coveninja.cove.ui.pages.profile

import com.coveninja.cove.shared.model.ActivityStats
import com.coveninja.cove.shared.model.ActivityTitle
import com.coveninja.cove.shared.model.ContributingTitle
import com.coveninja.cove.shared.model.DecadeCount
import com.coveninja.cove.shared.model.DiscoveryInsights
import com.coveninja.cove.shared.model.DiscoveryTaste
import com.coveninja.cove.shared.model.InsightsRange
import com.coveninja.cove.shared.model.LanguageCount
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.StudioEntry
import com.coveninja.cove.shared.model.WatchMoment
import com.coveninja.cove.shared.model.WatchProgress
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InsightsModelTest {

    // A Monday, so the Sunday-first grid has to shift by exactly one day to find the start
    // of the current week — an off-by-one in that shift is visible rather than masked.
    private val today = LocalDate.parse("2026-08-17")

    private fun entry(
        tmdbId: Int,
        status: LibraryStatus,
        type: MediaType = MediaType.Movie,
        rating: Double? = null,
    ) = LibraryEntry(
        id = "entry-$tmdbId",
        tmdbId = tmdbId,
        mediaType = type,
        title = "Title $tmdbId",
        status = status,
        rating = rating,
    )

    private fun progress(
        tmdbId: Int,
        position: Double,
        duration: Double,
        completed: Boolean = false,
    ) = WatchProgress(
        id = "progress-$tmdbId",
        tmdbId = tmdbId,
        mediaType = MediaType.Movie,
        positionSeconds = position,
        durationSeconds = duration,
        completed = completed,
    )

    // ── Durations ────────────────────────────────────────────────────────────

    @Test
    fun `watch time reads as hours and minutes`() {
        assertEquals("5h 30m", formatWatchDuration(5 * 3600 + 30 * 60L))
        assertEquals("6h", formatWatchDuration(6 * 3600L))
        assertEquals("47m", formatWatchDuration(47 * 60L))
        assertEquals("342h 18m", formatWatchDuration(342 * 3600 + 18 * 60L))
    }

    @Test
    fun `a few seconds of playback is not rounded away to nothing`() {
        assertEquals("<1m", formatWatchDuration(10))
        assertEquals("<1m", formatWatchDuration(59))
        // Zero really is nothing, and must not claim otherwise.
        assertEquals("0m", formatWatchDuration(0))
        assertEquals("0m", formatWatchDuration(-5))
    }

    // ── Year over year ───────────────────────────────────────────────────────

    @Test
    fun `year over year is a percentage of the previous year`() {
        val doubled = yearOverYearDelta(thisYearSeconds = 200, lastYearSeconds = 100)
        assertEquals(YearDelta(100, TrendDirection.Up), doubled)

        val halved = yearOverYearDelta(thisYearSeconds = 50, lastYearSeconds = 100)
        assertEquals(YearDelta(-50, TrendDirection.Down), halved)

        val same = yearOverYearDelta(thisYearSeconds = 100, lastYearSeconds = 100)
        assertEquals(YearDelta(0, TrendDirection.Flat), same)
    }

    @Test
    fun `a first year has nothing to compare against`() {
        assertNull(yearOverYearDelta(thisYearSeconds = 5_000, lastYearSeconds = 0))
    }

    // ── Monthly bars ─────────────────────────────────────────────────────────

    @Test
    fun `both years are scaled against one shared peak`() {
        val bars = monthBars(
            thisYear = List(12) { if (it == 0) 100L else 0L },
            lastYear = List(12) { if (it == 0) 50L else 0L },
        )

        assertEquals(1f, bars[0].thisYearFraction)
        assertEquals(0.5f, bars[0].lastYearFraction)
    }

    @Test
    fun `a short month list leaves the rest of the chart empty`() {
        val bars = monthBars(thisYear = listOf(10L), lastYear = emptyList())

        assertEquals(12, bars.size)
        assertEquals(0L, bars[11].thisYearSeconds)
        assertEquals(0f, bars[11].lastYearFraction)
    }

    // ── Heatmap ──────────────────────────────────────────────────────────────

    @Test
    fun `heatmap columns start on Sunday and end on the current week`() {
        val weeks = heatmapWeeks(calendar = emptyMap(), today = today, weeks = 3)

        assertEquals(3, weeks.size)
        weeks.forEach { week ->
            val firstDay = week.first { it != null }
            // 7 is Sunday in ISO numbering.
            if (week.first() != null) assertEquals(7, week.first()!!.date.dayOfWeek.isoDayNumber)
            assertNotNull(firstDay)
        }
        assertEquals(LocalDate.parse("2026-08-02"), weeks.first().first()?.date)
    }

    // The `% 7` that turns Monday-first ISO numbering into a Sunday-first grid is invisible
    // on any other weekday — on a Monday, `1 % 7` and `1` are the same number — so it takes
    // a Sunday to pin down. Without the modulo a Sunday shifts back a whole week.
    //
    @Test
    fun `on a Sunday the current week opens a new column`() {
        val sunday = LocalDate.parse("2026-08-16")

        val weeks = heatmapWeeks(calendar = emptyMap(), today = sunday, weeks = 3)

        assertEquals(sunday, weeks.last().first()?.date)
        assertEquals(1, weeks.last().count { it != null })
        assertTrue(weeks.flatten().filterNotNull().any { it.date == sunday })
    }

    @Test
    fun `days after today are absent rather than empty`() {
        val weeks = heatmapWeeks(calendar = emptyMap(), today = today, weeks = 3)

        // The current week runs Sun 16th and Mon 17th; the 18th onwards has not happened.
        assertEquals(2, weeks.last().count { it != null })
        assertEquals(LocalDate.parse("2026-08-17"), weeks.last()[1]?.date)
        assertNull(weeks.last()[2])
    }

    @Test
    fun `intensity is scaled against the busiest visible day`() {
        val calendar = mapOf(
            "2026-08-10" to 3_600L,
            "2026-08-05" to 1_800L,
            "2026-08-03" to 900L,
            // Far outside the three-week window and enormous.
            "2020-01-01" to 500_000L,
        )

        val cells = heatmapWeeks(calendar, today, weeks = 3).flatten().filterNotNull()
            .associateBy { it.date.toString() }

        assertEquals(4, cells.getValue("2026-08-10").level)
        assertEquals(2, cells.getValue("2026-08-05").level)
        assertEquals(1, cells.getValue("2026-08-03").level)
        assertEquals(0, cells.getValue("2026-08-04").level)
    }

    @Test
    fun `intensity buckets cover the range and handle no history`() {
        assertEquals(0, intensityLevel(seconds = 0, peak = 100))
        assertEquals(1, intensityLevel(seconds = 25, peak = 100))
        assertEquals(2, intensityLevel(seconds = 50, peak = 100))
        assertEquals(3, intensityLevel(seconds = 75, peak = 100))
        assertEquals(4, intensityLevel(seconds = 100, peak = 100))
        assertEquals(0, intensityLevel(seconds = 10, peak = 0))
    }

    @Test
    fun `heatmap labels a month only where it starts`() {
        val weeks = heatmapWeeks(calendar = emptyMap(), today = today, weeks = 3)

        // The window opens on 2 August and never leaves August, so exactly one label.
        assertEquals(listOf(0 to "Aug"), heatmapMonthLabels(weeks))
    }

    @Test
    fun `heatmap cells name their own day`() {
        // 2026-08-10 is a Monday; today is the 17th.
        assertEquals("Mon 10 Aug", heatDayLabel(LocalDate.parse("2026-08-10"), today))
        assertEquals("Sun 16 Aug", heatDayLabel(LocalDate.parse("2026-08-16"), today))
        // The current day is named rather than dated — it is the one cell a reader can
        // locate without counting columns.
        assertEquals("Today", heatDayLabel(today, today))
    }

    @Test
    fun `each range ends its heatmap on the last day it covers`() {
        val (thisEnd, thisWeeks) = heatmapWindow(InsightsRange.ThisYear, today)
        assertEquals(today, thisEnd)
        // 17 August is week 33 of the year, so the window is far shorter than a full year.
        assertTrue(thisWeeks in 30..36, "this year should span its elapsed weeks, got $thisWeeks")

        val (lastEnd, lastWeeks) = heatmapWindow(InsightsRange.LastYear, today)
        assertEquals(LocalDate.parse("2025-12-31"), lastEnd)
        assertEquals(HEATMAP_WEEKS, lastWeeks)

        val (allEnd, allWeeks) = heatmapWindow(InsightsRange.AllTime, today)
        assertEquals(today, allEnd)
        assertEquals(HEATMAP_WEEKS, allWeeks)
    }

    // ── Rhythm ───────────────────────────────────────────────────────────────

    @Test
    fun `peaks are null until something has actually been watched`() {
        assertNull(peakHour(List(24) { 0L }))
        assertNull(busiestWeekday(List(7) { 0L }))

        assertEquals(21, peakHour(List(24) { if (it == 21) 500L else 10L }))
        assertEquals(6, busiestWeekday(List(7) { if (it == 6) 500L else 10L }))
    }

    @Test
    fun `hours read as a twelve hour clock`() {
        assertEquals("12 am", formatHour(0))
        assertEquals("9 am", formatHour(9))
        assertEquals("12 pm", formatHour(12))
        assertEquals("9 pm", formatHour(21))
        assertEquals("11 pm", formatHour(23))
    }

    @Test
    fun `the rhythm sentence stays quiet with nothing to say`() {
        assertNull(rhythmSummary(ActivityStats()))

        val stats = ActivityStats(
            byHourOfDay = List(24) { if (it == 21) 500L else 0L },
            byDayOfWeek = List(7) { if (it == 6) 500L else 0L },
        )
        assertEquals(
            "Most of your watching happens around 9 pm, and Saturday is your biggest day.",
            rhythmSummary(stats),
        )
    }

    // ── Library composition ──────────────────────────────────────────────────

    @Test
    fun `every status is counted even when nothing is in it`() {
        val breakdown = libraryBreakdown(
            listOf(
                entry(1, LibraryStatus.Finished),
                entry(2, LibraryStatus.Finished),
                entry(3, LibraryStatus.Watching, type = MediaType.Tv),
            ),
        )

        assertEquals(2, breakdown.statusCounts[LibraryStatus.Finished])
        assertEquals(1, breakdown.statusCounts[LibraryStatus.Watching])
        assertEquals(0, breakdown.statusCounts[LibraryStatus.Dropped])
        assertEquals(0, breakdown.statusCounts[LibraryStatus.WatchLater])
        assertEquals(3, breakdown.total)
        assertEquals(2, breakdown.movies)
        assertEquals(1, breakdown.shows)
    }

    @Test
    fun `an unrated library has no average rather than an average of zero`() {
        val unrated = libraryBreakdown(listOf(entry(1, LibraryStatus.Finished)))
        assertNull(unrated.averageRating)
        assertEquals(0, unrated.ratedCount)

        val rated = libraryBreakdown(
            listOf(
                entry(1, LibraryStatus.Finished, rating = 5.0),
                entry(2, LibraryStatus.Dropped, rating = 2.0),
                entry(3, LibraryStatus.Watching),
            ),
        )
        assertEquals(2, rated.ratedCount)
        assertEquals(3.5, rated.averageRating)
    }

    // ── Taste ────────────────────────────────────────────────────────────────

    @Test
    fun `disliked genres still produce bars that grow with strength`() {
        val bars = normalizeTaste(
            listOf(
                DiscoveryTaste(1, "Horror", -8.0),
                DiscoveryTaste(2, "Western", -4.0),
            ),
        )

        assertEquals(1f, bars[0].fraction)
        assertEquals(0.5f, bars[1].fraction)
        // The sign is preserved for the caller even though the bar length ignores it.
        assertEquals(-8.0, bars[0].score)
    }

    @Test
    fun `taste bars survive an all-zero profile`() {
        val bars = normalizeTaste(listOf(DiscoveryTaste(1, "Drama", 0.0)))

        assertEquals(0f, bars.single().fraction)
        assertTrue(normalizeTaste(emptyList()).isEmpty())
    }

    @Test
    fun `leaderboard shares are relative to the most watched title`() {
        val shares = titleShares(
            listOf(
                ActivityTitle(tmdbId = 1, mediaType = "movie", seconds = 600),
                ActivityTitle(tmdbId = 2, mediaType = "tv", seconds = 300),
                ActivityTitle(tmdbId = 3, mediaType = "movie", seconds = 100),
            ),
        )

        assertEquals(listOf(1f, 0.5f), shares.take(2))
        assertTrue(titleShares(emptyList()).isEmpty())
    }

    // ── You against the crowd ────────────────────────────────────────────────

    @Test
    fun `ratings are compared on the same scale`() {
        val comparison = ratingComparison(
            listOf(
                // ★4 is 8.0 out of ten — exactly what everyone else gave it.
                entry(1, LibraryStatus.Finished, rating = 4.0).copy(voteAverage = 8.0),
                // ★5 is 10.0; against a crowd 6.0 that is four points more.
                entry(2, LibraryStatus.Finished, rating = 5.0).copy(voteAverage = 6.0),
            ),
        )

        assertEquals(2, comparison.rated)
        // (0.0 + 4.0) / 2
        assertEquals(2.0, comparison.averageDelta)
        assertEquals(1, comparison.higher)
        assertEquals(0, comparison.lower)
    }

    @Test
    fun `titles the crowd has not scored are left out`() {
        val comparison = ratingComparison(
            listOf(
                entry(1, LibraryStatus.Finished, rating = 4.0).copy(voteAverage = 8.0),
                // Rated by the viewer, never scored publicly.
                entry(2, LibraryStatus.Finished, rating = 4.0).copy(voteAverage = 0.0),
                // Scored publicly, never rated by the viewer.
                entry(3, LibraryStatus.Finished).copy(voteAverage = 9.0),
            ),
        )

        assertEquals(1, comparison.rated)
        assertEquals(0.0, comparison.averageDelta)
    }

    @Test
    fun `the biggest disagreement leads regardless of direction`() {
        val comparison = ratingComparison(
            listOf(
                entry(1, LibraryStatus.Finished, rating = 5.0).copy(voteAverage = 9.0, title = "Mild"),
                entry(2, LibraryStatus.Dropped, rating = 1.0).copy(voteAverage = 9.0, title = "Hated"),
            ),
        )

        assertEquals("Hated", comparison.gaps.first().title)
    }

    // ── Finishing what you start ─────────────────────────────────────────────

    @Test
    fun `a title barely started does not count as abandoned`() {
        val stats = finishStats(
            listOf(
                progress(1, position = 10.0, duration = 6_000.0),
                progress(2, position = 3_000.0, duration = 6_000.0),
                progress(3, position = 6_000.0, duration = 6_000.0, completed = true),
            ),
        )

        assertEquals(3, stats.started)
        assertEquals(1, stats.finished)
        assertEquals(1, stats.stalled.size)
        assertEquals(2, stats.stalled.single().tmdbId)
    }

    @Test
    fun `the finish rate is a share of everything started`() {
        val stats = finishStats(
            listOf(
                progress(1, position = 100.0, duration = 100.0, completed = true),
                progress(2, position = 50.0, duration = 100.0),
            ),
        )

        assertEquals(0.5f, stats.rate)
        assertEquals(FinishStats(0, 0, 0f, emptyList()), finishStats(emptyList()))
    }

    // ── Library growth ───────────────────────────────────────────────────────

    @Test
    fun `library growth walks back twelve months across a year boundary`() {
        val growth = libraryGrowth(
            listOf(
                entry(1, LibraryStatus.Finished).copy(addedAt = "2026-08-02T10:00:00Z"),
                entry(2, LibraryStatus.Finished).copy(addedAt = "2026-08-19T10:00:00Z"),
                // Eleven months earlier — the first column of the window.
                entry(3, LibraryStatus.Finished).copy(addedAt = "2025-09-14T10:00:00Z"),
                // Outside the window entirely.
                entry(4, LibraryStatus.Finished).copy(addedAt = "2024-01-01T10:00:00Z"),
            ),
            today,
        )

        assertEquals(12, growth.size)
        assertEquals(1, growth.first().added)
        assertEquals(2, growth.last().added)
        assertEquals(1f, growth.last().fraction)
    }

    // ── All time ─────────────────────────────────────────────────────────────

    @Test
    fun `year bars run oldest first and scale to the biggest year`() {
        val bars = yearBars(mapOf("2026" to 50L, "2024" to 100L, "2025" to 25L))

        assertEquals(listOf("2024", "2025", "2026"), bars.map { it.year })
        assertEquals(1f, bars.first().fraction)
        assertEquals(0.25f, bars[1].fraction)
    }

    // ── Labels ───────────────────────────────────────────────────────────────

    @Test
    fun `languages are named where known and echoed where not`() {
        assertEquals("Japanese", languageName("ja"))
        assertEquals("English", languageName("EN"))
        // An unknown code is echoed rather than dropped or guessed at.
        assertEquals("ZZ", languageName("zz"))
        assertEquals("1990s", decadeLabel(1990))
    }

    @Test
    fun `languages sharing a name are merged into one row`() {
        val merged = namedLanguages(
            listOf(
                LanguageCount("en", 10),
                LanguageCount("zh", 3),
                LanguageCount("cn", 4),
                LanguageCount("ja", 5),
            ),
        )

        assertEquals(listOf("English", "Chinese", "Japanese"), merged.map { it.first })
        // 3 + 4, not two rows of 3 and 4.
        assertEquals(7, merged.first { it.first == "Chinese" }.second)
    }

    // ── Empty state ──────────────────────────────────────────────────────────

    @Test
    fun `saved titles alone are enough to have something to show`() {
        assertTrue(insightsAreEmpty(ActivityStats(), DiscoveryInsights(), libraryCount = 0))

        assertFalse(insightsAreEmpty(ActivityStats(), DiscoveryInsights(), libraryCount = 4))
        assertFalse(
            insightsAreEmpty(
                ActivityStats(totalSeconds = 900, calendar = mapOf("2026-08-10" to 900L)),
                DiscoveryInsights(),
                libraryCount = 0,
            ),
        )
        assertFalse(
            insightsAreEmpty(ActivityStats(), DiscoveryInsights(signalsUsed = 3), libraryCount = 0),
        )
    }

    // ── Headlines ────────────────────────────────────────────────────────────
    //
    // These are the sentences the page states about the viewer, so the thing worth
    // asserting is not the wording but the *claim*: that a headline names the right month,
    // that it declines to name a decade nothing dominates, and that it falls back rather
    // than overclaiming when the data underneath it is thin.

    @Test
    fun `time bands split the day where the words change`() {
        assertEquals(TimeBand.Morning, timeBand(5))
        assertEquals(TimeBand.Morning, timeBand(11))
        assertEquals(TimeBand.Afternoon, timeBand(12))
        assertEquals(TimeBand.Afternoon, timeBand(16))
        assertEquals(TimeBand.Evening, timeBand(17))
        assertEquals(TimeBand.Evening, timeBand(21))
        assertEquals(TimeBand.Night, timeBand(22))
        // The night band wraps midnight rather than restarting there: 1am belongs to the
        // sitting that began the evening before. Splitting at 0 files it under Morning.
        assertEquals(TimeBand.Night, timeBand(0))
        assertEquals(TimeBand.Night, timeBand(4))
    }

    @Test
    fun `the night band is the only one that reads differently on its own`() {
        assertEquals("nights", bandAfterWeekday(TimeBand.Night))
        assertEquals("late nights", bandAlone(TimeBand.Night))
        // Every other band is the same word either way; making them differ would be churn.
        assertEquals(bandAfterWeekday(TimeBand.Evening), bandAlone(TimeBand.Evening))
        assertEquals(bandAfterWeekday(TimeBand.Morning), bandAlone(TimeBand.Morning))
    }

    @Test
    fun `the monthly headline names the peak month`() {
        val months = MutableList(12) { 0L }
        months[2] = 9 * 3600L
        months[6] = 4 * 3600L
        // March, not July: picking the last maximum instead of the first, or reading the
        // index one out, both name the wrong month here.
        assertEquals("March was your biggest month", monthlyHeadline(months, currentMonth = 8))
    }

    @Test
    fun `the running month is only ever the biggest so far`() {
        val months = MutableList(12) { 0L }
        months[7] = 9 * 3600L
        // August is the peak *and* the month in progress, so the claim is qualified. Drop
        // the qualifier and the page states something the rest of the month can overturn.
        assertEquals("August is your biggest month so far", monthlyHeadline(months, 8))
        // The same data seen from September is a settled fact.
        assertEquals("August was your biggest month", monthlyHeadline(months, 9))
    }

    @Test
    fun `a year with nothing in it gets no monthly claim`() {
        assertEquals("This year against last", monthlyHeadline(List(12) { 0L }, currentMonth = 8))
    }

    @Test
    fun `the rhythm headline joins the weekday to the part of day`() {
        val stats = ActivityStats(
            byHourOfDay = List(24) { if (it == 21) 5_000L else 0L },
            byDayOfWeek = List(7) { if (it == 0) 5_000L else 0L },
        )
        // 21:00 is the Evening band, and index 0 of byDayOfWeek is Sunday. A Monday-based
        // weekday index, or an off-by-one band boundary, both change this string.
        assertEquals("Sunday evenings, around 9 pm", rhythmHeadline(stats))
    }

    @Test
    fun `half a habit is not stated at all`() {
        val noHours = ActivityStats(byDayOfWeek = List(7) { if (it == 3) 900L else 0L })
        val noDays = ActivityStats(byHourOfDay = List(24) { if (it == 20) 900L else 0L })
        // Naming a weekday with no time, or a time with no weekday, would read as a fact
        // the page cannot actually support.
        assertEquals("When your watching actually happens", rhythmHeadline(noHours))
        assertEquals("When your watching actually happens", rhythmHeadline(noDays))
    }

    @Test
    fun `the heatmap denominator follows the range`() {
        // today is 2026-08-17, the 229th day of a non-leap year.
        assertEquals(
            "You watched on 100 of the year's 229 days so far",
            heatmapHeadline(100, InsightsRange.ThisYear, today),
        )
        // A finished year is measured against the whole of itself, not against today.
        assertEquals(
            "You watched on 100 of 365 days",
            heatmapHeadline(100, InsightsRange.LastYear, today),
        )
        // All-time has no denominator to offer, and must not invent one.
        assertEquals(
            "You watched on 100 separate days",
            heatmapHeadline(100, InsightsRange.AllTime, today),
        )
        assertEquals(
            "You watched on 1 separate day",
            heatmapHeadline(1, InsightsRange.AllTime, today),
        )
    }

    @Test
    fun `leap years are counted as leap years`() {
        assertEquals(366, daysInYear(2024))
        assertEquals(365, daysInYear(2026))
        // The century rules, which a naive `year % 4` gets wrong in both directions.
        assertEquals(366, daysInYear(2000))
        assertEquals(365, daysInYear(1900))
    }

    @Test
    fun `the library headline reports what the titles are doing`() {
        val mixed = libraryBreakdown(
            listOf(
                entry(1, LibraryStatus.Finished),
                entry(2, LibraryStatus.Finished),
                entry(3, LibraryStatus.Watching),
                entry(4, LibraryStatus.WatchLater),
            ),
        )
        assertEquals("2 finished, 1 still going", compositionHeadline(mixed))

        // Nothing in progress: the sentence changes shape rather than saying "0 still going".
        val doneOnly = libraryBreakdown(
            listOf(entry(1, LibraryStatus.Finished), entry(2, LibraryStatus.WatchLater)),
        )
        assertEquals("1 of 2 finished", compositionHeadline(doneOnly))

        val untouched = libraryBreakdown(listOf(entry(1, LibraryStatus.WatchLater)))
        assertEquals("1 title saved", compositionHeadline(untouched))
    }

    @Test
    fun `follow-through is reported as a number, never as a verdict`() {
        assertEquals(
            "You finish 80% of what you start",
            finishHeadline(FinishStats(started = 10, finished = 8, rate = 0.8f, stalled = emptyList())),
        )
        // The one special case, because "You finish 100% of what you start" is stilted.
        assertEquals(
            "You finish almost everything you start",
            finishHeadline(FinishStats(started = 10, finished = 10, rate = 1f, stalled = emptyList())),
        )
        // A low rate gets the same neutral sentence — the page does not editorialise about
        // abandoning things, for the same reason a falling year badge is grey and not red.
        assertEquals(
            "You finish 10% of what you start",
            finishHeadline(FinishStats(started = 10, finished = 1, rate = 0.1f, stalled = emptyList())),
        )
    }

    @Test
    fun `genres are merged across movies and shows before they are ranked`() {
        val movies = listOf(DiscoveryTaste(1, "Sci-Fi", 3.0), DiscoveryTaste(2, "Thriller", 5.0))
        val tv = listOf(DiscoveryTaste(1, "Sci-Fi", 4.0), DiscoveryTaste(3, "Drama", 2.0))
        // Sci-Fi wins on 3 + 4 = 7 despite losing to Thriller in the movie list alone.
        // Ranking the two lists separately puts Thriller first and buries the real answer.
        assertEquals("Sci-Fi, Thriller and Drama", genreHeadline(movies, tv))
    }

    @Test
    fun `genre headlines read as a list of whatever length survives`() {
        assertEquals("Sci-Fi", genreHeadline(listOf(DiscoveryTaste(1, "Sci-Fi", 3.0)), emptyList()))
        assertEquals(
            "Sci-Fi and Drama",
            genreHeadline(
                listOf(DiscoveryTaste(1, "Sci-Fi", 3.0), DiscoveryTaste(2, "Drama", 1.0)),
                emptyList(),
            ),
        )
        assertEquals(
            "The genres your library argues for hardest",
            genreHeadline(emptyList(), emptyList()),
        )
        // Negative weights are the *disliked* list; they must never be named as a favourite.
        assertEquals(
            "The genres your library argues for hardest",
            genreHeadline(listOf(DiscoveryTaste(1, "Horror", -4.0)), emptyList()),
        )
    }

    @Test
    fun `a rating gap too small to be real is reported as agreement`() {
        assertEquals(
            "You rate 0.8 above the crowd",
            crowdHeadline(RatingComparison(12, 0.8, 8, 4, emptyList())),
        )
        assertEquals(
            "You rate 0.8 below the crowd",
            crowdHeadline(RatingComparison(12, -0.8, 4, 8, emptyList())),
        )
        // Under a fifth of a point the sign is decided by a couple of titles and would flip
        // between visits; stating a direction there would be the page guessing.
        assertEquals(
            "You rate about the same as everyone else",
            crowdHeadline(RatingComparison(12, 0.1, 6, 6, emptyList())),
        )
    }

    @Test
    fun `a decade is only claimed when one actually dominates`() {
        val dominant = listOf(DecadeCount(2010, 7), DecadeCount(1990, 2), DecadeCount(2020, 1))
        assertEquals("The 2010s are your decade", decadesHeadline(dominant))

        // Four near-equal decades: naming the largest would be reading meaning into noise,
        // so the honest finding is the spread instead.
        val spread = listOf(
            DecadeCount(2010, 3),
            DecadeCount(1990, 3),
            DecadeCount(2000, 3),
            DecadeCount(1980, 2),
        )
        assertEquals("You range from the 1980s to the 2010s", decadesHeadline(spread))
    }

    @Test
    fun `the language headline states a share it can be checked against`() {
        val quarter = listOf(LanguageCount("en", 75), LanguageCount("ja", 25))
        assertEquals("25% of your titles aren't in English", languagesHeadline(quarter))

        assertEquals(
            "Everything you watch is in English",
            languagesHeadline(listOf(LanguageCount("en", 40))),
        )
        // Merged by display name first, so zh and cn count as one language and not two.
        val chinese = listOf(
            LanguageCount("en", 50),
            LanguageCount("zh", 25),
            LanguageCount("cn", 25),
        )
        assertEquals("50% of your titles aren't in English", languagesHeadline(chinese))
    }

    @Test
    fun `the identity line needs at least two clauses to be worth printing`() {
        val shows = libraryBreakdown(
            List(8) { entry(it, LibraryStatus.Finished, MediaType.Tv) } +
                List(2) { entry(it + 100, LibraryStatus.Finished, MediaType.Movie) },
        )
        val nights = ActivityStats(byHourOfDay = List(24) { if (it == 23) 900L else 0L })
        val decades = listOf(DecadeCount(2010, 8), DecadeCount(1990, 2))
        assertEquals(
            "mostly series · late nights · the 2010s",
            identityLine(shows, nights, decades),
        )

        // One clause is not a portrait — it says less than the total directly above it.
        assertNull(identityLine(shows, ActivityStats(), emptyList()))
    }

    @Test
    fun `a library that leans neither way is not called one or the other`() {
        val even = libraryBreakdown(
            List(5) { entry(it, LibraryStatus.Finished, MediaType.Tv) } +
                List(5) { entry(it + 100, LibraryStatus.Finished, MediaType.Movie) },
        )
        val nights = ActivityStats(byHourOfDay = List(24) { if (it == 23) 900L else 0L })
        val decades = listOf(DecadeCount(2010, 8), DecadeCount(1990, 2))
        // The shape clause drops out, leaving two: someone with five of each watches both.
        assertEquals("late nights · the 2010s", identityLine(even, nights, decades))
    }

    @Test
    fun `the hero only claims a best year once there is another to beat`() {
        val onlyYear = ActivityStats(totalSeconds = 100, byYear = mapOf("2026" to 100L))
        // A first year of use has beaten nothing, so there is no claim to make.
        assertNull(heroContext(onlyYear, InsightsRange.ThisYear, 2026))

        val beaten = ActivityStats(
            totalSeconds = 300,
            byYear = mapOf("2025" to 100L, "2026" to 300L),
        )
        assertEquals("your biggest year yet", heroContext(beaten, InsightsRange.ThisYear, 2026))
        // 2026 is the best year, so viewing 2025 must not inherit the badge.
        assertNull(heroContext(beaten, InsightsRange.LastYear, 2026))
        assertEquals("across 2 years", heroContext(beaten, InsightsRange.AllTime, 2026))
    }

    @Test
    fun `the leaderboard names the title and the period together`() {
        val titles = listOf(
            ActivityTitle(1, "tv", 9_000, "Severance", ""),
            ActivityTitle(2, "movie", 3_000, "Arrival", ""),
        )
        assertEquals("Severance led your year", leaderboardHeadline(titles, InsightsRange.ThisYear))
        assertEquals("Severance led last year", leaderboardHeadline(titles, InsightsRange.LastYear))
        assertEquals(
            "Severance is your most-watched",
            leaderboardHeadline(titles, InsightsRange.AllTime),
        )
        // Activity rows are keyed by tmdb id and learn their name from the library, so a
        // title watched and then removed arrives here nameless rather than absent.
        assertEquals(
            "The titles you gave the most hours to",
            leaderboardHeadline(listOf(ActivityTitle(1, "tv", 9_000, "", "")), InsightsRange.ThisYear),
        )
    }

    @Test
    fun `taste signals fall back through people, themes, then studios`() {
        val people = DiscoveryInsights(topPeople = listOf(DiscoveryTaste(1, "Denis Villeneuve", 4.0)))
        assertEquals("Denis Villeneuve shows up more than anyone", signalsHeadline(people))

        val themes = DiscoveryInsights(topKeywords = listOf(DiscoveryTaste(2, "time loop", 3.0)))
        assertEquals("You keep coming back to time loop", signalsHeadline(themes))

        val studios = DiscoveryInsights(topStudios = listOf(StudioEntry(3, "A24", 6)))
        assertEquals("A24 made more of your library than anyone", signalsHeadline(studios))

        assertEquals(
            "Themes, people and studios behind your recommendations",
            signalsHeadline(DiscoveryInsights()),
        )
    }

    @Test
    fun `contributors name whichever direction the profile actually has`() {
        val positive = DiscoveryInsights(
            topContributors = listOf(ContributingTitle(1, "tv", "Severance", "", 4.0)),
        )
        assertEquals("Severance shaped your profile most", contributorsHeadline(positive))

        // A profile with only negative signal still has something true to say.
        val negative = DiscoveryInsights(
            negativeContributors = listOf(ContributingTitle(2, "movie", "Morbius", "", -3.0)),
        )
        assertEquals("Morbius steered you away hardest", contributorsHeadline(negative))

        assertEquals(
            "The strongest pulls in each direction",
            contributorsHeadline(DiscoveryInsights()),
        )
    }

    // ── Moments ──────────────────────────────────────────────────────────────

    @Test
    fun `a date in this year carries its weekday and an older one carries its year`() {
        // today is 2026-08-17. 14 March 2026 was a Saturday.
        assertEquals("Saturday 14 March", formatMomentDate("2026-03-14", today))
        // An older date drops the weekday, which nobody can place, and gains the year,
        // which is the part that actually locates it.
        assertEquals("14 March 2025", formatMomentDate("2025-03-14", today))
        assertNull(formatMomentDate("not-a-date", today))
    }

    @Test
    fun `a monthly headliner is labelled by its month`() {
        assertEquals("Mar", momentMonthLabel("2026-03-01"))
        assertEquals("Dec", momentMonthLabel("2026-12-01"))
        assertNull(momentMonthLabel(""))
    }

    @Test
    fun `moment headlines lead with whichever half is the claim`() {
        val day = WatchMoment(date = "2026-03-14", seconds = 9 * 3600 + 12 * 60, tmdbId = 1)
        // The biggest day is a claim about a date, so the date is in the sentence.
        assertEquals("9h 12m on Saturday 14 March", biggestDayHeadline(day, today))
        // The longest sitting is a claim about a duration; its date is a qualifier and
        // belongs in the support line, not here.
        assertEquals("9h 12m without stopping", longestSessionHeadline(day))
    }

    @Test
    fun `an empty moment produces no headline at all`() {
        val empty = WatchMoment(date = "", seconds = 900)
        val dateless = WatchMoment(date = "2026-03-14", seconds = 0)
        assertNull(biggestDayHeadline(empty, today))
        assertNull(biggestDayHeadline(dateless, today))
        assertNull(longestSessionHeadline(empty))
        assertNull(firstWatchHeadline(empty, today, InsightsRange.ThisYear))
    }

    @Test
    fun `the first watch names the title only when there is one`() {
        val named = WatchMoment(date = "2026-01-02", seconds = 3600, tmdbId = 1, title = "Arrival")
        assertEquals(
            "You started with Arrival on Friday 2 January",
            firstWatchHeadline(named, today, InsightsRange.ThisYear),
        )
        // All-time is not a year anyone started, so the phrasing changes with it.
        assertEquals(
            "It all started with Arrival on Friday 2 January",
            firstWatchHeadline(named, today, InsightsRange.AllTime),
        )
        // A title the library has forgotten leaves the date standing on its own rather
        // than producing "You started with  on ...".
        val nameless = WatchMoment(date = "2026-01-02", seconds = 3600, tmdbId = 1)
        assertEquals(
            "You started on Friday 2 January",
            firstWatchHeadline(nameless, today, InsightsRange.ThisYear),
        )
    }

    // ── Recap ────────────────────────────────────────────────────────────────

    @Test
    fun `the recap file is named after the period it covers`() {
        // today is 2026-08-17. A downloads folder full of "image.png" is the thing this
        // avoids, so the period has to be in the name and has to follow the range.
        assertEquals("cove-2026.png", recapFileName(InsightsRange.ThisYear, today))
        assertEquals("cove-2025.png", recapFileName(InsightsRange.LastYear, today))
        assertEquals("cove-all-time.png", recapFileName(InsightsRange.AllTime, today))
    }
}
