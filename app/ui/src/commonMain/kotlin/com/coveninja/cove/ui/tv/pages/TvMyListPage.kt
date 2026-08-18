package com.coveninja.cove.ui.tv.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.CalendarState
import com.coveninja.cove.shared.data.LibraryState
import com.coveninja.cove.shared.model.CalendarItem
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.pages.common.MediaRailStateStore
import com.coveninja.cove.ui.pages.common.rememberMediaRailStateStore
import com.coveninja.cove.ui.pages.home.calendarWideImageUrl
import com.coveninja.cove.ui.pages.mylist.MyListRow
import com.coveninja.cove.ui.pages.mylist.MyListView
import com.coveninja.cove.ui.pages.mylist.calendar.CalendarDay
import com.coveninja.cove.ui.pages.mylist.calendar.availableNow
import com.coveninja.cove.ui.pages.mylist.calendar.episodeMarker
import com.coveninja.cove.ui.pages.mylist.calendar.groupByDay
import com.coveninja.cove.ui.state.LibraryIndex
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.MediaCatalog
import com.coveninja.cove.ui.state.WatchProgressIndex
import com.coveninja.cove.ui.state.hasUnwatchedAired
import com.coveninja.cove.ui.state.mediaFor
import com.coveninja.cove.ui.tv.TvTheme
import com.coveninja.cove.ui.tv.components.TvButton
import com.coveninja.cove.ui.tv.components.TvComingSoonPage
import com.coveninja.cove.ui.tv.components.TvMediaCard
import com.coveninja.cove.ui.tv.components.TvMediaRow
import com.coveninja.cove.ui.tv.components.TvWideCard
import com.coveninja.cove.ui.tv.focus.TvSectionScroll
import com.coveninja.cove.ui.tv.focus.tvFocusGroup
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** Scroll positions worth keeping while another destination is on screen. */
@Stable
class TvMyListPageState internal constructor(
    internal val listState: LazyListState,
    internal val railStates: MediaRailStateStore,
)

@Composable
fun rememberTvMyListPageState(): TvMyListPageState {
    val listState = rememberLazyListState()
    val railStates = rememberMediaRailStateStore()
    return remember(listState, railStates) { TvMyListPageState(listState, railStates) }
}

/**
 * The library, one row per category, plus the release calendar.
 *
 * The phone's My List is a grid with a toolbar of filters, a sort control and a layout switch.
 * None of that survives the trip: every control is a focus stop standing between the viewer and
 * a poster, and the previous TV shell's own feedback was that there were far too many of them.
 * A category *is* a row here, so walking down the page is the filter and no control is needed
 * for it at all.
 *
 * Categories keep the phone's order and its counts, both from the same `MyListRow` shapes, so
 * the two screens never disagree about what is in the list or which pile it is in.
 */
@Composable
internal fun TvMyListPage(
    libraryState: LibraryState,
    pageState: TvMyListPageState,
    index: LibraryIndex,
    watchProgress: WatchProgressIndex,
    catalog: MediaCatalog,
    onOpenMedia: (Media) -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = LocalAppGraph.current
    val dimens = TvTheme.dimens
    val calendarState by graph.calendar.calendar.collectAsState()
    var view by remember { mutableStateOf(MyListView.Library) }
    var focusedSection by remember { mutableStateOf<Int?>(null) }

    val rows = remember(libraryState, catalog, index, watchProgress) {
        (libraryState as? LibraryState.Ready)?.entries.orEmpty().mapNotNull { entry ->
            val media = catalog.enrich(entry)
            val category = index.categoryOf(media.id) ?: return@mapNotNull null
            MyListRow(
                media = media,
                entry = entry,
                category = category,
                watchFraction = watchProgress.fractionFor(media.id),
                hasNewEpisodes = index.hasUnwatchedAired(media.id),
                progress = watchProgress.progressFor(media.id),
            )
        }
    }

    val calendarItems = remember(calendarState) {
        (calendarState as? CalendarState.Ready)?.items.orEmpty()
    }
    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }

    val sections = remember(rows, calendarItems, view, today) {
        tvMyListSections(rows, calendarItems, view, today)
    }

    TvSectionScroll(
        state = pageState.listState,
        focusedIndex = focusedSection,
        margin = dimens.focusScrollMargin,
    )

    LazyColumn(
        state = pageState.listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = dimens.overscanVertical + 20.dp,
            bottom = dimens.overscanVertical + 32.dp,
        ),
    ) {
        item(key = "view-switch") {
            Row(
                modifier = Modifier
                    .padding(horizontal = dimens.overscanHorizontal)
                    .tvFocusGroup(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MyListView.entries.forEach { entry ->
                    TvButton(
                        label = entry.label,
                        onClick = { view = entry },
                        icon = entry.icon,
                        selected = entry == view,
                    )
                }
            }
        }

        if (sections.isEmpty()) {
            item(key = "empty") {
                TvComingSoonPage(
                    title = if (view == MyListView.Library) {
                        "Nothing saved yet"
                    } else {
                        "Nothing scheduled"
                    },
                    detail = if (view == MyListView.Library) {
                        "Titles you save turn up here, sorted into the pile you put them in."
                    } else {
                        "Episodes from the shows you follow appear here as they are dated."
                    },
                    icon = if (view == MyListView.Library) {
                        "iconamoon:bookmark"
                    } else {
                        "lucide:calendar-days"
                    },
                )
            }
            return@LazyColumn
        }

        itemsIndexed(
            items = sections,
            key = { _, section -> section.key },
        ) { position, section ->
            // Offset by one: the view switch is item zero, and a section reporting its own
            // index in `sections` would scroll the page to the row above it.
            val report: (Boolean) -> Unit = { focused ->
                if (focused) focusedSection = position + 1
            }
            when (section) {
                is TvMyListSection.Category -> TvMediaRow(
                    title = section.category.label,
                    subtitle = "${section.rows.size} titles",
                    icon = section.category.icon,
                    items = section.rows,
                    key = MyListRow::id,
                    state = pageState.railStates.stateFor(section.key),
                    onFocusChanged = report,
                    modifier = Modifier.padding(top = dimens.sectionSpacing),
                ) { row ->
                    TvMediaCard(
                        media = row.media,
                        watchFraction = row.watchFraction,
                        onClick = { onOpenMedia(row.media) },
                    )
                }

                is TvMyListSection.Available -> TvMediaRow(
                    title = "Ready to watch",
                    subtitle = "${section.items.size} waiting",
                    icon = "lucide:tv",
                    items = section.items,
                    key = CalendarItem::id,
                    state = pageState.railStates.stateFor(section.key),
                    onFocusChanged = report,
                    modifier = Modifier.padding(top = dimens.sectionSpacing),
                ) { item ->
                    TvCalendarCard(item = item, index = index, catalog = catalog, onOpen = onOpenMedia)
                }

                is TvMyListSection.Day -> TvMediaRow(
                    title = section.day.label,
                    subtitle = section.day.relative,
                    icon = "lucide:calendar-clock",
                    items = section.day.items,
                    key = CalendarItem::id,
                    state = pageState.railStates.stateFor(section.key),
                    onFocusChanged = report,
                    modifier = Modifier.padding(top = dimens.sectionSpacing),
                ) { item ->
                    TvCalendarCard(item = item, index = index, catalog = catalog, onOpen = onOpenMedia)
                }
            }
        }
    }
}

