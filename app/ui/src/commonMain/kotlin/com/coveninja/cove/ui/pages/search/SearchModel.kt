package com.coveninja.cove.ui.pages.search

import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.MediaType
import com.coveninja.cove.ui.model.resolveGenreName
import kotlin.math.round

/**
 * Everything Search decides before it draws anything.
 *
 * Deliberately free of Compose and of the repositories, like `HomeModel` and `ExploreModel`:
 * the ranking and the narrowing are the parts that are easy to get subtly wrong and the only
 * parts a test can pin down. `SearchSession` does the fetching, the view files do the drawing,
 * and the choices live here.
 */

// ── Arrangement ─────────────────────────────────────────────────────────────

/** Which of the two ways results are arranged. */
enum class SearchLayout(val label: String, val icon: String) {
    /** Posters. For recognising something you already know the look of. */
    Grid("Grid", "lucide:layout-grid"),

    /** Rows with a synopsis. For weighing up titles you have never heard of. */
    List("List", "lucide:list"),
}

/**
 * How results are ordered.
 *
 * [Relevance] is not a sort at all — it is the absence of one, and that is the point. The
 * backend already ranked these (`LocalContentRepository.search` orders by popularity, on top
 * of TMDB's own match ordering), so re-ranking client-side would fight a better-informed
 * ordering with a worse one. The other three exist because "best match" is the wrong question
 * when you are looking for the newest entry in a franchise or the one that reviewed well.
 */
enum class SearchSort(val label: String) {
    Relevance("Best match"),
    Rating("Top rated"),
    Newest("Newest first"),
    Title("A–Z"),
}

/**
 * What the viewer has narrowed the results to.
 *
 * [type] is nullable here, unlike Explore's: those are two separate catalog endpoints that
 * cannot be paged as one query, whereas search returns films and series together in a single
 * list, so "both" is a real and in fact the default answer.
 */
data class SearchFilters(
    val type: MediaType? = null,
    val genreId: Int? = null,
    val sort: SearchSort = SearchSort.Relevance,
    val hideSaved: Boolean = false,
) {
    /**
     * True when something is being *hidden*.
     *
     * The sort is deliberately excluded. Reordering shows the same titles in a different
     * order, so "nothing matches" can never be the sort's fault, and offering to clear it
     * would be offering to fix something that is not broken.
     */
    val narrowed: Boolean get() = type != null || genreId != null || hideSaved

    /** Drops the narrowing and keeps the ordering, which is what "clear filters" means. */
    fun cleared(): SearchFilters = SearchFilters(sort = sort)
}

// ── Narrowing and ordering ──────────────────────────────────────────────────

/**
 * The results actually drawn, narrowed then ordered.
 *
 * [saved] is passed in rather than reached for so this stays a pure function; the page
 * supplies a lookup over `LibraryIndex`.
 */
fun applySearchFilters(
    results: List<Media>,
    filters: SearchFilters,
    saved: (String) -> Boolean = { false },
): List<Media> {
    val narrowed = results.filter { media ->
        (filters.type == null || media.type == filters.type) &&
            (filters.genreId == null || filters.genreId in media.genreIds) &&
            (!filters.hideSaved || !saved(media.id))
    }

    return when (filters.sort) {
        SearchSort.Relevance -> narrowed
        // Unrated titles sort last rather than as zero: a title nobody has voted on is not
        // the worst title, it is an unknown one, and burying it under a 2.0 would be a claim
        // the data does not support.
        SearchSort.Rating -> narrowed.sortedWith(
            compareByDescending<Media> { it.rating ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.displayTitle().lowercase() },
        )
        // Years are four characters, so lexicographic ordering is numeric ordering. Undated
        // titles carry "" and fall to the end, where an unknown release date belongs.
        SearchSort.Newest -> narrowed.sortedWith(
            compareByDescending<Media> { it.displayYear().orEmpty() }
                .thenBy { it.displayTitle().lowercase() },
        )
        SearchSort.Title -> narrowed.sortedBy { it.displayTitle().lowercase() }
    }
}

/**
 * The one result presented as *the* answer rather than as tile #1.
 *
 * Two tiers, both about the title itself: an exact match, then a prefix match. There is
 * deliberately no "contains" tier — every result already contains the query in some form, so
 * that tier would only ever return the first item, which is what the fallback does anyway.
 *
 * The tiers matter because the backend orders by popularity: searching "alien" otherwise
 * leads with whichever sequel or unrelated blockbuster is hot this week rather than with
 * *Alien*.
 */
fun topResult(results: List<Media>, query: String): Media? {
    if (results.isEmpty()) return null
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return results.first()

    return results.firstOrNull { it.displayTitle().lowercase() == needle }
        ?: results.firstOrNull { it.displayTitle().lowercase().startsWith(needle) }
        ?: results.first()
}

// ── Genre facets ────────────────────────────────────────────────────────────

/** One genre pill over the results, and how many of them it accounts for. */
data class GenreFacet(val id: Int, val name: String, val count: Int)

/**
 * The genres worth offering as filters, commonest first.
 *
 * Built from the results rather than from the catalog vocabulary on purpose: a genre list
 * that offers thirty options where only four match anything is a list of twenty-six dead
 * ends. Counting also gives the pills something to say.
 *
 * The type each id is resolved against is taken from the first title carrying it, because
 * the two vocabularies overlap and disagree — see [com.coveninja.cove.ui.model.TmdbGenres].
 */
