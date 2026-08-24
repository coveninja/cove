package com.coveninja.cove.ui.pages.explore

import com.coveninja.cove.shared.model.AddonCatalogDescriptor
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.MediaType
import com.coveninja.cove.ui.model.TmdbGenres
import com.coveninja.cove.ui.model.resolveGenreName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExploreModelTest {

    /**
     * A title as the catalog actually delivers one: TMDB fills `title` for films and
     * `name` for series, never both. A helper that populated both would quietly make any
     * test of the two-field fallback pass no matter which field the code reads.
     */
    private fun media(
        title: String,
        type: MediaType = MediaType.Movie,
        backdrop: String? = "/backdrop.jpg",
    ): Media = Media(
        id = "${type.name}:${title.hashCode() and 0x7fffffff}",
        tmdbId = title.hashCode() and 0x7fffffff,
        title = title.takeIf { type == MediaType.Movie },
        name = title.takeIf { type == MediaType.Series },
        overview = null,
        released = null,
        firstAirDate = null,
        posterUrl = "/poster.jpg",
        logoUrl = null,
        backdropUrl = backdrop,
        rating = null,
        type = type,
        popularity = null,
        adult = null,
        originalLanguage = null,
    )

    private fun shelf(id: String, media: List<Media>, kind: ShelfKind = ShelfKind.Genre) =
        ExploreShelf(
            id = id,
            title = id,
            subtitle = "",
            icon = "lucide:layout-grid",
            kind = kind,
            media = media,
        )

    private fun titles(items: List<Media>) = items.map { it.title ?: it.name }

    // ── buildShelves ────────────────────────────────────────────────────────

    // Mutation applied to verify: dropped the `distinct.size < minimumSize` guard → test
    // failed, the two-title rail came back in the result.
    @Test
    fun `a rail with too few titles is not worth a heading`() {
        val long = (1..6).map { media("Long $it") }
        val short = (1..2).map { media("Short $it") }

        val kept = buildShelves(
            listOf(shelf("long", long), shelf("short", short)),
            minimumSize = 5,
        )

        assertEquals(listOf("long"), kept.map { it.id })
    }

    // A rail that repeats what the page already showed teaches the viewer that scrolling
    // is pointless, however long it is.
    // Mutation applied to verify: compared against `distinct.size` instead of the unseen
    // count so only length mattered → test failed, the duplicate rail survived.
    @Test
    fun `a rail that only repeats earlier titles is dropped however long it is`() {
        val shared = (1..8).map { media("Shared $it") }
        val nearlyAllRepeats = shared.take(7) + media("Novel")

        val kept = buildShelves(
            listOf(shelf("first", shared), shelf("second", nearlyAllRepeats)),
            minimumSize = 5,
        )

        assertEquals(listOf("first"), kept.map { it.id })
    }

    // The dedupe is between rails, not between titles: stripping a film from a later rail
    // because an earlier one already had it would hollow it out and, for an ordered rail
    // like "top rated", silently reorder it into something that is no longer top-rated.
    // Mutation applied to verify: subtracted `seen` from each shelf's media before keeping
    // it → test failed, the second rail lost its overlapping titles and its ordering.
    @Test
    fun `a title may appear in more than one kept rail`() {
        val overlap = (1..3).map { media("Both $it") }
        val first = overlap + (1..5).map { media("OnlyFirst $it") }
        val second = overlap + (1..5).map { media("OnlySecond $it") }

        val kept = buildShelves(
            listOf(shelf("first", first), shelf("second", second)),
            minimumSize = 5,
        )

        assertEquals(listOf("first", "second"), kept.map { it.id })
        assertEquals(titles(second), titles(kept[1].media))
    }

    // "Top rated" is a claim about an ordering, so it is worth drawing even when it lists
    // the same films as "Trending" above it. Without this exemption a catalog smaller than
    // a few hundred titles collapses to a single rail — which is exactly what the fixture
    // backend is, so the whole page would look broken with no backend running.
    // Mutation applied to verify: applied the novelty guard to every kind → test failed,
    // the top-rated rail was dropped for overlapping with trending.
    @Test
    fun `an ordered rail survives overlapping with the rail above it`() {
        val catalog = (1..8).map { media("Film $it") }
        val trending = catalog
        val topRated = catalog.reversed()
        val horror = catalog.take(6)

        val kept = buildShelves(
            listOf(
                shelf("trending", trending, ShelfKind.Trending),
                shelf("top-rated", topRated, ShelfKind.TopRated),
                shelf("horror", horror, ShelfKind.Genre),
            ),
            minimumSize = 5,
        )

        // The genre rail is membership-defined, so repeating the same films says nothing.
        assertEquals(listOf("trending", "top-rated"), kept.map { it.id })
        assertEquals(titles(topRated), titles(kept[1].media))
    }

    // The exemption is about overlap, not about length: an ordered rail still needs enough
    // titles to fill a row.
    // Mutation applied to verify: skipped the size guard for ordered rails → test failed,
    // the three-title top-rated rail was kept.
    @Test
    fun `an ordered rail still has to be long enough`() {
        val kept = buildShelves(
            listOf(shelf("top-rated", (1..3).map { media("Film $it") }, ShelfKind.TopRated)),
            minimumSize = 5,
        )

        assertTrue(kept.isEmpty())
    }

    // Mutation applied to verify: dropped the `distinctBy` so a repeated title counted
    // twice → test failed, a rail of one title repeated six times was kept.
    @Test
    fun `a rail padded with one repeated title does not count as full`() {
        val repeated = List(6) { media("Same") }

        val kept = buildShelves(listOf(shelf("padded", repeated)), minimumSize = 5)

        assertTrue(kept.isEmpty())
    }

    // ── spotlightPicks ──────────────────────────────────────────────────────

    // The hero is a wide image with text over it; a poster-only title renders as a
    // stretched smear behind unreadable copy.
    // Mutation applied to verify: removed the backdrop filter → test failed, the title
    // with no backdrop was picked.
    @Test
    fun `the spotlight never picks a title with no backdrop`() {
        val picks = spotlightPicks(
            listOf(
                media("No backdrop", backdrop = null),
                media("Blank backdrop", backdrop = ""),
                media("Fine"),
            ),
            count = 5,
        )

        assertEquals(listOf("Fine"), titles(picks))
    }

    // Mutation applied to verify: dropped `distinctBy` → test failed, the same title
    // appeared twice and the rotation would have shown it back to back.
    @Test
    fun `the spotlight shows each title at most once and honours its cap`() {
        val repeated = listOf(media("A"), media("A"), media("B"), media("C"), media("D"))

        assertEquals(listOf("A", "B", "C", "D"), titles(spotlightPicks(repeated, count = 9)))
        assertEquals(listOf("A", "B"), titles(spotlightPicks(repeated, count = 2)))
        assertTrue(spotlightPicks(repeated, count = 0).isEmpty())
    }

    // ── applyQuery ──────────────────────────────────────────────────────────

    // Mutation applied to verify: matched only `title` and not `name` → test failed, the
    // series (which carries its title in `name`) was filtered out.
    @Test
    fun `the query matches either title field, ignoring case`() {
        val items = listOf(
            media("The Matrix"),
            // A series carries its title in `name` and has no `title` at all, which is the
            // whole reason the query has to consult both.
            media("Breaking Bad", type = MediaType.Series),
            media("Inception"),
        )

        assertEquals(listOf("The Matrix"), titles(applyQuery(items, "matrix")))
        assertEquals(listOf("Breaking Bad"), titles(applyQuery(items, "BREAKING")))
        assertEquals(titles(items), titles(applyQuery(items, "   ")))
    }

    // The catalog already ranked these; re-sorting by match quality would fight whichever
    // order the viewer chose in the toolbar.
    // Mutation applied to verify: sorted matches by title length → test failed, the
    // shortest title led instead of the catalog's own order.
    @Test
    fun `filtering by query preserves the catalog's order`() {
        val items = listOf(media("Star Wars"), media("Star"), media("A Star Is Born"))

        assertEquals(
            listOf("Star Wars", "Star", "A Star Is Born"),
            titles(applyQuery(items, "star")),
        )
    }

    // ── titleCountLabel ─────────────────────────────────────────────────────

    // Mutation applied to verify: always used the plural noun → test failed on the
    // single-title case, which read "1 titles".
    @Test
    fun `the count label reads naturally and admits when more is reachable`() {
        assertEquals("1 title", titleCountLabel(1, moreAvailable = false))
        assertEquals("0 titles", titleCountLabel(0, moreAvailable = false))
        assertEquals("24+ titles", titleCountLabel(24, moreAvailable = true))
    }

    // ── Genre resolution ────────────────────────────────────────────────────

    // TMDB's two vocabularies disagree on ids they both use, so a single flat table would
    // mislabel a large share of all series.
    // Mutation applied to verify: made nameOf consult one combined map → test failed,
    // 10765 resolved for films and 878 resolved for series, neither of which is real.
    @Test
    fun `genre ids resolve against the vocabulary for their own format`() {
        assertEquals("Science Fiction", TmdbGenres.nameOf(878, MediaType.Movie))
        assertNull(TmdbGenres.nameOf(878, MediaType.Series))

        assertEquals("Sci-Fi & Fantasy", TmdbGenres.nameOf(10765, MediaType.Series))
        assertNull(TmdbGenres.nameOf(10765, MediaType.Movie))

        // Shared ids mean the same thing in both, so an unknown format still resolves them.
        assertEquals("Animation", TmdbGenres.nameOf(16, MediaType.Movie))
        assertEquals("Animation", TmdbGenres.nameOf(16, MediaType.Series))
        assertEquals("Animation", TmdbGenres.nameOf(16, null))
    }

    // An id TMDB added after this table was written must drop out of a filter row, not
    // appear as a pill that matches nothing recognisable.
    // Mutation applied to verify: returned "Genre $id" instead of null → test failed,
    // namesOf emitted a label for the unknown id.
    @Test
    fun `an unknown genre id is dropped rather than labelled`() {
        assertNull(TmdbGenres.nameOf(999_999, MediaType.Movie))
        assertEquals(
            listOf("Action", "Drama"),
            TmdbGenres.namesOf(listOf(28, 999_999, 18), MediaType.Movie),
        )
    }

    // The backend's list is localized and current; the baked-in one is only the floor.
    // Mutation applied to verify: consulted the static table first → test failed, the
    // English fallback won over the backend's own name.
    @Test
    fun `the backend genre vocabulary outranks the baked-in fallback`() {
        val backend = mapOf(28 to "Aksiyon")

        assertEquals("Aksiyon", resolveGenreName(28, MediaType.Movie, backend))
        assertEquals("Drama", resolveGenreName(18, MediaType.Movie, backend))
        // A blank name from the backend is not an answer, so the fallback still applies.
        assertEquals("Drama", resolveGenreName(18, MediaType.Movie, mapOf(18 to "")))
    }

    // ── Addon catalogs ──────────────────────────────────────────────────────

    private fun descriptor(addonId: String, catalogId: String, type: String = "movie") =
        AddonCatalogDescriptor(
            addonId = addonId,
            addonName = "Provider",
            addonUrl = "https://addon.test",
            type = type,
            catalogId = catalogId,
            name = catalogId,
        )

    /**
     * `catalogKey` is what `setGridFilters` diffs on to decide whether the grid needs
     * reloading. Two different addon catalogs are two different pages, so they must not
     * compare equal — otherwise "See all" on the second row would show the first row's
     * results.
     *
     * Mutation applied to verify: dropped `catalog?.addonId` and `catalog?.key` from
     * `catalogKey` → test failed, the two keys compared equal.
     */
    @Test
    fun `two addon catalogs are two different grid pages`() {
        val first = ExploreFilters(catalog = descriptor("addon.one", "popular"))
        val second = ExploreFilters(catalog = descriptor("addon.one", "trending"))
        val otherAddon = ExploreFilters(catalog = descriptor("addon.two", "popular"))

        assertTrue(first.catalogKey != second.catalogKey, "same addon, different catalog")
        assertTrue(first.catalogKey != otherAddon.catalogKey, "same catalog id, different addon")
        assertEquals(first.catalogKey, ExploreFilters(catalog = descriptor("addon.one", "popular")).catalogKey)
    }

    /**
     * A catalog is a narrowing of the page just as a genre is, which is what puts the
     * "Clear filters" affordance in reach — the only way back out of a catalog grid whose
     * results are empty.
     *
     * Mutation applied to verify: removed `|| catalog != null` from `narrowed` → test
     * failed, a catalog-filtered page reported itself unnarrowed.
     */
    @Test
    fun `a catalog narrows the page`() {
        assertTrue(ExploreFilters(catalog = descriptor("addon.one", "popular")).narrowed)
        assertTrue(!ExploreFilters().narrowed)
    }

    /**
     * The catalog grid is the one arrangement reached from somewhere else, so it has to
     * name itself. The addon is the identifying half — "Popular" is what half the
     * catalogs in circulation are called, and it says nothing on its own.
     *
     * Mutation applied to verify: dropped `catalog.addonName` from
     * `catalogGridSubtitle` → test failed, the provider was no longer named.
     */
    @Test
    fun `a catalog grid names its addon alongside the count`() {
        val catalog = descriptor("addon.one", "popular")

        assertEquals("Provider · 24+ titles", catalogGridSubtitle(catalog, 24, moreAvailable = true))
        assertEquals("Provider · 3 titles", catalogGridSubtitle(catalog, 3, moreAvailable = false))
        assertEquals("Provider · 1 title", catalogGridSubtitle(catalog, 1, moreAvailable = false))
    }

    /**
     * Leaving the catalog has to leave the page unnarrowed, or Explore stays filtered to
     * something nothing on screen still mentions.
     *
     * Mutation applied to verify: made the exit `filters.copy()` without clearing the
     * catalog → test failed, the page was still narrowed.
     */
    @Test
    fun `clearing the catalog unnarrows the page`() {
        val inCatalog = ExploreFilters(catalog = descriptor("addon.one", "popular"))
        val left = inCatalog.copy(catalog = null)

        assertTrue(inCatalog.narrowed)
        assertTrue(!left.narrowed)
        assertTrue(inCatalog.catalogKey != left.catalogKey, "leaving reloads the grid")
    }

    /**
     * An addon's rail is defined by its ordering, so it survives `buildShelves` even where
     * every title on it has already appeared. A membership rail in the same position would
     * be dropped as saying nothing new — that difference is the whole point of
     * [ShelfKind.definedByOrder].
     *
     * Mutation applied to verify: declared `AddonCatalog(definedByOrder = false)` → test
     * failed, the catalog shelf was dropped as redundant.
     */
    @Test
    fun `an addon catalog rail survives overlapping an earlier rail`() {
        val titles = List(6) { media("Title $it") }
        val kept = buildShelves(
            listOf(
                shelf("trending", titles, ShelfKind.Trending),
                shelf("addon", titles, ShelfKind.AddonCatalog),
                shelf("genre", titles, ShelfKind.Genre),
            ),
        )

        assertEquals(listOf("trending", "addon"), kept.map(ExploreShelf::id))
    }

}
