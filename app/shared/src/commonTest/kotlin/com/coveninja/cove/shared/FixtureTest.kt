package com.coveninja.cove.shared

import com.coveninja.cove.shared.data.BrowseQuery
import com.coveninja.cove.shared.data.CalendarState
import com.coveninja.cove.shared.data.HomeState
import com.coveninja.cove.shared.data.LibraryState
import com.coveninja.cove.shared.fixture.FixtureAppGraph
import com.coveninja.cove.shared.model.CatalogSort
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.MediaType
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val SCIENCE_FICTION = 878

class FixtureTest {

    @Test
    fun `fixture home state is Ready and contains expected titles`() {
        val graph = FixtureAppGraph()
        val state = graph.content.home.value
        assertIs<HomeState.Ready>(state)
        assertTrue(state.items.any { it.displayTitle == "Fight Club" })
        assertTrue(state.items.any { it.displayTitle == "Breaking Bad" })

        // Explore builds a rail per genre out of this and drops any that comes back too
        // short, so a fixture catalog has to be big enough and varied enough to produce
        // more than one. A corpus that fails this makes every rail look broken with no
        // way to tell a layout bug from a data shortage.
        assertTrue(
            state.items.size >= 30,
            "fixture catalog is ${state.items.size} titles; too few to fill Explore's rails",
        )
        assertTrue(
            state.items.all { it.genreIds.isNotEmpty() },
            "every fixture title needs genre ids, or the genre filter has nothing to do",
        )
        assertTrue(
            state.items.flatMap { it.genreIds }.distinct().size >= 10,
            "fixture genres are too uniform to exercise per-genre rails",
        )
    }

    @Test
    fun `fixture discovery browses, sorts, pages and runs out`() = runTest {
        val discovery = FixtureAppGraph().discovery

        val byRating = discovery.browse(BrowseQuery(MediaType.Movie, sort = CatalogSort.Rating))
        assertEquals(
            byRating.map { it.voteAverage }.sortedDescending(),
            byRating.map { it.voteAverage },
            "rating order is not actually applied",
        )

        val sciFi = discovery.browse(BrowseQuery(MediaType.Movie, genreId = SCIENCE_FICTION))
        assertTrue(sciFi.isNotEmpty(), "no fixture films carry the sci-fi genre")
        assertTrue(
            sciFi.all { SCIENCE_FICTION in it.genreIds },
            "genre filter let through a title without the genre",
        )

        // Paging has to terminate, or the grid's infinite scroll cannot be told apart
        // from one that is silently stuck.
        val first = discovery.browse(BrowseQuery(MediaType.Movie, page = 1))
        val beyond = discovery.browse(BrowseQuery(MediaType.Movie, page = 99))
        assertTrue(first.isNotEmpty())
        assertTrue(beyond.isEmpty(), "paging past the fixture catalog should return nothing")
    }

    @Test
    fun `fixture discovery has genres and taste to build personal rails from`() = runTest {
        val discovery = FixtureAppGraph().discovery

        assertTrue(discovery.genres(MediaType.Movie).isNotEmpty())
        assertTrue(discovery.genres(MediaType.Tv).isNotEmpty())
        assertTrue(discovery.topGenres(MediaType.Tv, 3).isNotEmpty(), "no taste from the fixture library")

        val favorite = discovery.favorites(1).singleOrNull()
        assertTrue(favorite != null, "no favourite to title a \"Because you watched\" rail with")
        // The rail's heading is built from this; an id alone would render as "Because you
        // watched ".
        assertTrue(favorite.title.isNotBlank())
    }

    @Test
    fun `fixture library state is Ready and contains expected entries`() {
        val graph = FixtureAppGraph()
        val state = graph.library.entries.value
        assertIs<LibraryState.Ready>(state)
        assertEquals(10, state.entries.size)
        // Every status has a member: My List renders a filter pill per status, and a
        // fixture that skipped one would leave that pill untestable without a backend.
        for (status in LibraryStatus.entries) {
            assertTrue(
                state.entries.any { it.status == status },
                "no fixture entry has status $status",
            )
        }
        // Dates drive sorting and the continue-watching pick, so they must not be blank.
        assertTrue(state.entries.all { it.addedAt.isNotBlank() })
        assertTrue(state.entries.any { it.lastWatchedAt?.isNotBlank() == true })
    }

    @Test
    fun `fixture calendar and progress have something to show`() {
        val graph = FixtureAppGraph()
        val state = graph.calendar.calendar.value
        assertIs<CalendarState.Ready>(state)
        assertTrue(state.items.any { it.available }, "no backlog item to demo")
        assertTrue(state.items.any { !it.available }, "no upcoming item to demo")
    }

