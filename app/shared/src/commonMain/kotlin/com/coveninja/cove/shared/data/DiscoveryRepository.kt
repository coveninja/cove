package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.CatalogSort
import com.coveninja.cove.shared.model.Media
import com.coveninja.cove.shared.model.MediaGenre
import com.coveninja.cove.shared.model.MediaType

/** One page of the catalog, narrowed and ordered. */
data class BrowseQuery(
    val type: MediaType,
    val genreId: Int? = null,
    val sort: CatalogSort = CatalogSort.Popularity,
    val page: Int = 1,
)

/** A title the viewer's own history argues for, named so a rail can be titled after it. */
data class FavoriteTitle(
    val tmdbId: Int,
    val type: MediaType,
    val title: String,
)

/**
 * Browsing the catalog, and the taste profile built from the viewer's library.
 *
 * The two halves are deliberately one interface because Explore mixes them on a single
 * screen, but they behave very differently and callers need to know which is which:
 *
 * - [genres] and [browse] are **cheap and impersonal** — one upstream request, the same
 *   answer for everyone. Safe to call on first paint.
 * - [recommended], [topGenres], [similarTo] and [favorites] are **personal and expensive**.
 *   Behind them sits a taste profile that costs one metadata request per saved title on a
 *   cold cache. Nothing that blocks first paint should wait on them; load the impersonal
 *   rails first and let these fill in.
 *
 * Every method returns empty rather than throwing when there is nothing to say — a fresh
 * profile with an empty library has no taste, which is an ordinary state and not an error.
 */
interface DiscoveryRepository {
    /** The provider's genre vocabulary for [type]. Cached; ids only mean anything with it. */
    suspend fun genres(type: MediaType): List<MediaGenre>

    /**
     * One page of the whole catalog. Includes titles already in the library — this is
     * browsing, not recommending.
     */
    suspend fun browse(query: BrowseQuery): List<Media>

    /** Taste-ranked picks, excluding anything already saved or dismissed. */
    suspend fun recommended(type: MediaType, limit: Int): List<Media>

    /** The genres the viewer's library argues for hardest, strongest first. */
    suspend fun topGenres(type: MediaType, limit: Int): List<MediaGenre>

    /** Titles like a given one, filtered against what the viewer has already seen. */
    suspend fun similarTo(type: MediaType, tmdbId: Int, limit: Int): List<Media>

    /** The strongest positive signals in the library, strongest first. */
    suspend fun favorites(limit: Int): List<FavoriteTitle>
}

/**
 * Stands in where no discovery engine is reachable — see [UnavailableAddonRepository].
 *
 * Unlike the addon and playback stand-ins this one does not throw: Explore is expected to
 * work without a catalog behind it, showing whatever the discover feed already loaded, so
 * an empty answer is the honest response rather than a failure to render around.
 */
object UnavailableDiscoveryRepository : DiscoveryRepository {
    override suspend fun genres(type: MediaType): List<MediaGenre> = emptyList()
    override suspend fun browse(query: BrowseQuery): List<Media> = emptyList()
    override suspend fun recommended(type: MediaType, limit: Int): List<Media> = emptyList()
    override suspend fun topGenres(type: MediaType, limit: Int): List<MediaGenre> = emptyList()
    override suspend fun similarTo(type: MediaType, tmdbId: Int, limit: Int): List<Media> = emptyList()
    override suspend fun favorites(limit: Int): List<FavoriteTitle> = emptyList()
}
