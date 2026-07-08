package com.coveninja.cove.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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

enum class LibrarySortMode(val label: String) {
    RECENTLY_WATCHED("Recently watched"),
    RECENTLY_ADDED("Recently added"),
    TITLE_AZ("Title A–Z"),
    RATING("Rating"),
}

class LibraryViewModel : ViewModel() {
    var entries by mutableStateOf<List<LibraryEntry>>(emptyList())
    var loading by mutableStateOf(true)
    var refreshing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    // Filter/sort state — plain mutableStateOf; survives tab switches since VM is
    // activity-scoped.
    var statusFilter by mutableStateOf("all")
    var typeFilter by mutableStateOf("all")
    var sortMode by mutableStateOf(LibrarySortMode.RECENTLY_WATCHED)

    /** Entries after applying type + status filters and the active sort. */
    val filteredEntries: List<LibraryEntry>
        get() {
            var result = entries
            if (typeFilter != "all") result = result.filter { it.mediaType == typeFilter }
            if (statusFilter != "all") result = result.filter { it.status == statusFilter }
            return when (sortMode) {
                LibrarySortMode.RECENTLY_WATCHED -> result.sortedWith(
                    // nulls last: non-null lastWatchedAt (false < true) comes first, then desc by date.
                    compareBy<LibraryEntry> { it.lastWatchedAt == null }
                        .thenByDescending { it.lastWatchedAt ?: "" }
                )
                LibrarySortMode.RECENTLY_ADDED -> result.sortedByDescending { it.addedAt }
                LibrarySortMode.TITLE_AZ -> result.sortedBy { it.title.lowercase() }
                LibrarySortMode.RATING -> result.sortedByDescending { it.voteAverage }
            }
        }

    init { load() }

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            entries = try {
                CoveApiClient.get("/library")
            } catch (e: Exception) {
                error = e.message ?: "Failed to load library"
                emptyList()
            }
            loading = false
        }
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing = true
            error = null
            val result: List<LibraryEntry> = try {
                CoveApiClient.get("/library")
            } catch (e: Exception) {
                if (entries.isEmpty()) error = e.message ?: "Failed to load library"
                entries // keep existing on error
            }
            if (error == null) entries = result
            refreshing = false
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpenDetail: (Media) -> Unit) {
    val vm: LibraryViewModel = viewModel()

    // Reload when SyncCoordinator detects a remote library change.
    val libraryVersion by SyncCoordinator.libraryVersion.collectAsState()
    LaunchedEffect(libraryVersion) {
        if (libraryVersion > 0) vm.refresh()
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "My List",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp),
        )

        // ── Status filter chips (scrollable row) ─────────────────────────────
        val statusChips = listOf("all" to "All") +
            STATUS_ORDER.map { it to (STATUS_LABELS[it] ?: it) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            statusChips.forEach { (key, label) ->
                // Counts from full unfiltered entries so numbers don't change with type filter.
                val count = if (key == "all") vm.entries.size
                            else vm.entries.count { it.status == key }
                FilterChip(
                    selected = vm.statusFilter == key,
                    onClick = { vm.statusFilter = key },
                    label = { Text("$label ($count)") },
                )
            }
        }

        // ── Type filter + sort dropdown ───────────────────────────────────────
        val controlHeight = 48.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .weight(1f)
                    .height(controlHeight),
            ) {
                SegmentedButton(
                    selected = vm.typeFilter == "all",
                    onClick = { vm.typeFilter = "all" },
                    shape = SegmentedButtonDefaults.itemShape(0, 3),
                    icon = {},
                ) { Text("All") }
                SegmentedButton(
                    selected = vm.typeFilter == "movie",
                    onClick = { vm.typeFilter = "movie" },
                    shape = SegmentedButtonDefaults.itemShape(1, 3),
                    icon = {},
                ) { Text("Movies") }
                SegmentedButton(
                    selected = vm.typeFilter == "tv",
                    onClick = { vm.typeFilter = "tv" },
                    shape = SegmentedButtonDefaults.itemShape(2, 3),
                    icon = {},
                ) { Text("TV") }
            }

            var sortExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = sortExpanded,
                onExpandedChange = { sortExpanded = it },
                modifier = Modifier.weight(1f),
            ) {
                OutlinedTextField(
                    value = vm.sortMode.label,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sortExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                        .height(controlHeight),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    colors = OutlinedTextFieldDefaults.colors(),
                )
                ExposedDropdownMenu(
                    expanded = sortExpanded,
                    onDismissRequest = { sortExpanded = false },
                ) {
                    LibrarySortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            onClick = { vm.sortMode = mode; sortExpanded = false },
                        )
                    }
                }
            }
        }

        // ── Content ──────────────────────────────────────────────────────────
        PullToRefreshBox(
            isRefreshing = vm.refreshing,
            onRefresh = { vm.refresh() },
            modifier = Modifier.weight(1f),
        ) {
            when {
                vm.loading -> PosterGridSkeleton()

                // Total failure with empty library.
                vm.error != null && vm.entries.isEmpty() -> {
                    ErrorRetryBox(
                        message = vm.error ?: "Failed to load library",
                        modifier = Modifier.fillMaxSize(),
                        onRetry = { vm.load() },
                    )
                }

                // Library is empty (no entries at all).
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

                // Active filter returns no results.
                vm.filteredEntries.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No titles match the current filter.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                // Status = "All" → keep grouped-by-status section headers.
                vm.statusFilter == "all" -> {
                    val groups = STATUS_ORDER.mapNotNull { status ->
                        val group = vm.filteredEntries.filter { it.status == status }
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

                // Specific status selected → flat grid (no section headers).
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(vm.filteredEntries) { entry ->
                            PosterCard(entry.toMedia(), onOpenDetail)
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Spacer(Modifier.height(80.dp))
                        }
                    }
                }
            }
        }
    }
}
