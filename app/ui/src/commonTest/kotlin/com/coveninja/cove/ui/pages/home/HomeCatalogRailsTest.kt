package com.coveninja.cove.ui.pages.home

import com.coveninja.cove.shared.data.AddonRepository
import com.coveninja.cove.shared.data.DiscoveryRepository
import com.coveninja.cove.shared.data.FavoriteTitle
import com.coveninja.cove.shared.fixture.FixtureAppGraph
import com.coveninja.cove.shared.model.AddonCatalogDescriptor
import com.coveninja.cove.shared.model.AddonCatalogPage
import com.coveninja.cove.shared.model.AppSettings
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

    /**
     * The viewer's order decides which catalogs reach Home, not the order their addons were
     * installed in. Applied the other way round the cap would take the addon's own first
     * few, and reordering anything past the last drawn row would do nothing at all.
     *
     * Mutation check: capping before the sort in `HomeLayout.catalogsToDraw` leaves Popular
     * first and fails this.
     */
    @Test
    fun `catalog rails follow the viewer's order`() = runTest {
        val graph = FixtureAppGraph()
        val controller = HomeController(graph.content, graph.discovery, graph.addons, this)

        val reversed = graph.addons.catalogs().reversed().map(::catalogSectionKey)
        controller.loadCatalogs(
            AppSettings(homeSectionOrder = defaultHomeOrder(reversed)).homeLayout(reversed),
        )
        advanceUntilIdle()

        assertEquals(listOf("Top rated", "Popular"), controller.catalogRails.map(HomeRail::title))
    }

    /**
     * Hiding a catalog does not merely leave its row undrawn — it must never be fetched.
     * Resolving one costs a metadata request per title, which is the whole reason the layout
     * is handed to the controller instead of being applied to its output.
     *
     * Mutation check: filtering hidden catalogs in the page rather than in `catalogsToDraw`
     * leaves the call count at 2 and fails this.
     */
    @Test
    fun `a hidden catalog is never fetched`() = runTest {
        val graph = FixtureAppGraph()
        val counting = CountingCatalogPages(graph.addons)
        val controller = HomeController(graph.content, graph.discovery, counting, this)

        val hidden = catalogSectionKey(graph.addons.catalogs().first())
        controller.loadCatalogs(AppSettings(homeSectionsHidden = listOf(hidden)).homeLayout())
        advanceUntilIdle()

        assertEquals(listOf("Top rated"), controller.catalogRails.map(HomeRail::title))
        assertEquals(1, counting.pageCalls, "the hidden catalog's page was fetched anyway")
    }

    /**
     * The cap is a setting now. Zero is a real choice — a viewer who wants Home personal and
     * nothing else — and has to mean no requests rather than no rows.
     *
     * Mutation check: ignoring `catalogRows` draws both rails and fails this.
     */
    @Test
    fun `the catalog row count caps what is drawn`() = runTest {
        val graph = FixtureAppGraph()
        val controller = HomeController(graph.content, graph.discovery, graph.addons, this)

        controller.loadCatalogs(AppSettings(homeCatalogRows = 1).homeLayout())
        advanceUntilIdle()
        assertEquals(listOf("Popular"), controller.catalogRails.map(HomeRail::title))
    }

    /**
     * Reordering catalogs has to take effect without a restart. The personal rails run once
     * a session on purpose; these cannot, or the setting would look broken until relaunch.
     *
     * Mutation check: keeping the old one-shot `catalogStarted` guard leaves the rails in
     * their first order and fails this.
     */
    @Test
    fun `changing the order re-resolves the rails`() = runTest {
        val graph = FixtureAppGraph()
        val controller = HomeController(graph.content, graph.discovery, graph.addons, this)

        controller.loadCatalogs()
        advanceUntilIdle()
        assertEquals(listOf("Popular", "Top rated"), controller.catalogRails.map(HomeRail::title))

        val reversed = graph.addons.catalogs().reversed().map(::catalogSectionKey)
        controller.loadCatalogs(
            AppSettings(homeSectionOrder = defaultHomeOrder(reversed)).homeLayout(reversed),
        )
        advanceUntilIdle()
        assertEquals(listOf("Top rated", "Popular"), controller.catalogRails.map(HomeRail::title))
    }

    /**
     * The other half of that: a layout change that says nothing about catalogs must not throw
     * resolved rails away and refetch every one of them.
     *
     * Mutation check: keying the guard on the whole `HomeLayout` fetches again and fails this.
     */
    @Test
    fun `an unrelated layout change does not refetch`() = runTest {
        val graph = FixtureAppGraph()
        val counting = CountingCatalogPages(graph.addons)
        val controller = HomeController(graph.content, graph.discovery, counting, this)

        controller.loadCatalogs(AppSettings().homeLayout())
        advanceUntilIdle()
        val afterFirst = counting.pageCalls

        // Moving the greeting and widening the upcoming horizon: neither says anything about
        // which catalogs are drawn.
        controller.loadCatalogs(
            AppSettings(
                homeSectionOrder = listOf("greeting", "hero"),
                homeUpcomingDays = 40,
            ).homeLayout(),
        )
        advanceUntilIdle()

        assertEquals(afterFirst, counting.pageCalls)
    }

    /**
     * The page builds its layout out of the rails this controller produced, so once the first
     * fetch lands the *reconciled* order gains the catalog keys it did not have a moment
     * earlier. Key the refetch guard on that and the arrival of the answer invalidates the
     * question: every catalog page is fetched twice on every cold start, for nothing.
     *
     * Mutation check: reading `layout.order` instead of `layout.savedOrder` in
     * `catalogSelection` doubles the count and fails this.
     */
    @Test
    fun `catalog keys appearing in the resolved order do not cause a refetch`() = runTest {
        val graph = FixtureAppGraph()
        val counting = CountingCatalogPages(graph.addons)
        val controller = HomeController(graph.content, graph.discovery, counting, this)

        // What the page does on a cold start: no catalogs known yet, so the layout is
        // reconciled against nothing.
        controller.loadCatalogs(AppSettings().homeLayout())
        advanceUntilIdle()
        val afterFirst = counting.pageCalls
        assertTrue(afterFirst > 0, "nothing was fetched at all")

        // And what it does on the next recomposition, once those rails exist: the same
        // stored settings, now reconciled against the catalogs that just arrived.
        controller.loadCatalogs(
            AppSettings().homeLayout(controller.catalogRails.map(HomeRail::section)),
        )
        advanceUntilIdle()

        assertEquals(afterFirst, counting.pageCalls, "the cold start fetched every catalog twice")
    }

    /**
     * Behind the personal rails sits a metadata request per saved title. Hiding both of them
     * has to mean that work never *starts* — counted at the repository, because the rails come
     * back empty either way and asserting on those alone would pass against an implementation
     * that spends the whole taste profile and throws the answer away.
     *
     * Mutation check: dropping the `wanted.isEmpty()` guard makes the call count 1 and fails
     * this. (Asserting only on `personalRails` does not: `resolvePersonal` skips both rails
     * itself, so the list is empty in both worlds.)
     */
    @Test
    fun `hiding both personal rails skips the taste profile entirely`() = runTest {
        val graph = FixtureAppGraph()
        val counting = CountingFavorites(graph.discovery)
        val controller = HomeController(graph.content, counting, graph.addons, this)

        controller.loadPersonal(
            AppSettings(
                homeSectionsHidden = listOf("personal-because", "personal-for-you"),
            ).homeLayout(),
        )
        advanceUntilIdle()

        assertEquals(0, counting.favoriteCalls, "the taste profile was built for nobody")
        assertTrue(controller.personalRails.isEmpty())
        assertTrue(!controller.personalizing, "the page was left saying it was still working")
    }

    /**
     * Un-hiding one has to bring it back without a restart. The guard is keyed on which rails
     * are wanted rather than on having run at all, precisely so this works.
     *
     * Mutation check: restoring the one-shot `personalStarted` boolean leaves the second call
     * a no-op and fails this.
     */
    @Test
    fun `un-hiding a personal rail loads it without a restart`() = runTest {
        val graph = FixtureAppGraph()
        val controller = HomeController(graph.content, graph.discovery, graph.addons, this)

        controller.loadPersonal(
            AppSettings(homeSectionsHidden = listOf("personal-because")).homeLayout(),
        )
        advanceUntilIdle()
        val whileHidden = controller.personalRails.map(HomeRail::section)
        assertTrue(
            "personal-because" !in whileHidden,
            "a hidden rail was built anyway: $whileHidden",
        )

        controller.loadPersonal(AppSettings().homeLayout())
        advanceUntilIdle()

        assertTrue(
            "personal-because" in controller.personalRails.map(HomeRail::section),
            "un-hiding did nothing until restart: ${controller.personalRails.map(HomeRail::section)}",
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

/**
 * Counts the page fetches rather than the catalog listing, which is the cost the layout is
 * meant to avoid: listing catalogs is one call, resolving each one is a metadata request per
 * title. Same delegation trick as [CountingCatalogRepository] and for the same reason.
 */
private class CountingCatalogPages(
    private val delegate: AddonRepository,
) : AddonRepository by delegate {
    var pageCalls = 0
        private set

    override suspend fun catalogPage(
        addonId: String,
        type: String,
        catalogId: String,
        skip: Int,
        limit: Int,
    ): AddonCatalogPage {
        pageCalls += 1
        return delegate.catalogPage(addonId, type, catalogId, skip, limit)
    }
}

/**
 * Counts the taste-profile call that gates both personal rails. Same delegation trick as the
 * other stubs here, and for the same reason.
 */
private class CountingFavorites(
    private val delegate: DiscoveryRepository,
) : DiscoveryRepository by delegate {
    var favoriteCalls = 0
        private set

    override suspend fun favorites(limit: Int): List<FavoriteTitle> {
        favoriteCalls += 1
        return delegate.favorites(limit)
    }
}
