package com.coveninja.cove.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.coveninja.cove.api.*
import com.coveninja.cove.sync.SyncCoordinator
import kotlinx.coroutines.*

data class HeroItem(
    val media: Media,
    val backdropUrl: String,
    val logoUrl: String,
)

class HomeViewModel : ViewModel() {
    data class MediaRow(val title: String, val items: List<Media>)

    var continueWatching by mutableStateOf<List<Pair<LibraryEntry, WatchProgress?>>>(emptyList())
        private set
    var rows by mutableStateOf<List<MediaRow>>(emptyList())
        private set
    var heroItems by mutableStateOf<List<HeroItem>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set

    init { load() }

    fun load() {
        viewModelScope.launch {
            loading = true

            // Continue watching: library entries with "watching" status + progress
            val library: List<LibraryEntry> = try {
                CoveApiClient.get("/library")
            } catch (e: Exception) { emptyList() }

            val cwEntries = library.filter { it.status == "watching" }
            val cwWithProgress = cwEntries.mapNotNull { entry ->
                val prog = try {
                    CoveApiClient.getOrNull<WatchProgress>(
                        "/library/progress?tmdb_id=${entry.tmdbId}&media_type=${entry.mediaType}"
                    )
                } catch (e: Exception) { null }
                if (prog != null && !prog.completed) entry to prog else null
            }
            continueWatching = cwWithProgress

            // Hero: fetch discover items then decorate with images in parallel.
            val heroMedia: List<Media> = try {
                CoveApiClient.get("/discover?type=all&limit=10")
            } catch (_: Exception) { emptyList() }

            if (heroMedia.isNotEmpty()) {
                heroItems = coroutineScope {
                    heroMedia.map { media ->
                        async {
                            val images = try {
                                CoveApiClient.get<MediaImages>(
                                    "/images?id=${media.id}&type=${media.mediaType}"
                                )
                            } catch (_: Exception) { null }
                            val backdrop = images?.backdrops?.firstOrNull()
                                ?.url?.let { CoveApiClient.resolveImgUrl(it) } ?: ""
                            val logo = (images?.logos?.firstOrNull { it.iso == "en" }
                                ?: images?.logos?.firstOrNull())
                                ?.url?.let { CoveApiClient.resolveImgUrl(it) } ?: ""
                            HeroItem(media, backdrop, logo)
                        }
                    }.awaitAll()
                }
            }

            // Discovery rows
            val newRows = mutableListOf<MediaRow>()
            try {
                val discover: List<Media> = CoveApiClient.get("/discover?type=all&limit=20")
                if (discover.isNotEmpty()) newRows.add(MediaRow("Based on your tastes", discover))
            } catch (_: Exception) {}
            try {
                val movies: List<Media> = CoveApiClient.get("/discover?type=movie&limit=20")
                if (movies.isNotEmpty()) newRows.add(MediaRow("Popular movies", movies))
            } catch (_: Exception) {}
            try {
                val tv: List<Media> = CoveApiClient.get("/discover?type=tv&limit=20")
                if (tv.isNotEmpty()) newRows.add(MediaRow("Popular TV", tv))
            } catch (_: Exception) {}
            rows = newRows
            loading = false
        }
    }
}

