package com.coveninja.cove.ui.pages.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Stable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.coveninja.cove.shared.data.CalendarState
import com.coveninja.cove.shared.data.HomeState
import com.coveninja.cove.shared.model.CalendarItem
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.components.navigation.NavBarClearance
import com.coveninja.cove.ui.components.navigation.NavBarPlacement
import com.coveninja.cove.shared.model.AddonCatalogDescriptor
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.toUiMedia
import com.coveninja.cove.ui.pages.common.MediaRail
import com.coveninja.cove.ui.pages.common.MediaRailStateStore
import com.coveninja.cove.ui.pages.common.PageEmptyState
import com.coveninja.cove.ui.pages.common.PageError
import com.coveninja.cove.ui.pages.common.PageLayoutDefaults
import com.coveninja.cove.ui.pages.common.RailDefaults
import com.coveninja.cove.ui.pages.common.ScrollToTopButton
import com.coveninja.cove.ui.pages.common.ShimmerBlock
import com.coveninja.cove.ui.pages.common.rememberMediaRailStateStore
import com.coveninja.cove.ui.pages.mylist.calendar.availableNow
import com.coveninja.cove.ui.state.LibraryIndex
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.MediaCatalog
import com.coveninja.cove.ui.state.WatchProgressIndex
import com.coveninja.cove.ui.state.mediaFor
import com.coveninja.cove.ui.state.rememberMediaActions
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** State worth retaining when Home is temporarily removed by primary navigation. */
@Stable
class HomePageState internal constructor(
    internal val listState: LazyListState,
    internal val railStates: MediaRailStateStore,
)

@Composable
fun rememberHomePageState(): HomePageState {
    val listState = rememberLazyListState()
    val railStates = rememberMediaRailStateStore()
    return remember(listState, railStates) { HomePageState(listState, railStates) }
}

/**
 * Home: what *this viewer* should do next.
 *
 * The distinction from Explore is the whole design. Explore is the catalog — impersonal,
 * browsable, the same for everybody. Home is the evening: the thing you are halfway through,
 * the episodes that aired while you were away, what lands this week, and a couple of picks
 * that only make sense given what you have watched. Neither page needs the other's content
 * to justify itself, which is why Home leads with a single decisive hero rather than a second
 * carousel of popular titles.
 *
 * Everything above the personal rails paints with no I/O: the library, watch progress, the
 * discover feed and the cached calendar are all `StateFlow`s the composition already
 * collects. `HomeController` handles only the three things that genuinely cost a request —
 * the hero's artwork, the carry-on rail's episode stills, and the taste-profile rails — each
 * of which upgrades the page in place rather than holding up its first paint.
 */