    @Test
    fun `LibraryStatus wire names round-trip through JSON`() {
        for (status in LibraryStatus.entries) {
            val json = Json.encodeToString(status)
            val decoded = Json.decodeFromString<LibraryStatus>(json)
            assertEquals(status, decoded, "round-trip failed for $status")
            // wireName matches what the backend sends over the wire
            assertEquals(status.wireName, json.removeSurrounding("\""))
        }
    }
}

class FixtureInsightsTest {

    /**
     * The fixture insights have to agree with themselves.
     *
     * Fixtures mode is what `make run` falls back to with no `--backend-mode`, so this data
     * is what the insights page is judged by. Every roll-up here is derived from one
     * generated calendar precisely so the totals cannot drift apart — this asserts that they
     * have not, because a chart that contradicts the headline above it reads as a rendering
     * bug rather than as bad sample data.
     */
    @Test
    fun `fixture activity totals agree with the calendar they came from`() = runTest {
        val stats = FixtureAppGraph().insights.activity()

        assertEquals(
            stats.calendar.values.sum(),
            stats.totalSeconds,
            "headline total must be the sum of the days behind it",
        )
        assertEquals(
            stats.totalSeconds,
            stats.byDayOfWeek.sum(),
            "every active day belongs to exactly one weekday",
        )
        assertEquals(
            stats.totalSeconds / stats.calendar.size,
            stats.avgSecondsPerActiveDay,
            "the per-day average has to be the total over the active days",
        )
        // The hour curve is apportioned by integer division, so it loses up to one second
        // per bucket rather than matching exactly.
        assertTrue(
            stats.totalSeconds - stats.byHourOfDay.sum() in 0..24,
            "hour buckets should account for the total to within rounding",
        )
        assertTrue(
            stats.currentStreak <= stats.longestStreak,
            "a current streak cannot exceed the longest one ever recorded",
        )
    }

    /**
     * The generated year has to have texture.
     *
     * A heatmap with no gaps is a solid block and a heatmap with almost no filled days is
     * empty; either makes the component impossible to judge. The same goes for the clock —
     * a flat hour distribution looks broken rather than evening-weighted.
     */
    @Test
    fun `fixture activity has enough shape to judge the charts by`() = runTest {
        val stats = FixtureAppGraph().insights.activity()

        assertTrue(stats.calendar.size > 200, "too few active days to fill a heatmap")
        assertTrue(stats.calendar.size < 430, "no gaps at all makes the heatmap a solid block")
        assertTrue(stats.longestStreak >= 3, "streak counters need a run worth showing")
        assertTrue(
            stats.byMonthThisYear.sum() > 0 && stats.byMonthLastYear.sum() > 0,
            "the year-over-year chart needs both years populated",
        )
        assertTrue(
            stats.lastYearSeconds > 0,
            "without a previous year the hero's comparison badge never renders",
        )

        val peak = stats.byHourOfDay.indices.maxBy { stats.byHourOfDay[it] }
        assertTrue(peak in 18..23, "the viewing clock should peak in the evening, not at $peak")

        val titles = stats.titlesWatchedThisYear
        assertTrue(titles.isNotEmpty(), "the leaderboard needs titles")
        assertEquals(
            titles.map { it.seconds }.sortedDescending(),
            titles.map { it.seconds },
            "the leaderboard is ranked, so it has to arrive sorted",
        )
        assertTrue(
            titles.all { it.posterPath.isNotBlank() },
            "leaderboard posters come from the fixture catalog and must resolve",
        )
    }

    /** The taste half has to be populated too, or half the page hides itself. */
    @Test
    fun `fixture taste fills every section of the page`() = runTest {
        val taste = FixtureAppGraph().insights.taste()

        assertTrue(taste.topMovieGenres.isNotEmpty(), "movie genre bars would be empty")
        assertTrue(taste.topTvGenres.isNotEmpty(), "tv genre bars would be empty")
        assertTrue(taste.topKeywords.isNotEmpty(), "keyword chips would be empty")
        assertTrue(taste.topPeople.isNotEmpty(), "people chips would be empty")
        assertTrue(taste.topStudios.isNotEmpty(), "studio chips would be empty")
        assertTrue(taste.signalsUsed > 0, "the explainer counts signals and would read zero")
        assertTrue(taste.topContributors.isNotEmpty(), "positive contributor row would be empty")
        assertTrue(
            taste.negativeContributors.isNotEmpty(),
            "negative contributor row would be empty",
        )
        assertTrue(
            (taste.topMovieGenres + taste.topTvGenres).all { it.name.isNotBlank() },
            "a genre id with no name renders as a nameless bar",
        )
        assertTrue(
            taste.topContributors.all { it.weight > 0 } &&
                taste.negativeContributors.all { it.weight < 0 },
            "contributors are split by sign; a mixed list mislabels the rows",
        )
    }
}
