package com.coveninja.cove.ui.pages.home

import com.coveninja.cove.shared.model.AddonCatalogDescriptor
import com.coveninja.cove.shared.model.AppSettings

/**
 * Which sections Home draws, in which order, and which ones it leaves out.
 *
 * Split from [HomeModel] rather than added to it because the two answer different questions:
 * that file decides what is *worth* showing, this one decides what the viewer has *asked* to
 * see. Same discipline either way — no Compose and no repositories, because an ordering rule
 * that is subtly wrong is invisible until someone's page comes back in the wrong order, and a
 * pure function is the only version of it a test can pin down.
 */

/**
 * A section of Home that can be moved or hidden.
 *
 * [key] is the whole point of this enum: the rails themselves are identified by ids that
 * *change* — `HomeController` builds `because-${tmdbId}` and `for-you-${genreId}`, both of
 * which move as the viewer's taste does — so an order stored against a rail id would quietly
 * forget itself the first time a favourite changed. These keys are stable across restarts,
 * across taste, and across releases.
 */
enum class HomeSectionKind(
    val key: String,
    val label: String,
    val blurb: String,
    val icon: String,
) {
    Hero(
        key = "hero",
        label = "Spotlight",
        blurb = "The single title across the top of the page.",
        icon = "lucide:sparkles",
    ),
    Greeting(
        key = "greeting",
        label = "Greeting and stats",
        blurb = "The time of day, and what your list adds up to.",
        icon = "iconamoon:profile-circle",
    ),
    ContinueWatching(
        key = "continue",
        label = "Carry on watching",
        blurb = "Where you left off.",
        icon = "iconamoon:history",
    ),
    Backlog(
        key = "backlog",
        label = "Waiting for you",
        blurb = "Episodes that aired while you were away.",
        icon = "lucide:tv",
    ),
    Upcoming(
        key = "upcoming",
        label = "Coming this week",
        blurb = "Dated releases from your list.",
        icon = "lucide:calendar-clock",
    ),
    // Hyphenated, where the catalog keys are colon-separated, and not a style choice: the
    // icon verifier scans source for quoted colon-separated literals and fails the build on
    // any that is missing from the generated table. A personal key spelled with a colon
    // reads to it as an icon nobody generated — including one written in a comment, which is
    // why this paragraph describes the shape rather than showing it. Catalog keys are built
    // by interpolation, so they never appear as a literal and never trip it.
    BecauseYouWatched(
        key = "personal-because",
        label = "Because you watched…",
        blurb = "Picked from what you finished and rated.",
        icon = "lucide:heart",
    ),
    PickedForYou(
        key = "personal-for-you",
        label = "Picked for you",
        blurb = "From the titles you have saved.",
        icon = "lucide:sparkles",
    ),
    Trending(
        key = "trending",
        label = "Trending now",
        blurb = "What everyone is watching.",
        icon = "lucide:flame",
    ),
    ;

    companion object {
        private val byKey = entries.associateBy(HomeSectionKind::key)

        fun forKey(key: String): HomeSectionKind? = byKey[key]
    }
}

/** Where the addon catalogs sit by default: after the personal rails, ahead of Trending. */
private val CATALOG_ANCHOR = HomeSectionKind.Trending

/**
 * The ordering key for one addon catalog.
 *
 * [AddonCatalogDescriptor.key] is only unique within its addon — two addons can both offer a
 * `movie/popular` — so the addon id travels with it. Deliberately *not* the rail id, which
 * spells the same three fields with `-` separators and is free to change.
 */
fun catalogSectionKey(descriptor: AddonCatalogDescriptor): String =
    "catalog:${descriptor.addonId}:${descriptor.key}"

/**
 * Every section this profile could see, in the order Home shipped with.
 *
 * This is the list an unconfigured profile gets, and it is also the frame of reference
 * [orderHomeSections] anchors against, so it has to name *everything* — including sections a
 * given shell never draws. The television has no greeting and no upcoming strip; those keys
 * still appear here, and simply go unused there.
 */
fun defaultHomeOrder(catalogKeys: List<String> = emptyList()): List<String> {
    val catalogs = catalogKeys.distinct()
    return buildList {
        for (kind in HomeSectionKind.entries) {
            if (kind == CATALOG_ANCHOR) addAll(catalogs)
            add(kind.key)
        }
    }
}

/**
 * The viewer's order, reconciled against the sections that currently exist.
 *
 * Three rules, and the third is the one that earns this function:
 *
 *  - A key the viewer has placed keeps the position they put it in.
 *  - A key they placed that no longer exists is dropped — an addon was removed, and holding
 *    a slot open for it would leave a gap that nothing can ever fill.
 *  - A key that exists but was never placed is **anchored to its default predecessor**:
 *    inserted directly after the nearest section that comes before it in [available] and has
 *    already landed. Appending it instead would be the obvious thing and the wrong one — it
 *    would drop every catalog from a newly installed addon at the bottom of a customised
 *    page, and bury any section a later release adds underneath an order saved before it
 *    existed. Anchoring puts each one where its neighbours are.
 */
