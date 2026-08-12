package com.coveninja.cove.shared.data

import com.coveninja.cove.shared.model.Media
import com.coveninja.cove.shared.model.MediaDetails
import com.coveninja.cove.shared.model.MediaImages
import com.coveninja.cove.shared.model.MediaVideos
import com.coveninja.cove.shared.model.PersonDetails
import com.coveninja.cove.shared.model.TvEpisode
import kotlinx.coroutines.flow.StateFlow

sealed interface HomeState {
    data object Loading : HomeState
    data class Ready(val items: List<Media>) : HomeState
    data class Failed(val message: String) : HomeState
}

sealed interface ExploreState {
    data object Loading : ExploreState
    data class Ready(val movies: List<Media>, val tv: List<Media>) : ExploreState
    data class Failed(val message: String) : ExploreState
}

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState

    /**
     * [people] is defaulted rather than required: a host that cannot search people —
     * a compatibility backend predating it — reports titles alone rather than failing.
     */
    data class Ready(
        val results: List<Media>,
        val people: List<PersonDetails> = emptyList(),
    ) : SearchState

    data class Failed(val message: String) : SearchState
}

data class ContentDetails(
    val media: Media,
    val details: MediaDetails,
    val images: MediaImages,
    val videos: MediaVideos,
    val similar: List<Media>,
)

interface ContentRepository {
    val home: StateFlow<HomeState>
    val explore: StateFlow<ExploreState>
    val searchResults: StateFlow<SearchState>
    suspend fun search(query: String)
    suspend fun details(media: Media): ContentDetails
    suspend fun person(id: Int): PersonDetails
    suspend fun episodes(id: Int, season: Int): List<TvEpisode>
}
