package com.coveninja.cove.ui.pages.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.LibraryState
import com.coveninja.cove.shared.model.ActivityStats
import com.coveninja.cove.shared.model.DiscoveryInsights
import com.coveninja.cove.shared.model.InsightsRange
import com.coveninja.cove.shared.model.TrackerStats
import com.coveninja.cove.shared.model.DiscoveryTaste
import com.coveninja.cove.shared.model.LibraryStatus
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.components.insights.ActivityHeatmap
import com.coveninja.cove.ui.components.insights.ChartLegend
import com.coveninja.cove.ui.components.insights.CompositionRing
import com.coveninja.cove.ui.components.insights.MonthBars
import com.coveninja.cove.ui.components.insights.RankedBars
import com.coveninja.cove.ui.components.insights.RingLegend
import com.coveninja.cove.ui.components.insights.RingSlice
import com.coveninja.cove.ui.components.insights.StatTile
import com.coveninja.cove.ui.components.insights.rememberCountUp
import com.coveninja.cove.ui.components.insights.ViewingClock
import com.coveninja.cove.ui.components.insights.WeekdayBars
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.pages.common.ChoicePill
import com.coveninja.cove.ui.pages.common.ChoicePillRow
import com.coveninja.cove.ui.pages.common.PageLayoutDefaults
import com.coveninja.cove.ui.pages.common.WindowSizeClass
import com.coveninja.cove.ui.pages.common.ShimmerBlock
import com.coveninja.cove.ui.pages.common.StaggeredAppear
import com.coveninja.cove.ui.state.LocalAppGraph
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/**
 * Everything the viewer's own history has to say about them.
 *
 * Reads top to bottom as one argument: how much (the hero), the headline numbers, when
 * across the year, when across the day and week, the year day by day, what specifically,
 * what the library looks like, and finally what all of that adds up to as taste.
 *
 * A plain [Column], not a `LazyColumn` — the whole profile page is already inside a
 * `verticalScroll`, and nesting a lazy list in one crashes on unbounded height. The
 * horizontal rows inside are lazy, which is where laziness actually buys anything here.
 *
 * Every section hides itself when its own slice is empty rather than drawing a chart of
 * zeroes. A new profile therefore grows this page a section at a time instead of showing a
 * wall of flat lines that look like a bug.
 */
/**
 * What the page has fetched, kept above the tab switcher.
 *
 * `ProfilePage` swaps tabs with an `AnimatedContent`, which disposes whichever tab is not
 * showing — so state owned inside the tab is thrown away and refetched every time the
 * viewer looks at Settings and comes back. The taste half costs one metadata request per
 * saved title when its cache misses, which is not a price to pay for a tab switch. Hoisting
 * it here is the same fix `openedCategory` already uses one level up.
 */
@Stable
internal class InsightsState {
    var range by mutableStateOf(InsightsRange.ThisYear)
    var activity by mutableStateOf<ActivityStats?>(null)
    var taste by mutableStateOf<DiscoveryInsights?>(null)
    var trackers by mutableStateOf<List<TrackerStats>?>(null)

    /** Which range [activity] holds, so returning to the tab does not refetch the same one. */
    var loadedRange by mutableStateOf<InsightsRange?>(null)
}

@Composable
internal fun rememberInsightsState(): InsightsState = remember { InsightsState() }

/**
 * Everything the viewer's own history has to say about them.
 *
 * Reads top to bottom as one argument: how much (the hero), the headline numbers, when
 * across the year, when across the day and week, the year day by day, what specifically,
 * what the library looks like, and finally what all of that adds up to as taste.
 *
 * A plain [Column], not a `LazyColumn` — the whole profile page is already inside a
 * `verticalScroll`, and nesting a lazy list in one crashes on unbounded height. The
 * horizontal rows inside are lazy, which is where laziness actually buys anything here.
 *
 * Every section hides itself when its own slice is empty rather than drawing a chart of
 * zeroes. A new profile therefore grows this page a section at a time instead of showing a
 * wall of flat lines that look like a bug.
 */
