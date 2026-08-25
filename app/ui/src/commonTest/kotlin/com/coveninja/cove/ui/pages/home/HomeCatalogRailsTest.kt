package com.coveninja.cove.ui.pages.home

import com.coveninja.cove.shared.data.AddonRepository
import com.coveninja.cove.shared.fixture.FixtureAppGraph
import com.coveninja.cove.shared.model.AddonCatalogDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * The catalog rail stage, driven against `FixtureAppGraph()`.
 *
 * [HomeController] takes its scope rather than creating one, so the stage can be run
 * outside a composition — which is the only part of Home's rail assembly that talks to a
 * repository, and therefore the only part the pure-model tests in `HomeModelTest` cannot
 * reach.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeCatalogRailsTest {

    /**
     * Nothing draws catalog rows unless the controller asks the addon repository for them
     * and turns the answer into rails — the wiring between two layers that each have their
     * own tests and no shared one.
     */
    @Test
    fun `catalog rails are built from the addon repository`() = runTest {
        val graph = FixtureAppGraph()
        val controller = HomeController(graph.content, graph.discovery, graph.addons, this)

        assertTrue(controller.catalogRails.isEmpty(), "nothing is loaded before it is asked for")

        controller.loadCatalogs()
        advanceUntilIdle()

        val rails = controller.catalogRails
        assertEquals(
            listOf("addon-fixture.provider-movie/popular", "addon-fixture.provider-series/top"),
            rails.map { it.id },
            "one rail per enabled catalog, keyed so two addons cannot collide",
        )
        // Home is not a typed page: a film catalog and a series catalog both belong.
        assertEquals(listOf("movie", "series"), rails.map { it.catalog?.type })
        assertEquals(listOf("Popular", "Top rated"), rails.map { it.title })
        assertEquals(listOf("From Fixture Provider", "From Fixture Provider"), rails.map { it.subtitle })
        assertTrue(rails.all { it.media.isNotEmpty() }, "an empty rail is dropped, not drawn")

        // Ordered, so buildHomeRails keeps them even where Trending already showed the
        // same titles — the addon's choice of order is the content of the row.
        assertTrue(rails.all { it.ordered })
        // Carried so "See all" can page the rest of that same catalog.
        assertEquals(listOf("popular", "top"), rails.map { it.catalog?.catalogId })
    }

    /**
     * Disabling a catalog has to remove its row, which is the whole point of the switch
     * the settings screen now draws.
     */
    @Test
    fun `a disabled catalog contributes no rail`() = runTest {
        val graph = FixtureAppGraph()
        graph.addons.setCatalogEnabled("fixture.provider", "movie/popular", enabled = false)

        val controller = HomeController(graph.content, graph.discovery, graph.addons, this)
        controller.loadCatalogs()
        advanceUntilIdle()

        assertEquals(listOf("Top rated"), controller.catalogRails.map { it.title })
    }

    /**
     * The stage runs once per session. It is kicked off from a `LaunchedEffect` that
     * re-runs whenever content readiness changes, so a second call has to be a no-op or
     * every one of those would re-resolve every catalog against the metadata provider.
     */
    @Test
    fun `the catalog stage runs once`() = runTest {
        val graph = FixtureAppGraph()
        val counting = CountingCatalogRepository(graph.addons)
        val controller = HomeController(graph.content, graph.discovery, counting, this)

        controller.loadCatalogs()
        advanceUntilIdle()
        controller.loadCatalogs()
        advanceUntilIdle()

        assertEquals(1, counting.calls)
    }

    /**
     * What HomePage and TvHomePage actually draw: the controller's rails run through
     * `buildHomeRails` alongside Trending. The earlier tests stop at `catalogRails` and
     * would not notice a catalog row that is built correctly and then dropped here.
     */
    @Test
    fun `catalog rails survive the rail assembly both shells run`() = runTest {
        val graph = FixtureAppGraph()
        val controller = HomeController(graph.content, graph.discovery, graph.addons, this)
        controller.loadCatalogs()
        advanceUntilIdle()

        // Trending carries the same fixture titles the catalogs do, which is exactly the
        // overlap that would sink a rail that was not marked as ordered.
        val trending = controller.catalogRails.first().media
        val assembled = buildHomeRails(
            controller.personalRails + controller.catalogRails + listOf(
                HomeRail(
                    id = "trending",
                    title = "Trending now",
                    subtitle = "",
                    icon = "lucide:flame",
                    media = trending,
                    ordered = true,
                ),
            ),
        )

        assertTrue(
            assembled.any { it.id.startsWith("addon-") },
            "no catalog row survived: ${assembled.map(HomeRail::id)}",
        )
        assertEquals(
            listOf("Popular", "Top rated"),
            assembled.filter { it.id.startsWith("addon-") }.map(HomeRail::title),
        )
    }
}

/**
 * Delegates everything and counts the one call under test. A hand-written stub would have
 * to reimplement the whole interface, and would keep compiling — silently answering
 * nothing — as members are added to it.
 */
private class CountingCatalogRepository(
    private val delegate: AddonRepository,
) : AddonRepository by delegate {
    var calls = 0
        private set

    override suspend fun catalogs(): List<AddonCatalogDescriptor> {
        calls += 1
        return delegate.catalogs()
    }
}
