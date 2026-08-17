package com.coveninja.cove.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.zIndex
import com.coveninja.cove.shared.data.AppGraph
import com.coveninja.cove.shared.data.ExploreState
import com.coveninja.cove.shared.data.HomeState
import com.coveninja.cove.shared.data.SearchState
import com.coveninja.cove.ui.components.common.AppUpdateOverlay
import com.coveninja.cove.ui.components.navigation.NavDestination
import com.coveninja.cove.ui.components.player.PlayerLayer
import com.coveninja.cove.ui.pages.common.LocalPageBottomClearance
import com.coveninja.cove.ui.pages.common.LocalPageHorizontalPadding
import com.coveninja.cove.ui.pages.common.LocalPageViewport
import com.coveninja.cove.ui.pages.common.PageViewport
import com.coveninja.cove.ui.platform.PlatformBackHandler
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.LocalFullscreenController
import com.coveninja.cove.ui.state.LocalMotionPolicy
import com.coveninja.cove.ui.state.LocalVideoPlayerHost
import com.coveninja.cove.ui.state.MotionPolicy
import com.coveninja.cove.ui.state.PlaybackPresentation
import com.coveninja.cove.ui.state.VideoPlayerHost
import com.coveninja.cove.ui.state.rememberLibraryIndex
import com.coveninja.cove.ui.state.rememberLocalizedLibraryMedia
import com.coveninja.cove.ui.state.rememberMediaActions
import com.coveninja.cove.ui.state.rememberMediaCatalog
import com.coveninja.cove.ui.state.rememberMediaDetailsState
import com.coveninja.cove.ui.state.rememberPlaybackSession
import com.coveninja.cove.ui.state.rememberWatchProgressIndex
import com.coveninja.cove.ui.tv.components.TvComingSoonPage
import com.coveninja.cove.ui.tv.components.TvSideRail
import com.coveninja.cove.ui.tv.focus.TvKeyAction
import com.coveninja.cove.ui.tv.focus.toFocusDirection
import com.coveninja.cove.ui.tv.focus.tvKeyAction

/**
 * Cove for a television: the same graph and the same state, steered by a remote.
 *
 * A separate root from [com.coveninja.cove.ui.CoveApp] rather than a mode inside it. The shared
 * screens are pointer-shaped in a way no breakpoint reaches — hover reveals row controls,
 * secondary click opens menus, cards are dragged between library categories — and a viewer with
 * four arrows and an OK button can use none of it. What the two roots *do* share is everything
 * below the surface: one `AppGraph`, one catalog, one library index, one playback session, so a
 * title marked watched on a phone is watched here without either screen knowing about the other.
 */
@Composable
fun CoveTvApp(
    graph: AppGraph,
    // Null on a host with no player; the watch action then reports that playback is
    // unavailable rather than crashing.
    videoPlayerHost: VideoPlayerHost? = null,
    // The Android host uses this to hold the screen awake while something is playing.
    onFullscreenPlaybackVisibilityChanged: (Boolean) -> Unit = {},
    // Desktop closes its graph after a verified detached updater starts.
    onUpdateExitRequested: () -> Unit = {},
) {
    val performance by graph.device.performance.collectAsState()
    LaunchedEffect(graph.updates) { graph.updates.start() }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dimens = tvDimensFor(width = maxWidth, height = maxHeight)
        CompositionLocalProvider(
            LocalAppGraph provides graph,
            LocalMotionPolicy provides MotionPolicy(reducedMotion = performance.lowPerformanceMode),
            LocalVideoPlayerHost provides videoPlayerHost,
            // A television window is already the whole screen; there is nothing to toggle.
            LocalFullscreenController provides null,
            // Shared components that read the phone/desktop page metrics still have to land
            // somewhere sensible when the TV shell reuses them.
            LocalPageHorizontalPadding provides dimens.overscanHorizontal,
            LocalPageViewport provides PageViewport(
                width = maxWidth,
                height = maxHeight,
                hasBottomNavigation = false,
            ),
            LocalPageBottomClearance provides dimens.overscanVertical,
        ) {
            TvTheme(dimens) {
                TvAppContent(
                    onFullscreenPlaybackVisibilityChanged = onFullscreenPlaybackVisibilityChanged,
                    onUpdateExitRequested = onUpdateExitRequested,
                )
            }
        }
    }
}