@Composable
internal fun InsightsTab(
    state: InsightsState,
    onOpenMedia: (Media) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val graph = LocalAppGraph.current
    val libraryState by graph.library.entries.collectAsState()
    val entries = (libraryState as? LibraryState.Ready)?.entries.orEmpty()
    val progress by graph.library.watchProgress.collectAsState()

    // Refetched when the range changes and on first load, but not merely because the tab
    // was reopened — the guard is what makes the hoisted state worth having.
    LaunchedEffect(graph, state.range) {
        if (state.loadedRange == state.range && state.activity != null) return@LaunchedEffect
        val loaded = runCatching { graph.insights.activity(state.range) }
            .getOrDefault(ActivityStats())
        state.activity = loaded
        state.loadedRange = state.range
    }
    // The taste half does not vary by range, so it is fetched once and kept.
    LaunchedEffect(graph) {
        if (state.taste == null) {
            state.taste = runCatching { graph.insights.taste() }
                .getOrDefault(DiscoveryInsights())
        }
        if (state.trackers == null) {
            state.trackers = runCatching { graph.insights.trackers() }.getOrDefault(emptyList())
        }
    }

    val stats = state.activity
    if (stats == null) {
        InsightsSkeleton(modifier)
        return
    }

    val profile = state.taste ?: DiscoveryInsights()
    if (insightsAreEmpty(stats, profile, entries.size)) {
        InsightsEmpty(modifier)
        return
    }

    val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
    val compact = PageLayoutDefaults.IsCompact
    val twoUp = PageLayoutDefaults.Viewport.sizeClass == WindowSizeClass.Expanded
    val breakdown = libraryBreakdown(entries)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        InsightsHero(activity = stats, thisYear = today.year)

        RangePicker(selected = state.range, onSelect = { state.range = it })

        StatTiles(stats = stats, breakdown = breakdown, compact = compact, range = state.range)

        // Cards arrive in reading order rather than all at once. The taste half composes
        // whenever its slower fetch lands, so the same wrapper doubles as its arrival
        // animation instead of it popping in fully formed.
        if (stats.totalSeconds > 0L) {
            StaggeredAppear(index = 1) { MonthlySection(stats = stats, thisYear = today.year) }
            StaggeredAppear(index = 2) { RhythmSection(stats = stats, compact = compact) }
            StaggeredAppear(index = 3) {
                HeatmapSection(stats = stats, today = today, range = state.range)
            }
        }

        if (state.range == InsightsRange.AllTime && stats.byYear.size > 1) {
            StaggeredAppear(index = 4) { AllTimeSection(stats = stats) }
        }

        if (stats.titlesWatchedThisYear.isNotEmpty()) {
            StaggeredAppear(index = 4) {
                SettingsCard(
                    title = "Your top picks by watch time",
                    iconName = "lucide:flame",
                    description = rangeLeaderboardCaption(state.range),
                ) {
                    TopTitlesRow(
                        titles = stats.titlesWatchedThisYear,
                        onOpenMedia = onOpenMedia,
                        modifier = Modifier.padding(top = InsightsCardTop, bottom = 16.dp),
                    )
                }
            }
        }

        // The smaller cards pair up on a desktop window. In one column they leave most of a
        // 1000dp page empty, and the page is long enough that halving its height matters
        // more than keeping a single reading order.
        CardGrid(
            twoUp = twoUp,
            cards = buildList {
                if (entries.isNotEmpty()) {
                    add { CompositionSection(breakdown = breakdown, compact = true) }
                }
                val comparison = ratingComparison(entries)
                if (comparison.rated > 0) add { CrowdSection(comparison = comparison) }
                val finish = finishStats(progress)
                if (finish.started > 0) add { FinishSection(stats = finish) }
                if (entries.isNotEmpty()) {
                    add { GrowthSection(entries = entries, today = today) }
                }
                if (profile.decades.isNotEmpty()) add { DecadesSection(profile = profile) }
                if (profile.languages.isNotEmpty()) add { LanguagesSection(profile = profile) }
                if (stats.rewatched.isNotEmpty()) {
                    add { RewatchSection(titles = stats.rewatched, onOpenMedia = onOpenMedia) }
                }
                state.trackers.orEmpty().forEach { tracker ->
                    add { TrackerSection(stats = tracker) }
                }
            },
        )

        TasteSections(profile = profile, onOpenMedia = onOpenMedia, compact = compact)

        if (profile.signalsUsed > 0) {
            StaggeredAppear(index = 9) {
                RecommendationExplainer(signalsUsed = profile.signalsUsed)
            }
        }
    }
}

