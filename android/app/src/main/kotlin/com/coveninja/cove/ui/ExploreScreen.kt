package com.coveninja.cove.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coveninja.cove.api.CoveApiClient
import com.coveninja.cove.api.Genre
import com.coveninja.cove.api.Media
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// One row of genre-specific content shown beneath the featured row.
data class ExploreGenreRow(
    val genre: Genre,
    val items: List<Media>,
    val loading: Boolean,
)

class ExploreViewModel : ViewModel() {
    var mediaType by mutableStateOf("movie")
        private set
    var genres by mutableStateOf<List<Genre>>(emptyList())
        private set
    var selectedGenreId by mutableStateOf<Int?>(null)
        private set
    var featuredItems by mutableStateOf<List<Media>>(emptyList())
        private set
    var featuredLoading by mutableStateOf(true)
        private set
    var genreRows by mutableStateOf<List<ExploreGenreRow>>(emptyList())
        private set
    // true until the first genre + row fetch for the current mediaType finishes
    var initialLoading by mutableStateOf(true)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var genreLoadJob: Job? = null
    private var featuredJob: Job? = null

    init {
        loadForType("movie")
    }

    @JvmName("changeMediaType")
    fun setMediaType(type: String) {
        if (type == mediaType) return
        mediaType = type
        selectedGenreId = null
        loadForType(type)
    }

    @JvmName("changeGenre")
    fun setGenre(genreId: Int?) {
        selectedGenreId = genreId
        loadFeatured(mediaType, genreId)
    }

    /** Re-run the current type's full load — called by retry button. */
    fun reload() {
        loadForType(mediaType)
    }

    /**
     * Pull-to-refresh: reload the featured row only (genre rows stay in place).
     * Sets [refreshing] around the network call so the PTR indicator dismisses
     * when done.
     */
    fun refresh() {
        if (refreshing) return
        viewModelScope.launch {
            refreshing = true
            error = null
            val path = if (selectedGenreId != null)
                "/discover/genre?type=$mediaType&genre=$selectedGenreId&limit=20"
            else
                "/discover?type=$mediaType&limit=20"
            val result: List<Media> = try {
                CoveApiClient.get(path)
            } catch (e: Exception) {
                // Only surface as error when we have nothing else to show.
                if (featuredItems.isEmpty() && genreRows.all { it.items.isEmpty() }) {
                    error = e.message ?: "Failed to load"
                }
                featuredItems // keep existing
            }
            if (error == null) featuredItems = result
            refreshing = false
        }
    }

    private fun loadFeatured(type: String, genreId: Int?) {
        featuredJob?.cancel()
        featuredJob = viewModelScope.launch {
            featuredLoading = true
            featuredItems = emptyList()
            featuredItems = try {
                if (genreId != null) {
                    CoveApiClient.get("/discover/genre?type=$type&genre=$genreId&limit=20")
                } else {
                    CoveApiClient.get("/discover?type=$type&limit=20")
                }
            } catch (_: Exception) {
                emptyList()
            }
            featuredLoading = false
        }
    }

