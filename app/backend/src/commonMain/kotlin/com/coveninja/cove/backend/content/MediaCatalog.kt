package com.coveninja.cove.backend.content

import com.coveninja.cove.backend.addons.AddonCatalogItem
import com.coveninja.cove.backend.addons.mediaType
import com.coveninja.cove.backend.addons.tmdbId
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
     * The reverse of [imdbId]: one `tt…` id resolved onto this catalog's own [Media], or
     * null when nothing matches.
     *
     * Needed because not every source speaks TMDB. Simkl answers its sync endpoints with
     * IMDB and TVDB ids and no TMDB id at all, while every screen downstream is keyed on a
     * numeric catalog id, so an entry that will not resolve has no card to become. The
     * default returns null for the implementations that cannot look an external id up.
     */
    suspend fun findByImdbId(imdbId: String, type: MediaType): Media? = null

    /**
     * One person plus their combined filmography, which is what the person sheet is
     * built from. On the interface rather than on [TmdbClient] alone because Android
     * runs no HTTP host: in-process is the only way it can reach this at all.
     */
    suspend fun person(id: Int): PersonDetails

    /** The provider's own genre vocabulary for [type]. Ids are only meaningful alongside it. */
    suspend fun genres(type: MediaType): List<MediaGenre>

    /**
     * One Stremio catalog entry resolved onto this catalog's own [Media], or null when it
     * cannot be. Addon catalogs name titles in their own vocabulary — an IMDB id, a
     * `tmdb:` id, sometimes a scheme this app has never heard of — and every screen
     * downstream is keyed on a numeric catalog id, so an entry that will not resolve has
     * no card to become and is dropped rather than half-drawn.
     *
     * On the interface for the same reason as [person]: Android runs no HTTP host, so
     * in-process is the only way it can reach this at all. The default handles the one
     * form any catalog can answer — its own ids, spelled `tmdb:` — and leaves the rest to
     * implementations that can look an external id up.
     */
    suspend fun resolveAddonMeta(meta: AddonCatalogItem): Media? {
        val type = meta.mediaType() ?: return null
        val id = meta.tmdbId() ?: return null
        return runCatching { media(id, type) }.getOrNull()
    }

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
