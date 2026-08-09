package com.coveninja.cove.ui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.coveninja.cove.shared.data.ExploreState
import com.coveninja.cove.shared.data.HomeState
import com.coveninja.cove.shared.data.SearchState
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.toDomainMedia
import com.coveninja.cove.ui.model.toUiMedia
import com.coveninja.cove.shared.model.Media as DomainMedia

// Cross-page content index. MyListPage upgrades thin LibraryEntry objects using
// the fuller domain items seen in home/explore/search; the details fetch also
// needs the domain object behind a UI id.
@Stable
class MediaCatalog(private val domainItems: List<DomainMedia>) {
    // Built once in init — safe to access from many recompositions without re-hashing.
    val domainByUiId: Map<String, DomainMedia> = domainItems.associateBy { it.toUiMedia().id }

    fun domainFor(media: Media): DomainMedia = domainByUiId[media.id] ?: media.toDomainMedia()

    fun enrich(entry: LibraryEntry): Media {
        // Prefer the richer domain object if one exists for this entry's TMDB id + type;
        // fall back to constructing a thin UI object from the library row itself.
        val domain = domainItems.firstOrNull {
            it.id == entry.tmdbId && it.mediaType == entry.mediaType
        }
        return domain?.toUiMedia() ?: entry.toUiMedia()
    }
}

// Wrapping construction in remember(homeState, exploreState, searchState) prevents
// the catalog — and all its maps — from being rebuilt on every recomposition of
// the host composable (which includes every pointer move during a drag).
@Composable
fun rememberMediaCatalog(): MediaCatalog {
    val graph = LocalAppGraph.current
    val homeState by graph.content.home.collectAsState()
    val exploreState by graph.content.explore.collectAsState()
    val searchState by graph.content.searchResults.collectAsState()

    return remember(homeState, exploreState, searchState) {
        val homeDomain = (homeState as? HomeState.Ready)?.items.orEmpty()
        val exploreDomain = (exploreState as? ExploreState.Ready)
            ?.let { it.movies + it.tv }
            .orEmpty()
        val searchDomain = (searchState as? SearchState.Ready)?.results.orEmpty()
        val allItems = (homeDomain + exploreDomain + searchDomain)
            .distinctBy { it.mediaType to it.id }
        MediaCatalog(allItems)
    }
}