@Composable
fun HomePage(
    homeState: HomeState,
    controller: HomeController,
    pageState: HomePageState,
    index: LibraryIndex,
    watchProgress: WatchProgressIndex,
    catalog: MediaCatalog,
    mediaCard: @Composable (Media, Modifier) -> Unit,
    onOpenMedia: (Media) -> Unit,
    onPlayMedia: (Media) -> Unit,
    onExplore: () -> Unit,
    onExploreCatalog: (AddonCatalogDescriptor) -> Unit,
    onOpenMyList: () -> Unit,
    navBarPlacement: NavBarPlacement = NavBarPlacement.Top,
    modifier: Modifier = Modifier,
) {
    val graph = LocalAppGraph.current
    val calendarState by graph.calendar.calendar.collectAsState()
    val actions = rememberMediaActions(index)

    val initialContentReady = homeState !is HomeState.Loading

    // Let the first useful frame win the race for the main thread and network. These requests
    // only upgrade content that is already on screen, so starting them during initial discovery
    // makes startup slower without improving the first paint.
    LaunchedEffect(initialContentReady) {
        if (initialContentReady) {
            withFrameNanos { }
            graph.calendar.refresh(force = false)
        }
    }
    LaunchedEffect(initialContentReady) {
        if (initialContentReady) {
            withFrameNanos { }
            withFrameNanos { }
            withFrameNanos { }
            controller.loadPersonal()
            controller.loadCatalogs()
        }
    }

    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    val greeting = remember {
        greetingFor(Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour)
    }

    val trending = remember(homeState) {
        (homeState as? HomeState.Ready)?.items.orEmpty().map { it.toUiMedia() }
    }
    // A failed calendar is an ordinary state, not a page error: every host without a content
    // backend publishes `Failed` permanently, and Home has to be fine on those.
    val calendarItems = remember(calendarState) {
        (calendarState as? CalendarState.Ready)?.items.orEmpty()
    }

    val continuing = remember(index, watchProgress, catalog) {
        continueWatchingRows(
            entries = index.entries,
            progressFor = watchProgress::progressFor,
            enrich = catalog::enrich,
        )
    }
    // Resolved against the library up front: a card is only drawn for something whose
    // buttons can actually do their job.
    val backlog = remember(calendarItems, index, catalog) {
        backlogRows(availableNow(calendarItems)) { item -> index.mediaFor(item, catalog) }
    }
    val upcoming = remember(calendarItems, today) { comingUp(calendarItems, today) }
    val stats = remember(index, calendarItems) { libraryStats(index.entries, calendarItems) }
    val hero = remember(continuing, backlog, trending) { heroPick(continuing, backlog, trending) }

    LaunchedEffect(initialContentReady, hero?.media?.id) {
        if (initialContentReady) {
            hero?.let { controller.enrichHero(it.media) }
        }
    }
    // Safe to re-invoke on every rebuild of the rows — which playback does every few seconds
    // — because the controller skips any season it has already fetched or already failed.
    LaunchedEffect(initialContentReady, continuing) {
        if (initialContentReady) {
            controller.loadEpisodeStills(continuing)
        }
    }

    val rails = remember(controller.personalRails, controller.catalogRails, trending) {
        buildHomeRails(
            controller.personalRails + controller.catalogRails + listOf(
                HomeRail(
                    id = "trending",
                    title = "Trending now",
                    subtitle = "What everyone is watching",
                    icon = "lucide:flame",
                    media = trending,
                    ordered = true,
                ),
            ),
        )
    }

    val nothingYet = hero == null && continuing.isEmpty() && backlog.isEmpty() && rails.isEmpty()

    when {
        nothingYet && homeState is HomeState.Failed ->
            PageError("Home could not load", (homeState as HomeState.Failed).message)

        nothingYet -> HomeSkeleton(modifier = modifier)

        else -> HomeReady(
            hero = hero,
            heroArt = hero?.let { controller.heroArt(it.media) },
            greeting = greeting,
            stats = stats,
            continuing = continuing,
            backlog = backlog,
            upcoming = upcoming,
            rails = rails,
            today = today,
            personalizing = controller.personalizing,
            libraryEmpty = index.entries.isEmpty(),
            inList = { media -> index.categoryOf(media.id) != null },
            onToggleList = { media ->
                if (index.categoryOf(media.id) != null) {
                    actions.removeFromList(media)
                } else {
                    actions.setListCategory(media, MyListCategory.WatchLater)
                }
            },
            resolveCalendarMedia = { item -> index.mediaFor(item, catalog) },
            stillFor = controller::stillFor,
            mediaCard = mediaCard,
            onOpenMedia = onOpenMedia,
            onPlayMedia = onPlayMedia,
            onExplore = onExplore,
            onExploreCatalog = onExploreCatalog,
            onOpenMyList = onOpenMyList,
            navBarPlacement = navBarPlacement,
            pageState = pageState,
            modifier = modifier,
        )
    }
}

