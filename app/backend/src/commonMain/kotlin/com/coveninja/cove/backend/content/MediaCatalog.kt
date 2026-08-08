package com.coveninja.cove.backend.content

import com.coveninja.cove.shared.model.Media
import com.coveninja.cove.shared.model.MediaDetails
import com.coveninja.cove.shared.model.MediaImages
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.MediaVideos
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
}
