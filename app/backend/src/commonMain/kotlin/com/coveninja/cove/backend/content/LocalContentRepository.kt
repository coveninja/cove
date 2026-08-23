package com.coveninja.cove.backend.content

import com.coveninja.cove.shared.data.ContentDetails
import com.coveninja.cove.shared.data.ContentArtwork
import com.coveninja.cove.shared.data.ContentRepository
import com.coveninja.cove.shared.data.ExploreState
import com.coveninja.cove.shared.data.HomeState
import com.coveninja.cove.shared.data.PluginMediaRequest
import com.coveninja.cove.shared.data.PluginRepository
import com.coveninja.cove.shared.data.SearchState
import com.coveninja.cove.shared.data.UnavailablePluginRepository
import com.coveninja.cove.shared.model.Media
import com.coveninja.cove.shared.model.MediaImage
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.shared.model.PersonDetails
import com.coveninja.cove.shared.model.TvEpisode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class LocalContentRepository(
    private val catalog: MediaCatalog,
    private val scope: CoroutineScope,
    localeChanges: Flow<String> = flowOf("en"),
    initialLocale: String = "en",
    private val plugins: PluginRepository = UnavailablePluginRepository,
) : ContentRepository {
    private val _presentationLocale = MutableStateFlow(initialLocale)
    override val presentationLocale: StateFlow<String> = _presentationLocale.asStateFlow()

    private val _home = MutableStateFlow<HomeState>(HomeState.Loading)
    override val home: StateFlow<HomeState> = _home.asStateFlow()

    private val _explore = MutableStateFlow<ExploreState>(ExploreState.Loading)
    override val explore: StateFlow<ExploreState> = _explore.asStateFlow()

    private val _searchResults = MutableStateFlow<SearchState>(SearchState.Idle)
    override val searchResults: StateFlow<SearchState> = _searchResults.asStateFlow()

    init {
        scope.launch {
            localeChanges.distinctUntilChanged().collectLatest { locale ->
                _presentationLocale.value = locale
                // Never leave a complete page of the previous locale visible while its
                // replacement is loading. Search is request-driven, so invalidate it.
                _home.value = HomeState.Loading
                _explore.value = ExploreState.Loading
                _searchResults.value = SearchState.Idle
                refreshDiscover()
            }
        }
    }

    suspend fun refreshDiscover() = coroutineScope {
        try {
            val movies = async { catalog.discover(com.coveninja.cove.shared.model.MediaType.Movie) }
            val tv = async { catalog.discover(com.coveninja.cove.shared.model.MediaType.Tv) }
            val movieItems = movies.await()
            val tvItems = tv.await()
            _home.value = HomeState.Ready(movieItems + tvItems)
            _explore.value = ExploreState.Ready(movieItems, tvItems)
        } catch (error: Exception) {
            val message = describeContentFailure(error, "Unknown error loading discover")
            _home.value = HomeState.Failed(message)
            _explore.value = ExploreState.Failed(message)
        }
    }

    override suspend fun search(query: String) {
        if (query.isBlank()) {
            _searchResults.value = SearchState.Idle
            return
        }
        _searchResults.value = SearchState.Loading
        try {
            val results = catalog.searchMulti(query)
            _searchResults.value = SearchState.Ready(
                results = (results.movies + results.tv).sortedByDescending(Media::popularity),
                people = results.people,
            )
        } catch (error: Exception) {
            _searchResults.value =
                SearchState.Failed(describeContentFailure(error, "Unknown error searching"))
        }
    }

    override suspend fun media(id: Int, type: MediaType): Media = catalog.media(id, type)

    override suspend fun artwork(media: Media): ContentArtwork {
        val type = requireNotNull(media.mediaType) { "Media type is required to load artwork" }
        return ContentArtwork(media, catalog.images(media.id, type))
    }

    override suspend fun details(media: Media): ContentDetails = coroutineScope {
        val type = requireNotNull(media.mediaType) { "Media type is required to load details" }
        val details = async { catalog.details(media.id, type) }
        val images = async { catalog.images(media.id, type) }
        val videos = async { catalog.videos(media.id, type) }
        val similar = async { catalog.similar(media.id, type) }
        val resolvedDetails = details.await()
        val resolvedImages = images.await()
        val augment = runCatching {
            plugins.augmentMetadata(
                PluginMediaRequest(
                    tmdbId = media.id,
                    mediaType = type,
                    imdbId = runCatching { catalog.imdbId(media.id, type) }.getOrDefault(""),
                    title = media.displayTitle,
                    year = media.displayDate?.take(4)?.toIntOrNull() ?: 0,
                ),
            )
        }.getOrDefault(emptyList())
        val overview = resolvedDetails.overview.ifBlank {
            augment.firstNotNullOfOrNull { it.overview?.takeIf(String::isNotBlank) }.orEmpty()
        }
        val posters = resolvedImages.posters.ifEmpty {
            augment.firstNotNullOfOrNull { it.posterUrl?.takeIf(String::isNotBlank) }
                ?.let { listOf(MediaImage(url = it)) }
                .orEmpty()
        }
        val backdrops = resolvedImages.backdrops.ifEmpty {
            augment.firstNotNullOfOrNull { it.backdropUrl?.takeIf(String::isNotBlank) }
                ?.let { listOf(MediaImage(url = it)) }
                .orEmpty()
        }
        ContentDetails(
            media,
            resolvedDetails.copy(overview = overview),
            resolvedImages.copy(posters = posters, backdrops = backdrops),
            videos.await(),
            similar.await(),
        )
    }

    override suspend fun person(id: Int): PersonDetails = catalog.person(id)

    override suspend fun episodes(id: Int, season: Int): List<TvEpisode> =
        catalog.episodes(id, season)
}