/** The period every range-sensitive figure on the page is answering for. */
@Composable
private fun RangePicker(selected: InsightsRange, onSelect: (InsightsRange) -> Unit) {
    ChoicePillRow {
        InsightsRange.entries.forEach { range ->
            ChoicePill(
                label = rangeLabel(range),
                selected = selected == range,
                onClick = { onSelect(range) },
            )
        }
    }
}

private fun rangeLabel(range: InsightsRange): String = when (range) {
    InsightsRange.ThisYear -> "This year"
    InsightsRange.LastYear -> "Last year"
    InsightsRange.AllTime -> "All time"
}

private fun rangeLeaderboardCaption(range: InsightsRange): String = when (range) {
    InsightsRange.ThisYear -> "The titles you gave the most hours to this year."
    InsightsRange.LastYear -> "The titles you gave the most hours to last year."
    InsightsRange.AllTime -> "The titles you have given the most hours to."
}

/**
 * Lays a set of cards out in one column, or in two balanced ones.
 *
 * Alternating cards between two fixed columns was the obvious approach and produced a
 * visibly ragged result: the cards differ in height by a factor of three, so one side ran
 * far past the other and left a long empty gap beside the last few. This measures each card
 * first and drops it into whichever column is currently shorter, which keeps the two sides
 * within one card's height of each other however the mix changes as sections hide
 * themselves.
 *
 * A custom [Layout] rather than a staggered grid because the whole page already sits inside
 * a `verticalScroll`, and the lazy staggered grid cannot be nested in one.
 */
@Composable
private fun CardGrid(twoUp: Boolean, cards: List<@Composable () -> Unit>) {
    if (cards.isEmpty()) return
    if (!twoUp) {
        Column(verticalArrangement = Arrangement.spacedBy(CardSpacing)) {
            cards.forEachIndexed { index, card ->
                StaggeredAppear(index = index + 5) { card() }
            }
        }
        return
    }
    Layout(
        content = {
            cards.forEachIndexed { index, card ->
                StaggeredAppear(index = index + 5) { card() }
            }
        },
    ) { measurables, constraints ->
        val gap = CardSpacing.roundToPx()
        val columnWidth = ((constraints.maxWidth - gap) / 2).coerceAtLeast(0)
        val childConstraints = constraints.copy(
            minWidth = columnWidth,
            maxWidth = columnWidth,
            minHeight = 0,
            maxHeight = Constraints.Infinity,
        )
        val columnHeights = intArrayOf(0, 0)
        val placements = measurables.map { measurable ->
            val placeable = measurable.measure(childConstraints)
            // Shorter column wins; ties go left so the order stays predictable.
            val column = if (columnHeights[0] <= columnHeights[1]) 0 else 1
            val x = column * (columnWidth + gap)
            val y = columnHeights[column]
            columnHeights[column] = y + placeable.height + gap
            Triple(placeable, x, y)
        }
        val height = (columnHeights.max() - gap).coerceAtLeast(0)
        layout(constraints.maxWidth, height) {
            placements.forEach { (placeable, x, y) -> placeable.place(x, y) }
        }
    }
}

/** One gap value for the page, so the masonry columns line up with the cards above them. */
private val CardSpacing = 14.dp

// ── Tiles ────────────────────────────────────────────────────────────────────

/**
 * The four headline numbers.
 *
 * Total watch time is not among them — it already owns the hero directly above, and
 * repeating it here would spend a quarter of the row saying something the reader has just
 * read. These are the four figures that qualify it instead.
 */
