package com.coveninja.cove.ui.pages.explore

import com.coveninja.cove.shared.model.CatalogSort
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.MediaType

/** Which of the two ways Explore is arranged. */
enum class ExploreLayout(val label: String, val icon: String) {
    Shelves("Shelves", "lucide:gallery-horizontal-end"),
    Grid("Grid", "lucide:layout-grid"),
}

/**
 * How the grid is ordered.
 *
 * A presentation-layer name for each [CatalogSort]: the wire vocabulary says what the
 * catalog does, this says what the viewer thinks they asked for. "Trending" is popularity
 * ordering — the honest label for it, since nothing here measures a trend.
 */
enum class ExploreSort(val label: String, val catalog: CatalogSort) {
    Trending("Trending", CatalogSort.Popularity),
    TopRated("Top rated", CatalogSort.Rating),
    Newest("Newest first", CatalogSort.Newest),
    Oldest("Oldest first", CatalogSort.Oldest),
    Title("A–Z", CatalogSort.Title),
}

/**
 * What the viewer has narrowed Explore to.
 *
 * [type] is not nullable, unlike My List's equivalent: films and series are separate
 * catalog endpoints with separate genre vocabularies and separate paging, so "both at
 * once" is not one query that can be paged. The switch is a real two-way choice here.
 */
data class ExploreFilters(
    val type: MediaType = MediaType.Movie,
    val genreId: Int? = null,
    val sort: ExploreSort = ExploreSort.Trending,
    val query: String = "",
) {
    /** True when anything beyond the format switch is narrowing the page. */
    val narrowed: Boolean get() = genreId != null || query.isNotBlank()

    /** Everything that changes which page the catalog would return, and nothing else. */
    val catalogKey: Triple<MediaType, Int?, ExploreSort> get() = Triple(type, genreId, sort)
}

/**
 * Where a shelf's titles came from. Also the order the shelves appear in.
 *
 * [definedByOrder] separates the two kinds of rail, which matters for whether overlapping
 * with an earlier rail makes one redundant:
 *
 *  - **Ordered rails** ("Top rated", "New releases") answer a question *about* a set. They
 *    are worth drawing even when they list much the same titles as the rail above, because
 *    the ordering is the content — "the best of these" is not the same statement as "the
 *    most popular of these", however similar the two lists look.
 *  - **Membership rails** ("Horror", "Because you watched…") are the set. One that repeats
 *    what the page already showed says nothing at all, and should not be drawn.
 */
enum class ShelfKind(val definedByOrder: Boolean) {
    BecauseYouWatched(definedByOrder = false),
    YourGenre(definedByOrder = false),
    Trending(definedByOrder = true),
    TopRated(definedByOrder = true),
    NewReleases(definedByOrder = true),
    Genre(definedByOrder = false),
}

/** One horizontal rail. */
data class ExploreShelf(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: String,
    val kind: ShelfKind,
    val media: List<Media>,
    /** Set when "See all" can hand the grid a filter that means the same thing. */
    val genreId: Int? = null,
    val sort: ExploreSort = ExploreSort.Trending,
)

/** Below this a rail has too few posters to be worth a heading and a row of its own. */
const val MINIMUM_SHELF_SIZE = 5

/**
 * Picks which rails actually make the page, in the order given.
 *
 * Two reasons to drop one:
 *
 *  - **Too small.** Fewer than [minimumSize] titles never justifies a heading. Applies to
 *    every rail.
 *  - **Nothing new to say.** A rail whose titles the page has almost entirely shown
 *    already is noise. Applies only to [ShelfKind.definedByOrder]`== false` rails — see
 *    [ShelfKind] for why an ordered rail earns its place regardless of overlap.
 *
 * Note what is *not* deduplicated: the titles themselves. A film may legitimately appear
 * in several rails, and stripping it from the later ones would hollow them out and reorder
 * a "top rated" list into something that is no longer top-rated. Rails are deduplicated;
 * titles are not.
 */
fun buildShelves(
    candidates: List<ExploreShelf>,
    minimumSize: Int = MINIMUM_SHELF_SIZE,
): List<ExploreShelf> {
    val seen = mutableSetOf<String>()
    val kept = mutableListOf<ExploreShelf>()

    for (shelf in candidates) {
        // Distinct first: a rail padded out with one title repeated is short, not full.
        val distinct = shelf.media.distinctBy(Media::id)
        if (distinct.size < minimumSize) continue
        if (!shelf.kind.definedByOrder && distinct.count { it.id !in seen } < minimumSize) {
            continue
        }
        kept += shelf.copy(media = distinct)
        seen += distinct.map(Media::id)
    }
    return kept
}

/**
 * The titles the spotlight can rotate through.
 *
 * Backdrop-only: the hero is a wide image with text over it, and a title with just a
 * poster renders as a stretched, cropped smear behind unreadable copy. Better to show
 * one fewer title than one broken one.
 */
fun spotlightPicks(media: List<Media>, count: Int): List<Media> =
    media.asSequence()
        .filter { !it.backdropUrl.isNullOrBlank() }
        .distinctBy(Media::id)
        .take(count.coerceAtLeast(0))
        .toList()

/**
 * Narrows already-loaded pages by the viewer's typed query.
 *
 * Client-side on purpose, and only for the query: format, genre and order are catalog
 * queries that re-page from the server, but typing is per-keystroke and must not fire a
 * request per character. Order is preserved — the catalog already ranked these, and
 * re-sorting by match quality would fight whichever sort the viewer chose.
 */
fun applyQuery(media: List<Media>, query: String): List<Media> {
    val needle = query.trim()
    if (needle.isEmpty()) return media
    return media.filter { item ->
        item.title?.contains(needle, ignoreCase = true) == true ||
            item.name?.contains(needle, ignoreCase = true) == true
    }
}

/** "1 title" / "24 titles", and "24+ titles" while more pages are still reachable. */
fun titleCountLabel(count: Int, moreAvailable: Boolean): String {
    val noun = if (count == 1) "title" else "titles"
    return if (moreAvailable) "$count+ $noun" else "$count $noun"
}
