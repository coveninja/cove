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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalUriHandler
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
import com.coveninja.cove.ui.components.navigation.NavBarClearance
import com.coveninja.cove.ui.components.navigation.NavBarPlacement
import com.coveninja.cove.ui.components.navigation.NavDestination
import com.coveninja.cove.ui.components.person.PersonDetailsSharedOverlay
import com.coveninja.cove.ui.components.player.InlineVideoPlayer
import com.coveninja.cove.ui.components.player.PlayerLayer
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.Person
import com.coveninja.cove.ui.model.toMedia
import com.coveninja.cove.ui.model.toUiMedia
import com.coveninja.cove.ui.pages.explore.ExplorePage
import com.coveninja.cove.ui.pages.home.HomePage
import com.coveninja.cove.ui.pages.mylist.MyListPage
import com.coveninja.cove.ui.pages.common.LocalPageHorizontalPadding
import com.coveninja.cove.ui.pages.common.LocalPageViewport
import com.coveninja.cove.ui.pages.common.PageViewport
import com.coveninja.cove.ui.pages.profile.ProfilePage
import com.coveninja.cove.ui.pages.search.SearchPage
import com.coveninja.cove.ui.state.FullscreenController
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.LocalFullscreenController
import com.coveninja.cove.ui.state.LocalVideoPlayerHost
import com.coveninja.cove.ui.state.PlaybackPresentation
import com.coveninja.cove.ui.state.VideoPlayerHost
import com.coveninja.cove.ui.state.rememberDragSession
import com.coveninja.cove.ui.state.rememberLibraryIndex
import com.coveninja.cove.ui.state.rememberMediaActions
import com.coveninja.cove.ui.state.rememberMediaCatalog
import com.coveninja.cove.ui.state.rememberMediaDetailsState
import com.coveninja.cove.ui.state.rememberPersonDetailsState
import com.coveninja.cove.ui.state.rememberPlaybackSession
import com.coveninja.cove.ui.state.rememberSearchSession
import com.coveninja.cove.ui.state.rememberWatchProgressIndex
import com.coveninja.cove.ui.state.toUiCategory
import com.coveninja.cove.ui.platform.PlatformBackHandler
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
    // Null on any target without a player; the Watch button then reports that
    // playback is unavailable instead of crashing.
    videoPlayerHost: VideoPlayerHost? = null,
    // Absent on mobile, where the player is already fullscreen.
    fullscreenController: FullscreenController? = null,
    // Desktop keeps the floating bar over the top edge; the Android host opts into
    // bottom placement without making a narrow desktop window behave like a phone.
    navBarPlacement: NavBarPlacement = NavBarPlacement.Top,
    // The Android host uses this to enter immersive mode while a details sheet owns
    // the screen. Desktop and other hosts can ignore it.
    onDetailsOverlayVisibilityChanged: (Boolean) -> Unit = {},
    // Android combines this with the details callback to manage immersive mode
    // and keep-awake state. Desktop does not need a window callback here.
    onFullscreenPlaybackVisibilityChanged: (Boolean) -> Unit = {},
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalAppGraph provides graph,
            LocalVideoPlayerHost provides videoPlayerHost,
            LocalFullscreenController provides fullscreenController,
            LocalPageHorizontalPadding provides if (navBarPlacement == NavBarPlacement.Bottom) {
                0.dp
            } else {
                24.dp
            },
            LocalPageViewport provides PageViewport(
                width = maxWidth,
                height = maxHeight,
                hasBottomNavigation = navBarPlacement == NavBarPlacement.Bottom,
            ),
        ) {
            CoveAppContent(
                navBarPlacement,
                onDetailsOverlayVisibilityChanged,
                onFullscreenPlaybackVisibilityChanged,
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CoveAppContent(
    navBarPlacement: NavBarPlacement,
    onDetailsOverlayVisibilityChanged: (Boolean) -> Unit,
    onFullscreenPlaybackVisibilityChanged: (Boolean) -> Unit,
) {
    val graph = LocalAppGraph.current
    val libraryState by graph.library.entries.collectAsState()
    val progressRows by graph.library.watchProgress.collectAsState()
    val homeState by graph.content.home.collectAsState()
    val exploreState by graph.content.explore.collectAsState()
    val searchState by graph.content.searchResults.collectAsState()
    val catalog = rememberMediaCatalog(homeState, exploreState, searchState)
    val index = rememberLibraryIndex(libraryState)
    val watchProgress = rememberWatchProgressIndex(progressRows)
    val actions = rememberMediaActions(index)
    val detailsState = rememberMediaDetailsState(catalog)
    val personState = rememberPersonDetailsState()
    val drag = rememberDragSession()
    val playback = rememberPlaybackSession()
    val search = rememberSearchSession()
    val videoPlayerHost = LocalVideoPlayerHost.current
    val uriHandler = LocalUriHandler.current
    val pageViewport = LocalPageViewport.current

    val currentOverlayVisibilityCallback = rememberUpdatedState(
        onDetailsOverlayVisibilityChanged,
    )
    val detailsOverlayVisible = detailsState.selected != null || personState.selected != null
    LaunchedEffect(detailsOverlayVisible) {
        currentOverlayVisibilityCallback.value(detailsOverlayVisible)
    }
    DisposableEffect(Unit) {
        onDispose { currentOverlayVisibilityCallback.value(false) }
    }
    val currentPlaybackVisibilityCallback = rememberUpdatedState(
        onFullscreenPlaybackVisibilityChanged,
    )
    val fullscreenPlaybackVisible = playback.active &&
        playback.presentation == PlaybackPresentation.Fullscreen
    LaunchedEffect(fullscreenPlaybackVisible) {
        currentPlaybackVisibilityCallback.value(fullscreenPlaybackVisible)
    }
    DisposableEffect(Unit) {
        onDispose { currentPlaybackVisibilityCallback.value(false) }
    }

    var selectedDestination by remember { mutableStateOf(NavDestination.Home) }
    var searchMode by remember { mutableStateOf(false) }

    /**
     * Opening a title, plus the one thing that has to happen alongside it on Search.
     *
     * A query that was typed rather than submitted is only known to be the one the viewer
     * meant once they act on its results — see [SearchSession]. This is where that is
     * observed, because every route into a details sheet passes through here.
     */
    val openMedia: (Media) -> Unit = { media ->
        if (selectedDestination == NavDestination.Search) {
            search.submitted?.let(search::rememberQuery)
        }
        detailsState.open(media)
    }

    /** Opening a person out of a search result says as much about the query as a title does. */
    val openPerson: (Person) -> Unit = { person ->
        if (selectedDestination == NavDestination.Search) {
            search.submitted?.let(search::rememberQuery)
        }
        personState.open(person)
    }

    val underlyingNavAlpha by animateFloatAsState(
        targetValue = if (detailsState.selected == null && personState.selected == null) 1f else 0f,
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
                        onOpen = { openMedia(media) },
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

            // Desktop heroes remain edge-to-edge beneath the top bar. Mobile pages respect
            // system insets and reserve a clear band behind the floating bottom bar.
            val heroDestination = selectedDestination == NavDestination.Home ||
                selectedDestination == NavDestination.Explore
            val pageModifier = when {
                navBarPlacement == NavBarPlacement.Top && heroDestination ->
                    Modifier.fillMaxSize()

                navBarPlacement == NavBarPlacement.Top ->
                    Modifier
                        .fillMaxSize()
                        .safeContentPadding()
                        .padding(top = NavBarClearance)

                else ->
                    Modifier
                        .fillMaxSize()
                        .safeContentPadding()
                        .padding(bottom = pageViewport.bottomNavigationClearance)
            }

            Box(modifier = pageModifier) {
                when (selectedDestination) {
                    NavDestination.Home -> HomePage(
                        homeState = homeState,
                        index = index,
                        watchProgress = watchProgress,
                        catalog = catalog,
                        mediaCard = pageMediaCard,
                        onOpenMedia = openMedia,
                        // Home's resume goes straight to playback rather than via the details
                        // sheet: the whole claim of "carry on watching" is that it is one press.
                        onPlayMedia = { playback.open(it) },
                        onExplore = { selectedDestination = NavDestination.Explore },
                        onOpenMyList = { selectedDestination = NavDestination.MyList },
                        navBarPlacement = navBarPlacement,
                    )

                    NavDestination.MyList -> MyListPage(
                        libraryState = libraryState,
                        index = index,
                        progress = watchProgress,
                        catalog = catalog,
                        mediaCard = pageMediaCard,
                        onExplore = { selectedDestination = NavDestination.Explore },
                        onOpenMedia = openMedia,
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
                        exploreState = exploreState,
                        index = index,
                        mediaCard = pageMediaCard,
                        onOpenMedia = openMedia,
                        navBarPlacement = navBarPlacement,
                    )

                    NavDestination.Search -> SearchPage(
                        searchState = searchState,
                        exploreState = exploreState,
                        index = index,
                        session = search,
                        mediaCard = pageMediaCard,
                        onOpenMedia = openMedia,
                        onPlayMedia = { playback.open(it) },
                        onOpenPerson = openPerson,
                    )

                    NavDestination.Account -> ProfilePage(
                        onNavigateBack = { selectedDestination = NavDestination.Home },
                    )
                }
            }

            // Floating nav: it no longer consumes vertical layout space.
            NavBar(
                selectedDestination = selectedDestination,
                searchMode = searchMode,
                listCategoryMode = drag.draggedPayload != null,
                hoveredListCategory = drag.hoveredCategory,
                searchQuery = search.query,
                // Deliberately draft-only: the overlay can be typed into from any page, and
                // debouncing here would spend upstream requests filling a page nobody has
                // navigated to. The page's own field is the one that searches as you type.
                onSearchQueryChange = search::setDraft,
                // Already on Search, the overlay would be a second field over the top of the
                // page's own. Hand focus to that one instead.
                onOpenSearch = {
                    if (selectedDestination == NavDestination.Search) {
                        search.requestFocus()
                    } else {
                        searchMode = true
                    }
                },
                onCloseSearch = {
                    searchMode = false
                    search.setDraft(search.submitted.orEmpty())
                },
                onSubmitSearch = { query ->
                    searchMode = false
                    selectedDestination = NavDestination.Search
                    search.submit(query)
                },
                onDestinationSelected = { destination ->
                    searchMode = false
                    selectedDestination = destination
                },
                onListCategoryBoundsChanged = { category, bounds ->
                    drag.categoryBounds[category] = bounds
                },
                placement = navBarPlacement,
                modifier = Modifier
                    .align(
                        if (navBarPlacement == NavBarPlacement.Top) {
                            Alignment.TopCenter
                        } else {
                            Alignment.BottomCenter
                        },
                    )
                    .safeContentPadding()
                    .padding(
                        top = if (navBarPlacement == NavBarPlacement.Top) 16.dp else 0.dp,
                        bottom = if (navBarPlacement == NavBarPlacement.Bottom) 16.dp else 0.dp,
                    )
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
            // An extra playing embedded belongs to the sheet it was started from,
            // so it follows that sheet: it is only drawn while the same title is
            // open, and it ends when the sheet does.
            val inlineExtra = playback.active &&
                playback.presentation == PlaybackPresentation.Inline &&
                playback.request?.extra != null &&
                playback.request?.media?.id == detailsState.overlayMedia?.id
            val closeInlineExtra = {
                if (playback.presentation == PlaybackPresentation.Inline) playback.close()
            }
            MediaDetailsSharedOverlay(
                media = detailsState.overlayMedia,
                // Hidden, not dismissed, while playback owns the window: the
                // selection survives, so closing the player brings the same sheet
                // back without refetching anything. An embedded video is the
                // opposite case — the sheet is the thing it is drawn inside.
                //
                // The person sheet is the same bargain: one sheet at a time, but the
                // title stays selected underneath so coming back costs nothing.
                visible = detailsState.selected != null &&
                    personState.selected == null &&
                    !(playback.active && playback.presentation == PlaybackPresentation.Fullscreen),
                onDismiss = {
                    closeInlineExtra()
                    detailsState.dismiss()
                },
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
                // Another title replaces this sheet, and an extra of the one being
                // left has nowhere to be drawn.
                onMediaSelected = {
                    closeInlineExtra()
                    detailsState.open(it)
                },
                // An extra playing inside this sheet has nowhere to be drawn once the
                // person sheet covers it.
                onPersonSelected = { person ->
                    closeInlineExtra()
                    personState.open(person)
                },
                videoPlayer = if (inlineExtra) {
                    { modifier -> InlineVideoPlayer(session = playback, modifier = modifier) }
                } else {
                    null
                },
                // Extras are YouTube pages, not media files. Where yt-dlp is
                // installed to unwrap one, it plays embedded in the sheet just
                // below; where it is not, the browser is the only thing that can
                // open it at all — and it is one click either way.
                onVideoSelected = { media, video ->
                    val url = video.url
                    if (videoPlayerHost?.playsWebVideos == true || url == null) {
                        // With no address, this reports why rather than doing nothing.
                        playback.openExtra(media, video)
                    } else {
                        // openUri throws where AWT has no browser to hand the link
                        // to — a machine with neither a desktop nor xdg-open.
                        runCatching { uriHandler.openUri(url) }.onFailure { error ->
                            playback.failExtra(
                                media = media,
                                video = video,
                                message = "Could not open this video in a browser: " +
                                    (error.message ?: "nothing here handles web links."),
                            )
                        }
                    }
                },
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

            PersonDetailsSharedOverlay(
                person = personState.overlayPerson,
                visible = personState.selected != null &&
                    !(playback.active && playback.presentation == PlaybackPresentation.Fullscreen),
                onDismiss = { personState.dismiss() },
                // Only shown when there is a title to go back to; the sheet underneath
                // is still selected, so dismissing is all it takes.
                backTitle = detailsState.selected?.let { selected ->
                    detailsState.detailed?.title
                        ?: detailsState.detailed?.name
                        ?: selected.title
                        ?: selected.name
                },
                // One sheet at a time in both directions: leaving for a title closes
                // the person rather than stacking a second sheet behind it.
                onMediaSelected = { media ->
                    personState.dismiss()
                    detailsState.open(media)
                },
                modifier = Modifier.zIndex(210f),
            )

            // A person that failed to load is only worth reporting while their sheet is
            // the one on screen.
            val overlayError = if (personState.selected != null) {
                personState.error
            } else {
                detailsState.error
            }
            overlayError?.let { message ->
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
                    initialOffsetY = {
                        if (navBarPlacement == NavBarPlacement.Top) -it / 2 else it / 2
                    },
                ),
                exit = fadeOut(tween(110)) + slideOutVertically(
                    animationSpec = tween(140),
                    targetOffsetY = {
                        if (navBarPlacement == NavBarPlacement.Top) -it / 3 else it / 3
                    },
                ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeContentPadding()
                        .padding(
                            top = if (navBarPlacement == NavBarPlacement.Top) 16.dp else 0.dp,
                            bottom = if (navBarPlacement == NavBarPlacement.Bottom) 16.dp else 0.dp,
                        ),
                    contentAlignment = if (navBarPlacement == NavBarPlacement.Top) {
                        Alignment.TopCenter
                    } else {
                        Alignment.BottomCenter
                    },
                ) {
                    NavBar(
                        selectedDestination = selectedDestination,
                        searchMode = false,
                        listCategoryMode = true,
                        hoveredListCategory = drag.hoveredCategory,
                        searchQuery = search.query,
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
                        placement = navBarPlacement,
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

    val mobileBackAction = resolveMobileBackAction(
        fullscreenPlayback = playback.active &&
            playback.presentation == PlaybackPresentation.Fullscreen,
        fullscreenExtra = playback.request?.extra != null,
        personOverlay = personState.selected != null,
        mediaOverlay = detailsState.selected != null,
        searchMode = searchMode,
        destination = selectedDestination,
    )
    PlatformBackHandler(
        enabled = navBarPlacement == NavBarPlacement.Bottom &&
            mobileBackAction != MobileBackAction.None,
    ) {
        when (mobileBackAction) {
            MobileBackAction.CollapseExtra -> playback.collapseToInline()
            MobileBackAction.ClosePlayback -> playback.close()
            MobileBackAction.ClosePerson -> personState.dismiss()
            MobileBackAction.CloseMedia -> {
                if (playback.presentation == PlaybackPresentation.Inline) playback.close()
                detailsState.dismiss()
            }
            MobileBackAction.CloseSearch -> {
                searchMode = false
                search.setDraft(search.submitted.orEmpty())
            }
            MobileBackAction.GoHome -> selectedDestination = NavDestination.Home
            MobileBackAction.None -> Unit
        }
    }
}

internal enum class MobileBackAction {
    CollapseExtra,
    ClosePlayback,
    ClosePerson,
    CloseMedia,
    CloseSearch,
    GoHome,
    None,
}

/** One ordered decision keeps Android Back consistent across every app-owned layer. */
internal fun resolveMobileBackAction(
    fullscreenPlayback: Boolean,
    fullscreenExtra: Boolean,
    personOverlay: Boolean,
    mediaOverlay: Boolean,
    searchMode: Boolean,
    destination: NavDestination,
): MobileBackAction = when {
    fullscreenPlayback && fullscreenExtra -> MobileBackAction.CollapseExtra
    fullscreenPlayback -> MobileBackAction.ClosePlayback
    personOverlay -> MobileBackAction.ClosePerson
    mediaOverlay -> MobileBackAction.CloseMedia
    searchMode -> MobileBackAction.CloseSearch
    destination != NavDestination.Home && destination != NavDestination.Account ->
        MobileBackAction.GoHome
    else -> MobileBackAction.None
}
