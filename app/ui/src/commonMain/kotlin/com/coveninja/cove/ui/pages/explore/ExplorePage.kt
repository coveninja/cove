package com.coveninja.cove.ui.pages.explore

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.coveninja.cove.shared.data.ExploreState
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.components.navigation.NavBarClearance
import com.coveninja.cove.ui.components.navigation.NavBarPlacement
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.toUiMedia
import com.coveninja.cove.ui.pages.common.PageEmptyState
import com.coveninja.cove.ui.pages.common.PageError
import com.coveninja.cove.ui.pages.common.PageLayoutDefaults
import com.coveninja.cove.ui.pages.common.MediaRailStateStore
import com.coveninja.cove.ui.pages.common.ScrollToTopButton
import com.coveninja.cove.ui.pages.common.ShimmerBlock
import com.coveninja.cove.ui.pages.common.rememberMediaRailStateStore
import com.coveninja.cove.ui.state.LibraryIndex
import com.coveninja.cove.ui.state.LocalMotionPolicy
import com.coveninja.cove.ui.state.rememberMediaActions
import kotlin.random.Random
import kotlinx.coroutines.launch

/** Filters, scroll positions, and rail offsets retained while another primary tab is visible. */
@Stable
class ExplorePageState internal constructor(
    internal val shelvesListState: LazyListState,
    internal val gridState: LazyGridState,
    internal val railStates: MediaRailStateStore,
) {
    var filters by mutableStateOf(ExploreFilters())
        internal set

    var layout by mutableStateOf(ExploreLayout.Shelves)
        internal set
}

@Composable
fun rememberExplorePageState(): ExplorePageState {
    val shelvesListState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val railStates = rememberMediaRailStateStore()
    return remember(shelvesListState, gridState, railStates) {
        ExplorePageState(shelvesListState, gridState, railStates)
    }
}

/**
 * Explore: a spotlight over rails of things to watch, collapsing into a filtered,
 * infinitely-paged grid the moment the viewer narrows anything.
 *
 * The two arrangements do different jobs, which is why they are not the same layout with a
 * different item size. **Shelves** is for being shown things — the hero leads, the toolbar
 * rides along with the content. **Grid** is for looking for something — the toolbar pins to
 * the top where a filter can be changed without scrolling back to it.
 *
 * The discover feed is used only as a seed for the first paint. Everything after it comes
 * from the catalog through `DiscoveryRepository`, so a failure in one is not a failure of
 * the page.
 */
