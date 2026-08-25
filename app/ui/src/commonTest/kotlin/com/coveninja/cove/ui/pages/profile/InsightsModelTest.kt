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
}
