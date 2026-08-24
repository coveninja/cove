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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
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
import com.coveninja.cove.ui.components.common.FixtureDataBadge
import com.coveninja.cove.ui.components.navigation.NavDestination
import com.coveninja.cove.ui.onboarding.OnboardingGate
import com.coveninja.cove.ui.tv.onboarding.TvOnboardingFlow
import com.coveninja.cove.ui.model.toPerson
import com.coveninja.cove.ui.model.toMedia
import com.coveninja.cove.ui.pages.common.LocalPageBottomClearance
import com.coveninja.cove.ui.pages.common.LocalPageHorizontalPadding
import com.coveninja.cove.ui.pages.common.LocalPageViewport
import com.coveninja.cove.ui.pages.common.PageViewport
import com.coveninja.cove.ui.pages.home.rememberHomeController
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
import com.coveninja.cove.ui.state.mediaWatchAction
import com.coveninja.cove.ui.state.rememberMediaActions
import com.coveninja.cove.ui.state.rememberMediaCatalog
import com.coveninja.cove.ui.state.rememberMediaDetailsState
import com.coveninja.cove.ui.state.rememberPersonDetailsState
import com.coveninja.cove.ui.state.rememberPlaybackSession
import com.coveninja.cove.ui.state.PluginPlaybackEffect
import com.coveninja.cove.ui.state.rememberWatchProgressIndex
import com.coveninja.cove.ui.state.toUiCategory
import com.coveninja.cove.ui.tv.components.TvSideRail
import com.coveninja.cove.ui.tv.details.TvPersonScreen
import com.coveninja.cove.ui.tv.details.TvDetailsScreen
import com.coveninja.cove.ui.tv.focus.TvDirection
import com.coveninja.cove.ui.tv.focus.TvKeyAction
import com.coveninja.cove.ui.tv.focus.toFocusDirection
import com.coveninja.cove.ui.tv.focus.tvKeyAction
import com.coveninja.cove.ui.tv.pages.TvSettingsPage
import com.coveninja.cove.ui.pages.explore.rememberExploreController
import com.coveninja.cove.ui.state.rememberSearchSession
import com.coveninja.cove.ui.tv.pages.TvExplorePage
import com.coveninja.cove.ui.tv.pages.TvSearchPage
import com.coveninja.cove.ui.tv.pages.rememberTvExplorePageState
import com.coveninja.cove.ui.tv.pages.rememberTvSearchPageState
import com.coveninja.cove.ui.tv.pages.rememberTvSettingsPageState
import com.coveninja.cove.ui.tv.pages.TvHomePage
import com.coveninja.cove.ui.tv.pages.TvMyListPage
import com.coveninja.cove.ui.tv.pages.rememberTvHomePageState
import com.coveninja.cove.ui.tv.pages.rememberTvMyListPageState
import com.coveninja.cove.ui.tv.player.TvPlayerLayer
import kotlin.math.roundToInt

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
    // Re-opens the first-run flow on a device that has already been through it — the
    // `--onboarding` harness, and nothing else.
    forceOnboarding: Boolean = false,
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
                // The marker is a sibling of the whole shell rather than something a page
                // draws, so it survives every destination, the details screen and the
                // onboarding flow alike — a canned catalog is a property of the run.
                Box(modifier = Modifier.fillMaxSize()) {
                    OnboardingGate(
                        graph = graph,
                        forced = forceOnboarding,
                        flow = { preview, onFinished ->
                            TvOnboardingFlow(preview = preview, onFinished = onFinished)
                        },
                    ) {
                        TvAppContent(
                            onFullscreenPlaybackVisibilityChanged =
                                onFullscreenPlaybackVisibilityChanged,
                            onUpdateExitRequested = onUpdateExitRequested,
                        )
                    }

                    if (graph.fixtures) {
                        FixtureDataBadge(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(
                                    top = dimens.overscanVertical,
                                    end = dimens.overscanHorizontal,
                                )
                                // Above the player and the update overlay, the two layers
                                // that would otherwise hide it for the rest of the run.
                                .zIndex(1000f),
                        )
                    }
                }
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
    val watchProgress = rememberWatchProgressIndex(progressRows)
    val actions = rememberMediaActions(index)
    val detailsState = rememberMediaDetailsState(catalog)
    val personState = rememberPersonDetailsState()
    val playback = rememberPlaybackSession()
    PluginPlaybackEffect(playback)
    // Above the destination switch, so returning to Home keeps its loaded rails and every
    // scroll position instead of rebuilding the controllers that own those requests.
    val homeController = rememberHomeController(graph.content, graph.discovery, graph.addons)
    val homePageState = rememberTvHomePageState()
    val myListPageState = rememberTvMyListPageState()
    val exploreController = rememberExploreController(graph.discovery, graph.addons)
    val explorePageState = rememberTvExplorePageState()
    val search = rememberSearchSession()
    val searchPageState = rememberTvSearchPageState()
    val settingsPageState = rememberTvSettingsPageState()
    val videoPlayerHost = LocalVideoPlayerHost.current
    val playerStatus = videoPlayerHost?.status?.collectAsState()?.value

    var selectedDestination by remember { mutableStateOf(NavDestination.Home) }
    var railExpanded by remember { mutableStateOf(false) }
    val railFocusRequester = remember { FocusRequester() }
    val pageFocusRequester = remember { FocusRequester() }

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
    val personOpen = personState.selected != null
    // Focus must not be able to wander back into the page while a sheet or the player owns the
    // screen. The old TV shell learned this the hard way: pressing down behind an open overlay
    // scrolled a page nobody could see, and coming back left focus somewhere unrelated.
    val pageReachable = !detailsOpen && !personOpen && !fullscreenPlaybackVisible

    // Closing a layer removes whatever held focus from the composition, and Compose does not
    // hand it anywhere else. With no pointer to click something with, that leaves the whole
    // interface dead — so every dismissal has to say where focus goes next.
    val overlayOpen = detailsOpen || personOpen || fullscreenPlaybackVisible
    var overlayHasBeenOpen by remember { mutableStateOf(false) }
    LaunchedEffect(overlayOpen) {
        if (overlayOpen) {
            overlayHasBeenOpen = true
            return@LaunchedEffect
        }
        if (!pageReclaimsFocus(overlayOpen = overlayOpen, overlayHasBeenOpen = overlayHasBeenOpen)) {
            return@LaunchedEffect
        }
        overlayHasBeenOpen = false
        withFrameNanos { }
        // The rail is the fallback rather than an afterthought: a destination with nothing
        // focusable in it — a placeholder, an empty list — would otherwise swallow the request
        // and leave the viewer exactly as stuck as before.
        if (runCatching { pageFocusRequester.requestFocus() }.isFailure) {
            runCatching { railFocusRequester.requestFocus() }.let { }
        }
    }

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
                if (action !is TvKeyAction.Move) return@onKeyEvent false
                if (focusManager.moveFocus(action.direction.toFocusDirection())) {
                    return@onKeyEvent true
                }
                if (railTakesFocusAfterFailedMove(action.direction, pageReachable, railExpanded)) {
                    runCatching { railFocusRequester.requestFocus() }.isSuccess
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
                .focusGate(pageReachable)
                // A group with a restorer, so handing focus back here after an overlay closes
                // lands on the card the overlay was opened from rather than at the top of the
                // page. Safe alongside the rail hand-off: a focus search that cannot leave this
                // group simply fails, and a failed Left is exactly what reaches for navigation.
                .focusRequester(pageFocusRequester)
                .focusRestorer()
                .focusGroup(),
        ) {
            when (selectedDestination) {
                NavDestination.Home -> TvHomePage(
                    homeState = homeState,
                    controller = homeController,
                    pageState = homePageState,
                    index = index,
                    watchProgress = watchProgress,
                    catalog = catalog,
                    onOpenMedia = { detailsState.open(it) },
                    // Carrying on goes straight to playback rather than via the details
                    // screen: the whole claim of "where you left off" is that it is one press.
                    onPlayMedia = { playback.open(it) },
                )

                NavDestination.MyList -> TvMyListPage(
                    libraryState = libraryState,
                    pageState = myListPageState,
                    index = index,
                    watchProgress = watchProgress,
                    catalog = catalog,
                    onOpenMedia = { detailsState.open(it) },
                )

                NavDestination.Explore -> TvExplorePage(
                    exploreState = exploreState,
                    controller = exploreController,
                    pageState = explorePageState,
                    onOpenMedia = { detailsState.open(it) },
                )

                NavDestination.Search -> TvSearchPage(
                    searchState = searchState,
                    session = search,
                    pageState = searchPageState,
                    onOpenMedia = { media ->
                        // A result the viewer acted on is what proves the query was the one
                        // they meant, which is when it earns a place in their history.
                        search.submitted?.let(search::rememberQuery)
                        detailsState.open(media)
                    },
                )

                NavDestination.Account -> TvSettingsPage(pageState = settingsPageState)
            }
        }

        // Above the rail: a title fills the screen, and navigation is reachable by Back rather
        // than by being drawn over the thing the viewer just opened.
        detailsState.overlayMedia?.takeIf { detailsOpen }?.let { overlay ->
            val entry = index.entryOf(overlay.id)
            TvDetailsScreen(
                media = overlay,
                listCategory = entry?.status?.toUiCategory(),
                rating = entry?.rating?.roundToInt(),
                watchLabel = mediaWatchAction(overlay, entry, progressRows).label,
                onPlay = { playback.open(overlay) },
                onChooseSource = { playback.open(overlay, forcePicker = true) },
                onSetListCategory = { category -> actions.setListCategory(overlay, category) },
                onRemoveFromList = { actions.removeFromList(overlay) },
                onSetRating = { value -> actions.setRating(overlay, value) },
                onPlayEpisode = { season, episode ->
                    playback.open(
                        media = overlay,
                        season = season.number,
                        episode = episode.number,
                        episodeTitle = episode.title,
                    )
                },
                onLoadEpisodes = { season -> actions.episodesFor(overlay, season) },
                onOpenRecommendation = { recommendation ->
                    detailsState.open(recommendation.toMedia())
                },
                onOpenPerson = { member -> personState.open(member.toPerson()) },
                covered = personOpen || fullscreenPlaybackVisible,
                modifier = Modifier.zIndex(200f).focusGate(!personOpen),
            )
        }

        // One sheet at a time, and the title stays selected underneath: closing the person
        // lands back on the title they were reached from, having refetched nothing.
        personState.overlayPerson?.takeIf { personState.selected != null }?.let { person ->
            TvPersonScreen(
                person = person,
                covered = fullscreenPlaybackVisible,
                onOpenMedia = { media ->
                    personState.dismiss()
                    detailsState.open(media)
                },
                modifier = Modifier.zIndex(210f),
            )
        }

        TvSideRail(
            selected = selectedDestination,
            onSelect = { destination -> selectedDestination = destination },
            expanded = railExpanded,
            onExpandedChange = { railExpanded = it },
            selectedFocusRequester = railFocusRequester,
            modifier = Modifier
                .zIndex(50f)
                // Navigation is behind the details screen and the player, so it has to be
                // unreachable while either is up — otherwise Left from an open title walks
                // focus into a rail nobody can see.
                .focusGate(pageReachable),
        )

        AppUpdateOverlay(
            updates = graph.updates,
            state = updateState,
            playbackActive = playerStatus?.hasMedia == true,
            onExitRequired = onUpdateExitRequested,
            modifier = Modifier.zIndex(600f),
        )

        TvPlayerLayer(
            session = playback,
            modifier = Modifier.zIndex(500f),
        )
    }

    val backAction = resolveTvBackAction(
        fullscreenPlayback = fullscreenPlaybackVisible,
        personOpen = personOpen,
        detailsOpen = detailsOpen,
        destination = selectedDestination,
        railFocused = railExpanded,
    )
    PlatformBackHandler(enabled = backAction != TvBackAction.None) {
        when (backAction) {
            TvBackAction.ClosePlayback -> playback.close()
            TvBackAction.ClosePerson -> personState.dismiss()
            TvBackAction.CloseDetails -> detailsState.dismiss()
            TvBackAction.FocusRail -> runCatching { railFocusRequester.requestFocus() }.let { }
            TvBackAction.GoHome -> selectedDestination = NavDestination.Home
            TvBackAction.None -> Unit
        }
    }
}