@Composable
private fun TvAppContent(
    onFullscreenPlaybackVisibilityChanged: (Boolean) -> Unit,
    onUpdateExitRequested: () -> Unit,
) {
    val graph = LocalAppGraph.current
    val dimens = TvTheme.dimens
    val focusManager = LocalFocusManager.current

    val libraryState by graph.library.entries.collectAsState()
    val progressRows by graph.library.watchProgress.collectAsState()
    val homeState by graph.content.home.collectAsState()
    val exploreState by graph.content.explore.collectAsState()
    val searchState by graph.content.searchResults.collectAsState()
    val presentationLocale by graph.content.presentationLocale.collectAsState()
    val updateState by graph.updates.state.collectAsState()

    val index = rememberLibraryIndex(libraryState)
    val knownPresentationItems = remember(homeState, exploreState, searchState) {
        buildList {
            addAll((homeState as? HomeState.Ready)?.items.orEmpty())
            (exploreState as? ExploreState.Ready)?.let { state ->
                addAll(state.movies)
                addAll(state.tv)
            }
            addAll((searchState as? SearchState.Ready)?.results.orEmpty())
        }.distinctBy { item -> item.mediaType to item.id }
    }
    val localizedLibraryItems = rememberLocalizedLibraryMedia(
        entries = index.entries,
        content = graph.content,
        localeKey = presentationLocale,
        initialContentReady = homeState !is HomeState.Loading,
        knownItems = knownPresentationItems,
    )
    val catalog = rememberMediaCatalog(homeState, exploreState, searchState, localizedLibraryItems)
    val detailsState = rememberMediaDetailsState(catalog)
    val playback = rememberPlaybackSession()
    val videoPlayerHost = LocalVideoPlayerHost.current
    val playerStatus = videoPlayerHost?.status?.collectAsState()?.value

    var selectedDestination by remember { mutableStateOf(NavDestination.Home) }
    var railExpanded by remember { mutableStateOf(false) }
    val railFocusRequester = remember { FocusRequester() }

    val fullscreenPlaybackVisible = playback.active &&
        playback.presentation == PlaybackPresentation.Fullscreen
    val currentPlaybackVisibilityCallback =
        rememberUpdatedState(onFullscreenPlaybackVisibilityChanged)
    LaunchedEffect(fullscreenPlaybackVisible) {
        currentPlaybackVisibilityCallback.value(fullscreenPlaybackVisible)
    }
    DisposableEffect(Unit) {
        onDispose { currentPlaybackVisibilityCallback.value(false) }
    }

    val detailsOpen = detailsState.selected != null
    // Focus must not be able to wander back into the page while a sheet or the player owns the
    // screen. The old TV shell learned this the hard way: pressing down behind an open overlay
    // scrolled a page nobody could see, and coming back left focus somewhere unrelated.
    val pageReachable = !detailsOpen && !fullscreenPlaybackVisible

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Bubble phase, not preview: whatever holds focus gets first refusal, so a seek bar
            // or a text field can claim the arrows before they become navigation. Only what
            // nothing wanted turns into a focus move.
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val action = tvKeyAction(event.key)
                if (action is TvKeyAction.Move) {
                    focusManager.moveFocus(action.direction.toFocusDirection())
                } else {
                    false
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // The rail's collapsed width is a permanent gutter. It expands over this, so
                // the page never reflows when navigation is entered.
                .padding(start = dimens.railCollapsedWidth)
                .focusGroup()
                .focusProperties { canFocus = pageReachable },
        ) {
            when (selectedDestination) {
                NavDestination.Home -> TvComingSoonPage(
                    title = "Home",
                    detail = "Rows, continue watching and the hero land in the next pass.",
                    icon = "iconamoon:home",
                )

                NavDestination.MyList -> TvComingSoonPage(
                    title = "My List",
                    detail = "Your library and the release calendar, shaped for a remote.",
                    icon = "iconamoon:bookmark",
                )

                NavDestination.Explore -> TvComingSoonPage(
                    title = "Explore",
                    detail = "Catalogue browsing with D-pad filters.",
                    icon = "iconamoon:discover",
                )

                NavDestination.Search -> TvComingSoonPage(
                    title = "Search",
                    detail = "Waiting on the on-screen keyboard work.",
                    icon = "iconamoon:search",
                )

                NavDestination.Account -> TvComingSoonPage(
                    title = "Profile",
                    detail = "Profiles, settings and sign-in.",
                    icon = "iconamoon:profile-circle",
                )
            }
        }

        TvSideRail(
            selected = selectedDestination,
            onSelect = { destination -> selectedDestination = destination },
            expanded = railExpanded,
            onExpandedChange = { railExpanded = it },
            selectedFocusRequester = railFocusRequester,
            modifier = Modifier.zIndex(50f),
        )

        AppUpdateOverlay(
            updates = graph.updates,
            state = updateState,
            playbackActive = playerStatus?.hasMedia == true,
            onExitRequired = onUpdateExitRequested,
            modifier = Modifier.zIndex(600f),
        )

        PlayerLayer(
            session = playback,
            modifier = Modifier.zIndex(500f),
        )
    }

    val backAction = resolveTvBackAction(
        fullscreenPlayback = fullscreenPlaybackVisible,
        detailsOpen = detailsOpen,
        destination = selectedDestination,
        railFocused = railExpanded,
    )
    PlatformBackHandler(enabled = backAction != TvBackAction.None) {
        when (backAction) {
            TvBackAction.ClosePlayback -> playback.close()
            TvBackAction.CloseDetails -> detailsState.dismiss()
            TvBackAction.FocusRail -> runCatching { railFocusRequester.requestFocus() }.let { }
            TvBackAction.GoHome -> selectedDestination = NavDestination.Home
            TvBackAction.None -> Unit
        }
    }
}

internal enum class TvBackAction {
    ClosePlayback,
    CloseDetails,
    FocusRail,
    GoHome,
    None,
}

/**
 * One ordered decision for the Back button, walking outward one layer at a time.
 *
 * Player, then sheet, then the page's focus, then the page itself. The rail step is the one
 * that does not exist on the phone and matters most here: on a television Back is a large
 * button next to the D-pad and pressing it once too often should land the viewer in navigation,
 * not drop them out of the app. [TvBackAction.None] is what finally lets the system close Cove,
 * and it is only reachable from Home with navigation already focused — three deliberate presses.
 */
internal fun resolveTvBackAction(
    fullscreenPlayback: Boolean,
    detailsOpen: Boolean,
    destination: NavDestination,
    railFocused: Boolean,
): TvBackAction = when {
    fullscreenPlayback -> TvBackAction.ClosePlayback
    detailsOpen -> TvBackAction.CloseDetails
    !railFocused -> TvBackAction.FocusRail
    destination != NavDestination.Home -> TvBackAction.GoHome
    else -> TvBackAction.None
}