fun orderHomeSections(available: List<String>, saved: List<String>): List<String> {
    if (saved.isEmpty()) return available

    val exists = available.toSet()
    val resolved = saved.filter(exists::contains).distinct().toMutableList()
    val placed = resolved.toMutableSet()

    // Default order, so each unplaced key can see the ones before it — including earlier
    // unplaced keys, which is what keeps a run of new catalogs in their own order.
    for ((index, key) in available.withIndex()) {
        if (key in placed) continue
        val anchor = (index - 1 downTo 0).firstOrNull { available[it] in placed }
        val at = anchor?.let { resolved.indexOf(available[it]) + 1 } ?: 0
        resolved.add(at, key)
        placed += key
    }
    return resolved
}

/** Moves one section, clamping rather than throwing: the buttons run off the ends. */
fun moveSection(order: List<String>, from: Int, to: Int): List<String> {
    if (from !in order.indices) return order
    val target = to.coerceIn(0, order.lastIndex)
    if (from == target) return order
    return order.toMutableList().apply { add(target, removeAt(from)) }
}

/**
 * Puts already-built sections into the viewer's order and drops the ones they hid.
 *
 * Deliberately the dumb half of the pair: every decision about *where* an unfamiliar key
 * belongs was already made by [orderHomeSections], so [order] names every section that
 * exists and a lookup can never come back empty. Generic in the item type because the two
 * shells build different section types over the same ordering — the television's carries its
 * focus machinery — and the part that must never drift between them is this, not that.
 */
fun <T> arrangeHomeSections(
    items: List<T>,
    key: (T) -> String,
    order: List<String>,
    hidden: Set<String>,
): List<T> {
    val rank = order.withIndex().associate { (index, value) -> value to index }
    return items
        .filterNot { key(it) in hidden }
        // Unranked sorts last rather than throwing. Unreachable when `order` came from
        // `orderHomeSections` over the same sections, which is the only supported way in.
        .sortedBy { rank[key(it)] ?: Int.MAX_VALUE }
}

/**
 * Home's share of [AppSettings], resolved once so neither shell re-derives it.
 *
 * [order] is the reconciled list, not the raw stored one — callers get something they can
 * index into directly.
 */
data class HomeLayout(
    /**
     * Exactly what was stored, before reconciliation. Kept because [order] was resolved
     * against only the sections its caller knew about, and [catalogsToDraw] is handed the
     * profile's *full* catalog list — it has to re-resolve, and it can only do that from the
     * original.
     */
    val savedOrder: List<String>,
    val order: List<String>,
    val hidden: Set<String>,
    val catalogRows: Int,
    val continueRows: Int,
    val upcomingDays: Int,
) {
    fun isHidden(key: String): Boolean = key in hidden

    fun isHidden(kind: HomeSectionKind): Boolean = isHidden(kind.key)

    /** The catalogs to draw: the viewer's order, hidden ones dropped, capped at [catalogRows]. */
    fun catalogsToDraw(catalogs: List<AddonCatalogDescriptor>): List<AddonCatalogDescriptor> {
        val keys = catalogs.map(::catalogSectionKey)
        val rank = orderHomeSections(defaultHomeOrder(keys), savedOrder)
            .withIndex().associate { (index, value) -> value to index }
        return catalogs
            .filterNot { isHidden(catalogSectionKey(it)) }
            .sortedBy { rank[catalogSectionKey(it)] ?: Int.MAX_VALUE }
            // After the ordering, never before it: the cap decides *which* catalogs make
            // Home, so applying it to the addon's own order would leave the viewer's
            // ordering of anything past the third catalog doing nothing at all.
            .take(catalogRows.coerceAtLeast(0))
    }

    companion object {
        /**
         * Derived from [AppSettings]'s own defaults rather than restated here. Those live in
         * `:shared`, which cannot see this module, so writing the numbers again is the one
         * way the two could ever disagree.
         */
        val Default: HomeLayout = AppSettings().homeLayout()
    }
}

/**
 * Reads Home's settings, reconciled against the catalogs this profile actually has.
 *
 * The counts are coerced rather than trusted: they arrive from a JSON blob that a
 * compatibility client or an older build could have written, and a zero-length carry-on rail
 * or a negative horizon would empty a section with nothing to say why.
 */
fun AppSettings.homeLayout(catalogKeys: List<String> = emptyList()): HomeLayout = HomeLayout(
    savedOrder = homeSectionOrder,
    order = orderHomeSections(defaultHomeOrder(catalogKeys), homeSectionOrder),
    hidden = homeSectionsHidden.toSet(),
    catalogRows = homeCatalogRows.coerceIn(0, MAX_CATALOG_ROWS),
    continueRows = homeContinueRows.coerceIn(MIN_CONTINUE_ROWS, MAX_CONTINUE_ROWS),
    upcomingDays = homeUpcomingDays.coerceIn(MIN_UPCOMING_DAYS, MAX_UPCOMING_DAYS),
)

/**
 * The ranges the settings sliders offer, and the bounds a stored value is coerced into.
 *
 * The *defaults* are not here — they are `AppSettings`'s own field defaults, which is the
 * only copy of them. These are the limits, which the settings screen owns.
 */
const val MAX_CATALOG_ROWS = 10

const val MIN_CONTINUE_ROWS = 4
const val MAX_CONTINUE_ROWS = 30

/** The calendar itself only spans 90 days, so a wider horizon would show nothing more. */
const val MIN_UPCOMING_DAYS = 1
const val MAX_UPCOMING_DAYS = 90
