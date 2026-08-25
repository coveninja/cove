package com.coveninja.cove.ui.pages.mylist

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.coveninja.cove.shared.data.CalendarState
import com.coveninja.cove.shared.data.LibraryState
import com.coveninja.cove.shared.model.CalendarItem
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.toUiMedia
import com.coveninja.cove.ui.pages.common.PageEmptyState
import com.coveninja.cove.ui.pages.common.PageError
import com.coveninja.cove.ui.pages.common.PageHeader
import com.coveninja.cove.ui.pages.common.PageLayoutDefaults
import com.coveninja.cove.ui.pages.common.PageLoading
import com.coveninja.cove.ui.pages.common.ScrollToTopButton
import com.coveninja.cove.ui.pages.common.SegmentedControl
import com.coveninja.cove.ui.pages.mylist.calendar.CalendarAgenda
import com.coveninja.cove.ui.pages.mylist.calendar.CalendarMonthBar
import com.coveninja.cove.ui.pages.mylist.calendar.CalendarSectionState
import com.coveninja.cove.ui.pages.mylist.calendar.availableNow
import com.coveninja.cove.ui.pages.mylist.calendar.groupByDay
import com.coveninja.cove.ui.pages.mylist.calendar.itemsInMonth
import com.coveninja.cove.ui.pages.mylist.calendar.rememberCalendarSections
import com.coveninja.cove.ui.platform.hasPointerHover
import com.coveninja.cove.ui.state.LibraryIndex
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.LocalMotionPolicy
import com.coveninja.cove.ui.state.MediaActions
import com.coveninja.cove.ui.state.MediaCatalog
import com.coveninja.cove.ui.state.WatchProgressIndex
import com.coveninja.cove.ui.state.mediaFor
import com.coveninja.cove.ui.state.rememberMediaActions
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minusMonth
import kotlinx.datetime.plusMonth
import kotlinx.datetime.todayIn
import kotlinx.datetime.yearMonth
import kotlin.time.Clock

