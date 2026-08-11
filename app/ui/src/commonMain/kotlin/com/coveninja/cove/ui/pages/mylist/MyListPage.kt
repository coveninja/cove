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
import com.coveninja.cove.ui.state.LibraryIndex
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.mediaFor
import com.coveninja.cove.ui.state.MediaActions
import com.coveninja.cove.ui.state.rememberLibraryIndex
import com.coveninja.cove.ui.state.rememberMediaActions
import com.coveninja.cove.ui.state.rememberMediaCatalog
import com.coveninja.cove.ui.state.rememberWatchProgressIndex
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minusMonth
import kotlinx.datetime.plusMonth
import kotlinx.datetime.todayIn
import kotlinx.datetime.yearMonth
import kotlin.time.Clock

@Composable
fun MyListPage(
    mediaCard: @Composable (Media, Modifier) -> Unit,
    onExplore: () -> Unit,
    onOpenMedia: (Media) -> Unit,
    onPlayMedia: (Media) -> Unit,
    onPlayEpisode: (Media, Int, Int, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = LocalAppGraph.current
    val libraryState by graph.library.entries.collectAsState()

    when (val state = libraryState) {
        LibraryState.Loading -> PageLoading("Loading your list…")
        is LibraryState.Failed -> PageError("My List could not load", state.message)
        is LibraryState.Ready -> {
            val catalog = rememberMediaCatalog()
            val index = rememberLibraryIndex()
            val progress = rememberWatchProgressIndex()
            val actions = rememberMediaActions(index)

            // Enrich each library entry with the fuller domain object from the
            // cross-page catalog (home/explore/search), falling back to the thin
            // library-row version when no richer record is cached yet.
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
    actions: MediaActions,
    mediaCard: @Composable (Media, Modifier) -> Unit,
    onExplore: () -> Unit,
    onOpenMedia: (Media) -> Unit,
    onPlayMedia: (Media) -> Unit,
    onPlayEpisode: (Media, Int, Int, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var view by remember { mutableStateOf(MyListView.Library) }
    // Owned here rather than inside the calendar so a trip to the Library tab does not
    // silently unfold everything the viewer collapsed.
    val sections = rememberCalendarSections()

    Column(modifier = modifier.fillMaxSize()) {
        // The switch sits beside the title where there is room and beneath it where there
        // is not; on a phone the two together are wider than the screen.
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
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
                val enter = fadeIn(tween(200)) + slideInHorizontally(tween(240)) {
                    if (forward) it / 8 else -it / 8
                }
                val exit = fadeOut(tween(140)) + slideOutHorizontally(tween(200)) {
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

    // A title removed while ticked must not stay ticked, or a later bulk action would
    // reach for something that no longer exists.
    LaunchedEffect(rows) { selection.retainAll(rows.map { it.id }.toSet()) }

    val hero = remember(rows) { continueWatching(rows) }

    // Only the layout on screen has a meaningful scroll position; reading both would let
    // the other one's stale offset keep the button up after a layout switch.
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

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            MyListCategoryPills(
                counts = counts,
                total = rows.size,
                selected = filters.category,
                onSelect = { filters = filters.copy(category = it) },
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )

            MyListToolbar(
                filters = filters,
                layout = layout,
                selectionActive = selection.active,
                onFiltersChange = { filters = it },
                onLayoutChange = { layout = it },
                onToggleSelection = { selection.setSelectionMode(!selection.active) },
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

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
                val padding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 40.dp)

                Box(modifier = Modifier.weight(1f)) {
                    when (layout) {
                        MyListLayout.Grid -> MyListGrid(
                            rows = visible,
                            selection = selection,
                            mediaCard = mediaCard,
                            contentPadding = padding,
                            state = gridState,
                            header = heroContent,
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
                            header = heroContent,
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
                            .padding(end = 24.dp, bottom = 24.dp),
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
                        // Resolved against every row, not the filtered view: changing a
                        // filter after ticking must not silently drop titles from the
                        // action the viewer is about to confirm.
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

    // The repository decides whether this actually costs anything: it serves the cache
    // until the library changes or the window lapses.
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
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )

                CalendarAgenda(
                    available = available,
                    days = days,
                    today = today,
                    // Backlog belongs to now, not to whichever month is being browsed.
                    showAvailable = month == today.yearMonth,
                    sections = sections,
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 24.dp,
                        end = 24.dp,
                        top = 4.dp,
                        bottom = 40.dp,
                    ),
                    onOpen = { item -> index.mediaFor(item)?.let(onOpenMedia) },
                    onPlay = { item -> playCalendarItem(item, index, onPlayMedia, onPlayEpisode) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun playCalendarItem(
    item: CalendarItem,
    index: LibraryIndex,
    onPlayMedia: (Media) -> Unit,
    onPlayEpisode: (Media, Int, Int, String?) -> Unit,
) {
    val media = index.mediaFor(item) ?: return
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
            message = "Right-click a media card or drag it onto a list category in the navigation bar.",
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
