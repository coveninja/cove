package com.coveninja.cove.backend.content

import com.coveninja.cove.shared.data.HomeState
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class LocalContentRepositoryTest {
    @Test
    fun `locale changes replace discover presentation and are exposed to the UI`() = runTest {
        val locale = MutableStateFlow("tr")
        val repository = LocalContentRepository(
            catalog = FakeCatalog(locale),
            scope = backgroundScope,
            localeChanges = locale,
            initialLocale = locale.value,
        )

        // The host-provided locale is visible synchronously; the UI never starts in English.
        assertEquals("tr", repository.presentationLocale.value)
        runCurrent()

        assertEquals(
            listOf("tr-movie", "tr-tv"),
            assertIs<HomeState.Ready>(repository.home.value).items.map(Media::displayTitle),
        )

        locale.value = "de"
        runCurrent()

        assertEquals("de", repository.presentationLocale.value)
        assertEquals(
            listOf("de-movie", "de-tv"),
            assertIs<HomeState.Ready>(repository.home.value).items.map(Media::displayTitle),
        )
    }
}

private class FakeCatalog(private val locale: MutableStateFlow<String>) : MediaCatalog {
    override suspend fun discover(type: MediaType, limit: Int): List<Media> =
        listOf(item(type))

    override suspend fun searchMulti(query: String) = SearchResultsDto()
    override suspend fun media(id: Int, type: MediaType): Media = item(type).copy(id = id)
    override suspend fun details(id: Int, type: MediaType) = MediaDetails()
    override suspend fun images(id: Int, type: MediaType) = MediaImages()
    override suspend fun videos(id: Int, type: MediaType) = MediaVideos()
    override suspend fun similar(id: Int, type: MediaType): List<Media> = emptyList()
    override suspend fun seasons(id: Int): List<TvSeason> = emptyList()
    override suspend fun episodes(id: Int, season: Int): List<TvEpisode> = emptyList()
    override suspend fun imdbId(id: Int, type: MediaType) = "tt$id"
    override suspend fun person(id: Int) = PersonDetails(id = id, name = "Person")
    override suspend fun genres(type: MediaType): List<MediaGenre> = emptyList()
    override suspend fun discoverFiltered(
        type: MediaType,
        genreId: Int?,
        keywordId: Int?,
        personId: Int?,
        sort: CatalogSort,
        page: Int,
    ): List<Media> = listOf(item(type))

    private fun item(type: MediaType) = Media(
        id = if (type == MediaType.Movie) 1 else 2,
        title = "${locale.value}-${type.wireName}",
        posterPath = "/poster.jpg",
        mediaType = type,
    )
}