@Composable
fun MyListPage(
    libraryState: LibraryState,
    index: LibraryIndex,
    progress: WatchProgressIndex,
    catalog: MediaCatalog,
    mediaCard: @Composable (Media, Modifier) -> Unit,
    onExplore: () -> Unit,
    onOpenMedia: (Media) -> Unit,
    onPlayMedia: (Media) -> Unit,
    onPlayEpisode: (Media, Int, Int, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val state = libraryState) {
        LibraryState.Loading -> PageLoading("Loading your list…")
        is LibraryState.Failed -> PageError("My List could not load", state.message)
        is LibraryState.Ready -> {
            val actions = rememberMediaActions(index)

            // Prefer richer cached catalog media over the library-row fallback.
            val rows = remember(state, catalog, index, progress) {
                state.entries.mapNotNull { entry ->
                    val media = catalog.enrich(entry)
                    val category = index.categoryOf(media.id) ?: return@mapNotNull null
                    MyListRow(
                        media = media,
                        entry = entry,
                        category = category,
                        watchFraction = progress.fractionFor(media.id),
                        hasNewEpisodes = index.hasUnwatchedAired(media.id),
                        progress = progress.progressFor(media.id),
                    )
                }
            }

            MyListReady(
                rows = rows,
                index = index,
                catalog = catalog,
                actions = actions,
                mediaCard = mediaCard,
                onExplore = onExplore,
                onOpenMedia = onOpenMedia,
                onPlayMedia = onPlayMedia,
                onPlayEpisode = onPlayEpisode,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun MyListReady(
    rows: List<MyListRow>,
    index: LibraryIndex,
    catalog: MediaCatalog,
    actions: MediaActions,
    mediaCard: @Composable (Media, Modifier) -> Unit,
    onExplore: () -> Unit,
    onOpenMedia: (Media) -> Unit,
    onPlayMedia: (Media) -> Unit,
    onPlayEpisode: (Media, Int, Int, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    var view by remember { mutableStateOf(MyListView.Library) }
    // Preserve collapsed calendar groups across tab changes.
    val sections = rememberCalendarSections()

    Column(modifier = modifier.fillMaxSize()) {
        val shortViewport = PageLayoutDefaults.Viewport.isShort
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = PageLayoutDefaults.HorizontalPadding,
                    vertical = if (shortViewport) 6.dp else 12.dp,
                ),
        ) {
            val stacked = maxWidth < COMPACT_HEADER_WIDTH

            @Composable
            fun viewSwitch(switchModifier: Modifier) = SegmentedControl(
                options = MyListView.entries,
                selected = view,
                label = { it.label },
                icon = { it.icon },
                onSelect = { view = it },
                modifier = switchModifier,
            )

            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PageHeader(title = "My List", subtitle = summaryLine(rows))
                    viewSwitch(Modifier.fillMaxWidth())
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        PageHeader(title = "My List", subtitle = summaryLine(rows))
                    }
                    viewSwitch(Modifier.width(220.dp))
                }
            }
        }

        AnimatedContent(
            targetState = view,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val enterDuration = if (reducedMotion) 0 else 200
                val movementDuration = if (reducedMotion) 0 else 240
                val exitDuration = if (reducedMotion) 0 else 140
                val enter = fadeIn(tween(enterDuration)) + slideInHorizontally(tween(movementDuration)) {
                    if (forward) it / 8 else -it / 8
                }
                val exit = fadeOut(tween(exitDuration)) + slideOutHorizontally(tween(enterDuration)) {
                    if (forward) -it / 8 else it / 8
                }
                enter togetherWith exit
            },
            label = "MyListView",
        ) { current ->
            when (current) {
                MyListView.Library -> LibraryView(
                    rows = rows,
                    actions = actions,
                    mediaCard = mediaCard,
                    onExplore = onExplore,
                    onOpenMedia = onOpenMedia,
                    onPlayMedia = onPlayMedia,
                )

                MyListView.Calendar -> CalendarView(
                    index = index,
                    catalog = catalog,
                    sections = sections,
                    onOpenMedia = onOpenMedia,
                    onPlayMedia = onPlayMedia,
                    onPlayEpisode = onPlayEpisode,
                )
            }
        }
    }
}

