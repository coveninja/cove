package com.coveninja.cove.ui.pages.home

import com.coveninja.cove.shared.model.AddonCatalogDescriptor
import com.coveninja.cove.shared.model.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ordering rules, which are the part of Home's personalization that fails invisibly: a
 * page that comes back in the wrong order looks like a page, not like a bug.
 *
 * Every assertion below was checked by breaking the implementation first and confirming the
 * failure, per the convention the other pure-logic suites follow.
 */
class HomeLayoutTest {

    // ── defaultHomeOrder ────────────────────────────────────────────────────

    /**
     * The shipped order, which every unconfigured profile gets and which every anchoring
     * decision is measured against.
     *
     * Mutation check: reordering any two entries of `HomeSectionKind` fails this.
     */
    @Test
    fun `the default order is the page Home has always drawn`() {
        assertEquals(
            listOf(
                "hero",
                "greeting",
                "continue",
                "backlog",
                "upcoming",
                "personal-because",
                "personal-for-you",
                "trending",
            ),
            defaultHomeOrder(),
        )
    }

    /**
     * Catalogs land between the personal rails and Trending, which is where `HomeController`
     * has always put them.
     *
     * Mutation check: pointing `CATALOG_ANCHOR` at any other kind fails this.
     */
    @Test
    fun `catalogs sit after the personal rails and before trending`() {
        val order = defaultHomeOrder(listOf("catalog:a:movie/popular", "catalog:a:movie/top"))

        assertEquals(
            listOf("personal-for-you", "catalog:a:movie/popular", "catalog:a:movie/top", "trending"),
            order.takeLast(4),
        )
    }

    /**
     * Mutation check: dropping the `.distinct()` fails this with the key listed twice, which
     * would in turn give the arranged list two sections claiming one `LazyColumn` key.
     */
    @Test
    fun `a catalog key offered twice appears once`() {
        val order = defaultHomeOrder(listOf("catalog:a:movie/popular", "catalog:a:movie/popular"))

        assertEquals(1, order.count { it == "catalog:a:movie/popular" })
    }

    // ── orderHomeSections ───────────────────────────────────────────────────

    /**
     * An untouched profile gets the defaults, not an empty page.
     *
     * Mutation check: returning `saved` instead of `available` on the empty branch fails this.
     */
    @Test
    fun `no saved order means the default one`() {
        val available = defaultHomeOrder()

        assertEquals(available, orderHomeSections(available, saved = emptyList()))
    }

    /**
     * Mutation check: sorting by the default position rather than the saved one fails this.
     */
    @Test
    fun `a saved order is honoured`() {
        val available = listOf("hero", "greeting", "continue", "trending")
        val saved = listOf("trending", "hero", "continue", "greeting")

        assertEquals(saved, orderHomeSections(available, saved))
    }

    /**
     * An addon was removed since the order was saved. Keeping its key would leave a slot in
     * the order that nothing can ever fill.
     *
     * Mutation check: dropping the `filter(exists::contains)` fails this.
     */
    @Test
    fun `a saved section that no longer exists is dropped`() {
        val available = listOf("hero", "trending")
        val saved = listOf("trending", "catalog:gone:movie/popular", "hero")

        assertEquals(listOf("trending", "hero"), orderHomeSections(available, saved))
    }

    /**
     * The rule the whole function exists for. A catalog from an addon installed since the
     * order was saved belongs beside its siblings, not at the bottom of the page.
     *
     * Mutation check: replacing the anchor search with `resolved.add(key)` — the obvious
     * implementation — puts `b` and `c` last and fails this.
     */
    @Test
    fun `an unplaced section is anchored after its default predecessor`() {
        val available = listOf("a", "b", "c", "d")
        val saved = listOf("c", "a")

        // `b` follows the `a` it follows by default; `d` follows `c`, which the viewer moved
        // to the front — so it travels there with it rather than staying at the end. Appending
        // both instead would give [c, a, b, d], which is why this case is written with a saved
        // order that tells the two apart.
        assertEquals(listOf("c", "d", "a", "b"), orderHomeSections(available, saved))
    }

    /**
     * Several new sections in a row keep their own relative order, because each anchors
     * against the one before it once that has landed.
     *
     * Mutation check: seeding `placed` from `resolved` only, and not adding each key as it
     * lands, reverses the run and fails this.
     */
    @Test
    fun `a run of new sections keeps its own order`() {
        val available = listOf("a", "b", "c", "d")

        assertEquals(listOf("a", "b", "c", "d"), orderHomeSections(available, saved = listOf("a")))
    }

    /**
     * Nothing precedes it that the viewer has placed, so it goes to the front rather than
     * being appended to the end.
     *
     * Mutation check: defaulting the insertion point to `resolved.size` instead of 0 fails
     * this — `a` lands last.
     */
    @Test
    fun `an unplaced section with no placed predecessor goes to the front`() {
        val available = listOf("a", "b", "c")
        val saved = listOf("c", "b")

        assertEquals(listOf("a", "c", "b"), orderHomeSections(available, saved))
    }

