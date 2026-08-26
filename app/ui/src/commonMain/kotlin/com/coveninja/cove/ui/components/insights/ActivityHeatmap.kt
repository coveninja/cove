package com.coveninja.cove.ui.components.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.pages.profile.HeatDay
import com.coveninja.cove.ui.pages.profile.formatWatchDuration
import com.coveninja.cove.ui.pages.profile.heatDayLabel
import com.coveninja.cove.ui.pages.profile.heatmapMonthLabels
import kotlinx.datetime.LocalDate

/**
 * A year of watching as a grid of days, one column per week.
 *
 * The shape people already know from contribution graphs, and it is the right shape here
 * for the same reason it works there: a year is too long for a line chart to stay legible
 * and too irregular for a bar chart to summarise, but habits — the fortnight off, the run
 * of weekends, the series binged over four days — are obvious the moment the year is folded
 * into weeks.
 *
 * Three hundred and seventy-one cells is far too many to give each its own hit target, so
 * the pointer position is divided down to a cell instead, and the day under it is named in
 * the readout above. The same readout carries [summary] when nothing is being pointed at,
 * which is what stops it being an empty row for most of the time the chart is on screen.
 *
 * Scrolls horizontally rather than shrinking its cells. Below roughly 700dp the whole year
 * cannot fit at a size where individual days are still distinguishable, and a heatmap whose
 * cells have collapsed into a smear is decoration rather than data.
 */
@Composable
internal fun ActivityHeatmap(
    weeks: List<List<HeatDay?>>,
    today: LocalDate,
    summary: String,
    modifier: Modifier = Modifier,
) {
    val reveal = rememberChartReveal(weeks)
    val levels = heatLevelColors(
        accent = LocalInsightsAccent.current,
        empty = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
    )
    val ringColor = MaterialTheme.colorScheme.onSurface
    val labels = heatmapMonthLabels(weeks)
    val scroll = rememberScrollState()
    // The cell's coordinates, not the cell: the canvas needs to know where to draw the
    // ring, and searching the grid for a matching date on every frame to find that out
    // would be 371 comparisons per redraw.
    var hoveredCell by remember(weeks) { mutableStateOf<Pair<Int, Int>?>(null) }
    val hovered = hoveredCell?.let { (week, day) -> weeks.getOrNull(week)?.getOrNull(day) }

    val gridWidth = CellSize * weeks.size + CellGap * (weeks.size - 1).coerceAtLeast(0)

    Column(modifier = modifier.semantics { contentDescription = "Activity heatmap. $summary" }) {
        ChartReadout(
            headline = hovered?.let { heatDayLabel(it.date, today) } ?: summary,
            value = hovered?.let { formatWatchDuration(it.seconds) } ?: "",
        )

        Row(modifier = Modifier.horizontalScroll(scroll)) {
            // The weekday gutter scrolls with the grid rather than pinning: three letters
            // frozen over a scrolling year is more distracting than it is useful, and the
            // rows are readable from the month axis alone.
            Column(
                modifier = Modifier.padding(top = MonthAxisHeight, end = 6.dp),
                verticalArrangement = Arrangement.spacedBy(CellGap),
            ) {
                WEEKDAY_GUTTER.forEach { label ->
                    Box(
                        modifier = Modifier.height(CellSize),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Text(
                            text = label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            Column {
                Box(modifier = Modifier.width(gridWidth).height(MonthAxisHeight)) {
                    labels.forEach { (weekIndex, label) ->
                        Text(
                            text = label,
                            modifier = Modifier.offset(x = (CellSize + CellGap) * weekIndex),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Canvas(
                    modifier = Modifier
                        .width(gridWidth)
                        .height(CellSize * 7 + CellGap * 6)
                        .pointerInput(weeks) {
                            val stride = (CellSize + CellGap).toPx()
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    // A finger lifting has to clear the readout. There is no
                                    // Exit event on touch, so without this the value under
                                    // whatever was tapped last stays on screen for good.
                                    if (event.type == PointerEventType.Exit ||
                                        event.isTouchRelease()
                                    ) {
                                        hoveredCell = null
                                        continue
                                    }
                                    val position = event.changes.lastOrNull()?.position
                                        ?: continue
                                    val week = (position.x / stride).toInt()
                                    val day = (position.y / stride).toInt()
                                    hoveredCell = (week to day)
                                        .takeIf { weeks.getOrNull(week)?.getOrNull(day) != null }
                                }
                            }
                        },
                ) {
                    val cell = CellSize.toPx()
                    val gap = CellGap.toPx()
                    val stride = cell + gap
                    val corner = CornerRadius(CellCorner.toPx(), CellCorner.toPx())
                    val total = weeks.size + 14

                    weeks.forEachIndexed { week, days ->
                        days.forEachIndexed { day, entry ->
                            entry ?: return@forEachIndexed
                            // Weighting the row index more heavily than the column tilts the
                            // reveal into a diagonal, so the year fills in like a wipe rather
                            // than a curtain falling straight down.
                            val grown = waveAt(reveal, week + day * 2, total, spread = 0.6f)
                            if (grown <= 0f) return@forEachIndexed

                            val base = levels[entry.level.coerceIn(0, levels.lastIndex)]
                            // Cells scale up from their own centre as they arrive; fading
                            // alone reads as a slow render, while a little growth reads as
                            // the day landing on the grid.
                            val inset = cell * (1f - grown) / 2f
                            drawRoundRect(
                                color = base.copy(alpha = base.alpha * grown),
                                topLeft = Offset(week * stride + inset, day * stride + inset),
                                size = Size(cell * grown, cell * grown),
                                cornerRadius = corner,
                            )
                        }
                    }

                    // Drawn last so the ring is never cut into by a neighbouring cell.
                    hoveredCell?.let { (week, day) ->
                        drawRoundRect(
                            color = ringColor,
                            topLeft = Offset(
                                week * stride - RingInset,
                                day * stride - RingInset,
                            ),
                            size = Size(cell + RingInset * 2, cell + RingInset * 2),
                            cornerRadius = corner,
                            style = Stroke(width = 1.2f),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Less",
                modifier = Modifier.padding(end = 2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            levels.forEach { color ->
                Box(
                    modifier = Modifier
                        .size(CellSize)
                        .clip(RoundedCornerShape(CellCorner))
                        .background(color),
                )
            }
            Text(
                text = "More",
                modifier = Modifier.padding(start = 2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * Mon/Wed/Fri only.
 *
 * Seven labels against 11dp rows collide at any readable type size; three spaced ones give
 * the reader enough to count rows from without turning the gutter into a wall of text.
 */
private val WEEKDAY_GUTTER = listOf("", "Mon", "", "Wed", "", "Fri", "")

private val CellSize = 11.dp
private val CellGap = 3.dp
private val MonthAxisHeight = 16.dp

/** How far the hover ring sits outside its cell, so it reads as around it rather than on it. */
private const val RingInset = 1.5f
