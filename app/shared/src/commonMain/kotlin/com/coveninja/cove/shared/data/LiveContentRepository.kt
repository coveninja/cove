package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.network.CoveApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LiveContentRepository(
    private val api: CoveApi,
    private val scope: CoroutineScope,
) : ContentRepository {

    private val _home = MutableStateFlow<HomeState>(HomeState.Loading)
    override val home: StateFlow<HomeState> = _home.asStateFlow()

    private val _explore = MutableStateFlow<ExploreState>(ExploreState.Loading)
    override val explore: StateFlow<ExploreState> = _explore.asStateFlow()

    private val _searchResults = MutableStateFlow<SearchState>(SearchState.Idle)
    override val searchResults: StateFlow<SearchState> = _searchResults.asStateFlow()

    init {
        scope.launch { loadDiscover() }
    }

    private suspend fun loadDiscover() {
        try {
            val movies = api.discover("movie", 20)
            val tv = api.discover("tv", 20)
            // /api/discover legitimately returns [] on OSS builds that lack the
            // discover build tag — an empty list is a valid Ready state, not a failure.
            _home.value = HomeState.Ready(movies + tv)
            _explore.value = ExploreState.Ready(movies = movies, tv = tv)
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error loading discover"
            _home.value = HomeState.Failed(msg)
            _explore.value = ExploreState.Failed(msg)
        }
    }

    override suspend fun search(query: String) {
        if (query.isBlank()) {
            _searchResults.value = SearchState.Idle
            return
        }
        _searchResults.value = SearchState.Loading
        try {
            val results = api.searchMulti(query)
            _searchResults.value = SearchState.Ready(results.movies + results.tv)
        } catch (e: Exception) {
            _searchResults.value = SearchState.Failed(e.message ?: "Unknown error searching")
        }
    }

    override suspend fun details(media: com.coveninja.cove.shared.model.Media): ContentDetails =
        coroutineScope {
            val type = requireNotNull(media.mediaType) {
                "Media type is required to load details"
            }
            val details = async { api.details(media.id, type) }
            val images = async { api.images(media.id, type) }
            val videos = async { api.videos(media.id, type) }
            val similar = async { api.similar(media.id, type) }

            ContentDetails(
                media = media,
                details = details.await(),
                images = images.await(),
                videos = videos.await(),
                similar = similar.await(),
            )
        }

    override suspend fun episodes(id: Int, season: Int) =
        api.tvEpisodes(id, season)
}