/**
 * A calendar entry as a card.
 *
 * Only openable when the entry resolves back to a saved title. Nothing reaches the calendar
 * that is not in the library, so an unresolved one means the library moved underneath a cached
 * snapshot — and a card whose press does nothing is worse than one that plainly cannot be
 * pressed.
 */
@Composable
private fun TvCalendarCard(
    item: CalendarItem,
    index: LibraryIndex,
    catalog: MediaCatalog,
    onOpen: (Media) -> Unit,
) {
    val media = remember(item, index, catalog) { index.mediaFor(item, catalog) }
    TvWideCard(
        imageUrl = calendarWideImageUrl(item),
        title = item.title,
        caption = listOfNotNull(
            item.episodeMarker(),
            item.episodeName.takeIf { it.isNotBlank() },
        ).joinToString("  ·  ").ifBlank { item.date },
        badge = item.waitingCount.takeIf { it > 0 }?.let { "$it waiting" },
        onClick = { media?.let(onOpen) },
    )
}


/**
 * The rows the page is made of, for the view being shown.
 *
 * Pure, and separate from the drawing, because two of the decisions in here fail quietly. An
 * empty category has to be dropped rather than drawn as a heading over nothing, and a
 * watchable-now entry must appear in exactly one place — its air date points backwards,
 * sometimes by months, so grouping it by day as well would both duplicate it and file the
 * duplicate somewhere nobody scrolls to.
 */
internal fun tvMyListSections(
    rows: List<MyListRow>,
    calendarItems: List<CalendarItem>,
    view: MyListView,
    today: LocalDate,
): List<TvMyListSection> = when (view) {
    MyListView.Library -> MyListCategory.entries.mapNotNull { category ->
        rows.filter { it.category == category }
            .takeIf { it.isNotEmpty() }
            ?.let { TvMyListSection.Category(category, it) }
    }

    MyListView.Calendar -> buildList {
        availableNow(calendarItems)
            .takeIf { it.isNotEmpty() }
            ?.let { add(TvMyListSection.Available(it)) }
        groupByDay(calendarItems.filter { !it.available }, today)
            .forEach { day -> add(TvMyListSection.Day(day)) }
    }
}

/**
 * One row of the page, so its index and its focus report cannot drift apart.
 *
 * Keys are hyphenated rather than colon-separated because `verifyIcons` scans source for quoted
 * `prefix:name` literals and fails the build on any it cannot find artwork for — a section key
 * that happens to read like an icon name takes the whole build with it.
 */
internal sealed interface TvMyListSection {
    val key: String

    data class Category(
        val category: MyListCategory,
        val rows: List<MyListRow>,
    ) : TvMyListSection {
        override val key: String get() = "category-${category.name}"
    }

    data class Available(val items: List<CalendarItem>) : TvMyListSection {
        override val key: String get() = "calendar-available"
    }

    data class Day(val day: CalendarDay) : TvMyListSection {
        override val key: String get() = "calendar-${day.key}"
    }
}
