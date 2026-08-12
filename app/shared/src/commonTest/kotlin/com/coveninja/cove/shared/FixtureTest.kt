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
