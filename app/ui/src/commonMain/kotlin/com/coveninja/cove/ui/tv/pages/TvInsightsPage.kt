package com.coveninja.cove.ui.tv.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.LibraryState
import com.coveninja.cove.shared.model.ActivityStats
import com.coveninja.cove.shared.model.DiscoveryInsights
import com.coveninja.cove.shared.model.InsightsRange
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.components.insights.ChartLegend
import com.coveninja.cove.ui.components.insights.CompositionRing
import com.coveninja.cove.ui.components.insights.MonthBars
import com.coveninja.cove.ui.components.insights.PosterWall
import com.coveninja.cove.ui.components.insights.RankedBars
import com.coveninja.cove.ui.components.insights.RingLegend
import com.coveninja.cove.ui.components.insights.RingSlice
import com.coveninja.cove.ui.components.insights.StatTile
import com.coveninja.cove.ui.components.insights.WeekdayBars
import com.coveninja.cove.ui.pages.profile.LibraryBreakdown
import com.coveninja.cove.ui.pages.profile.MonthBar
import com.coveninja.cove.ui.pages.profile.TasteBar
import com.coveninja.cove.ui.pages.profile.formatWatchDuration
import com.coveninja.cove.ui.pages.profile.insightsAreEmpty
import com.coveninja.cove.ui.pages.profile.libraryBreakdown
import com.coveninja.cove.ui.pages.profile.monthBars
import com.coveninja.cove.ui.pages.profile.normalizeTaste
import com.coveninja.cove.ui.pages.profile.rhythmSummary
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.tv.TvTheme
import com.coveninja.cove.ui.tv.components.TvButton
import com.coveninja.cove.ui.tv.components.TvComingSoonPage
import com.coveninja.cove.ui.tv.focus.TvSectionScroll
import com.coveninja.cove.ui.tv.focus.tvFocusGroup
import com.coveninja.cove.ui.tv.focus.tvFocusVisuals
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** Survives a trip to another destination, so reopening does not refetch or lose the scroll. */
@Stable
class TvInsightsPageState internal constructor(
    internal val listState: LazyListState,
) {
    internal var range by mutableStateOf(InsightsRange.AllTime)
    internal var activity by mutableStateOf<ActivityStats?>(null)
    internal var loadedRange by mutableStateOf<InsightsRange?>(null)
    internal var taste by mutableStateOf<DiscoveryInsights?>(null)
}

@Composable
fun rememberTvInsightsPageState(): TvInsightsPageState {
    val listState = rememberLazyListState()
    return remember(listState) { TvInsightsPageState(listState) }
}

/**
 * What the viewer's own history has to say about them, at three metres.
 *
 * Every number and every bar here is [com.coveninja.cove.ui.pages.profile.InsightsModel]'s,
 * unchanged — it is four hundred lines of pure functions over the same `ActivityStats`, so a
 * total that reads one way on a phone reads the same way here.
 *
 * What is dropped is dropped for the distance rather than for the remote. The year heatmap is
 * a grid of cells a few pixels across and unreadable from a sofa; the recap is a portrait image
 * built to be *sent* to somebody, and a television has nowhere to send it. What is left is the
 * part that survives being looked at rather than read: big figures, few bars, and posters.
 *
 * Each card is one focus stop and nothing inside it is focusable. That is not only a focus
 * budget — a page of unfocusable content cannot be scrolled by a D-pad at all, so the cards
 * being reachable is what makes the page navigable in the first place.
 */