@Composable
fun ExplorePage(
    exploreState: ExploreState,
    controller: ExploreController,
    pageState: ExplorePageState,
    index: LibraryIndex,
    mediaCard: @Composable (Media, Modifier) -> Unit,
    onOpenMedia: (Media) -> Unit,
    navBarPlacement: NavBarPlacement = NavBarPlacement.Top,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val actions = rememberMediaActions(index)

    val filters = pageState.filters
    val layout = pageState.layout

    val seed = remember(exploreState) {
        (exploreState as? ExploreState.Ready)
            ?.let { it.movies + it.tv }
            ?.map { item -> item.toUiMedia() }
            .orEmpty()
    }

    LaunchedEffect(filters.type, seed) { controller.loadShelves(filters.type, seed) }

    // Only once the grid is actually on screen. Loading it alongside the rails would spend
    // a catalog request on a page nobody is looking at, and the rails already cost several.
    // `setGridFilters` no-ops on an unchanged filter, so toggling back and forth is free.
    LaunchedEffect(filters.catalogKey, layout) {
        if (layout == ExploreLayout.Grid) controller.setGridFilters(filters)
    }

    // Anything but the format switch is a request to go looking, and looking happens in
    // the grid. The format switch is not: it re-shelves rather than narrowing.
    val onFiltersChange: (ExploreFilters) -> Unit = { next ->
        if (next.catalogKey != filters.catalogKey && next.type == filters.type) {
            pageState.layout = ExploreLayout.Grid
        }
        if (next.query != filters.query && next.query.isNotBlank()) {
            pageState.layout = ExploreLayout.Grid
        }
        pageState.filters = next
    }

    val shelfMedia = remember(controller.shelves) {
        controller.shelves.flatMap(ExploreShelf::media).distinctBy(Media::id)
    }
    val visibleGrid = remember(controller.gridItems, filters.query) {
        applyQuery(controller.gridItems, filters.query)
    }

    fun surpriseMe() {
        val pool = if (layout == ExploreLayout.Grid) visibleGrid else shelfMedia
        pool.randomOrNullStable()?.let(onOpenMedia)
    }

    val toolbar: @Composable () -> Unit = {
        ExploreToolbar(
            filters = filters,
            layout = layout,
            genres = controller.genres,
            onFiltersChange = onFiltersChange,
            onLayoutChange = { pageState.layout = it },
            onSurpriseMe = ::surpriseMe,
            modifier = Modifier.padding(
                horizontal = PageLayoutDefaults.HorizontalPadding,
                vertical = 10.dp,
            ),
        )
    }

    val everythingEmpty = controller.shelves.isEmpty() &&
        !controller.shelvesLoading &&
        seed.isEmpty()

    if (exploreState is ExploreState.Failed && everythingEmpty) {
        PageError("Explore could not load", (exploreState as ExploreState.Failed).message)
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = layout,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val enterDuration = if (reducedMotion) 0 else 200
                val movementDuration = if (reducedMotion) 0 else 240
                val exitDuration = if (reducedMotion) 0 else 140
                val enter = fadeIn(tween(enterDuration)) + slideInHorizontally(tween(movementDuration)) {
                    if (forward) it / 10 else -it / 10
                }
                val exit = fadeOut(tween(exitDuration)) + slideOutHorizontally(tween(enterDuration)) {
                    if (forward) -it / 10 else it / 10
                }
                enter togetherWith exit
            },
            label = "ExploreLayout",
        ) { current ->
            when (current) {
                ExploreLayout.Shelves -> ShelvesLayout(
                    controller = controller,
                    filters = filters,
                    seed = seed,
                    inList = { media -> index.categoryOf(media.id) != null },
                    onOpenMedia = onOpenMedia,
                    onToggleList = { media ->
                        if (index.categoryOf(media.id) != null) {
                            actions.removeFromList(media)
                        } else {
                            actions.setListCategory(media, MyListCategory.WatchLater)
                        }
                    },
                    onSeeAll = { shelf ->
                        pageState.filters = filters.copy(
                            genreId = shelf.genreId,
                            sort = shelf.sort,
                        )
                        pageState.layout = ExploreLayout.Grid
                    },
                    mediaCard = mediaCard,
                    toolbar = toolbar,
                    navBarPlacement = navBarPlacement,
                    listState = pageState.shelvesListState,
                    railStates = pageState.railStates,
                )

                ExploreLayout.Grid -> GridLayout(
                    controller = controller,
                    filters = filters,
                    visible = visibleGrid,
                    onClearFilters = {
                        pageState.filters = ExploreFilters(
                            type = filters.type,
                            sort = filters.sort,
                        )
                    },
                    mediaCard = mediaCard,
                    toolbar = toolbar,
                    navBarPlacement = navBarPlacement,
                    gridState = pageState.gridState,
                )
            }
        }
    }
}