/**
 * Keeps focus out of a subtree that is covered by something else.
 *
 * Two things are worth stating, because the obvious spelling of this is wrong in both. First,
 * `canFocus = false` does not do it: that deactivates the node itself and explicitly leaves its
 * children focusable — it is what `focusGroup()` is built from — so a page behind an open
 * details screen stayed perfectly reachable. Cancelling *entry* to the group is the property
 * that actually closes it.
 *
 * Second, nothing at all is added while the subtree is reachable. An always-present focus group
 * around the page is a boundary that focus search has to climb out of, and that is what stopped
 * Left from reaching the rail: the search stayed inside the page and reported failure rather
 * than looking at the sibling beside it.
 */
/**
 * Whether a focus move that found nothing should hand focus to the navigation rail.
 *
 * Left out of the page is the one direction with somewhere to go that focus search cannot find
 * for itself. The rail is a sibling subtree drawn over the page's left gutter rather than a
 * neighbour laid out inside the page, so a search that runs off the page's left edge reports
 * failure instead of arriving there — which read as focus simply vanishing.
 *
 * It doubles as the recovery path. Whatever else has gone wrong, Left puts focus somewhere
 * visible, which on a device with no pointer to click with is the only way back.
 *
 * Guarded on the rail not already holding focus, so pressing Left repeatedly inside navigation
 * does not keep re-requesting the same button and trapping focus on the selected destination.
 */