@Composable
internal fun TvInsightsPage(
    pageState: TvInsightsPageState,
    modifier: Modifier = Modifier,
) {
    val graph = LocalAppGraph.current
    val dimens = TvTheme.dimens
    val libraryState by graph.library.entries.collectAsState()
    val entries = (libraryState as? LibraryState.Ready)?.entries.orEmpty()
    var focusedSection by remember { mutableStateOf<Int?>(null) }

    // Refetched when the range changes, not merely because the page was reopened — which is
    // what the hoisted state is for.
    LaunchedEffect(graph, pageState.range) {
        if (pageState.loadedRange == pageState.range && pageState.activity != null) {
            return@LaunchedEffect
        }
        pageState.activity = runCatching { graph.insights.activity(pageState.range) }
            .getOrDefault(ActivityStats())
        pageState.loadedRange = pageState.range
    }
    LaunchedEffect(graph) {
        if (pageState.taste == null) {
            pageState.taste = runCatching { graph.insights.taste() }
                .getOrDefault(DiscoveryInsights())
        }
    }

    val stats = pageState.activity
    if (stats == null) {
        TvComingSoonPage(
            title = "Working it out",
            detail = "Reading what you have watched.",
            icon = "lucide:loader-circle",
            modifier = modifier,
        )
        return
    }

    val profile = pageState.taste ?: DiscoveryInsights()
    if (insightsAreEmpty(stats, profile, entries.size)) {
        TvComingSoonPage(
            title = "Nothing to show yet",
            detail = "Watch something and this page fills in on its own.",
            icon = "lucide:chart-line",
            modifier = modifier,
        )
        return
    }

    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    val breakdown = remember(entries) { libraryBreakdown(entries) }
    val sections = remember(stats, profile, breakdown) {
        buildTvInsightsSections(stats, profile, breakdown)
    }

    TvSectionScroll(
        state = pageState.listState,
        focusedIndex = focusedSection,
        margin = dimens.focusScrollMargin,
    )

    LazyColumn(
        state = pageState.listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = dimens.overscanHorizontal,
            end = dimens.overscanHorizontal,
            top = dimens.overscanVertical + 20.dp,
            bottom = dimens.overscanVertical + 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(key = "range") {
            Row(
                modifier = Modifier.tvFocusGroup(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Two, not the phone's three. "Last year" is a comparison, and the year bars
                // below already draw it against this one.
                TV_RANGES.forEach { entry ->
                    TvButton(
                        label = tvRangeLabel(entry),
                        onClick = { pageState.range = entry },
                        selected = entry == pageState.range,
                    )
                }
            }
        }

        itemsIndexed(items = sections, key = { _, section -> section.key }) { position, section ->
            TvInsightsCard(
                title = section.title,
                caption = section.caption,
                // Offset by one: the range switch is item zero, and a card reporting its own
                // index would scroll the page to the row above it.
                onFocusChanged = { if (it) focusedSection = position + 1 },
            ) {
                TvInsightsSectionBody(section = section, thisYear = today.year)
            }
        }
    }
}

@Composable
private fun TvInsightsSectionBody(section: TvInsightsSection, thisYear: Int) {
    when (section) {
        is TvInsightsSection.Headline -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            section.tiles.forEach { tile ->
                StatTile(
                    icon = tile.icon,
                    value = tile.value,
                    caption = tile.caption,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        is TvInsightsSection.Posters -> PosterWall(
            posterPaths = section.posterPaths,
            modifier = Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(14.dp)),
            tile = 110.dp,
        )

        is TvInsightsSection.Months -> Column {
            MonthBars(bars = section.bars, height = 150.dp)
            ChartLegend(
                entries = listOf(
                    thisYear.toString() to CoveColors.Brand.Accent,
                    (thisYear - 1).toString() to CoveColors.Neutral.MutedDim,
                ),
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        is TvInsightsSection.Weekdays -> WeekdayBars(seconds = section.seconds)

        is TvInsightsSection.Composition -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            CompositionRing(
                slices = section.slices,
                centreValue = section.total.toString(),
                centreCaption = "titles",
            )
            RingLegend(slices = section.slices, modifier = Modifier.weight(1f))
        }

        is TvInsightsSection.Taste -> RankedBars(bars = section.bars)
    }
}

/**
 * A card that can be focused but does nothing when pressed.
 *
 * Deliberately inert. Focus here is a scrolling mechanism, not an invitation — there is
 * nothing on this page to open — so it takes the focus ring and skips the scale and the
 * activation that [com.coveninja.cove.ui.tv.focus.tvFocusTarget] would add, both of which
 * would promise a press that has nowhere to go.
 */
@Composable
private fun TvInsightsCard(
    title: String,
    caption: String?,
    onFocusChanged: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(focused) { onFocusChanged(focused) }
    val shape = RoundedCornerShape(18.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 1180.dp)
            .tvFocusVisuals(focused = focused, shape = shape, scale = 1f)
            .focusable(interactionSource = interactionSource)
            .background(CoveColors.Neutral.Surface, shape)
            .padding(horizontal = 26.dp, vertical = 22.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = CoveColors.Neutral.Text,
        )
        caption?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = CoveColors.Neutral.MutedDim,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Box(modifier = Modifier.padding(top = 18.dp)) { content() }
    }
}

/** One headline figure. */
internal data class TvStat(val icon: String, val value: String, val caption: String)

/** A card on the page, so its index in the list and its focus report cannot drift apart. */
internal sealed interface TvInsightsSection {
    val key: String
    val title: String
    val caption: String?

    data class Headline(val tiles: List<TvStat>) : TvInsightsSection {
        override val key: String get() = "headline"
        override val title: String get() = "The numbers"
        override val caption: String? get() = null
    }

    data class Posters(val posterPaths: List<String>, override val caption: String?) :
        TvInsightsSection {
        override val key: String get() = "posters"
        override val title: String get() = "What you watched"
    }

    data class Months(
        val bars: List<MonthBar>,
    ) : TvInsightsSection {
        override val key: String get() = "months"
        override val title: String get() = "Across the year"
        override val caption: String? get() = "This year against last."
    }

    data class Weekdays(
        val seconds: List<Long>,
        override val caption: String?,
    ) : TvInsightsSection {
        override val key: String get() = "weekdays"
        override val title: String get() = "Across the week"
    }

    data class Composition(val slices: List<RingSlice>, val total: Int) : TvInsightsSection {
        override val key: String get() = "composition"
        override val title: String get() = "Your list"
        override val caption: String? get() = "Where the titles you saved ended up."
    }

    data class Taste(
        val bars: List<TasteBar>,
    ) : TvInsightsSection {
        override val key: String get() = "taste"
        override val title: String get() = "What you go back to"
        override val caption: String? get() = "Genres, weighted by what you actually finish."
    }
}

/**
 * The cards worth drawing, for the history there is.
 *
 * Pure and separate from the drawing for the same reason every other TV page does it: a card
 * whose slice is empty has to be *absent* rather than drawn as a chart of zeroes, and on this
 * shell an empty card is also a focus stop with nothing in it — a place the D-pad stops for no
 * reason on the way down the page.
 */
internal fun buildTvInsightsSections(
    stats: ActivityStats,
    profile: DiscoveryInsights,
    breakdown: LibraryBreakdown,
): List<TvInsightsSection> = buildList {
    add(
        TvInsightsSection.Headline(
            listOf(
                TvStat("lucide:clock", formatWatchDuration(stats.totalSeconds), "watched"),
                TvStat("lucide:film", stats.totalTitles.toString(), "titles"),
                TvStat("lucide:flame", stats.currentStreak.toString(), "day streak"),
                TvStat(
                    "lucide:calendar-days",
                    formatWatchDuration(stats.avgSecondsPerActiveDay),
                    "on an active day",
                ),
            ),
        ),
    )

    stats.titlesWatchedThisYear
        .map { it.posterPath }
        .filter { it.isNotBlank() }
        .takeIf { it.isNotEmpty() }
        ?.let { posters ->
            add(
                TvInsightsSection.Posters(
                    posterPaths = posters,
                    caption = "${stats.titlesWatchedThisYear.size} titles.",
                ),
            )
        }

    // Both years flat means nothing has been watched in either, and twelve empty columns say
    // that far less clearly than the card simply not being there.
    if (stats.byMonthThisYear.any { it > 0 } || stats.byMonthLastYear.any { it > 0 }) {
        add(
            TvInsightsSection.Months(
                monthBars(stats.byMonthThisYear, stats.byMonthLastYear),
            ),
        )
    }

    if (stats.byDayOfWeek.any { it > 0 }) {
        add(TvInsightsSection.Weekdays(stats.byDayOfWeek, rhythmSummary(stats)))
    }

    if (breakdown.total > 0) {
        add(
            TvInsightsSection.Composition(
                slices = listOf(
                    RingSlice(
                        "Watching",
                        breakdown.statusCounts[LibraryStatus.Watching] ?: 0,
                        CoveColors.Category.Watching,
                    ),
                    RingSlice(
                        "Finished",
                        breakdown.statusCounts[LibraryStatus.Finished] ?: 0,
                        CoveColors.Category.Finished,
                    ),
                    RingSlice(
                        "Watch later",
                        breakdown.statusCounts[LibraryStatus.WatchLater] ?: 0,
                        CoveColors.Category.WatchLater,
                    ),
                    RingSlice(
                        "Dropped",
                        breakdown.statusCounts[LibraryStatus.Dropped] ?: 0,
                        CoveColors.Category.Dropped,
                    ),
                ),
                total = breakdown.total,
            ),
        )
    }

    normalizeTaste(profile.topMovieGenres + profile.topTvGenres)
        .sortedByDescending { it.fraction }
        .take(TASTE_BARS)
        .takeIf { it.isNotEmpty() }
        ?.let { add(TvInsightsSection.Taste(it)) }
}

/** Deliberately two: "last year" is a comparison, and the month bars already draw it. */
internal val TV_RANGES = listOf(InsightsRange.ThisYear, InsightsRange.AllTime)

internal fun tvRangeLabel(range: InsightsRange): String = when (range) {
    InsightsRange.ThisYear -> "This year"
    InsightsRange.LastYear -> "Last year"
    InsightsRange.AllTime -> "All time"
}

/** Past this the bars are too short to compare from across a room. */
private const val TASTE_BARS = 6
