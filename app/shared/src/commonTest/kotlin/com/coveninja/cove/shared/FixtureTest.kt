package com.coveninja.cove.shared

import com.coveninja.cove.shared.data.CalendarState
import com.coveninja.cove.shared.data.HomeState
import com.coveninja.cove.shared.data.LibraryState
import com.coveninja.cove.shared.fixture.FixtureAppGraph
import com.coveninja.cove.shared.model.LibraryStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FixtureTest {

    @Test
    fun `fixture home state is Ready and contains expected titles`() {
        val graph = FixtureAppGraph()
        val state = graph.content.home.value
        assertIs<HomeState.Ready>(state)
        // Six movies + four TV shows
        assertEquals(10, state.items.size)
        assertTrue(state.items.any { it.displayTitle == "Fight Club" })
        assertTrue(state.items.any { it.displayTitle == "Breaking Bad" })
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
