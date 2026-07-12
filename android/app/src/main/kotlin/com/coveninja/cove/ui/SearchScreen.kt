package com.coveninja.cove.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coveninja.cove.api.*
import kotlinx.coroutines.*

class SearchViewModel : ViewModel() {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Media>>(emptyList())
    var loading by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var searchJob: Job? = null

    fun onQueryChange(q: String) {
        query = q
        searchJob?.cancel()
        if (q.isBlank()) {
            results = emptyList()
            loading = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(300) // debounce
            loading = true
            error = null
            try {
                val r = CoveApiClient.get<SearchResults>(
                    "/search/multi?q=${java.net.URLEncoder.encode(q, "UTF-8")}"
                )
                results = r.movies + r.tv
            } catch (e: Exception) {
                error = e.message
            }
            loading = false
        }
    }

    /**
     * Immediately re-run the current query without debounce.  No-op when the
     * query is blank.  Called by pull-to-refresh and the error retry button.
     */
    fun refresh() {
        if (query.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            refreshing = true
            error = null
            try {
                val r = CoveApiClient.get<SearchResults>(
                    "/search/multi?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
                )
                results = r.movies + r.tv
            } catch (e: Exception) {
                error = e.message
            }
            refreshing = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onOpenDetail: (Media) -> Unit) {
    val vm: SearchViewModel = viewModel()
    val focusManager = LocalFocusManager.current

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = vm.query,
            onValueChange = { vm.onQueryChange(it) },
            placeholder = { Text("Search movies & TV…") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
            shape = CircleShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )

        PullToRefreshBox(
            isRefreshing = vm.refreshing,
            onRefresh = { vm.refresh() },
            modifier = Modifier.weight(1f),
        ) {
            when {
                vm.loading -> PosterGridSkeleton()

                vm.error != null -> ErrorRetryBox(
                    message = vm.error ?: "Unknown error",
                    modifier = Modifier.fillMaxSize(),
                    onRetry = { vm.refresh() },
                )

                vm.query.isBlank() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Search for movies and TV shows",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                vm.results.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No results for “${vm.query}”",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(vm.results) { media -> PosterCard(media, onOpenDetail) }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}
