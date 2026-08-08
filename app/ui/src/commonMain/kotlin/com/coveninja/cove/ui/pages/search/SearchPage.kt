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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.pages.common.PageEmptyState
import com.coveninja.cove.ui.pages.common.PageHeader
import com.coveninja.cove.ui.model.Media

@Composable
fun SearchPage(
    query: String?,
    media: List<Media>,
    mediaCard: @Composable (Media, Modifier) -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val normalizedQuery = query.orEmpty().trim()
    val results = if (normalizedQuery.isBlank()) {
        emptyList()
    } else {
        media.filter { item ->
            listOfNotNull(
                item.title,
                item.name,
                item.overview,
                item.type?.label,
            ).any { value -> value.contains(normalizedQuery, ignoreCase = true) } ||
                item.genres.any { genre -> genre.contains(normalizedQuery, ignoreCase = true) } ||
                item.cast.any { member ->
                    member.name.contains(normalizedQuery, ignoreCase = true) ||
                        member.character?.contains(normalizedQuery, ignoreCase = true) == true
                }
        }
    }

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

        when {
            normalizedQuery.isBlank() -> {
                PageEmptyState(
                    iconName = "lucide:search",
                    title = "What do you want to watch?",
                    message = "Open search above and try a title, genre, actor, or character.",
                    actionLabel = "Start searching",
                    onAction = onOpenSearch,
                    modifier = Modifier.weight(1f),
                )
            }

            results.isEmpty() -> {
                PageEmptyState(
                    iconName = "lucide:file-question",
                    title = "No results found",
                    message = "Try a shorter title, another genre, or a cast member's name.",
                    actionLabel = "Search again",
                    onAction = onOpenSearch,
                    modifier = Modifier.weight(1f),
                )
            }

            else -> {
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
}
