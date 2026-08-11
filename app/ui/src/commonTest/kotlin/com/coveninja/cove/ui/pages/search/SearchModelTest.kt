package com.coveninja.cove.ui.pages.search

import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchModelTest {

    /**
     * A result as the catalog actually delivers one: TMDB fills `title` for films and `name`
     * for series, never both, and dates likewise arrive in different fields. A helper that
     * populated both would quietly make any test of the two-field fallback pass no matter
     * which field the code reads.
     */
    private fun media(
        label: String,
        type: MediaType = MediaType.Movie,
        rating: Double? = null,
        year: String? = null,
        genreIds: List<Int> = emptyList(),
    ): Media = Media(
        id = "${type.name}:$label",
        tmdbId = label.hashCode() and 0x7fffffff,
        title = label.takeIf { type == MediaType.Movie },
        name = label.takeIf { type == MediaType.Series },
        overview = null,
        released = year.takeIf { type == MediaType.Movie },
        firstAirDate = year?.let { "$it-03-04" }.takeIf { type == MediaType.Series },
        posterUrl = "/poster.jpg",
        logoUrl = null,
        backdropUrl = null,
        rating = rating,
        type = type,
        popularity = null,
        adult = null,
        originalLanguage = null,
        genreIds = genreIds,
    )

    private fun titles(items: List<Media>) = items.map { it.displayTitle() }

    // ── applySearchFilters: narrowing ───────────────────────────────────────

    // Mutation applied to verify: dropped the `filters.type == null || media.type ==
    // filters.type` clause → test failed, the series came back in a films-only result.
    @Test
    fun `the format filter keeps only that format`() {
        val results = listOf(
            media("Arrival"),
            media("Severance", type = MediaType.Series),
            media("Dune"),
        )

        val films = applySearchFilters(results, SearchFilters(type = MediaType.Movie))

        assertEquals(listOf("Arrival", "Dune"), titles(films))
    }

    // Mutation applied to verify: changed the genre clause to `filters.genreId == null ||
    // true` → test failed, the horror-only title survived a sci-fi filter.
    @Test
    fun `the genre filter matches on ids rather than names`() {
        val results = listOf(
            media("Arrival", genreIds = listOf(878, 18)),
            media("Hereditary", genreIds = listOf(27)),
        )

        val sciFi = applySearchFilters(results, SearchFilters(genreId = 878))

        assertEquals(listOf("Arrival"), titles(sciFi))
    }

    // Mutation applied to verify: inverted the hideSaved clause to `!filters.hideSaved ||
    // saved(it.id)` → test failed, hiding saved titles left only the saved one.
    @Test
    fun `hide saved drops what is already in the library`() {
        val results = listOf(media("Arrival"), media("Dune"))
        val savedIds = setOf("Movie:Dune")

        val unsaved = applySearchFilters(
            results,
            SearchFilters(hideSaved = true),
            saved = { it in savedIds },
        )

        assertEquals(listOf("Arrival"), titles(unsaved))
    }

    // Mutation applied to verify: made hideSaved unconditional by dropping the
    // `!filters.hideSaved` guard → test failed, the saved title vanished with the toggle off.
    @Test
    fun `saved titles are kept while the toggle is off`() {
        val results = listOf(media("Arrival"), media("Dune"))

        val all = applySearchFilters(results, SearchFilters(), saved = { true })

        assertEquals(listOf("Arrival", "Dune"), titles(all))
    }

    // ── applySearchFilters: ordering ────────────────────────────────────────

    // Mutation applied to verify: made the Relevance branch sort by title → test failed, the
    // backend's own ranking was replaced by an alphabetical one.
    @Test
    fun `best match leaves the backend ordering alone`() {
        val results = listOf(media("Zodiac"), media("Arrival"), media("Mad Max"))

        val ordered = applySearchFilters(results, SearchFilters(sort = SearchSort.Relevance))

        assertEquals(listOf("Zodiac", "Arrival", "Mad Max"), titles(ordered))
    }

    // Mutation applied to verify: changed the unrated fallback from NEGATIVE_INFINITY to 0.0
    // → the assertion still held (nothing here scores below zero), so the second assertion
    // was added with a negative-rated title, which then failed. Both are kept.
    @Test
    fun `top rated puts unrated titles last rather than treating them as zero`() {
        val results = listOf(
            media("Unrated", rating = null),
            media("Good", rating = 7.5),
            media("Great", rating = 9.0),
        )

        val ordered = applySearchFilters(results, SearchFilters(sort = SearchSort.Rating))

        assertEquals(listOf("Great", "Good", "Unrated"), titles(ordered))
        // A rating below the fallback would sort *under* an unrated title if the fallback
        // were 0.0. It cannot happen with TMDB scores, which is exactly why the assertion
        // above is not enough on its own to pin the intent down.
        val withFloor = applySearchFilters(
            listOf(media("Unrated", rating = null), media("Awful", rating = -1.0)),
            SearchFilters(sort = SearchSort.Rating),
        )
        assertEquals(listOf("Awful", "Unrated"), titles(withFloor))
    }

    // Mutation applied to verify: swapped compareByDescending for compareBy → test failed,
    // the oldest title led a "newest first" ordering.
    @Test
    fun `newest first reads the year from whichever field the format uses`() {
        val results = listOf(
            media("Old", year = "1999"),
            media("Undated", year = null),
            media("Show", type = MediaType.Series, year = "2015"),
            media("New", year = "2020"),
        )

        val ordered = applySearchFilters(results, SearchFilters(sort = SearchSort.Newest))

        assertEquals(listOf("New", "Show", "Old", "Undated"), titles(ordered))
    }

    // Mutation applied to verify: dropped `.lowercase()` from the Title comparator → test
    // failed with [Alpha, Yankee, beta, zulu]: raw string ordering puts every capital ahead
    // of every lowercase letter. The mixed case in the data is the whole point of it — an
    // all-lowercase-but-one list sorts identically either way and pins nothing down.
    @Test
    fun `A to Z ignores case`() {
        val results = listOf(media("zulu"), media("Alpha"), media("beta"), media("Yankee"))

        val ordered = applySearchFilters(results, SearchFilters(sort = SearchSort.Title))

        assertEquals(listOf("Alpha", "beta", "Yankee", "zulu"), titles(ordered))
    }

    // ── topResult ───────────────────────────────────────────────────────────

    // Mutation applied to verify: removed the exact-match tier → test failed, the sequel the
    // backend ranked first was returned for a query naming the original exactly.
    @Test
    fun `an exact title match beats whatever the backend ranked first`() {
        val results = listOf(media("Alien: Romulus"), media("Alien"), media("Aliens"))

        assertEquals("Alien", topResult(results, "alien")?.displayTitle())
    }

    // Mutation applied to verify: removed the prefix tier → test failed, the first result was
    // returned instead of the title actually beginning with the query.
    @Test
    fun `a prefix match wins when nothing matches exactly`() {
        val results = listOf(media("The Blade"), media("Blade Runner"))

        assertEquals("Blade Runner", topResult(results, "blade")?.displayTitle())
    }

    // Mutation applied to verify: made the fallback return null → test failed; every keyword
    // hit is a result that does not contain the query, and those must still lead.
    @Test
    fun `a query matching no title at all still leads with the first result`() {
        val results = listOf(media("Heat"), media("Inside Man"))

        assertEquals("Heat", topResult(results, "heist")?.displayTitle())
    }

    // Mutation applied to verify: removed the `if (results.isEmpty()) return null` guard →
    // test failed, the `?: results.first()` fallback threw on an empty list.
    @Test
    fun `there is no top result without results`() {
        assertNull(topResult(emptyList(), "alien"))
    }

    // ── genreFacets ─────────────────────────────────────────────────────────

    // Mutation applied to verify: sorted facets by id instead of by count → test failed,
    // Drama led a set where Action accounted for three of the four results.
    @Test
    fun `facets count the results and lead with the commonest`() {
        val results = listOf(
            media("A", genreIds = listOf(28, 18)),
            media("B", genreIds = listOf(28)),
            media("C", genreIds = listOf(28)),
            media("D", genreIds = listOf(18)),
        )

        val facets = genreFacets(results)

        assertEquals(listOf("Action" to 3, "Drama" to 2), facets.map { it.name to it.count })
    }

    // Mutation applied to verify: counted `media.genreIds` without `.distinct()` → test
    // failed, one title listing a genre twice counted as two results for it.
    @Test
    fun `a title listing a genre twice counts once`() {
        val results = listOf(media("A", genreIds = listOf(28, 28)))

        assertEquals(listOf(1), genreFacets(results).map { it.count })
    }

    // Mutation applied to verify: passed emptyMap() to resolveGenreName instead of the
    // supplied names → test failed, the baked-in English name came back over the backend's.
    @Test
    fun `the backend vocabulary wins over the baked-in one`() {
        val results = listOf(media("A", genreIds = listOf(28)))

        val facets = genreFacets(results, backendNames = mapOf(28 to "Akcja"))

        assertEquals(listOf("Akcja"), facets.map { it.name })
    }

    // Mutation applied to verify: dropped the `.take(limit)` → test failed, all three facets
    // came back where two were asked for.
    @Test
    fun `the facet row is capped`() {
        val results = listOf(media("A", genreIds = listOf(28, 18, 27)))

        assertEquals(2, genreFacets(results, limit = 2).size)
    }

    // Mutation applied to verify: replaced mapNotNull with a map producing a placeholder name
    // → test failed, an id in no vocabulary became a pill that filters to nothing.
    @Test
    fun `an unrecognised genre id is not offered as a filter`() {
        val results = listOf(media("A", genreIds = listOf(999_999)))

        assertTrue(genreFacets(results).isEmpty())
    }

    // ── recordRecent ────────────────────────────────────────────────────────

    // Mutation applied to verify: dropped the filterNot → test failed, re-running a past
    // search left two spellings of it in the list.
    @Test
    fun `re-searching moves the entry to the front instead of duplicating it`() {
        val recents = listOf("dune", "arrival", "heat")

        assertEquals(listOf("Dune", "arrival", "heat"), recordRecent(recents, "Dune"))
    }

    // Mutation applied to verify: made the filterNot case-sensitive → test failed, "Dune"
    // and "dune" were kept as two separate searches.
    @Test
    fun `history is case-insensitive`() {
        assertEquals(1, recordRecent(listOf("dune"), "DUNE").size)
    }

    // Mutation applied to verify: dropped the `.take(limit)` → test failed, the list grew
    // past the cap and the oldest entry survived.
    @Test
    fun `history is capped, oldest first out`() {
        val full = (1..8).map { "query $it" }

        val next = recordRecent(full, "newest", limit = 8)

        assertEquals(8, next.size)
        assertEquals("newest", next.first())
        assertFalse(next.contains("query 8"))
    }

    // Mutation applied to verify: removed the blank guard → test failed, whitespace became a
    // history entry.
    @Test
    fun `blank queries are not history`() {
        assertEquals(listOf("dune"), recordRecent(listOf("dune"), "   "))
    }

    // ── matchSpan ───────────────────────────────────────────────────────────

    // Mutation applied to verify: dropped `ignoreCase = true` → test failed, a lowercase
    // query found nothing in a capitalised title.
    @Test
    fun `the highlight span ignores case and takes the first occurrence`() {
        assertEquals(6..11, matchSpan("Blade Runner", "runner"))
        assertEquals(0..1, matchSpan("Alien Aliens", "al"))
    }

    // Mutation applied to verify: removed the blank guard → test failed, an empty query
    // matched at index 0 and highlighted nothing at the start of every title.
    @Test
    fun `nothing is highlighted for a blank query`() {
        assertNull(matchSpan("Blade Runner", "   "))
    }

    // Mutation applied to verify: changed `start < 0` to `start < -1` → test failed, an
    // absent query produced a span starting at -1.
    @Test
    fun `nothing is highlighted when the query is absent`() {
        assertNull(matchSpan("Heat", "heist"))
    }

    // ── Labels and filter state ─────────────────────────────────────────────

    // Mutation applied to verify: dropped the singular branch → test failed, one result was
    // reported as "1 matches".
    @Test
    fun `the count label is singular for one result`() {
        assertEquals("1 match", resultCountLabel(1, 1))
        assertEquals("24 matches", resultCountLabel(24, 24))
    }

    // Mutation applied to verify: made the label always use the plain form → test failed,
    // filtering 24 results down to 12 still claimed 12 matches with no sign of the 24.
    @Test
    fun `the count label says how much is being hidden`() {
        assertEquals("12 of 24 matches", resultCountLabel(12, 24))
    }

    // Mutation applied to verify: added `|| sort != SearchSort.Relevance` to narrowed → test
    // failed, choosing an ordering lit up a "clear filters" button that had nothing to clear.
    @Test
    fun `changing the order is not narrowing`() {
        assertFalse(SearchFilters(sort = SearchSort.Rating).narrowed)
        assertTrue(SearchFilters(type = MediaType.Movie).narrowed)
        assertTrue(SearchFilters(genreId = 28).narrowed)
        assertTrue(SearchFilters(hideSaved = true).narrowed)
    }

    // Mutation applied to verify: made cleared() return SearchFilters() → test failed, the
    // chosen ordering was thrown away along with the narrowing.
    @Test
    fun `clearing the filters keeps the ordering`() {
        val filters = SearchFilters(
            type = MediaType.Series,
            genreId = 28,
            sort = SearchSort.Newest,
            hideSaved = true,
        )

        val cleared = filters.cleared()

        assertEquals(SearchSort.Newest, cleared.sort)
        assertFalse(cleared.narrowed)
    }
}