@Composable
fun HomeScreen(
    onOpenDetail: (Media) -> Unit,
    onStreamsRequested: (Media, Int?, Int?) -> Unit = { _, _, _ -> },
) {
    val vm: HomeViewModel = viewModel()

    // Reload when SyncCoordinator detects a remote library change.
    val libraryVersion by SyncCoordinator.libraryVersion.collectAsState()
    LaunchedEffect(libraryVersion) {
        if (libraryVersion > 0) vm.load()
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // Hero pager — first item, scrolls away with content like desktop.
        if (vm.heroItems.isNotEmpty()) {
            item {
                HeroPager(
                    items = vm.heroItems,
                    onOpenDetail = onOpenDetail,
                    onPlayRequested = { media -> onStreamsRequested(media, null, null) },
                )
            }
        }

        if (vm.continueWatching.isNotEmpty()) {
            item {
                SectionHeader("Continue Watching")
                MediaRow(vm.continueWatching.map { it.first.toMedia() }, onOpenDetail)
            }
        }

        if (vm.loading && vm.rows.isEmpty() && vm.continueWatching.isEmpty()) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }

        if (!vm.loading && vm.rows.isEmpty() && vm.continueWatching.isEmpty()) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Add titles to your library to see recommendations here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        items(vm.rows) { row ->
            SectionHeader(row.title)
            MediaRow(row.items, onOpenDetail)
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HeroPager(
    items: List<HeroItem>,
    onOpenDetail: (Media) -> Unit,
    onPlayRequested: (Media) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { items.size })

    // Auto-advance every 8 s; pauses while the user is dragging.
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) {
            delay(8_000L)
            val next = (pagerState.currentPage + 1) % items.size
            pagerState.animateScrollToPage(next)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // ~60 % of screen width — matches Netflix-like portrait hero feel.
            .aspectRatio(16f / 10f),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            HeroPage(
                item = items[page],
                onOpenDetail = onOpenDetail,
                onPlayRequested = onPlayRequested,
            )
        }

        // Page indicator dots — sit above the pager content.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(items.size) { i ->
                val selected = i == pagerState.currentPage
                val width by animateDpAsState(
                    targetValue = if (selected) 16.dp else 6.dp,
                    label = "dot-width",
                )
                Box(
                    modifier = Modifier
                        .width(width)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) Color.White
                            else Color.White.copy(alpha = 0.4f)
                        ),
                )
            }
        }
    }
}

@Composable
fun HeroPage(
    item: HeroItem,
    onOpenDetail: (Media) -> Unit,
    onPlayRequested: (Media) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Full-bleed backdrop; fall back to poster if no backdrop loaded.
        val imageUrl = item.backdropUrl.ifBlank {
            CoveApiClient.resolveImgUrl(item.media.posterPath)
        }
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Bottom scrim gradient so text is legible over any image.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.55f to Color.Black.copy(alpha = 0.55f),
                            1.0f to Color.Black.copy(alpha = 0.92f),
                        ),
                    )
                ),
        )

        // Top scrim so status-bar icons stay legible where the hero now bleeds
        // behind the status bar.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .height(96.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.35f),
                            Color.Transparent,
                        ),
                    )
                ),
        )

        // Content pinned to the bottom-start.
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, end = 16.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Logo image if available, otherwise text title.
            if (item.logoUrl.isNotBlank()) {
                AsyncImage(
                    model = item.logoUrl,
                    contentDescription = item.media.displayTitle,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                    modifier = Modifier
                        .height(52.dp)
                        .fillMaxWidth(0.6f),
                )
            } else {
                Text(
                    item.media.displayTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 2-line overview.
            if (item.media.overview.isNotBlank()) {
                Text(
                    item.media.overview,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Action buttons.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onPlayRequested(item.media) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play", fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = { onOpenDetail(item.media) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Default.Info, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Details")
                }
            }
        }
    }
}

fun LibraryEntry.toMedia() = Media(
    id = tmdbId, title = title, name = title,
    posterPath = posterPath, mediaType = mediaType, voteAverage = voteAverage,
)

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
fun MediaRow(items: List<Media>, onTap: (Media) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items) { media -> PosterCard(media, onTap) }
    }
}

@Composable
fun PosterCard(media: Media, onTap: (Media) -> Unit) {
    Column(modifier = Modifier.width(110.dp).clickable { onTap(media) }) {
        Card(
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            shape = MaterialTheme.shapes.small,
        ) {
            AsyncImage(
                model = CoveApiClient.resolveImgUrl(media.posterPath),
                contentDescription = media.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            media.displayTitle,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
