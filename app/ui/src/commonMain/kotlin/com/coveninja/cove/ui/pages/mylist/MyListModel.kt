package com.coveninja.cove.ui.pages.mylist

import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.WatchProgress
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.MediaType
import com.coveninja.cove.ui.state.neverPlayed
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Which of the two things My List is showing. */
enum class MyListView(val label: String, val icon: String) {
    Library("Library", "lucide:library"),
    Calendar("Calendar", "lucide:calendar-days"),
}

enum class MyListLayout(val label: String, val icon: String) {
    Grid("Grid", "lucide:layout-grid"),
    Rows("List", "lucide:list"),
}

enum class MyListSort(val label: String) {
    RecentlyAdded("Recently added"),
    RecentlyWatched("Recently watched"),
    Title("Title"),
    YourRating("Your rating"),
    ReleaseYear("Release year"),
}

data class MyListFilters(
    val category: MyListCategory? = null,
    val type: MediaType? = null,
    val query: String = "",
    val sort: MyListSort = MyListSort.RecentlyAdded,
    val descending: Boolean = true,
) {
    /** True when anything is narrowing the list, which is what the "clear" affordance needs. */
    val narrowed: Boolean get() = category != null || type != null || query.isNotBlank()
}

/** One saved title, joined to everything My List draws about it. */
data class MyListRow(
    val media: Media,
    val entry: LibraryEntry,
    val category: MyListCategory,
    val watchFraction: Float? = null,
    val hasNewEpisodes: Boolean = false,
    /** The resume point, kept whole so [hasSomethingToPlay] can tell "finished" from "never
     *  started" — [watchFraction] collapses both to null. */
    val progress: WatchProgress? = null,
) {
    val id: String get() = media.id

    /**
     * Whether there is genuinely something to play right now.
     *
     * Three ways for that to be true, and the library's status is none of them: `Watching`
     * is an intention set once that nothing ever clears, so it survives the last episode and
     * cannot stand in for "unfinished".
     */
    val hasSomethingToPlay: Boolean
        get() = watchFraction != null || hasNewEpisodes || entry.neverPlayed(progress)

    val displayTitle: String
        get() = media.title?.takeIf { it.isNotBlank() }
            ?: media.name?.takeIf { it.isNotBlank() }
            ?: entry.title

    /** Where the viewer stopped, as `S3 E7`, or null for movies and unstarted shows. */
    val episodeMarker: String?
        get() {
            val season = entry.lastWatchedSeason ?: return null
            val episode = entry.lastWatchedEpisode ?: return null
            return "S$season E$episode"
        }

    internal val sortTitle: String get() = displayTitle.lowercase()
}

/**
 * The title the hero offers to carry on with: whatever was watched most recently and still
 * has something left to play.
 *
 * The second half is not optional. Selecting on the `Watching` category alone put finished
 * titles under a "Continue watching" heading with a Resume button and the episode marker of
 * an episode already watched — see [MyListRow.hasSomethingToPlay]. Null when nothing
 * qualifies, which is the honest answer: no hero at all beats a hero pointing at something
 * that is over.
 *
 * Unlike Home's carry-on rail this *does* count a show with episodes waiting, because My List
 * has no separate backlog rail for one to belong to instead.
 */
fun continueWatching(rows: List<MyListRow>): MyListRow? =
    rows.filter { it.category == MyListCategory.Watching && it.hasSomethingToPlay }
        .maxByOrNull { it.entry.lastWatchedAt ?: it.entry.updatedAt }

/** Counts per category over the *unfiltered* list, so the pills do not count themselves. */
fun categoryCounts(rows: List<MyListRow>): Map<MyListCategory, Int> =
    rows.groupingBy { it.category }.eachCount()

/**
 * Applies the viewer's narrowing and ordering.
 *
 * Missing values — a title with no added date, no personal rating, no release year — always
 * sort to the end, in both directions. Flipping the order to hunt for the oldest entry
 * should not first surface everything the backend never dated.
 */
fun applyFilters(rows: List<MyListRow>, filters: MyListFilters): List<MyListRow> {
    val query = filters.query.trim()
    val matching = rows.filter { row ->
        (filters.category == null || row.category == filters.category) &&
            (filters.type == null || row.media.type == filters.type) &&
            (query.isEmpty() || row.displayTitle.contains(query, ignoreCase = true))
    }

    return when (filters.sort) {
        MyListSort.RecentlyAdded ->
            matching.sortedByOptional(filters.descending) { it.entry.addedAt.orNull() }

        MyListSort.RecentlyWatched ->
            matching.sortedByOptional(filters.descending) {
                it.entry.lastWatchedAt?.orNull() ?: it.entry.updatedAt.orNull()
            }

        MyListSort.Title ->
            matching.sortedByOptional(filters.descending) { it.sortTitle }

        MyListSort.YourRating ->
            matching.sortedByOptional(filters.descending) { it.entry.rating }

        MyListSort.ReleaseYear ->
            matching.sortedByOptional(filters.descending) { it.releaseKey() }
    }
}

/**
 * Movies carry a year, series a full first-air date; comparing the year alone keeps the two
 * on one scale rather than sorting every series after every movie.
 */
private fun MyListRow.releaseKey(): String? =
    media.released?.orNull() ?: media.firstAirDate?.take(4)?.orNull()

/**
 * "12 Aug", or "12 Aug 2025" once the year stops being obvious.
 *
 * Accepts either an instant (what the library stores) or a plain date, and returns null for
 * anything it cannot read — an unparsable timestamp should leave the label off, not put the
 * epoch on screen.
 */
fun shortDateLabel(iso: String?, today: LocalDate): String? {
    val value = iso?.orNull() ?: return null
    val date = runCatching { Instant.parse(value).toLocalDateTime(TimeZone.currentSystemDefault()).date }
        .getOrNull()
        ?: runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
        ?: return null

    val month = SHORT_MONTHS[date.month.number - 1]
    return if (date.year == today.year) {
        "${date.day} $month"
    } else {
        "${date.day} $month ${date.year}"
    }
}

private fun String.orNull(): String? = takeIf { it.isNotBlank() }

private val SHORT_MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

private fun <T : Comparable<T>> List<MyListRow>.sortedByOptional(
    descending: Boolean,
    selector: (MyListRow) -> T?,
): List<MyListRow> {
    val (present, missing) = partition { selector(it) != null }
    val primary = compareBy<MyListRow> { selector(it)!! }
    val ordered = if (descending) primary.reversed() else primary
    // Title breaks ties in a fixed direction so the order never depends on input order.
    return present.sortedWith(ordered.thenBy { it.sortTitle }) +
        missing.sortedBy { it.sortTitle }
}

/**
 * Whether the category pills and the filter toolbar stay pinned above the list.
 *
 * Together they cost roughly 180 dp. A desktop window has that to spare and reads better with a
 * filter always one click away. A phone does not: in portrait the pinned controls plus the title
 * block take about a third of the screen before any title is visible, and a landscape phone has
 * only ~411 dp of height in total, which leaves almost nothing. There they scroll with the
 * content instead.
 *
 * [listEmpty] overrides both — a filter that hides every row must not also hide the control that
 * clears it, and with no rows there is no list to scroll the header back into view.
 */
fun shouldPinListFilters(
    compactWidth: Boolean,
    shortViewport: Boolean,
    listEmpty: Boolean,
): Boolean = listEmpty || (!compactWidth && !shortViewport)
