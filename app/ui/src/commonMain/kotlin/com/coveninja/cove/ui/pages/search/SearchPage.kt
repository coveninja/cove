package com.coveninja.cove.ui.pages.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.SearchState
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.toUiMedia
import com.coveninja.cove.ui.pages.common.PageEmptyState
import com.coveninja.cove.ui.pages.common.PageError
import com.coveninja.cove.ui.pages.common.PageHeader
import com.coveninja.cove.ui.pages.common.PageLoading
import com.coveninja.cove.ui.state.LocalAppGraph

@Composable
fun SearchPage(
    query: String?,
    mediaCard: @Composable (Media, Modifier) -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = LocalAppGraph.current
    val searchState by graph.content.searchResults.collectAsState()

    when (val state = searchState) {
        SearchState.Idle -> SearchIdle(
            onOpenSearch = onOpenSearch,
            modifier = modifier,
        )

        SearchState.Loading -> PageLoading("Searching for ${query.orEmpty()}…")

        is SearchState.Failed -> PageError("Search failed", state.message)

        is SearchState.Ready -> {
            // The backend already ran the search against this exact query — its results
            // are authoritative. Re-filtering against cast/genres here would silently
            // drop valid hits because list-level Media always has empty cast and genres
            // (those are only populated after a details() call).
            val results = remember(state) { state.results.map { it.toUiMedia() } }
            SearchResults(
                query = query,
                results = results,
                mediaCard = mediaCard,
                onOpenSearch = onOpenSearch,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun SearchIdle(
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = "Search",
            subtitle = "Search titles, genres, cast members, and characters.",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        PageEmptyState(
            iconName = "lucide:search",
            title = "What do you want to watch?",
            message = "Open search above and try a title, genre, actor, or character.",
            actionLabel = "Start searching",
            onAction = onOpenSearch,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SearchResults(
    query: String?,
    results: List<Media>,
    mediaCard: @Composable (Media, Modifier) -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val normalizedQuery = query.orEmpty().trim()

    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = if (normalizedQuery.isBlank()) "Search" else "Results for “$normalizedQuery”",
            subtitle = if (normalizedQuery.isBlank()) {
                "Search titles, genres, cast members, and characters."
            } else {
                "${results.size} ${if (results.size == 1) "match" else "matches"} in your catalog"
            },
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )

        if (results.isEmpty()) {
            PageEmptyState(
                iconName = "lucide:file-question",
                title = "No results found",
                message = "Try a shorter title, another genre, or a cast member's name.",
                actionLabel = "Search again",
                onAction = onOpenSearch,
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(
                text = "Best matches",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = 24.dp,
                    end = 24.dp,
                    top = 8.dp,
                    bottom = 36.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(results, key = { item -> item.id }) { item ->
                    mediaCard(item, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