@Composable
private fun StatTiles(
    stats: ActivityStats,
    breakdown: LibraryBreakdown,
    compact: Boolean,
    range: InsightsRange,
) {
    val streak = @Composable { modifier: Modifier ->
        StaggeredAppear(index = 0, modifier = modifier) {
            StatTile(
                icon = "lucide:flame",
                value = "${rememberCountUp(stats.currentStreak.toLong())}",
                caption = "day streak",
                detail = "longest ${stats.longestStreak}",
            )
        }
    }
    val perDay = @Composable { modifier: Modifier ->
        StaggeredAppear(index = 1, modifier = modifier) {
            StatTile(
                icon = "lucide:gauge",
                value = formatWatchDuration(rememberCountUp(stats.avgSecondsPerActiveDay)),
                caption = "per active day",
                detail = "over ${stats.calendar.size} active " +
                    if (stats.calendar.size == 1) "day" else "days",
            )
        }
    }
    val titles = @Composable { modifier: Modifier ->
        StaggeredAppear(index = 2, modifier = modifier) {
            StatTile(
                icon = "lucide:clapperboard",
                value = "${rememberCountUp(stats.titlesThisYear.toLong())}",
                caption = when (range) {
                    InsightsRange.ThisYear -> "titles this year"
                    InsightsRange.LastYear -> "titles last year"
                    InsightsRange.AllTime -> "titles watched"
                },
                // Only worth saying when the tile is showing something narrower.
                detail = "${stats.totalTitles} all time"
                    .takeIf { range != InsightsRange.AllTime },
            )
        }
    }
    val rating = @Composable { modifier: Modifier ->
        StaggeredAppear(index = 3, modifier = modifier) {
            StatTile(
                icon = "lucide:star",
                // Counted in tenths and divided back, so the climb runs through the decimal
                // rather than snapping between whole stars.
                value = breakdown.averageRating?.let { average ->
                    "★ ${rememberCountUp((average * 10).toLong()) / 10.0}"
                } ?: "—",
                caption = "average rating",
                detail = "${breakdown.ratedCount} rated",
                tone = CoveColors.Status.Rating,
            )
        }
    }

    // IntrinsicSize.Max measures the tallest tile and gives every one of them that height,
    // so a tile whose value happens to have no qualifying line underneath still matches its
    // neighbours instead of sitting short in the row.
    if (compact) {
        // Two by two: four tiles across a 360dp screen leaves each one too narrow for its
        // own value, which is the only part that matters.
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                streak(Modifier.weight(1f).fillMaxHeight())
                perDay(Modifier.weight(1f).fillMaxHeight())
            }
            Row(
                modifier = Modifier.height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                titles(Modifier.weight(1f).fillMaxHeight())
                rating(Modifier.weight(1f).fillMaxHeight())
            }
        }
    } else {
        Row(
            modifier = Modifier.height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            streak(Modifier.weight(1f).fillMaxHeight())
            perDay(Modifier.weight(1f).fillMaxHeight())
            titles(Modifier.weight(1f).fillMaxHeight())
            rating(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

// ── Sections ─────────────────────────────────────────────────────────────────

@Composable
private fun MonthlySection(stats: ActivityStats, thisYear: Int) {
    SettingsCard(
        title = "Hours by month",
        iconName = "lucide:chart-line",
        description = "This year measured against the same months last year.",
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = RowPadding)
                .padding(top = InsightsCardTop, bottom = 16.dp),
        ) {
            MonthBars(bars = monthBars(stats.byMonthThisYear, stats.byMonthLastYear))
            ChartLegend(
                entries = listOf(
                    "$thisYear" to MaterialTheme.colorScheme.tertiary,
                    "${thisYear - 1}" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
                ),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun RhythmSection(stats: ActivityStats, compact: Boolean) {
    SettingsCard(
        title = "Your rhythm",
        iconName = "lucide:clock-3",
        description = rhythmSummary(stats) ?: "When your watching actually happens.",
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = RowPadding)
                .padding(top = InsightsCardTop, bottom = 18.dp),
        ) {
            if (compact) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ViewingClock(byHourOfDay = stats.byHourOfDay)
                    WeekdayBars(seconds = stats.byDayOfWeek)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViewingClock(
                        byHourOfDay = stats.byHourOfDay,
                        modifier = Modifier.width(196.dp),
                    )
                    WeekdayBars(seconds = stats.byDayOfWeek, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HeatmapSection(
    stats: ActivityStats,
    today: kotlinx.datetime.LocalDate,
    range: InsightsRange,
) {
    val activeDays = stats.calendar.count { it.value > 0L }
    // The grid always ends on the last day the range covers, so a past year fills its own
    // columns instead of being squeezed into a window that ends today.
    val window = heatmapWindow(range, today)
    SettingsCard(
        title = "The year, day by day",
        iconName = "lucide:calendar-days",
        description = "Every day you watched something, and roughly how much.",
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = RowPadding)
                .padding(top = InsightsCardTop, bottom = 18.dp),
        ) {
            ActivityHeatmap(
                // Folding a year into weeks is 371 cells of work; it only changes when the
                // calendar or the date does, not on every hover.
                weeks = remember(stats.calendar, window) {
                    heatmapWeeks(stats.calendar, window.first, window.second)
                },
                today = window.first,
                // What the readout says when nothing is under the pointer, so the row is
                // carrying information for the whole time the chart is simply being looked at.
                summary = "$activeDays active " + (if (activeDays == 1) "day" else "days") +
                    " · longest streak ${stats.longestStreak}",
            )
        }
    }
}

@Composable
private fun CompositionSection(breakdown: LibraryBreakdown, compact: Boolean) {
    val slices = listOf(
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
    )

    SettingsCard(
        title = "Where your titles sit",
        iconName = "lucide:library",
        description = "${breakdown.movies} " +
            (if (breakdown.movies == 1) "movie" else "movies") +
            " · ${breakdown.shows} " +
            (if (breakdown.shows == 1) "show" else "shows"),
    ) {
        // Hoisted here because the ring and its legend are two views of one selection;
        // owning it in either of them would leave the other unable to see it.
        var focused by remember { mutableStateOf<Int?>(null) }
        val content = @Composable {
            CompositionRing(
                slices = slices,
                centreValue = "${breakdown.total}",
                centreCaption = "in library",
                focused = focused,
            )
        }
        Box(
            modifier = Modifier
                .padding(horizontal = RowPadding)
                // A circle needs breathing room above it that a row of bars does not: the
                // ring's widest point is its middle, so aligning its bounding box to the
                // divider leaves the curve visually touching it.
                .padding(top = InsightsCardTop, bottom = 18.dp),
        ) {
            if (compact) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    content()
                    RingLegend(slices = slices, focused = focused, onFocusChange = { focused = it })
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.width(170.dp)) { content() }
                    RingLegend(
                        slices = slices,
                        modifier = Modifier.weight(1f),
                        focused = focused,
                        onFocusChange = { focused = it },
                    )
                }
            }
        }
    }
}

/**
 * The taste half of the page: three cards, each of which hides itself when its own slice of
 * the profile is empty.
 *
 * Split into one composable per card rather than one long function. They arrive whenever the
 * taste fetch lands — which can be seconds after the rest of the page on a cold cache — and
 * each needs its own entrance, so each needs to be its own unit.
 */
@Composable
private fun TasteSections(
    profile: DiscoveryInsights,
    onOpenMedia: (Media) -> Unit,
    compact: Boolean,
) {
    val movieBars = normalizeTaste(profile.topMovieGenres)
    val tvBars = normalizeTaste(profile.topTvGenres)

    if (movieBars.isNotEmpty() || tvBars.isNotEmpty()) {
        StaggeredAppear(index = 6) {
            GenreCard(
                movieBars = movieBars,
                tvBars = tvBars,
                disliked = profile.dislikedGenres,
                compact = compact,
            )
        }
    }

    val hasSignals = profile.topKeywords.isNotEmpty() ||
        profile.topPeople.isNotEmpty() ||
        profile.topStudios.isNotEmpty()
    if (hasSignals) {
        StaggeredAppear(index = 7) { SignalsCard(profile = profile) }
    }

    if (profile.topContributors.isNotEmpty() || profile.negativeContributors.isNotEmpty()) {
        StaggeredAppear(index = 8) {
            ContributorsCard(profile = profile, onOpenMedia = onOpenMedia)
        }
    }
}

@Composable
private fun GenreCard(
    movieBars: List<TasteBar>,
    tvBars: List<TasteBar>,
    disliked: List<DiscoveryTaste>,
    compact: Boolean,
) {
    SettingsCard(
        title = "What you enjoy most",
        iconName = "lucide:heart",
        description = "The genres your library argues for hardest.",
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = RowPadding)
                .padding(top = InsightsCardTop, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (compact) {
                if (movieBars.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SubSectionLabel("Top movie genres")
                        RankedBars(bars = movieBars.take(6))
                    }
                }
                if (tvBars.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SubSectionLabel("Top TV genres")
                        RankedBars(bars = tvBars.take(6))
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SubSectionLabel("Top movie genres")
                        if (movieBars.isEmpty()) {
                            SectionEmpty("Not enough signal yet.")
                        } else {
                            RankedBars(bars = movieBars.take(6))
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SubSectionLabel("Top TV genres")
                        if (tvBars.isEmpty()) {
                            SectionEmpty("Not enough signal yet.")
                        } else {
                            RankedBars(bars = tvBars.take(6))
                        }
                    }
                }
            }

            if (disliked.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SubSectionLabel("Steering you away")
                    TasteChips(
                        entries = tasteChips(disliked),
                        tone = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SignalsCard(profile: DiscoveryInsights) {
    SettingsCard(
        title = "Your taste signals",
        iconName = "lucide:sparkles",
        description = "Themes, people and studios that shape your recommendations.",
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = RowPadding)
                .padding(top = InsightsCardTop, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (profile.topKeywords.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    SubSectionLabel("Themes you return to")
                    TasteChips(entries = tasteChips(profile.topKeywords))
                }
            }
            if (profile.topPeople.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    SubSectionLabel("Cast & crew you gravitate to")
                    TasteChips(entries = tasteChips(profile.topPeople))
                }
            }
            if (profile.topStudios.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    SubSectionLabel("Studios you watch most")
                    TasteChips(entries = studioChips(profile.topStudios))
                }
            }
        }
    }
}

@Composable
private fun ContributorsCard(profile: DiscoveryInsights, onOpenMedia: (Media) -> Unit) {
    SettingsCard(
        title = "Titles that shaped your profile",
        iconName = "lucide:badge-check",
        description = "The strongest pulls in each direction.",
    ) {
        Column(
            modifier = Modifier.padding(top = InsightsCardTop, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (profile.topContributors.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    SubSectionLabel(
                        "Strongest positive influence",
                        modifier = Modifier.padding(horizontal = RowPadding),
                    )
                    ContributorRow(profile.topContributors, onOpenMedia)
                }
            }
            if (profile.negativeContributors.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    SubSectionLabel(
                        "Strongest negative influence",
                        modifier = Modifier.padding(horizontal = RowPadding),
                    )
                    ContributorRow(profile.negativeContributors, onOpenMedia)
                }
            }
        }
    }
}

