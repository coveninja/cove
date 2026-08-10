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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.coveninja.cove.shared.data.AppGraph
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.components.media.card.MediaCard
import com.coveninja.cove.ui.components.media.details.MediaDetailsSharedOverlay
import com.coveninja.cove.ui.components.media.details.MediaSharedKey
import com.coveninja.cove.ui.components.media.details.MediaSharedPart
import com.coveninja.cove.ui.components.media.drag.MediaDragPayload
import com.coveninja.cove.ui.components.media.drag.MediaDragPreview
import com.coveninja.cove.ui.components.navigation.NavBar
import com.coveninja.cove.ui.components.navigation.NavDestination
import com.coveninja.cove.ui.components.player.PlayerLayer
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.toMedia
import com.coveninja.cove.ui.model.toUiMedia
import com.coveninja.cove.ui.pages.explore.ExplorePage
import com.coveninja.cove.ui.pages.home.HomePage
import com.coveninja.cove.ui.pages.mylist.MyListPage
import com.coveninja.cove.ui.pages.profile.ProfilePage
import com.coveninja.cove.ui.pages.search.SearchPage
import com.coveninja.cove.ui.state.FullscreenController
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.LocalFullscreenController
import com.coveninja.cove.ui.state.LocalVideoPlayerHost
import com.coveninja.cove.ui.state.VideoPlayerHost
import com.coveninja.cove.ui.state.rememberDragSession
import com.coveninja.cove.ui.state.rememberLibraryIndex
import com.coveninja.cove.ui.state.rememberMediaActions
import com.coveninja.cove.ui.state.rememberMediaCatalog
import com.coveninja.cove.ui.state.rememberMediaDetailsState
import com.coveninja.cove.ui.state.rememberPlaybackSession
import com.coveninja.cove.ui.state.rememberWatchProgressIndex
import com.coveninja.cove.ui.state.toUiCategory
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.SharedMediaCard(
    media: Media,
    selectedMedia: Media?,
    listCategory: MyListCategory?,
    watchFraction: Float?,
    hasNewEpisodes: Boolean,
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
                    watchFraction = watchFraction,
                    hasNewEpisodes = hasNewEpisodes,
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
    // Null on any target without a player (currently everything but desktop); the
    // Watch button then reports that playback is unavailable instead of crashing.
    videoPlayerHost: VideoPlayerHost? = null,
    // Absent on mobile, where the player is already fullscreen.
    fullscreenController: FullscreenController? = null,
) {
    CompositionLocalProvider(
        LocalAppGraph provides graph,
        LocalVideoPlayerHost provides videoPlayerHost,
        LocalFullscreenController provides fullscreenController,
    ) {
        CoveAppContent()
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CoveAppContent() {
    val scope = rememberCoroutineScope()
    val graph = LocalAppGraph.current

    val catalog = rememberMediaCatalog()
    val index = rememberLibraryIndex()
    val watchProgress = rememberWatchProgressIndex()
    val actions = rememberMediaActions(index)
    val detailsState = rememberMediaDetailsState(catalog)
    val drag = rememberDragSession()
    val playback = rememberPlaybackSession()

    var selectedDestination by remember { mutableStateOf(NavDestination.Home) }
    var searchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf<String?>(null) }

    val underlyingNavAlpha by animateFloatAsState(
        targetValue = if (detailsState.selected == null) 1f else 0f,
        animationSpec = tween(120),
        label = "UnderlyingNavVisibility",
    )

    SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            val pageMediaCard: @Composable (Media, Modifier) -> Unit =
                { media, cardModifier ->
                    this@SharedTransitionLayout.SharedMediaCard(
                        media = media,
                        selectedMedia = detailsState.selected,
                        listCategory = index.categoryOf(media.id),
                        watchFraction = watchProgress.fractionFor(media.id),
                        hasNewEpisodes = index.hasUnwatchedAired(media.id),
                        onOpen = { detailsState.open(media) },
                        onSetListCategory = { actions.setListCategory(media, it) },
                        onRemoveFromList = { actions.removeFromList(media) },
                        onToggleWatched = { actions.toggleWatched(media) },
                        onDragStart = { payload, position ->
                            drag.start(payload, media, position, fromDetails = false)
                        },
                        onDrag = { drag.move(it) },
                        onDragEnd = { drag.finish(actions::setListCategory) },
                        onDragCancel = { drag.cancel() },
                        modifier = cardModifier,
                    )
                }

            // Home is intentionally edge-to-edge so FeaturedMedia can render beneath
            // the floating navigation bar. Other destinations retain top clearance.
            val pageModifier =
                if (selectedDestination == NavDestination.Home) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .fillMaxSize()
                        .safeContentPadding()
                        .padding(top = 96.dp)
                }

            Box(modifier = pageModifier) {
                when (selectedDestination) {
                    NavDestination.Home -> HomePage(
                        mediaCard = pageMediaCard,
                        onOpenMedia = { detailsState.open(it) },
                        onExplore = { selectedDestination = NavDestination.Explore },
                    )

                    NavDestination.MyList -> MyListPage(
                        mediaCard = pageMediaCard,
                        onExplore = { selectedDestination = NavDestination.Explore },
                        onOpenMedia = { detailsState.open(it) },
                        onPlayMedia = { playback.open(it) },
                        onPlayEpisode = { media, season, episode, title ->
                            playback.open(
                                media = media,
                                season = season,
                                episode = episode,
                                episodeTitle = title,
                            )
                        },
                    )

                    NavDestination.Explore -> ExplorePage(
                        mediaCard = pageMediaCard,
                    )

                    NavDestination.Search -> SearchPage(
                        query = submittedQuery,
                        mediaCard = pageMediaCard,
                        onOpenSearch = { searchMode = true },
                    )

                    NavDestination.Account -> ProfilePage()
                }
            }

            // Floating nav: it no longer consumes vertical layout space.
            NavBar(
                selectedDestination = selectedDestination,
                searchMode = searchMode,
                listCategoryMode = drag.draggedPayload != null,
                hoveredListCategory = drag.hoveredCategory,
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
                    drag.categoryBounds[category] = bounds
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .safeContentPadding()
                    .padding(top = 16.dp)
                    .graphicsLayer { alpha = underlyingNavAlpha }
                    .zIndex(100f),
            )

            val activePayload = drag.draggedPayload
            val activePosition = drag.dragPositionInRoot
            if (activePayload != null && activePosition != null) {
                MediaDragPreview(
                    media = activePayload,
                    positionInRoot = activePosition,
                    hoveredCategory = drag.hoveredCategory,
                    modifier = Modifier.zIndex(400f),
                )
            }

            val overlayEntry = detailsState.overlayMedia?.let { index.entryOf(it.id) }
            MediaDetailsSharedOverlay(
                media = detailsState.overlayMedia,
                // Hidden, not dismissed, while playback is up: the selection
                // survives, so closing the player brings the same sheet back
                // without refetching anything.
                visible = detailsState.selected != null && !playback.active,
                onDismiss = { detailsState.dismiss() },
                currentListCategory = overlayEntry?.status?.toUiCategory(),
                currentRating = overlayEntry?.rating?.roundToInt(),
                // The details sheet deliberately stays open underneath. Playback
                // covers it completely, and leaving it in place means closing the
                // player returns you to the title you were looking at — ready to
                // pick a different source rather than having to find it again.
                onWatch = { media -> playback.open(media) },
                onChooseSource = { media -> playback.open(media, forcePicker = true) },
                onListCategorySelected = actions::setListCategory,
                onRatingSelected = actions::setRating,
                onMediaSelected = { detailsState.open(it) },
                onEpisodeSelected = { media, season, episode ->
                    playback.open(
                        media = media,
                        season = season.number,
                        episode = episode.number,
                        episodeTitle = episode.title,
                    )
                },
                onEpisodeChooseSource = { media, season, episode ->
                    playback.open(
                        media = media,
                        season = season.number,
                        episode = episode.number,
                        episodeTitle = episode.title,
                        forcePicker = true,
                    )
                },
                onLoadEpisodes = { season ->
                    val active = detailsState.detailed ?: detailsState.selected
                    if (active == null) emptyList()
                    else actions.episodesFor(active, season)
                },
                onEpisodeWatchedChange = { media, season, episode, watched ->
                    actions.setEpisodeWatched(media, season, episode, watched)
                },
                onMediaDragStart = { payload, position ->
                    // Prefer the recommendation's own Media from moreLikeThis; fall back
                    // to the cross-page catalog (home/explore/search), then to the library,
                    // which is the only source for a saved title no feed happens to list.
                    val source = detailsState.detailed
                        ?.moreLikeThis
                        .orEmpty()
                        .firstOrNull { it.id == payload.mediaId }
                        ?.toMedia()
                        ?: catalog.domainByUiId[payload.mediaId]?.toUiMedia()
                        ?: index.entryOf(payload.mediaId)?.toUiMedia()
                    drag.start(payload, source, position, fromDetails = true)
                },
                onMediaDrag = { drag.move(it) },
                onMediaDragEnd = { drag.finish(actions::setListCategory) },
                onMediaDragCancel = { drag.cancel() },
                modifier = Modifier.zIndex(200f),
            )

            detailsState.error?.let { message ->
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
                visible = drag.detailsDragActive,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(300f),
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
                        hoveredListCategory = drag.hoveredCategory,
                        searchQuery = searchQuery,
                        onSearchQueryChange = {},
                        onOpenSearch = {},
                        onCloseSearch = {},
                        onSubmitSearch = {},
                        onDestinationSelected = {},
                        onListCategoryBoundsChanged = { category, bounds ->
                            if (drag.detailsDragActive) {
                                drag.categoryBounds[category] = bounds
                            }
                        },
                    )
                }
            }

            // Above every other layer: playback owns the window while it is open.
            PlayerLayer(
                session = playback,
                modifier = Modifier.zIndex(500f),
            )
        }
    }
}
