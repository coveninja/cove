package com.coveninja.cove.ui.pages.explore

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.ExploreState
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.MediaType
import com.coveninja.cove.ui.model.toUiMedia
import com.coveninja.cove.ui.pages.common.ChoicePill
import com.coveninja.cove.ui.pages.common.ChoicePillRow
import com.coveninja.cove.ui.pages.common.PageEmptyState
import com.coveninja.cove.ui.pages.common.PageError
import com.coveninja.cove.ui.pages.common.PageHeader
import com.coveninja.cove.ui.pages.common.PageLoading
import com.coveninja.cove.ui.state.LocalAppGraph

@Composable
fun ExplorePage(
    mediaCard: @Composable (Media, Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = LocalAppGraph.current
    val exploreState by graph.content.explore.collectAsState()

    when (val state = exploreState) {
        ExploreState.Loading -> PageLoading("Loading the catalog…")
        is ExploreState.Failed -> PageError("Explore could not load", state.message)
        is ExploreState.Ready -> {
            val media = remember(state) { (state.movies + state.tv).map { it.toUiMedia() } }
            ExploreReady(
                media = media,
                mediaCard = mediaCard,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun ExploreReady(
    media: List<Media>,
    mediaCard: @Composable (Media, Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedType by remember { mutableStateOf<MediaType?>(null) }
    var selectedGenre by remember { mutableStateOf<String?>(null) }

    val genres = remember(media) {
        media.flatMap { item -> item.genres }.distinct().sorted()
    }
    val filteredMedia = media.filter { item ->
        (selectedType == null || item.type == selectedType) &&
            (selectedGenre == null || selectedGenre in item.genres)
    }

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        PageHeader(
            title = "Explore",
            subtitle = "Browse the catalog by format and genre.",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        ChoicePillRow(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        ) {
            ChoicePill(
                label = "All",
                selected = selectedType == null,
                iconName = "lucide:layout-grid",
                onClick = { selectedType = null },
            )
            MediaType.entries.forEach { type ->
                ChoicePill(
                    label = type.label,
                    selected = selectedType == type,
                    onClick = { selectedType = type },
                )
            }
        }
        ChoicePillRow(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        ) {
            ChoicePill(
                label = "Every genre",
                selected = selectedGenre == null,
                onClick = { selectedGenre = null },
            )
            genres.forEach { genre ->
                ChoicePill(
                    label = genre,
                    selected = selectedGenre == genre,
                    onClick = {
                        selectedGenre = if (selectedGenre == genre) null else genre
                    },
                )
            }
        }
        Text(
            text = "${filteredMedia.size} ${if (filteredMedia.size == 1) "title" else "titles"}",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )

        if (filteredMedia.isEmpty()) {
            PageEmptyState(
                iconName = "lucide:filter-x",
                title = "No titles match",
                message = "Try a broader format or genre filter.",
                actionLabel = "Clear filters",
                onAction = {
                    selectedType = null
                    selectedGenre = null
                },
                modifier = Modifier.weight(1f),
            )
        } else {
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
                items(filteredMedia, key = { item -> item.id }) { item ->
                    mediaCard(item, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
