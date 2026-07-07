package com.coveninja.cove.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coveninja.cove.api.*
import com.coveninja.cove.sync.SyncCoordinator
import kotlinx.coroutines.launch

class LibraryViewModel : ViewModel() {
    var entries by mutableStateOf<List<LibraryEntry>>(emptyList())
    var loading by mutableStateOf(true)

    init { load() }

    fun load() {
        viewModelScope.launch {
            loading = true
            entries = try {
                CoveApiClient.get("/library")
            } catch (e: Exception) { emptyList() }
            loading = false
        }
    }
}

private val STATUS_ORDER = listOf("watching", "watch_later", "finished", "dropped")
private val STATUS_LABELS = mapOf(
    "watching" to "Watching",
    "watch_later" to "Watch Later",
    "finished" to "Finished",
    "dropped" to "Dropped",
)

@Composable
fun LibraryScreen(onOpenDetail: (Media) -> Unit) {
    val vm: LibraryViewModel = viewModel()

    // Reload when SyncCoordinator detects a remote library change.
    val libraryVersion by SyncCoordinator.libraryVersion.collectAsState()
    LaunchedEffect(libraryVersion) {
        if (libraryVersion > 0) vm.load()
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "My List",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp),
        )

        when {
            vm.loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            vm.entries.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Your library is empty.\nSearch for titles to add them.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            else -> {
                // Build ordered groups, skip empty ones.
                val groups = STATUS_ORDER.mapNotNull { status ->
                    val group = vm.entries.filter { it.status == status }
                    if (group.isNotEmpty()) status to group else null
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    groups.forEach { (status, groupEntries) ->
                        // Section header spans the full row width.
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                STATUS_LABELS[status] ?: status,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                            )
                        }
                        items(groupEntries) { entry ->
                            PosterCard(entry.toMedia(), onOpenDetail)
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Spacer(Modifier.height(80.dp))
                    }
                }
            }
        }
    }
}
