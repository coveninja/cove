package com.coveninja.cove.ui.pages.mylist

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
import com.coveninja.cove.shared.data.LibraryState
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.pages.common.ChoicePill
import com.coveninja.cove.ui.pages.common.ChoicePillRow
import com.coveninja.cove.ui.pages.common.PageEmptyState
import com.coveninja.cove.ui.pages.common.PageError
import com.coveninja.cove.ui.pages.common.PageHeader
import com.coveninja.cove.ui.pages.common.PageLoading
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.rememberLibraryIndex
import com.coveninja.cove.ui.state.rememberMediaCatalog

@Composable
fun MyListPage(
    mediaCard: @Composable (Media, Modifier) -> Unit,
    onExplore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = LocalAppGraph.current
    val libraryState by graph.library.entries.collectAsState()

    when (val state = libraryState) {
        LibraryState.Loading -> PageLoading("Loading your list…")
        is LibraryState.Failed -> PageError("My List could not load", state.message)
        is LibraryState.Ready -> {
            val catalog = rememberMediaCatalog()
            val index = rememberLibraryIndex()
            // Enrich each library entry with the fuller domain object from the
            // cross-page catalog (home/explore/search), falling back to the thin
            // library-row version when no richer record is cached yet.
            val media = remember(state, catalog) {
                state.entries.map { catalog.enrich(it) }
            }
            MyListReady(
                media = media,
                assignments = index.categories,
                mediaCard = mediaCard,
                onExplore = onExplore,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun MyListReady(
    media: List<Media>,
    assignments: Map<String, MyListCategory>,
    mediaCard: @Composable (Media, Modifier) -> Unit,
    onExplore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedCategory by remember { mutableStateOf<MyListCategory?>(null) }
    val savedMedia = media.filter { item ->
        val category = assignments[item.id]
        category != null && (selectedCategory == null || category == selectedCategory)
    }

    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = "My List",
            subtitle = "Keep track of what you are watching and what comes next.",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        ChoicePillRow(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            ChoicePill(
                label = "All (${assignments.size})",
                selected = selectedCategory == null,
                iconName = "lucide:library",
                onClick = { selectedCategory = null },
            )
            MyListCategory.entries.forEach { category ->
                val count = assignments.values.count { it == category }
                ChoicePill(
                    label = "${category.label} ($count)",
                    selected = selectedCategory == category,
                    iconName = category.icon,
                    accentColor = category.accentColor,
                    onClick = {
                        selectedCategory = if (selectedCategory == category) null else category
                    },
                )
            }
        }
        Text(
            text = if (selectedCategory == null) {
                "All saved titles"
            } else {
                selectedCategory!!.label
            },
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )

        if (savedMedia.isEmpty()) {
            PageEmptyState(
                iconName = selectedCategory?.icon ?: "lucide:bookmark-plus",
                title = if (selectedCategory == null) {
                    "Your list is empty"
                } else {
                    "Nothing in ${selectedCategory!!.label}"
                },
                message = "Right-click a media card or drag it onto a list category in the navigation bar.",
                actionLabel = "Explore titles",
                onAction = onExplore,
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
                items(savedMedia, key = { item -> item.id }) { item ->
                    mediaCard(item, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
