package com.coveninja.cove.backend.content

import com.coveninja.cove.shared.model.CatalogSort
import com.coveninja.cove.shared.model.Media
import com.coveninja.cove.shared.model.MediaDetails
import com.coveninja.cove.shared.model.MediaGenre
import com.coveninja.cove.shared.model.MediaImages
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.MediaVideos
import com.coveninja.cove.shared.model.PersonDetails
import com.coveninja.cove.shared.model.TvEpisode
import com.coveninja.cove.shared.model.TvSeason
import com.coveninja.cove.shared.network.SearchResultsDto

/** Raw metadata operations used by both the in-process UI graph and Ktor routes. */
interface MediaCatalog {
    suspend fun discover(type: MediaType, limit: Int = 20): List<Media>
    suspend fun searchMulti(query: String): SearchResultsDto
    suspend fun media(id: Int, type: MediaType): Media
    suspend fun details(id: Int, type: MediaType): MediaDetails
    suspend fun images(id: Int, type: MediaType): MediaImages
    suspend fun videos(id: Int, type: MediaType): MediaVideos
    suspend fun similar(id: Int, type: MediaType): List<Media>
    suspend fun seasons(id: Int): List<TvSeason>
    suspend fun episodes(id: Int, season: Int): List<TvEpisode>
    suspend fun imdbId(id: Int, type: MediaType): String

    /**
     * One person plus their combined filmography, which is what the person sheet is
     * built from. On the interface rather than on [TmdbClient] alone because Android
     * runs no HTTP host: in-process is the only way it can reach this at all.
     */
    suspend fun person(id: Int): PersonDetails

    /** The provider's own genre vocabulary for [type]. Ids are only meaningful alongside it. */
    suspend fun genres(type: MediaType): List<MediaGenre>

    /**
     * One page of the catalog narrowed by any combination of genre, keyword and person.
     *
     * This is the whole catalog, unfiltered by taste — [com.coveninja.cove.backend.discovery.DiscoveryService]
     * is what applies a profile on top. Browsing must be able to reach titles the viewer
     * has already saved; recommending must not.
     */
    suspend fun discoverFiltered(
        type: MediaType,
        genreId: Int? = null,
        keywordId: Int? = null,
        personId: Int? = null,
        sort: CatalogSort = CatalogSort.Popularity,
        page: Int = 1,
    ): List<Media>
}