@Composable
private fun HomeReady(
    hero: HomeHero?,
    heroArt: Media?,
    greeting: String,
    stats: HomeStats,
    continuing: List<ContinueRow>,
    backlog: List<BacklogRow>,
    upcoming: List<CalendarItem>,
    rails: List<HomeRail>,
    today: LocalDate,
    personalizing: Boolean,
    libraryEmpty: Boolean,
    inList: (Media) -> Boolean,
    onToggleList: (Media) -> Unit,
    resolveCalendarMedia: (CalendarItem) -> Media?,
    stillFor: (ContinueRow) -> String?,
    mediaCard: @Composable (Media, Modifier) -> Unit,
    onOpenMedia: (Media) -> Unit,
    onPlayMedia: (Media) -> Unit,
    onExplore: () -> Unit,
    onExploreCatalog: (AddonCatalogDescriptor) -> Unit,
    onOpenMyList: () -> Unit,
    navBarPlacement: NavBarPlacement,
    pageState: HomePageState,
    modifier: Modifier = Modifier,
) {
    val listState = pageState.listState
    val scope = rememberCoroutineScope()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(26.dp),
            contentPadding = PaddingValues(
                // On desktop, a hero passes *under* the top bar and its scrim keeps the bar
                // legible. Without a hero, the greeting needs top clearance. The mobile bar
                // floats over the page, so neither case needs extra room here.
                top = if (
                    hero == null && navBarPlacement == NavBarPlacement.Top
                ) {
                    NavBarClearance
                } else {
                    0.dp
                },
                bottom = 48.dp + PageLayoutDefaults.BottomClearance,
            ),
        ) {
            if (hero != null && heroArt != null) {
                item(key = "hero") {
                    HomeHeroBlock(
                        hero = hero,
                        art = heroArt,
                        inList = inList(hero.media),
                        onPlay = { onPlayMedia(hero.media) },
                        onOpen = { onOpenMedia(hero.media) },
                        onToggleList = { onToggleList(hero.media) },
                        scrollOffset = {
                            // Only meaningful while the hero is the first visible item; past
                            // that it has left the screen and the value is unused.
                            if (listState.firstVisibleItemIndex == 0) {
                                listState.firstVisibleItemScrollOffset.toFloat()
                            } else {
                                0f
                            }
                        },
                    )
                }
            }

            item(key = "greeting") {
                HomeGreeting(
                    greeting = greeting,
                    stats = stats,
                    modifier = Modifier.padding(
                        horizontal = PageLayoutDefaults.HorizontalPadding,
                    ),
                )
            }

            if (continuing.isNotEmpty()) {
                item(key = "continue") {
                    MediaRail(
                        title = "Carry on watching",
                        subtitle = "Where you left off",
                        icon = "iconamoon:history",
                        items = continuing,
                        key = ContinueRow::id,
                        itemWidth = RailDefaults.WideCardWidth,
                        itemHeight = RailDefaults.WideCardHeight,
                        state = pageState.railStates.stateFor("continue"),
                        onSeeAll = onOpenMyList,
                    ) { row, cardModifier ->
                        ContinueCard(
                            row = row,
                            onOpen = { onOpenMedia(row.media) },
                            onPlay = { onPlayMedia(row.media) },
                            modifier = cardModifier,
                            stillUrl = stillFor(row),
                        )
                    }
                }
            }

            if (backlog.isNotEmpty()) {
                item(key = "backlog") {
                    MediaRail(
                        title = "Waiting for you",
                        subtitle = "Episodes that aired while you were away",
                        icon = "lucide:tv",
                        items = backlog,
                        key = BacklogRow::id,
                        itemWidth = RailDefaults.WideCardWidth,
                        itemHeight = RailDefaults.WideCardHeight,
                        state = pageState.railStates.stateFor("backlog"),
                        onSeeAll = onOpenMyList,
                    ) { row, cardModifier ->
                        BacklogCard(
                            row = row,
                            onOpen = { onOpenMedia(row.media) },
                            // Deliberately the title rather than the dated episode: this is a
                            // backlog, and the playback session resolves the next *unwatched*
                            // episode, where the calendar item names the last one to air.
                            onPlay = { onPlayMedia(row.media) },
                            modifier = cardModifier,
                        )
                    }
                }
            }

            if (upcoming.isNotEmpty()) {
                item(key = "upcoming") {
                    MediaRail(
                        title = "Coming this week",
                        subtitle = "Dated releases from your list",
                        icon = "lucide:calendar-clock",
                        items = upcoming,
                        key = { it.id },
                        itemWidth = UpcomingTileDefaults.Width,
                        itemHeight = UpcomingTileDefaults.Height,
                        state = pageState.railStates.stateFor("upcoming"),
                        onSeeAll = onOpenMyList,
                    ) { item, tileModifier ->
                        UpcomingTile(
                            item = item,
                            today = today,
                            onOpen = { resolveCalendarMedia(item)?.let(onOpenMedia) },
                            modifier = tileModifier,
                        )
                    }
                }
            }

            if (libraryEmpty) {
                item(key = "start") {
                    PageEmptyState(
                        iconName = "lucide:compass",
                        title = "Your list is empty",
                        message = "Save a few titles and this page fills up with what you are " +
                            "watching, what has just aired, and what is coming next.",
                        actionLabel = "Find something",
                        onAction = onExplore,
                        modifier = Modifier.height(300.dp),
                    )
                }
            }

            items(rails, key = { it.id }) { rail ->
                MediaRail(
                    title = rail.title,
                    subtitle = rail.subtitle,
                    icon = rail.icon,
                    items = rail.media,
                    key = Media::id,
                    state = pageState.railStates.stateFor(rail.id),
                    // Only the impersonal rail has an equivalent anywhere else. A "see all"
                    // on a personal rail would have to show something other than the rail.
                    // A catalog rail is the exception that does have one of its own: the
                    // rest of that same catalog, which Explore can page through.
                    onSeeAll = rail.catalog
                        ?.let { catalog -> { onExploreCatalog(catalog) } }
                        ?: onExplore.takeIf { rail.ordered },
                    itemContent = mediaCard,
                )
            }

            if (personalizing) {
                item(key = "personalizing") {
                    // Worth the row: building the taste profile costs a metadata request per
                    // saved title, so on a large library the personal rails arrive noticeably
                    // late, and without this the page looks finished while it is still working.
                    ShimmerBlock(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PageLayoutDefaults.HorizontalPadding)
                            .height(18.dp),
                        corner = 6.dp,
                    )
                }
            }

            item(key = "tail") { Spacer(Modifier.height(8.dp)) }
        }

        ScrollToTopButton(
            visible = scrolled,
            onClick = { scope.launch { listState.animateScrollToItem(0) } },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = PageLayoutDefaults.HorizontalPadding, bottom = 24.dp)
                .zIndex(10f),
        )
    }
}