@Composable
private fun LibraryView(
    rows: List<MyListRow>,
    actions: MediaActions,
    mediaCard: @Composable (Media, Modifier) -> Unit,
    onExplore: () -> Unit,
    onOpenMedia: (Media) -> Unit,
    onPlayMedia: (Media) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }

    var filters by remember { mutableStateOf(MyListFilters()) }
    var layout by remember { mutableStateOf(MyListLayout.Grid) }
    var undo by remember { mutableStateOf<MyListUndo?>(null) }
    val selection = rememberMyListSelection()

    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()

    val counts = remember(rows) { categoryCounts(rows) }
    val visible = remember(rows, filters) { applyFilters(rows, filters) }

    // Drop selections for titles removed from the library.
    LaunchedEffect(rows) { selection.retainAll(rows.map { it.id }.toSet()) }

    val hero = remember(rows) { continueWatching(rows) }

    // Read scroll state only from the visible layout.
    val scrolled by remember {
        derivedStateOf {
            when (layout) {
                MyListLayout.Grid -> gridState.firstVisibleItemIndex > 4
                MyListLayout.Rows -> listState.firstVisibleItemIndex > 4
            }
        }
    }

    fun removeRows(targets: List<MyListRow>) {
        if (targets.isEmpty()) return
        undo = MyListUndo(targets.map { it.entry })
        targets.forEach { actions.removeFromList(it.media) }
        selection.clear()
    }

    // Let headers scroll on compact or short screens, except when an empty result needs
    // the filters to remain available.
    val pinFilters = shouldPinListFilters(
        compactWidth = PageLayoutDefaults.IsCompact,
        shortViewport = PageLayoutDefaults.Viewport.isShort,
        listEmpty = visible.isEmpty(),
    )

    val categoryPills: @Composable (Modifier) -> Unit = { pillModifier ->
        MyListCategoryPills(
            counts = counts,
            total = rows.size,
            selected = filters.category,
            onSelect = { filters = filters.copy(category = it) },
            modifier = pillModifier,
        )
    }
    val listToolbar: @Composable (Modifier) -> Unit = { toolbarModifier ->
        MyListToolbar(
            filters = filters,
            layout = layout,
            selectionActive = selection.active,
            onFiltersChange = { filters = it },
            onLayoutChange = { layout = it },
            onToggleSelection = { selection.setSelectionMode(!selection.active) },
            modifier = toolbarModifier,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (pinFilters) {
                categoryPills(
                    Modifier.padding(
                        horizontal = PageLayoutDefaults.HorizontalPadding,
                        vertical = 6.dp,
                    ),
                )
                listToolbar(
                    Modifier.padding(
                        horizontal = PageLayoutDefaults.HorizontalPadding,
                        vertical = 8.dp,
                    ),
                )
            }

            if (visible.isEmpty()) {
                EmptyLibrary(
                    filters = filters,
                    listEmpty = rows.isEmpty(),
                    onExplore = onExplore,
                    onClearFilters = { filters = MyListFilters(sort = filters.sort) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                val heroContent: (@Composable () -> Unit)? = hero?.let { row ->
                    {
                        MyListHero(
                            row = row,
                            onOpen = { onOpenMedia(row.media) },
                            onResume = { onPlayMedia(row.media) },
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
                val headerContent: (@Composable () -> Unit)? = if (pinFilters) {
                    heroContent
                } else {
                    {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            categoryPills(Modifier.padding(vertical = 6.dp))
                            listToolbar(Modifier.padding(vertical = 8.dp))
                            heroContent?.invoke()
                        }
                    }
                }
                val padding = PaddingValues(
                    start = PageLayoutDefaults.HorizontalPadding,
                    end = PageLayoutDefaults.HorizontalPadding,
                    top = 8.dp,
                    bottom = 40.dp + PageLayoutDefaults.BottomClearance,
                )

                Box(modifier = Modifier.weight(1f)) {
                    when (layout) {
                        MyListLayout.Grid -> MyListGrid(
                            rows = visible,
                            selection = selection,
                            mediaCard = mediaCard,
                            contentPadding = padding,
                            state = gridState,
                            header = headerContent,
                        )

                        MyListLayout.Rows -> MyListRows(
                            rows = visible,
                            selection = selection,
                            actions = MyListRowActions(
                                onOpen = { onOpenMedia(it.media) },
                                onResume = { onPlayMedia(it.media) },
                                onRemove = { removeRows(listOf(it)) },
                            ),
                            today = today,
                            contentPadding = padding,
                            state = listState,
                            header = headerContent,
                        )
                    }

                    ScrollToTopButton(
                        visible = scrolled,
                        onClick = {
                            scope.launch {
                                when (layout) {
                                    MyListLayout.Grid -> gridState.animateScrollToItem(0)
                                    MyListLayout.Rows -> listState.animateScrollToItem(0)
                                }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(
                                end = PageLayoutDefaults.HorizontalPadding,
                                bottom = 24.dp,
                            ),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .zIndex(10f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MyListUndoBar(
                undo = undo,
                onUndo = {
                    undo?.entries?.forEach(actions::restore)
                    undo = null
                },
                onDismiss = { undo = null },
            )

            if (selection.active) {
                MyListSelectionBar(
                    count = selection.count,
                    onMoveTo = { category ->
                        // Resolve bulk actions against the full library, not the filtered view.
                        selection.selected
                            .mapNotNull { id -> rows.firstOrNull { it.id == id } }
                            .forEach { actions.setListCategory(it.media, category) }
                        selection.clear()
                    },
                    onRemove = {
                        removeRows(
                            selection.selected.mapNotNull { id -> rows.firstOrNull { it.id == id } },
                        )
                    },
                    onClear = { selection.setSelectionMode(false) },
                )
            }
        }
    }
}

@Composable
private fun CalendarView(
    index: LibraryIndex,
    catalog: MediaCatalog,
    sections: CalendarSectionState,
    onOpenMedia: (Media) -> Unit,
    onPlayMedia: (Media) -> Unit,
    onPlayEpisode: (Media, Int, Int, String?) -> Unit,
) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val calendarState by graph.calendar.calendar.collectAsState()
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }

    var month by remember { mutableStateOf(today.yearMonth) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { graph.calendar.refresh(force = false) }

    when (val state = calendarState) {
        CalendarState.Loading -> PageLoading("Building your calendar…")
        is CalendarState.Failed -> PageError("The calendar could not load", state.message)
        is CalendarState.Ready -> {
            val available = remember(state.items) { availableNow(state.items) }
            val days = remember(state.items, month, today) {
                groupByDay(itemsInMonth(state.items, month), today)
            }

            Column(modifier = Modifier.fillMaxSize()) {
                CalendarMonthBar(
                    month = month,
                    isCurrentMonth = month == today.yearMonth,
                    refreshing = state.refreshing,
                    onPrevious = { month = month.minusMonth() },
                    onNext = { month = month.plusMonth() },
                    onToday = {
                        month = today.yearMonth
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    onRefresh = { scope.launch { graph.calendar.refresh(force = true) } },
                    modifier = Modifier.padding(
                        horizontal = PageLayoutDefaults.HorizontalPadding,
                        vertical = 8.dp,
                    ),
                )

                CalendarAgenda(
                    available = available,
                    days = days,
                    today = today,
                    showAvailable = month == today.yearMonth,
                    sections = sections,
                    state = listState,
                    contentPadding = PaddingValues(
                        start = PageLayoutDefaults.HorizontalPadding,
                        end = PageLayoutDefaults.HorizontalPadding,
                        top = 4.dp,
                        bottom = 40.dp + PageLayoutDefaults.BottomClearance,
                    ),
                    onOpen = { item -> index.mediaFor(item, catalog)?.let(onOpenMedia) },
                    onPlay = { item ->
                        playCalendarItem(item, index, catalog, onPlayMedia, onPlayEpisode)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun playCalendarItem(
    item: CalendarItem,
    index: LibraryIndex,
    catalog: MediaCatalog,
    onPlayMedia: (Media) -> Unit,
    onPlayEpisode: (Media, Int, Int, String?) -> Unit,
) {
    val media = index.mediaFor(item, catalog) ?: return
    val season = item.seasonNumber
    val episode = item.episodeNumber
    if (season != null && episode != null) {
        onPlayEpisode(media, season, episode, item.episodeName.takeIf { it.isNotBlank() })
    } else {
        onPlayMedia(media)
    }
}

@Composable
private fun EmptyLibrary(
    filters: MyListFilters,
    listEmpty: Boolean,
    onExplore: () -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (listEmpty) {
        PageEmptyState(
            iconName = "lucide:bookmark-plus",
            title = "Your list is empty",
            message = if (hasPointerHover) {
                "Right-click a media card or drag it onto a list category in the navigation bar."
            } else {
                "Open a title and choose My List to save it here."
            },
            actionLabel = "Explore titles",
            onAction = onExplore,
            modifier = modifier,
        )
    } else {
        PageEmptyState(
            iconName = filters.category?.icon ?: "lucide:filter-x",
            title = "Nothing matches",
            message = "No saved title fits the filters you have on.",
            actionLabel = "Clear filters",
            onAction = onClearFilters,
            modifier = modifier,
        )
    }
}

/** Under this the title and the view switch stop fitting on one line. */
private val COMPACT_HEADER_WIDTH = 560.dp

private fun summaryLine(rows: List<MyListRow>): String {
    val counts = categoryCounts(rows)
    val parts = buildList {
        add(if (rows.size == 1) "1 title" else "${rows.size} titles")
        counts[MyListCategory.Watching]?.takeIf { it > 0 }?.let { add("$it watching") }
        counts[MyListCategory.Finished]?.takeIf { it > 0 }?.let { add("$it finished") }
    }
    return parts.joinToString("  ·  ")
}
