package com.coveninja.cove.ui.model

/**
 * TMDB's genre vocabulary, baked in.
 *
 * List-level media carries genre *ids* and nothing else — names only ever arrive with a
 * details fetch, which is why the genre filter on Explore used to render empty against a
 * live backend. Resolving ids here makes genres available on every card, offline, in
 * fixtures, and on first paint, without waiting on a round trip.
 *
 * The live vocabulary from the backend still wins where it is available (it is localized;
 * this is not). This is the floor, not the source of truth — see `resolveGenreName`.
 *
 * Keyed by type as well as id because the two vocabularies overlap and disagree: `16` is
 * Animation in both, but `10759` is "Action & Adventure" for series and means nothing for
 * films, while films split War (`10752`) from a Sci-Fi (`878`) that series fold into
 * `10765`. A single flat map would silently mislabel about a third of all series.
 */
object TmdbGenres {

    private val movie: Map<Int, String> = mapOf(
        28 to "Action",
        12 to "Adventure",
        16 to "Animation",
        35 to "Comedy",
        80 to "Crime",
        99 to "Documentary",
        18 to "Drama",
        10751 to "Family",
        14 to "Fantasy",
        36 to "History",
        27 to "Horror",
        10402 to "Music",
        9648 to "Mystery",
        10749 to "Romance",
        878 to "Science Fiction",
        10770 to "TV Movie",
        53 to "Thriller",
        10752 to "War",
        37 to "Western",
    )

    private val series: Map<Int, String> = mapOf(
        10759 to "Action & Adventure",
        16 to "Animation",
        35 to "Comedy",
        80 to "Crime",
        99 to "Documentary",
        18 to "Drama",
        10751 to "Family",
        10762 to "Kids",
        9648 to "Mystery",
        10763 to "News",
        10764 to "Reality",
        10765 to "Sci-Fi & Fantasy",
        10766 to "Soap",
        10767 to "Talk",
        10768 to "War & Politics",
        37 to "Western",
    )

    /**
     * The name for [id], or null when this vocabulary has never heard of it.
     *
     * Null rather than a placeholder: an unknown id means TMDB added a genre since this
     * table was written, and dropping it from a filter row is far better than offering a
     * pill reading "Genre 10771" that matches nothing the viewer recognises.
     */
    fun nameOf(id: Int, type: MediaType?): String? = when (type) {
        MediaType.Movie -> movie[id]
        MediaType.Series -> series[id]
        // With no type to disambiguate, prefer the film vocabulary and fall back to the
        // series one — the ids they share carry the same meaning in both.
        null -> movie[id] ?: series[id]
    }

    /** Resolves a whole id list, dropping anything unrecognised, order preserved. */
    fun namesOf(ids: List<Int>, type: MediaType?): List<String> =
        ids.mapNotNull { nameOf(it, type) }.distinct()
}

/**
 * A genre name from the backend's own (localized) vocabulary where one exists, falling
 * back to the baked-in table.
 *
 * [backendNames] is whatever `DiscoveryRepository.genres` last returned, keyed by id. It
 * is preferred because it is localized and current; the fallback is what keeps the UI
 * working before it arrives, or when the host serves no genre route at all.
 */
fun resolveGenreName(id: Int, type: MediaType?, backendNames: Map<Int, String>): String? =
    backendNames[id]?.takeIf { it.isNotBlank() } ?: TmdbGenres.nameOf(id, type)