    /**
     * A section added by a later release appears where that release designed it to, inside an
     * order saved by a build that had never heard of it.
     *
     * Mutation check: appending unplaced keys puts `upcoming` after `trending` and fails this.
     */
    @Test
    fun `a section added by a later release lands in its designed place`() {
        val available = defaultHomeOrder()
        // An order saved before "Coming this week" existed, with the rest left alone.
        val saved = available.filterNot { it == "upcoming" }

        assertEquals(available, orderHomeSections(available, saved))
    }

    /**
     * Mutation check: dropping the `.distinct()` leaves the duplicate in and fails this.
     */
    @Test
    fun `a key stored twice is placed once`() {
        val available = listOf("a", "b")
        val saved = listOf("b", "b", "a")

        assertEquals(listOf("b", "a"), orderHomeSections(available, saved))
    }

    // ── moveSection ─────────────────────────────────────────────────────────

    /** Mutation check: swapping the `add`/`removeAt` order fails these. */
    @Test
    fun `a section moves up and down`() {
        val order = listOf("a", "b", "c", "d")

        assertEquals(listOf("a", "c", "b", "d"), moveSection(order, from = 2, to = 1))
        assertEquals(listOf("b", "a", "c", "d"), moveSection(order, from = 0, to = 1))
        assertEquals(listOf("b", "c", "d", "a"), moveSection(order, from = 0, to = 3))
    }

    /**
     * The arrows run off the ends and a drag can be flung past them; both clamp rather than
     * throwing, because an out-of-range move means "as far as it goes".
     *
     * Mutation check: removing the `coerceIn` throws and fails this.
     */
    @Test
    fun `a move past either end clamps`() {
        val order = listOf("a", "b", "c")

        assertEquals(listOf("b", "c", "a"), moveSection(order, from = 0, to = 9))
        assertEquals(listOf("c", "a", "b"), moveSection(order, from = 2, to = -4))
        // A row that is not there at all leaves the order alone rather than throwing.
        assertEquals(order, moveSection(order, from = 7, to = 0))
    }

    /** Mutation check: dropping the `from == target` guard still passes, but the list is a copy. */
    @Test
    fun `moving a section onto itself changes nothing`() {
        val order = listOf("a", "b", "c")

        assertEquals(order, moveSection(order, from = 1, to = 1))
    }

    // ── arrangeHomeSections ─────────────────────────────────────────────────

    /**
     * Mutation check: sorting by the item list's own index rather than the order's fails this.
     */
    @Test
    fun `sections are drawn in the resolved order`() {
        val arranged = arrangeHomeSections(
            items = listOf("hero", "trending", "continue"),
            key = { it },
            order = listOf("trending", "continue", "hero"),
            hidden = emptySet(),
        )

        assertEquals(listOf("trending", "continue", "hero"), arranged)
    }

    /** Mutation check: inverting the `filterNot` leaves only the hidden one and fails this. */
    @Test
    fun `a hidden section is not drawn`() {
        val arranged = arrangeHomeSections(
            items = listOf("hero", "greeting", "trending"),
            key = { it },
            order = defaultHomeOrder(),
            hidden = setOf("greeting"),
        )

        assertEquals(listOf("hero", "trending"), arranged)
    }

    /**
     * The television builds fewer sections than the phone, over the same order. Its missing
     * keys must simply go unmatched rather than needing a shell-specific branch.
     *
     * Mutation check: requiring every ordered key to be present fails this.
     */
    @Test
    fun `a shell that draws fewer sections needs no special case`() {
        val arranged = arrangeHomeSections(
            // No greeting and no upcoming strip, exactly as the television has it.
            items = listOf("trending", "hero", "continue"),
            key = { it },
            order = defaultHomeOrder(),
            hidden = emptySet(),
        )

        assertEquals(listOf("hero", "continue", "trending"), arranged)
    }

    // ── HomeLayout and the settings it comes from ───────────────────────────

    /**
     * Every field, because a settings object is a whole-object replace and a field this
     * function forgets to read is a control that silently does nothing.
     *
     * Mutation check: reading any one of the five from the wrong field fails this.
     */
    @Test
    fun `the layout reads every home setting`() {
        val layout = AppSettings(
            homeSectionOrder = listOf("trending", "hero"),
            homeSectionsHidden = listOf("greeting"),
            homeCatalogRows = 7,
            homeContinueRows = 20,
            homeUpcomingDays = 30,
        ).homeLayout()

        assertEquals(listOf("trending", "hero"), layout.savedOrder)
        assertEquals(setOf("greeting"), layout.hidden)
        assertEquals(7, layout.catalogRows)
        assertEquals(20, layout.continueRows)
        assertEquals(30, layout.upcomingDays)
        // Reconciled, not raw: the two the viewer placed lead, the rest follow from defaults.
        assertEquals(listOf("trending", "hero"), layout.order.take(2))
        assertEquals(defaultHomeOrder().size, layout.order.size)
    }