// ── Loading and empty ────────────────────────────────────────────────────────

/**
 * Mirrors the real layout's heights so nothing jumps when the data lands.
 *
 * Only the activity half is waited on — the taste cards appear later regardless — so the
 * skeleton stops after the shapes that are guaranteed to be there.
 */
@Composable
private fun InsightsSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ShimmerBlock(modifier = Modifier.fillMaxWidth().height(126.dp), corner = 18.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(4) {
                ShimmerBlock(modifier = Modifier.weight(1f).height(96.dp), corner = 14.dp)
            }
        }
        ShimmerBlock(modifier = Modifier.fillMaxWidth().height(230.dp), corner = 18.dp)
        ShimmerBlock(modifier = Modifier.fillMaxWidth().height(260.dp), corner = 18.dp)
    }
}

/** Nothing recorded and nothing saved — say how to change that, not that it is broken. */
@Composable
private fun InsightsEmpty(modifier: Modifier = Modifier) {
    SettingsCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(horizontal = RowPadding, vertical = 44.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .background(
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                IconifyIcon(
                    icon = "lucide:chart-line",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
            Text(
                text = "Nothing to analyse yet",
                modifier = Modifier.padding(top = 16.dp),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Watch a few titles and your stats will start appearing here. " +
                    "Finish, rate, or drop titles to build your taste profile.",
                modifier = Modifier.padding(top = 8.dp).widthIn(max = 420.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
