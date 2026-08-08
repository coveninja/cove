package com.coveninja.cove.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.coveninja.cove.shared.data.AppGraph
import com.coveninja.cove.shared.data.ExploreState
import com.coveninja.cove.shared.data.HomeState
import com.coveninja.cove.shared.data.LibraryState
import com.coveninja.cove.shared.data.SearchState
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.shared.model.Media as DomainMedia
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.components.media.card.MediaCard
import com.coveninja.cove.ui.components.media.details.MediaDetailsSharedOverlay
import com.coveninja.cove.ui.components.media.details.MediaSharedKey
import com.coveninja.cove.ui.components.media.details.MediaSharedPart
import com.coveninja.cove.ui.components.media.drag.MediaDragPayload
import com.coveninja.cove.ui.components.media.drag.MediaDragPreview
import com.coveninja.cove.ui.components.navigation.NavBar
import com.coveninja.cove.ui.components.navigation.NavDestination
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.toDomainMedia
import com.coveninja.cove.ui.model.toDomainType
import com.coveninja.cove.ui.model.toMedia
import com.coveninja.cove.ui.model.toUiEpisode
import com.coveninja.cove.ui.model.toUiMedia
import com.coveninja.cove.ui.pages.common.PageEmptyState
import com.coveninja.cove.ui.pages.explore.ExplorePage
import com.coveninja.cove.ui.pages.home.HomePage
import com.coveninja.cove.ui.pages.mylist.MyListPage
import com.coveninja.cove.ui.pages.profile.ProfilePage
import com.coveninja.cove.ui.pages.search.SearchPage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.SharedMediaCard(
    media: Media,
    selectedMedia: Media?,
    listCategory: MyListCategory?,
    onOpen: () -> Unit,
    onSetListCategory: (MyListCategory) -> Unit,
    onRemoveFromList: () -> Unit,
    onToggleWatched: () -> Unit,
    onDragStart: (MediaDragPayload, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.aspectRatio(2f / 3f)) {
        AnimatedVisibility(
            visible = selectedMedia?.id != media.id,
            modifier = Modifier.fillMaxSize(),
            enter = EnterTransition.None,
            exit = ExitTransition.None,
        ) {
            with(this@SharedMediaCard) {
                MediaCard(
                    media = media,
                    modifier = Modifier
                        .sharedBounds(
                            sharedContentState = rememberSharedContentState(
                                MediaSharedKey(media.id, MediaSharedPart.Container),
                            ),
                            animatedVisibilityScope = this@AnimatedVisibility,
                            boundsTransform = { _, _ ->
                                spring(
                                    dampingRatio = 0.82f,
                                    stiffness = Spring.StiffnessMediumLow,
                                )
                            },
                            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                            renderInOverlayDuringTransition = false,
                        )
                        .fillMaxSize(),
                    posterModifier = Modifier.sharedElement(
                        sharedContentState = rememberSharedContentState(
                            MediaSharedKey(media.id, MediaSharedPart.Poster),
                        ),
                        animatedVisibilityScope = this@AnimatedVisibility,
                        boundsTransform = { _, _ ->
                            spring(
                                dampingRatio = 0.82f,
                                stiffness = Spring.StiffnessMediumLow,
                            )
                        },
                        renderInOverlayDuringTransition = false,
                    ),
                    myListCategory = listCategory,
                    isWatched = listCategory == MyListCategory.Finished,
                    onClick = onOpen,
                    onSetMyListCategory = onSetListCategory,
                    onRemoveFromMyList = onRemoveFromList,
                    onMarkAsWatched = onToggleWatched,
                    onDragStart = onDragStart,
                    onDrag = onDrag,
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel,
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun CoveApp(
    graph: AppGraph,
    @Suppress("UNUSED_PARAMETER") videoSurface: @Composable () -> Unit = {},
) {
    val homeState by graph.content.home.collectAsState()
    val exploreState by graph.content.explore.collectAsState()
    val searchState by graph.content.searchResults.collectAsState()
    val libraryState by graph.library.entries.collectAsState()
    val settingsState by graph.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()

    val homeDomain = (homeState as? HomeState.Ready)?.items.orEmpty()
    val exploreDomain = (exploreState as? ExploreState.Ready)?.let { it.movies + it.tv }.orEmpty()
    val searchDomain = (searchState as? SearchState.Ready)?.results.orEmpty()
    val libraryEntries = (libraryState as? LibraryState.Ready)?.entries.orEmpty()
    val domainCatalog = (homeDomain + exploreDomain + searchDomain)
        .distinctBy { it.mediaType to it.id }
    val domainByUiId = domainCatalog.associateBy { it.toUiMedia().id }
    val homeMedia = homeDomain.map { it.toUiMedia() }
    val exploreMedia = exploreDomain.map { it.toUiMedia() }
    val searchMedia = searchDomain.map { it.toUiMedia() }
    val libraryMedia = libraryEntries.map { entry ->
        domainCatalog.firstOrNull {
            it.id == entry.tmdbId && it.mediaType == entry.mediaType
        }?.toUiMedia() ?: entry.toUiMedia()
    }
    val mediaById = (homeMedia + exploreMedia + searchMedia + libraryMedia)
        .associateBy(Media::id)
    val libraryById = libraryEntries.associateBy { it.toUiMedia().id }
    val listAssignments = libraryById.mapValues { (_, entry) ->
        entry.status.toUiCategory()
    }

    var selectedDestination by remember { mutableStateOf(NavDestination.Home) }
    var searchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf<String?>(null) }
    var selectedMedia by remember { mutableStateOf<Media?>(null) }
    var detailedMedia by remember { mutableStateOf<Media?>(null) }
    var retainedMedia by remember { mutableStateOf<Media?>(null) }
    var detailsError by remember { mutableStateOf<String?>(null) }

    var draggedPayload by remember { mutableStateOf<MediaDragPayload?>(null) }
    var draggedSource by remember { mutableStateOf<Media?>(null) }
    var dragPositionInRoot by remember { mutableStateOf<Offset?>(null) }
    var detailsDragActive by remember { mutableStateOf(false) }
    val categoryBounds = remember { mutableStateMapOf<MyListCategory, Rect>() }

    val hoveredListCategory = dragPositionInRoot?.let { position ->
        MyListCategory.entries.firstOrNull { category ->
            categoryBounds[category]?.contains(position) == true
        }
    }

    fun setListCategory(media: Media, category: MyListCategory) {
        val type = media.type.toDomainType() ?: return
        scope.launch {
            val existing = libraryById[media.id]
            if (category == MyListCategory.NotInterested) {
                if (existing != null) graph.library.remove(media.tmdbId, type)
                graph.library.setDismissed(media.tmdbId, type, true)
                return@launch
            }

            if (existing == null) {
                graph.library.add(
                    tmdbId = media.tmdbId,
                    mediaType = type,
                    title = media.title ?: media.name ?: "Untitled",
                    posterPath = media.posterUrl.orEmpty(),
                    voteAverage = media.rating ?: 0.0,
                )
            }
            category.toLibraryStatus()?.let { status ->
                graph.library.setStatus(media.tmdbId, type, status)
            }
        }
    }

    fun removeFromList(media: Media) {
        val type = media.type.toDomainType() ?: return
        scope.launch { graph.library.remove(media.tmdbId, type) }
    }

    fun setRating(media: Media, rating: Int) {
        val type = media.type.toDomainType() ?: return
        scope.launch {
            if (libraryById[media.id] == null) {
                graph.library.add(
                    tmdbId = media.tmdbId,
                    mediaType = type,
                    title = media.title ?: media.name ?: "Untitled",
                    posterPath = media.posterUrl.orEmpty(),
                    voteAverage = media.rating ?: 0.0,
                )
            }
            graph.library.setRating(media.tmdbId, type, rating.toDouble())
        }
    }

    fun cancelDrag() {
        detailsDragActive = false
        draggedPayload = null
        draggedSource = null
        dragPositionInRoot = null
        categoryBounds.clear()
    }

    fun finishDrag() {
        val source = draggedSource
        val category = hoveredListCategory
        if (source != null && category != null) setListCategory(source, category)
        cancelDrag()
    }

    LaunchedEffect(selectedMedia?.id) {
        val selected = selectedMedia ?: run {
            detailedMedia = null
            return@LaunchedEffect
        }
        detailedMedia = selected
        retainedMedia = selected
        detailsError = null

        val domain = domainByUiId[selected.id] ?: selected.toDomainMedia()
        runCatching { graph.content.details(domain).toUiMedia() }
            .onSuccess { loaded ->
                if (selectedMedia?.id == selected.id) {
                    detailedMedia = loaded
                    retainedMedia = loaded
                }
            }
            .onFailure { error ->
                if (selectedMedia?.id == selected.id) {
                    detailsError = error.message ?: "Unable to load media details"
                }
            }
    }

    val underlyingNavAlpha by animateFloatAsState(
        targetValue = if (selectedMedia == null) 1f else 0f,
        animationSpec = tween(120),
        label = "UnderlyingNavVisibility",
    )

    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Column(
                modifier = Modifier
                    .safeContentPadding()
                    .fillMaxSize()
                    .padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NavBar(
                    selectedDestination = selectedDestination,
                    searchMode = searchMode,
                    listCategoryMode = draggedPayload != null,
                    hoveredListCategory = hoveredListCategory,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onOpenSearch = { searchMode = true },
                    onCloseSearch = {
                        searchMode = false
                        searchQuery = submittedQuery.orEmpty()
                    },
                    onSubmitSearch = { query ->
                        submittedQuery = query
                        searchQuery = query
                        searchMode = false
                        selectedDestination = NavDestination.Search
                        scope.launch { graph.content.search(query) }
                    },
                    onDestinationSelected = { destination ->
                        searchMode = false
                        selectedDestination = destination
                    },
                    onListCategoryBoundsChanged = { category, bounds ->
                        categoryBounds[category] = bounds
                    },
                    modifier = Modifier.graphicsLayer { alpha = underlyingNavAlpha },
                )

                val pageMediaCard: @Composable (Media, Modifier) -> Unit =
                    { media, cardModifier ->
                        this@SharedTransitionLayout.SharedMediaCard(
                            media = media,
                            selectedMedia = selectedMedia,
                            listCategory = listAssignments[media.id],
                            onOpen = { selectedMedia = media },
                            onSetListCategory = { setListCategory(media, it) },
                            onRemoveFromList = { removeFromList(media) },
                            onToggleWatched = {
                                val next = if (listAssignments[media.id] == MyListCategory.Finished) {
                                    MyListCategory.Watching
                                } else {
                                    MyListCategory.Finished
                                }
                                setListCategory(media, next)
                            },
                            onDragStart = { payload, position ->
                                detailsDragActive = false
                                draggedPayload = payload
                                draggedSource = media
                                dragPositionInRoot = position
                            },
                            onDrag = { dragPositionInRoot = it },
                            onDragEnd = ::finishDrag,
                            onDragCancel = ::cancelDrag,
                            modifier = cardModifier,
                        )
                    }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 14.dp),
                ) {
                    when (selectedDestination) {
                        NavDestination.Home -> when (val state = homeState) {
                            HomeState.Loading -> LoadingPage("Loading your home feed…")
                            is HomeState.Failed -> ErrorPage("Home could not load", state.message)
                            is HomeState.Ready -> HomePage(
                                media = homeMedia,
                                mediaCard = pageMediaCard,
                                onOpenMedia = { selectedMedia = it },
                                onExplore = { selectedDestination = NavDestination.Explore },
                            )
                        }

                        NavDestination.MyList -> when (val state = libraryState) {
                            LibraryState.Loading -> LoadingPage("Loading your list…")
                            is LibraryState.Failed -> ErrorPage("My List could not load", state.message)
                            is LibraryState.Ready -> MyListPage(
                                media = libraryMedia,
                                assignments = listAssignments,
                                mediaCard = pageMediaCard,
                                onExplore = { selectedDestination = NavDestination.Explore },
                            )
                        }

                        NavDestination.Explore -> when (val state = exploreState) {
                            ExploreState.Loading -> LoadingPage("Loading the catalog…")
                            is ExploreState.Failed -> ErrorPage("Explore could not load", state.message)
                            is ExploreState.Ready -> ExplorePage(
                                media = exploreMedia,
                                mediaCard = pageMediaCard,
                            )
                        }

                        NavDestination.Search -> when (val state = searchState) {
                            SearchState.Loading -> LoadingPage("Searching for ${submittedQuery.orEmpty()}…")
                            is SearchState.Failed -> ErrorPage("Search failed", state.message)
                            SearchState.Idle -> SearchPage(
                                query = null,
                                media = emptyList(),
                                mediaCard = pageMediaCard,
                                onOpenSearch = { searchMode = true },
                            )
                            is SearchState.Ready -> SearchPage(
                                query = submittedQuery,
                                media = searchMedia,
                                mediaCard = pageMediaCard,
                                onOpenSearch = { searchMode = true },
                            )
                        }

                        NavDestination.Account -> when (val state = settingsState) {
                            SettingsState.Loading -> LoadingPage("Loading your preferences…")
                            is SettingsState.Failed -> ErrorPage("Preferences could not load", state.message)
                            is SettingsState.Ready -> ProfilePage(
                                autoplayNext = state.settings.autoPlay,
                                onAutoplayNextChange = { enabled ->
                                    scope.launch {
                                        graph.settings.update(state.settings.copy(autoPlay = enabled))
                                    }
                                },
                                hideSpoilers = state.settings.hideSpoilers,
                                onHideSpoilersChange = { enabled ->
                                    scope.launch {
                                        graph.settings.update(state.settings.copy(hideSpoilers = enabled))
                                    }
                                },
                                streamSelectionMode = state.settings.streamSelectionMode,
                                onStreamSelectionModeChange = { mode ->
                                    scope.launch {
                                        graph.settings.update(state.settings.copy(streamSelectionMode = mode))
                                    }
                                },
                            )
                        }
                    }
                }
            }

            val activePayload = draggedPayload
            val activePosition = dragPositionInRoot
            if (activePayload != null && activePosition != null) {
                MediaDragPreview(
                    media = activePayload,
                    positionInRoot = activePosition,
                    hoveredCategory = hoveredListCategory,
                    modifier = Modifier.zIndex(400f),
                )
            }

            val overlayMedia = if (selectedMedia != null) detailedMedia else retainedMedia
            val overlayEntry = overlayMedia?.let { libraryById[it.id] }
            MediaDetailsSharedOverlay(
                media = overlayMedia,
                visible = selectedMedia != null,
                onDismiss = { selectedMedia = null },
                currentListCategory = overlayEntry?.status?.toUiCategory(),
                currentRating = overlayEntry?.rating?.roundToInt(),
                onWatch = { selectedMedia = null },
                onListCategorySelected = ::setListCategory,
                onRatingSelected = ::setRating,
                onMediaSelected = { selectedMedia = it },
                onLoadEpisodes = { season ->
                    val active = detailedMedia ?: selectedMedia
                    if (active == null) {
                        emptyList()
                    } else {
                        val type = active.type.toDomainType()
                        val watchStates = if (type == null) {
                            emptyMap()
                        } else {
                            graph.library.episodeWatchStates(active.tmdbId, type)
                        }
                        graph.content.episodes(active.tmdbId, season.number).map { episode ->
                            episode.toUiEpisode(active.id, season.number).copy(
                                watched = watchStates[season.number to episode.episodeNumber] == true,
                            )
                        }
                    }
                },
                onEpisodeWatchedChange = { media, season, episode, watched ->
                    scope.launch {
                        graph.library.setEpisodeWatched(
                            tmdbId = media.tmdbId,
                            title = media.title ?: media.name ?: "Untitled",
                            posterPath = media.posterUrl.orEmpty(),
                            voteAverage = media.rating ?: 0.0,
                            season = season.number,
                            episode = episode.number,
                            runtimeMinutes = episode.runtimeMinutes,
                            watched = watched,
                        )
                    }
                },
                onMediaDragStart = { payload, position ->
                    detailsDragActive = true
                    draggedPayload = payload
                    draggedSource = (detailedMedia?.moreLikeThis.orEmpty())
                        .firstOrNull { it.id == payload.mediaId }
                        ?.toMedia()
                        ?: mediaById[payload.mediaId]
                    dragPositionInRoot = position
                },
                onMediaDrag = { dragPositionInRoot = it },
                onMediaDragEnd = ::finishDrag,
                onMediaDragCancel = ::cancelDrag,
                modifier = Modifier.zIndex(200f),
            )

            detailsError?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .zIndex(250f)
                        .padding(18.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            AnimatedVisibility(
                visible = detailsDragActive,
                modifier = Modifier.fillMaxSize().zIndex(300f),
                enter = fadeIn(tween(160)) + slideInVertically(
                    animationSpec = spring(
                        dampingRatio = 0.72f,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    initialOffsetY = { -it / 2 },
                ),
                exit = fadeOut(tween(110)) + slideOutVertically(
                    animationSpec = tween(140),
                    targetOffsetY = { -it / 3 },
                ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeContentPadding()
                        .padding(top = 16.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    NavBar(
                        selectedDestination = selectedDestination,
                        searchMode = false,
                        listCategoryMode = true,
                        hoveredListCategory = hoveredListCategory,
                        searchQuery = searchQuery,
                        onSearchQueryChange = {},
                        onOpenSearch = {},
                        onCloseSearch = {},
                        onSubmitSearch = {},
                        onDestinationSelected = {},
                        onListCategoryBoundsChanged = { category, bounds ->
                            if (detailsDragActive) categoryBounds[category] = bounds
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingPage(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                text = message,
                modifier = Modifier.padding(top = 14.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorPage(title: String, message: String) {
    PageEmptyState(
        iconName = "lucide:triangle-alert",
        title = title,
        message = message,
    )
}

private fun LibraryStatus.toUiCategory(): MyListCategory = when (this) {
    LibraryStatus.Watching -> MyListCategory.Watching
    LibraryStatus.WatchLater -> MyListCategory.WatchLater
    LibraryStatus.Finished -> MyListCategory.Finished
    LibraryStatus.Dropped -> MyListCategory.Dropped
}

private fun MyListCategory.toLibraryStatus(): LibraryStatus? = when (this) {
    MyListCategory.Watching -> LibraryStatus.Watching
    MyListCategory.WatchLater -> LibraryStatus.WatchLater
    MyListCategory.Finished -> LibraryStatus.Finished
    MyListCategory.Dropped -> LibraryStatus.Dropped
    MyListCategory.NotInterested -> null
}
