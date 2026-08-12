package com.coveninja.cove.backend.content

import com.coveninja.cove.shared.data.ContentDetails
import com.coveninja.cove.shared.data.ContentRepository
import com.coveninja.cove.shared.data.ExploreState
import com.coveninja.cove.shared.data.HomeState
import com.coveninja.cove.shared.data.SearchState
import com.coveninja.cove.shared.model.Media
import com.coveninja.cove.shared.model.PersonDetails
import com.coveninja.cove.shared.model.TvEpisode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LocalContentRepository(
    private val catalog: MediaCatalog,
    private val scope: CoroutineScope,
) : ContentRepository {
    private val _home = MutableStateFlow<HomeState>(HomeState.Loading)
    override val home: StateFlow<HomeState> = _home.asStateFlow()

    private val _explore = MutableStateFlow<ExploreState>(ExploreState.Loading)
    override val explore: StateFlow<ExploreState> = _explore.asStateFlow()

    private val _searchResults = MutableStateFlow<SearchState>(SearchState.Idle)
    override val searchResults: StateFlow<SearchState> = _searchResults.asStateFlow()

    init {
        scope.launch { refreshDiscover() }
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
            val message = error.message ?: "Unknown error loading discover"
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
            _searchResults.value = SearchState.Failed(error.message ?: "Unknown error searching")
        }
    }

    override suspend fun details(media: Media): ContentDetails = coroutineScope {
        val type = requireNotNull(media.mediaType) { "Media type is required to load details" }
        val details = async { catalog.details(media.id, type) }
        val images = async { catalog.images(media.id, type) }
        val videos = async { catalog.videos(media.id, type) }
        val similar = async { catalog.similar(media.id, type) }
        ContentDetails(media, details.await(), images.await(), videos.await(), similar.await())
    }

    override suspend fun person(id: Int): PersonDetails = catalog.person(id)

    override suspend fun episodes(id: Int, season: Int): List<TvEpisode> =
        catalog.episodes(id, season)
}