    private fun loadForType(type: String) {
        genreLoadJob?.cancel()
        initialLoading = true
        genreRows = emptyList()
        genres = emptyList()
        error = null
        loadFeatured(type, null)

        genreLoadJob = viewModelScope.launch {
            val fetchedGenres: List<Genre> = try {
                CoveApiClient.get("/genres?type=$type")
            } catch (e: Exception) {
                // Genre fetch failed — if featured also fails we'll show an error.
                error = e.message ?: "Failed to load genres"
                initialLoading = false
                return@launch
            }
            genres = fetchedGenres

            val top8 = fetchedGenres.take(8)
            genreRows = top8.map { ExploreGenreRow(it, emptyList(), true) }
            initialLoading = false

            // Fan-out: load each genre row in parallel (structured — children of genreLoadJob).
            top8.forEach { genre ->
                launch {
                    val items: List<Media> = try {
                        CoveApiClient.get("/discover/genre?type=$type&genre=${genre.id}&limit=20")
                    } catch (_: Exception) {
                        emptyList()
                    }
                    genreRows = genreRows.map { r ->
                        if (r.genre.id == genre.id) r.copy(items = items, loading = false) else r
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(onOpenDetail: (Media) -> Unit) {
    val vm: ExploreViewModel = viewModel()

    Column(Modifier.fillMaxSize()) {
        Text(
            "Explore",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp),
        )

        // OutlinedTextField enforces a ~56dp natural height; matching it here
        // keeps the segmented buttons and the genre dropdown the same height and
        // stops the dropdown's text from being vertically clipped.
        val controlHeight = 56.dp

        // Controls: Movies/TV segmented button + genre dropdown
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .weight(1f)
                    .height(controlHeight),
            ) {
                SegmentedButton(
                    selected = vm.mediaType == "movie",
                    onClick = { vm.setMediaType("movie") },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    // Suppress the default selected-state checkmark so only the
                    // media-type icon shows.
                    icon = {},
                ) { Icon(Icons.Filled.Movie, contentDescription = "Movies") }
                SegmentedButton(
                    selected = vm.mediaType == "tv",
                    onClick = { vm.setMediaType("tv") },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = {},
                ) { Icon(Icons.Filled.Tv, contentDescription = "TV Shows") }
            }

            var dropdownExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = dropdownExpanded,
                onExpandedChange = { dropdownExpanded = it },
                modifier = Modifier.weight(1f),
            ) {
                OutlinedTextField(
                    value = vm.genres.find { it.id == vm.selectedGenreId }?.name ?: "All genres",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                        .height(controlHeight),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    // no label -> saves the vertical space reserved for the floating label
                    colors = OutlinedTextFieldDefaults.colors(),
                )
                ExposedDropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("All genres") },
                        onClick = { vm.setGenre(null); dropdownExpanded = false },
                    )
                    vm.genres.forEach { genre ->
                        DropdownMenuItem(
                            text = { Text(genre.name) },
                            onClick = { vm.setGenre(genre.id); dropdownExpanded = false },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        PullToRefreshBox(
            isRefreshing = vm.refreshing,
            onRefresh = { vm.refresh() },
            modifier = Modifier.weight(1f),
        ) {
            when {
                // Initial load — show shimmer skeletons instead of the full-screen spinner.
                // During a PTR refresh we keep showing the content layer so existing rows
                // remain visible while the network call runs.
                vm.initialLoading && !vm.refreshing -> LazyColumn(Modifier.fillMaxSize()) {
                    repeat(3) { i ->
                        item(key = "skel-h-$i") {
                            val brush = shimmerBrush()
                            Box(
                                Modifier
                                    .padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                                    .width(140.dp)
                                    .height(18.dp)
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(brush),
                            )
                        }
                        item(key = "skel-r-$i") { MediaRowSkeleton() }
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }

                // Total failure: genres fetch failed, nothing to show.
                vm.error != null && vm.featuredItems.isEmpty() && vm.genreRows.isEmpty() -> {
                    ErrorRetryBox(
                        message = vm.error ?: "Failed to load",
                        modifier = Modifier.fillMaxSize(),
                        onRetry = { vm.reload() },
                    )
                }

                else -> {
                    val featuredHeader = run {
                        val g = vm.genres.find { it.id == vm.selectedGenreId }
                        when {
                            g != null && vm.mediaType == "movie" -> "${g.name} movies"
                            g != null -> "${g.name} shows"
                            vm.mediaType == "movie" -> "Popular movies"
                            else -> "Popular shows"
                        }
                    }

                    LazyColumn(Modifier.fillMaxSize()) {
                        // Featured row
                        item(key = "featured-header") {
                            SectionHeader(featuredHeader)
                        }
                        item(key = "featured-row") {
                            if (vm.featuredLoading) {
                                MediaRowSkeleton()
                            } else if (vm.featuredItems.isEmpty()) {
                                Box(
                                    Modifier.fillMaxWidth().height(120.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "Nothing here yet.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                MediaRow(vm.featuredItems, onOpenDetail)
                            }
                        }

                        // Per-genre rows, skipping whichever genre is currently featured
                        items(
                            vm.genreRows.filter { it.genre.id != vm.selectedGenreId },
                            key = { "genre-${it.genre.id}" },
                        ) { row ->
                            val rowHeader = if (vm.mediaType == "movie") {
                                "${row.genre.name} movies"
                            } else {
                                "${row.genre.name} shows"
                            }
                            SectionHeader(rowHeader)
                            if (row.loading) {
                                MediaRowSkeleton()
                            } else {
                                MediaRow(row.items, onOpenDetail)
                            }
                        }

                        item { Spacer(Modifier.height(80.dp)) }
                    }
                }
            }
        }
    }
}
