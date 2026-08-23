package com.coveninja.cove.backend.content

import com.coveninja.cove.shared.data.HomeState
import com.coveninja.cove.shared.data.PluginMediaRequest
import com.coveninja.cove.shared.data.PluginMetadataAugment
import com.coveninja.cove.shared.data.PluginRepository
import com.coveninja.cove.shared.data.UnavailablePluginRepository
import com.coveninja.cove.shared.model.CatalogSort
import com.coveninja.cove.shared.model.Media
import com.coveninja.cove.shared.model.MediaDetails
import com.coveninja.cove.shared.model.MediaGenre
import com.coveninja.cove.shared.model.MediaImages
import com.coveninja.cove.shared.model.MediaImage
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.MediaVideos
import com.coveninja.cove.shared.model.PersonDetails
import com.coveninja.cove.shared.model.TvEpisode
import com.coveninja.cove.shared.model.TvSeason
import com.coveninja.cove.shared.network.SearchResultsDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
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

    @Test
    fun `artwork fetches images without loading full details`() = runTest {
        val locale = MutableStateFlow("en")
        val catalog = FakeCatalog(locale)
        val repository = LocalContentRepository(
            catalog = catalog,
            scope = backgroundScope,
            localeChanges = locale,
            initialLocale = locale.value,
        )
        val media = Media(id = 42, title = "Hero", mediaType = MediaType.Movie)

        val artwork = repository.artwork(media)

        assertSame(media, artwork.media)
        assertEquals(1, catalog.imageCalls)
        assertEquals(0, catalog.detailCalls)
        assertEquals(0, catalog.videoCalls)
        assertEquals(0, catalog.similarCalls)
    }

    @Test
    fun `metadata plugins fill missing fields without replacing catalog values`() = runTest {
        val locale = MutableStateFlow("en")
        val catalog = FakeCatalog(locale)
        val plugins = object : PluginRepository by UnavailablePluginRepository {
            override val available = true
            override suspend fun augmentMetadata(request: PluginMediaRequest) = listOf(
                PluginMetadataAugment(
                    overview = "Plugin overview",
                    posterUrl = "https://images.test/plugin-poster.jpg",
                    backdropUrl = "https://images.test/plugin-backdrop.jpg",
                ),
            )
        }
        val repository = LocalContentRepository(
            catalog = catalog,
            scope = backgroundScope,
            localeChanges = locale,
            initialLocale = locale.value,
            plugins = plugins,
        )
        val media = Media(id = 42, title = "Hero", mediaType = MediaType.Movie)

        val augmented = repository.details(media)
        assertEquals("Plugin overview", augmented.details.overview)
        assertEquals("https://images.test/plugin-poster.jpg", augmented.images.posters.single().url)
        assertEquals("https://images.test/plugin-backdrop.jpg", augmented.images.backdrops.single().url)

        catalog.detailsResult = MediaDetails(overview = "Catalog overview")
        catalog.imagesResult = MediaImages(
            posters = listOf(MediaImage(url = "https://images.test/catalog-poster.jpg")),
            backdrops = listOf(MediaImage(url = "https://images.test/catalog-backdrop.jpg")),
        )
        val retained = repository.details(media)
        assertEquals("Catalog overview", retained.details.overview)
        assertEquals("https://images.test/catalog-poster.jpg", retained.images.posters.single().url)
        assertEquals("https://images.test/catalog-backdrop.jpg", retained.images.backdrops.single().url)
    }
}

private class FakeCatalog(private val locale: MutableStateFlow<String>) : MediaCatalog {
    var detailsResult = MediaDetails()
    var imagesResult = MediaImages()
    var detailCalls = 0
        private set
    var imageCalls = 0
        private set
    var videoCalls = 0
        private set
    var similarCalls = 0
        private set

    override suspend fun discover(type: MediaType, limit: Int): List<Media> =
        listOf(item(type))

    override suspend fun searchMulti(query: String) = SearchResultsDto()
    override suspend fun media(id: Int, type: MediaType): Media = item(type).copy(id = id)
    override suspend fun details(id: Int, type: MediaType): MediaDetails {
        detailCalls += 1
        return detailsResult
    }

    override suspend fun images(id: Int, type: MediaType): MediaImages {
        imageCalls += 1
        return imagesResult
    }

    override suspend fun videos(id: Int, type: MediaType): MediaVideos {
        videoCalls += 1
        return MediaVideos()
    }

    override suspend fun similar(id: Int, type: MediaType): List<Media> {
        similarCalls += 1
        return emptyList()
    }
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