    /**
     * The counts arrive from a JSON blob an older build or a compatibility client could have
     * written. A zero-length resume rail empties a section with nothing to say why.
     *
     * Mutation check: removing either `coerceIn` fails this.
     */
    @Test
    fun `out of range counts are pulled back into range`() {
        val low = AppSettings(
            homeCatalogRows = -5,
            homeContinueRows = 0,
            homeUpcomingDays = 0,
        ).homeLayout()

        assertEquals(0, low.catalogRows)
        assertEquals(MIN_CONTINUE_ROWS, low.continueRows)
        assertEquals(MIN_UPCOMING_DAYS, low.upcomingDays)

        val high = AppSettings(
            homeCatalogRows = 999,
            homeContinueRows = 999,
            homeUpcomingDays = 999,
        ).homeLayout()

        assertEquals(MAX_CATALOG_ROWS, high.catalogRows)
        assertEquals(MAX_CONTINUE_ROWS, high.continueRows)
        assertEquals(MAX_UPCOMING_DAYS, high.upcomingDays)
    }

    /** An untouched profile gets the page as shipped. */
    @Test
    fun `the default layout hides nothing and reorders nothing`() {
        assertEquals(defaultHomeOrder(), HomeLayout.Default.order)
        assertTrue(HomeLayout.Default.hidden.isEmpty())
        // Derived from AppSettings rather than restated, so the two cannot drift.
        assertEquals(AppSettings().homeCatalogRows, HomeLayout.Default.catalogRows)
        assertEquals(AppSettings().homeContinueRows, HomeLayout.Default.continueRows)
        assertEquals(AppSettings().homeUpcomingDays, HomeLayout.Default.upcomingDays)
    }

    // ── catalogsToDraw ──────────────────────────────────────────────────────

    /**
     * The cap decides *which* catalogs make Home, so it has to land after the viewer's
     * ordering. Applied first, it would take the addon's own three and leave every ordering
     * choice past the third doing nothing at all.
     *
     * Mutation check: moving the `.take` above the `.sortedBy` returns popular and top, and
     * fails this.
     */
    @Test
    fun `the catalog cap applies after the ordering, not before`() {
        val catalogs = listOf(catalog("popular"), catalog("top"), catalog("new"))
        val layout = AppSettings(
            homeSectionOrder = listOf(key("new"), key("top"), key("popular")),
            homeCatalogRows = 2,
        ).homeLayout()

        assertEquals(
            listOf("new", "top"),
            layout.catalogsToDraw(catalogs).map(AddonCatalogDescriptor::catalogId),
        )
    }

    /**
     * A hidden catalog is not fetched at all, which is most of the point: resolving one costs
     * a metadata request per title.
     *
     * Mutation check: dropping the `filterNot` returns popular and fails this.
     */
    @Test
    fun `a hidden catalog is never drawn`() {
        val catalogs = listOf(catalog("popular"), catalog("top"))
        val layout = AppSettings(homeSectionsHidden = listOf(key("popular"))).homeLayout()

        assertEquals(
            listOf("top"),
            layout.catalogsToDraw(catalogs).map(AddonCatalogDescriptor::catalogId),
        )
    }

    /**
     * A catalog installed since the order was saved is ranked where its siblings are. This is
     * why `HomeLayout` keeps the raw saved order: its `order` was resolved against whatever
     * the caller knew about, which need not have included this one.
     *
     * Mutation check: ranking against `order` instead of re-resolving from `savedOrder`
     * sorts `new` last and fails this.
     */
    @Test
    fun `a newly installed catalog is ranked beside its siblings`() {
        val layout = AppSettings(
            // Saved when only popular and top existed, with their order swapped.
            homeSectionOrder = defaultHomeOrder(listOf(key("top"), key("popular"))),
            homeCatalogRows = 3,
        ).homeLayout(listOf(key("top"), key("popular")))

        val drawn = layout
            .catalogsToDraw(listOf(catalog("popular"), catalog("top"), catalog("new")))
            .map(AddonCatalogDescriptor::catalogId)

        // The saved pair keeps the order the viewer chose, and the newcomer lands directly
        // behind `top` — the sibling it follows in the addon's own order — rather than at the
        // end. Worth being deliberate about, because "put new catalogs last" is the obvious
        // alternative and it is the worse one: `homeCatalogRows` caps this list, so a
        // newcomer appended to the end of a full page would never be drawn at all, and
        // installing an addon would look like it had done nothing.
        assertEquals(listOf("top", "new", "popular"), drawn)
    }

    /** Mutation check: dropping the `coerceAtLeast(0)` throws on a negative and fails this. */
    @Test
    fun `no catalog rows means no catalogs`() {
        val layout = AppSettings(homeCatalogRows = 0).homeLayout()

        assertTrue(layout.catalogsToDraw(listOf(catalog("popular"))).isEmpty())
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private fun catalog(id: String) = AddonCatalogDescriptor(
        addonId = "addon.one",
        addonName = "Addon One",
        addonUrl = "https://example.test/manifest.json",
        type = "movie",
        catalogId = id,
        name = id.replaceFirstChar(Char::uppercase),
    )

    private fun key(id: String) = catalogSectionKey(catalog(id))
}