fun genreFacets(
    results: List<Media>,
    backendNames: Map<Int, String> = emptyMap(),
    limit: Int = MAX_GENRE_FACETS,
): List<GenreFacet> {
    val counts = mutableMapOf<Int, Int>()
    val typeOf = mutableMapOf<Int, MediaType?>()

    results.forEach { media ->
        media.genreIds.distinct().forEach { id ->
            counts[id] = (counts[id] ?: 0) + 1
            if (id !in typeOf) typeOf[id] = media.type
        }
    }

    return counts.entries
        .mapNotNull { (id, count) ->
            resolveGenreName(id, typeOf[id], backendNames)?.let { name ->
                GenreFacet(id = id, name = name, count = count)
            }
        }
        .sortedWith(compareByDescending<GenreFacet> { it.count }.thenBy { it.name })
        .take(limit)
}

// ── Recent searches ─────────────────────────────────────────────────────────

/**
 * Adds a query to the history, most recent first.
 *
 * Re-searching something already in the list moves it to the front rather than duplicating
 * it, and the comparison is case-insensitive because "Blade Runner" and "blade runner" are
 * the same search to everyone except a string comparison.
 */
fun recordRecent(
    recents: List<String>,
    query: String,
    limit: Int = MAX_RECENT_SEARCHES,
): List<String> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return recents
    return (listOf(trimmed) + recents.filterNot { it.equals(trimmed, ignoreCase = true) })
        .take(limit)
}

// ── Presentation helpers ────────────────────────────────────────────────────

/**
 * Where the query appears inside a title, for highlighting it.
 *
 * The first occurrence only. Highlighting every one turns a title into a stripe pattern, and
 * the first is the one that explains why the row is in the list.
 */
fun matchSpan(title: String, query: String): IntRange? {
    val needle = query.trim()
    if (needle.isEmpty()) return null
    val start = title.indexOf(needle, ignoreCase = true)
    if (start < 0) return null
    return start until (start + needle.length)
}

/** "24 matches", "1 match", and "12 of 24 matches" once filters are hiding some. */
fun resultCountLabel(shown: Int, total: Int): String {
    val noun = if (total == 1) "match" else "matches"
    return if (shown == total) "$total $noun" else "$shown of $total $noun"
}

/** The title to show, whichever of TMDB's two fields this item happens to carry. */
internal fun Media.displayTitle(): String =
    title?.takeIf { it.isNotBlank() }
        ?: name?.takeIf { it.isNotBlank() }
        ?: ""

/**
 * The release year, from whichever field the format uses.
 *
 * `released` is already truncated to a year by the domain mapper; `firstAirDate` is a full
 * date and is the only one series carry.
 */
internal fun Media.displayYear(): String? =
    released?.takeIf { it.isNotBlank() }
        ?: firstAirDate?.takeIf { it.isNotBlank() }?.take(4)

/** "2017 · Movie · ★8.0 · Sci-Fi, Drama" — whichever parts of it are known. */
internal fun Media.metaLine(maxGenres: Int = 2): String = buildList {
    displayYear()?.let(::add)
    type?.label?.let(::add)
    rating?.let { add("★ ${roundToOneDecimal(it)}") }
    addAll(genres.take(maxGenres))
}.joinToString("  ·  ")

private fun roundToOneDecimal(value: Double): String {
    val scaled = round(value * 10.0).toInt()
    return "${scaled / 10}.${scaled % 10}"
}

// ── Starting points ─────────────────────────────────────────────────────────

/** One of the theme shortcuts offered before anything has been typed. */
data class SearchMood(val label: String, val icon: String) {
    /** What is actually searched. Lowercase because TMDB's keyword index is. */
    val query: String get() = label.lowercase()
}

/**
 * Themes to search for when you do not have a title in mind.
 *
 * These lean on the keyword half of the backend's search — `TmdbClient.searchMulti` folds in
 * `search/keyword` hits alongside title matches — which is exactly what makes a word like
 * "heist" return heist films rather than films with "heist" in the name. They are the reason
 * the page is useful before the first keystroke.
 */
val SEARCH_MOODS: List<SearchMood> = listOf(
    SearchMood("Heist", "lucide:blocks"),
    SearchMood("Time travel", "lucide:clock-3"),
    SearchMood("Space", "lucide:globe-2"),
    SearchMood("Revenge", "lucide:flame"),
    SearchMood("Coming of age", "lucide:users"),
    SearchMood("Superhero", "lucide:shield-check"),
    SearchMood("Post-apocalyptic", "lucide:triangle-alert"),
    SearchMood("True story", "lucide:badge-check"),
    SearchMood("Noir", "lucide:film"),
    SearchMood("Musical", "lucide:audio-lines"),
)

/** Placeholder examples the field cycles through while it is empty and unfocused. */
val SEARCH_HINTS: List<String> = listOf(
    "blade runner",
    "heist",
    "time travel",
    "spy thriller",
    "space opera",
    "coming of age",
)

// ── Constants ───────────────────────────────────────────────────────────────

/** Past this the pill row is a wall of genres rather than a way to narrow one. */
const val MAX_GENRE_FACETS = 12

/** Enough history to be useful, few enough that the row never wraps into a block. */
const val MAX_RECENT_SEARCHES = 8

/**
 * How long typing has to pause before the query is sent.
 *
 * One search is three upstream requests — `search/movie`, `search/tv`, and a keyword lookup
 * that fans out into two more `discover` calls — so this is the difference between a handful
 * of requests per query and one per keystroke.
 */
const val SEARCH_DEBOUNCE_MILLIS = 400L

/** Below this a query matches most of the catalog, which is not a search result. */
const val MIN_QUERY_CHARS = 2