internal fun railTakesFocusAfterFailedMove(
    direction: TvDirection,
    pageReachable: Boolean,
    railFocused: Boolean,
): Boolean = direction == TvDirection.Left && pageReachable && !railFocused

/**
 * Whether the page should take focus back now.
 *
 * The second argument is what stops this firing on the very first composition. At startup no
 * overlay has ever been open, and a page grabbing focus then would fight whatever the
 * destination itself focuses — on Home, the hero's Play button, which is where a viewer's first
 * press should land. Only a layer that was genuinely on screen and has now gone leaves focus
 * with nowhere to be.
 */
internal fun pageReclaimsFocus(overlayOpen: Boolean, overlayHasBeenOpen: Boolean): Boolean =
    !overlayOpen && overlayHasBeenOpen

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.focusGate(reachable: Boolean): Modifier = if (reachable) {
    this
} else {
    this.focusProperties { onEnter = { cancelFocusChange() } }.focusGroup()
}

internal enum class TvBackAction {
    ClosePlayback,
    ClosePerson,
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
    personOpen: Boolean,
    detailsOpen: Boolean,
    destination: NavDestination,
    railFocused: Boolean,
): TvBackAction = when {
    fullscreenPlayback -> TvBackAction.ClosePlayback
    personOpen -> TvBackAction.ClosePerson
    detailsOpen -> TvBackAction.CloseDetails
    !railFocused -> TvBackAction.FocusRail
    destination != NavDestination.Home -> TvBackAction.GoHome
    else -> TvBackAction.None
}
