package com.coveninja.cove.ui.tv.pages

import com.coveninja.cove.shared.model.ActivityStats
import com.coveninja.cove.shared.model.ActivityTitle
import com.coveninja.cove.shared.model.DiscoveryInsights
import com.coveninja.cove.shared.model.DiscoveryTaste
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.ui.pages.profile.LibraryBreakdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvInsightsSectionsTest {

    private fun breakdown(total: Int) = LibraryBreakdown(
        statusCounts = LibraryStatus.entries.associateWith { if (total > 0) 1 else 0 },
        total = total,
        movies = total,
        shows = 0,
        ratedCount = 0,
        averageRating = null,
    )

    private fun keys(
        stats: ActivityStats = ActivityStats(),
        profile: DiscoveryInsights = DiscoveryInsights(),
        total: Int = 0,
    ): List<String> = buildTvInsightsSections(stats, profile, breakdown(total)).map { it.key }

    // The headline is the one card that is always worth drawing: zeroes in it are a fact about
    // the viewer, where zeroes in a bar chart are just an empty chart.
    @Test
    fun `the headline card is always present`() {
        assertEquals(listOf("headline"), keys())
    }

    // Mutation check: drop the `any { it > 0 }` guards and a profile that has watched nothing
    // gets twelve empty columns and a flat week — each of which is also a focus stop the D-pad
    // has to walk past on the way down the page.
    @Test
    fun `charts with nothing in them are absent rather than flat`() {
        val empty = keys(ActivityStats(byMonthThisYear = List(12) { 0 }, byDayOfWeek = List(7) { 0 }))

        assertTrue("months" !in empty, "an empty year should not draw month bars")
        assertTrue("weekdays" !in empty, "an empty week should not draw weekday bars")
    }

    @Test
    fun `a year with time in it earns its bars`() {
        val stats = ActivityStats(
            byMonthThisYear = List(12) { index -> if (index == 3) 7_200L else 0L },
            byDayOfWeek = List(7) { index -> if (index == 5) 7_200L else 0L },
        )

        assertTrue("months" in keys(stats))
        assertTrue("weekdays" in keys(stats))
    }

    // Only last year having anything still earns the card: the comparison is the point of it,
    // and a viewer who watched nothing this year is exactly who that comparison is for.
    @Test
    fun `last year alone is enough to draw the comparison`() {
        val stats = ActivityStats(
            byMonthThisYear = List(12) { 0 },
            byMonthLastYear = List(12) { index -> if (index == 1) 3_600L else 0L },
        )

        assertTrue("months" in keys(stats))
    }

    // Mutation check: drop the isNotBlank filter and a title with no artwork contributes an
    // empty tile to the wall, which reads as a failed image rather than as missing data.
    @Test
    fun `the poster wall ignores titles with no artwork`() {
        val stats = ActivityStats(
            titlesWatchedThisYear = listOf(
                ActivityTitle(tmdbId = 1, mediaType = "movie", seconds = 60, posterPath = ""),
            ),
        )

        assertTrue("posters" !in keys(stats))
    }

    @Test
    fun `a title with artwork earns the wall`() {
        val stats = ActivityStats(
            titlesWatchedThisYear = listOf(
                ActivityTitle(tmdbId = 1, mediaType = "movie", seconds = 60, posterPath = "/a.jpg"),
            ),
        )

        assertTrue("posters" in keys(stats))
    }

    // Mutation check: drop the `breakdown.total > 0` guard and an empty library draws a ring of
    // four zero slices, which is a circle with nothing in it.
    @Test
    fun `an empty library draws no ring`() {
        assertTrue("composition" !in keys(total = 0))
        assertTrue("composition" in keys(total = 4))
    }

    // Movie and TV genres are one ranking here rather than the phone's two lists, so the bars
    // have to be re-sorted after the merge — otherwise the strongest TV genre sits below the
    // weakest film one purely because of which list it came from.
    @Test
    fun `taste bars are ranked across both genre lists`() {
        val profile = DiscoveryInsights(
            topMovieGenres = listOf(DiscoveryTaste(id = 18, name = "Drama", score = 1.0)),
            topTvGenres = listOf(DiscoveryTaste(id = 35, name = "Comedy", score = 9.0)),
        )
        val taste = buildTvInsightsSections(ActivityStats(), profile, breakdown(0))
            .filterIsInstance<TvInsightsSection.Taste>()
            .single()

        assertEquals("Comedy", taste.bars.first().name)
    }
}
