package com.coveninja.cove.ui.components.insights

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.pages.profile.MonthBar
import com.coveninja.cove.ui.pages.profile.TasteBar
import com.coveninja.cove.ui.pages.profile.WEEKDAY_INITIALS
import com.coveninja.cove.ui.pages.profile.formatWatchDuration
import com.coveninja.cove.ui.pages.profile.weekdayName
import kotlin.math.min

/**
 * Twelve months, this year against last.
 *
 * The two years are drawn as a pair of columns per month rather than stacked or overlaid:
 * stacking implies the years sum to something meaningful, and overlaying hides whichever
 * year is shorter. Side by side is the only arrangement where "was this March busier than
 * last March?" is answerable by looking.
 *
 * Pointing at a month names it and gives its exact hours in the readout above, which is how
 * the chart answers the follow-up question without printing twenty-four numbers nobody
 * asked for. The readout is a fixed-height row so nothing below it moves as the value
 * changes, and a tap does the same thing as a hover for anyone without a pointer.
 */
@Composable
internal fun MonthBars(
    bars: List<MonthBar>,
    modifier: Modifier = Modifier,
    height: Dp = 132.dp,
) {
    val reveal = rememberChartReveal(bars)
    var focused by remember(bars) { mutableStateOf<Int?>(null) }
    val currentColor = LocalInsightsAccent.current
    val previousColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val axisColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val busiest = bars.indices.maxByOrNull { bars[it].thisYearSeconds }
    // A Canvas is invisible to a screen reader — it has no children to describe. Every
    // chart on this page therefore states its own finding in words, which is the same
    // sentence a sighted reader takes from the shape.
    val summary = busiest?.let {
        "Hours by month. Busiest was ${bars[it].name} at " +
            formatWatchDuration(bars[it].thisYearSeconds) + "."
    } ?: "Hours by month. Nothing recorded."

    Column(modifier = modifier.fillMaxWidth().semantics { contentDescription = summary }) {
        ChartReadout(
            headline = focused?.let { bars[it].name } ?: "Busiest month",
            value = focused?.let { formatWatchDuration(bars[it].thisYearSeconds) }
                ?: busiest?.let { formatWatchDuration(bars[it].thisYearSeconds) }
                ?: "—",
            secondary = focused?.let { index ->
                bars[index].lastYearSeconds
                    .takeIf { it > 0L }
                    ?.let { "${formatWatchDuration(it)} last year" }
            },
        )

        Box {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    // The columns overshoot the baseline so their bottom corners land
                    // outside the canvas; without this they would be visibly rounded feet.
                    .clipToBounds(),
            ) {
                if (bars.isEmpty()) return@Canvas
                val baseline = size.height
                val slot = size.width / bars.size
                val barWidth = min(slot * 0.30f, 9.dp.toPx())
                val gap = barWidth * 0.30f

                drawLine(
                    color = axisColor,
                    start = Offset(0f, baseline - 0.5f),
                    end = Offset(size.width, baseline - 0.5f),
                    strokeWidth = 1f,
                )

                bars.forEachIndexed { index, bar ->
                    val grown = waveAt(reveal, index, bars.size)
                    val centre = slot * index + slot / 2f
                    val usable = baseline - 4.dp.toPx()
                    // Everything except the focused month steps back, rather than the
                    // focused one lighting up: dimming the field is what makes a single
                    // bar readable without changing its height and breaking the comparison.
                    val dim = if (focused == null || focused == index) 1f else 0.42f

                    drawColumn(
                        x = centre - barWidth - gap / 2f,
                        width = barWidth,
                        height = bar.lastYearFraction * usable * grown,
                        baseline = baseline,
                        color = previousColor.copy(alpha = previousColor.alpha * dim),
                    )
                    drawColumn(
                        x = centre + gap / 2f,
                        width = barWidth,
                        height = bar.thisYearFraction * usable * grown,
                        baseline = baseline,
                        color = currentColor.copy(alpha = dim),
                    )
                }
            }

            // Invisible hit strips, one per month, sitting over the whole chart height so
            // the target is the column of space rather than the bar itself — a January with
            // twenty minutes in it is otherwise three pixels tall and impossible to hit.
            Row(modifier = Modifier.matchParentSize()) {
                bars.indices.forEach { index ->
                    val interaction = remember { MutableInteractionSource() }
                    val hovered by interaction.collectIsHoveredAsState()
                    LaunchedEffect(hovered) {
                        if (hovered) focused = index else if (focused == index) focused = null
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .hoverable(interaction)
                            .pointerInput(index) {
                                detectTapGestures {
                                    focused = if (focused == index) null else index
                                }
                            },
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            bars.forEachIndexed { index, bar ->
                Text(
                    text = bar.label,
                    modifier = Modifier.weight(1f),
                    color = if (focused == index) {
                        LocalInsightsAccent.current
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (focused == index) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The live line above a chart: what is being pointed at, and what it is worth.
 *
 * Fixed height on purpose. A readout that grows and shrinks as values change shoves the
 * chart under it up and down, which is far more distracting than the number is useful.
 */
@Composable
internal fun ChartReadout(
    headline: String,
    value: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(24.dp).padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = headline,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        secondary?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The two-swatch key the month chart needs to be readable at all. */
@Composable
internal fun ChartLegend(
    entries: List<Pair<String, Color>>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        entries.forEach { (label, color) ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
                Text(
                    text = label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * Seven days, laid out as horizontal bars.
 *
 * Horizontal rather than vertical because this sits beside the circular clock: two round-ish
 * charts side by side compete, and a row of horizontal bars gives the eye somewhere flat to
 * land. It also leaves room for the actual duration at the end of each row, which is the
 * number people want once the shape has told them which day wins.
 */
@Composable
internal fun WeekdayBars(
    seconds: List<Long>,
    modifier: Modifier = Modifier,
) {
    val reveal = rememberChartReveal(seconds)
    val peak = (seconds.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val busiest = seconds.indices.maxByOrNull { seconds[it] }?.takeIf { seconds[it] > 0L }
    val accent = LocalInsightsAccent.current

    val summary = busiest?.let {
        "Hours by day of week. ${weekdayName(it)} is the busiest at " +
            formatWatchDuration(seconds[it]) + "."
    } ?: "Hours by day of week. Nothing recorded."

    // Hoisted so hover and tap drive one selection; see chartRowFocus.
    var focused by remember(seconds) { mutableStateOf<Int?>(null) }

    Column(
        modifier = modifier.fillMaxWidth().semantics { contentDescription = summary },
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        seconds.forEachIndexed { index, value ->
            val emphasis by animateFloatAsState(
                targetValue = if (focused == index) 1f else 0f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "WeekdayEmphasis",
            )
            val grown = waveAt(reveal, index, seconds.size, spread = 0.45f)
            val fraction = (value.toFloat() / peak) * grown

            Row(
                modifier = Modifier.chartRowFocus(
                    index = index,
                    focused = focused,
                    onFocusChange = { focused = it },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = WEEKDAY_INITIALS.getOrElse(index) { "" },
                    modifier = Modifier.width(14.dp),
                    color = lerp(
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        MaterialTheme.colorScheme.onSurface,
                        emphasis,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (index == busiest) FontWeight.Bold else FontWeight.Normal,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            // The winning day is the point of the chart, so it is the only
                            // one at full accent; the rest recede rather than compete, and
                            // hovering lifts whichever row is under the pointer up to match.
                            .background(
                                if (index == busiest) {
                                    accent
                                } else {
                                    accent.copy(alpha = 0.42f + 0.45f * emphasis)
                                },
                            ),
                    )
                }
                Text(
                    text = formatWatchDuration(value),
                    modifier = Modifier.width(58.dp).padding(start = 8.dp),
                    color = lerp(
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        MaterialTheme.colorScheme.onSurface,
                        emphasis,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A ranked list as bars — genres, keywords, anything with an order and a strength.
 *
 * The name sits above its bar rather than in a left-hand column: genre names vary from
 * "War" to "Science Fiction", and a column wide enough for the longest one wastes most of
 * the row's width on the short ones. Stacking also means the same component works in a
 * narrow half-width card on a phone.
 *
 * No numbers anywhere. The underlying score is a decayed profile weight with no unit, and
 * printing it would invite a reading it cannot support — the bar's length carries
 * everything that is actually knowable.
 */
@Composable
internal fun RankedBars(
    bars: List<TasteBar>,
    modifier: Modifier = Modifier,
    tone: Color = LocalInsightsAccent.current,
) {
    val reveal = rememberChartReveal(bars)
    // A slow highlight travelling along the top bar. Only the leader gets it — it marks
    // first place without a badge, and on every bar it would just be noise.
    val sheen = rememberSweep(durationMillis = 2_600, label = "RankedSheen")

    val summary = "Ranked: " + bars.joinToString(", ") { it.name } + "."

    var focused by remember(bars) { mutableStateOf<Int?>(null) }

    Column(
        modifier = modifier.fillMaxWidth().semantics { contentDescription = summary },
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        bars.forEachIndexed { index, bar ->
            val active = focused == index
            val emphasis by animateFloatAsState(
                targetValue = if (active) 1f else 0f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "RankedEmphasis",
            )
            val grown = waveAt(reveal, index, bars.size, spread = 0.5f)
            val fill = tone.copy(
                alpha = ((1f - index * 0.09f).coerceAtLeast(0.35f) + 0.25f * emphasis)
                    .coerceAtMost(1f),
            )

            Column(
                modifier = Modifier.chartRowFocus(
                    index = index,
                    focused = focused,
                    onFocusChange = { focused = it },
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = bar.name,
                    color = if (index == 0 || active) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((bar.fraction * grown).coerceIn(0f, 1f))
                            .height(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(fill),
                    ) {
                        if (index == 0) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(
                                        Brush.horizontalGradient(
                                            colorStops = sheenStops(sheen),
                                        ),
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The three stops of the travelling highlight, guaranteed strictly ascending.
 *
 * `Brush.horizontalGradient` rejects stops that are not increasing, and clamping a sweep
 * that runs from 0f to 1f collapses the leading pair at one end and the trailing pair at
 * the other. Deriving each stop from the previous one keeps them apart at both extremes.
 */
private fun sheenStops(sweep: Float): Array<Pair<Float, Color>> {
    val start = (sweep - 0.18f).coerceIn(0f, 0.96f)
    val peak = sweep.coerceIn(start + 0.02f, 0.98f)
    val end = (sweep + 0.18f).coerceIn(peak + 0.02f, 1f)
    return arrayOf(
        start to Color.Transparent,
        peak to Color.White.copy(alpha = 0.28f),
        end to Color.Transparent,
    )
}

/**
 * A small column chart with no axis and no numbers.
 *
 * For the secondary cards in the two-column grid, where a full [MonthBars] would dominate a
 * half-width card and its readout would compete with the card's own caption. Shape only:
 * the caller states the total in words underneath, which is all these are asked to support.
 */
@Composable
internal fun MiniBars(
    values: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    height: Dp = 68.dp,
) {
    val reveal = rememberChartReveal(values)
    val accent = LocalInsightsAccent.current
    val peakIndex = values.indices.maxByOrNull { values[it] }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier.fillMaxWidth().height(height).clipToBounds(),
        ) {
            if (values.isEmpty()) return@Canvas
            val baseline = size.height
            val slot = size.width / values.size
            val barWidth = min(slot * 0.55f, 12.dp.toPx())
            values.forEachIndexed { index, value ->
                val grown = waveAt(reveal, index, values.size, spread = 0.4f)
                drawColumn(
                    x = slot * index + (slot - barWidth) / 2f,
                    width = barWidth,
                    height = value * baseline * grown,
                    baseline = baseline,
                    color = if (index == peakIndex) accent else accent.copy(alpha = 0.42f),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 5.dp)) {
            labels.forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}