@Composable
private fun ShelvesLayout(
    controller: ExploreController,
    filters: ExploreFilters,
    seed: List<Media>,
    inList: (Media) -> Boolean,
    onOpenMedia: (Media) -> Unit,
    onToggleList: (Media) -> Unit,
    onSeeAll: (ExploreShelf) -> Unit,
    mediaCard: @Composable (Media, Modifier) -> Unit,
    toolbar: @Composable () -> Unit,
    navBarPlacement: NavBarPlacement,
    listState: LazyListState,
    railStates: MediaRailStateStore,
) {
    val scope = rememberCoroutineScope()

    // Rails are the better source once they exist — they are ordered and complete — but
    // the seed is here first, and a hero is the one thing that must never be blank.
    val picks = remember(controller.shelves, seed, filters.type) {
        val pool = controller.shelves.firstOrNull { it.kind == ShelfKind.Trending }?.media
            ?: seed.filter { it.type == filters.type }
        spotlightPicks(pool, SPOTLIGHT_SLIDES)
    }

    val scrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 2 }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (controller.shelvesLoading && controller.shelves.isEmpty()) {
            ExploreSkeleton()
            return@Box
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(26.dp),
            contentPadding = PaddingValues(
                // On desktop, a hero passes *under* the top bar and its scrim keeps the bar
                // legible. Without a hero, the toolbar needs top clearance. The mobile bar
                // floats over the page, so neither case needs extra room here.
                top = if (
                    picks.isEmpty() && navBarPlacement == NavBarPlacement.Top
                ) {
                    NavBarClearance
                } else {
                    0.dp
                },
                bottom = 48.dp,
            ),
        ) {
            if (picks.isNotEmpty()) {
                item(key = "spotlight") {
                    ExploreSpotlight(
                        picks = picks,
                        inList = inList,
                        onOpen = onOpenMedia,
                        onToggleList = onToggleList,
                        scrollOffset = {
                            // Only meaningful while the hero is the first visible item;
                            // past that it has left the screen and the value is unused.
                            if (listState.firstVisibleItemIndex == 0) {
                                listState.firstVisibleItemScrollOffset.toFloat()
                            } else {
                                0f
                            }
                        },
                    )
                }
            }

            item(key = "toolbar") { toolbar() }

            items(controller.shelves, key = { it.id }) { shelf ->
                ExploreShelfRow(
                    shelf = shelf,
                    state = railStates.stateFor(shelf.id),
                    mediaCard = mediaCard,
                    onSeeAll = onSeeAll,
                )
            }

            if (controller.personalizing) {
                item(key = "personalizing") {
                    PersonalizingNotice()
                }
            }

            if (controller.shelves.isEmpty()) {
                item(key = "empty") {
                    PageEmptyState(
                        iconName = "lucide:compass",
                        title = "Nothing to explore yet",
                        message = "The catalog returned no titles. Check that the backend has a TMDB key.",
                        modifier = Modifier.height(320.dp),
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

@Composable
private fun GridLayout(
    controller: ExploreController,
    filters: ExploreFilters,
    visible: List<Media>,
    onClearFilters: () -> Unit,
    mediaCard: @Composable (Media, Modifier) -> Unit,
    toolbar: @Composable () -> Unit,
    navBarPlacement: NavBarPlacement,
    gridState: LazyGridState,
) {
    val scope = rememberCoroutineScope()
    val scrolled by remember { derivedStateOf { gridState.firstVisibleItemIndex > 6 } }

    // The desktop grid has no hero to run under the top bar, so it takes the clearance and
    // safe insets that its edge-to-edge page wrapper omits. CoveApp supplies the insets on
    // mobile while allowing the bottom bar to float over this content.
    Box(
        modifier = if (navBarPlacement == NavBarPlacement.Top) {
            Modifier
                .fillMaxSize()
                .safeContentPadding()
                .padding(top = NavBarClearance)
        } else {
            // CoveApp already applies mobile safe insets; the bar itself remains an overlay.
            Modifier.fillMaxSize()
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            toolbar()

            Text(
                text = titleCountLabel(visible.size, moreAvailable = !controller.gridExhausted),
                modifier = Modifier.padding(
                    horizontal = PageLayoutDefaults.HorizontalPadding,
                    vertical = 6.dp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )

            // An empty result is only an empty *state* once loading has finished; showing
            // "nothing matches" over an in-flight first page would be a lie that corrects
            // itself a moment later.
            if (visible.isEmpty() && !controller.gridLoading && controller.gridError == null) {
                PageEmptyState(
                    iconName = if (filters.narrowed) "lucide:filter-x" else "lucide:compass",
                    title = if (filters.narrowed) "Nothing matches" else "Nothing here",
                    message = if (filters.narrowed) {
                        "No title in the catalog fits the filters you have on."
                    } else {
                        "The catalog returned no titles for this format."
                    },
                    actionLabel = if (filters.narrowed) "Clear filters" else null,
                    onAction = onClearFilters,
                    modifier = Modifier.weight(1f),
                )
            } else {
                ExploreGrid(
                    items = visible,
                    state = gridState,
                    loading = controller.gridLoading,
                    exhausted = controller.gridExhausted,
                    error = controller.gridError,
                    onLoadMore = controller::loadNextPage,
                    onRetry = controller::retry,
                    mediaCard = mediaCard,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        ScrollToTopButton(
            visible = scrolled,
            onClick = { scope.launch { gridState.animateScrollToItem(0) } },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = PageLayoutDefaults.HorizontalPadding, bottom = 24.dp)
                .zIndex(10f),
        )
    }
}

/**
 * Says that the personal rails are still on their way.
 *
 * Worth the row: building the taste profile costs a metadata request per saved title, so
 * on a large library the personalized rails can arrive noticeably after everything else,
 * and without this the page looks finished while it is still working.
 */
@Composable
private fun PersonalizingNotice() {
    ShimmerBlock(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PageLayoutDefaults.HorizontalPadding)
            .height(18.dp),
        corner = 6.dp,
    )
}

/**
 * A random element, or null when there is nothing to choose from.
 *
 * Named to be explicit that it is not `List.random()`: that throws on an empty list, and
 * "surprise me" with no titles loaded must do nothing rather than crash the page.
 */
private fun <T> List<T>.randomOrNullStable(): T? =
    if (isEmpty()) null else this[Random.nextInt(size)]

/** How many titles the hero rotates through before repeating. */
private const val SPOTLIGHT_SLIDES = 5
