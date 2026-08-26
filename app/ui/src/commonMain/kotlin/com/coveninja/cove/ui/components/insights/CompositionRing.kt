package com.coveninja.cove.ui.components.insights

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One arc of the ring: a colour, a label and how many titles are in it. */
internal data class RingSlice(val label: String, val count: Int, val color: Color)

/**
 * The library split by status, as a ring.
 *
 * A ring rather than a stacked bar because the question it answers is about proportion —
 * how much of the library is finished versus abandoned versus never started — and a closed
 * shape makes "most of it" and "hardly any of it" legible without reading the numbers. The
 * total sits in the hole, which is the one figure a proportion chart cannot show.
 *
 * Slices keep a gap between them so adjacent colours never touch. Two similar hues meeting
 * at a hard edge read as one slice, and the [com.coveninja.cove.ui.CoveColors.Category]
 * palette is only guaranteed mutually distinguishable, not high-contrast in every pairing.
 *
 * [focused] is driven by the legend beside it. Pointing at a row thickens its arc and swaps
 * the centre to that slice's own numbers, which is what turns two separate readings of the
 * same data into one control.
 */
@Composable
internal fun CompositionRing(
    slices: List<RingSlice>,
    modifier: Modifier = Modifier,
    centreValue: String,
    centreCaption: String,
    focused: Int? = null,
) {
    val reveal = rememberChartReveal(slices)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val innerWash = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
    val total = slices.sumOf { it.count }.coerceAtLeast(1)
    val emphasis by animateFloatAsState(
        targetValue = if (focused == null) 0f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "RingEmphasis",
    )

    val summary = "Library by status, $centreValue $centreCaption: " +
        slices.joinToString(", ") { "${it.label} ${it.count}" } + "."

    Box(
        modifier = modifier
            .widthIn(max = 190.dp)
            .aspectRatio(1f)
            .semantics { contentDescription = summary },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val thickness = size.minDimension * 0.15f
            val inset = thickness / 2f
            val arcSize = Size(size.width - thickness, size.height - thickness)
            val topLeft = Offset(inset, inset)

            // A wash inside the ring so the hole reads as lit rather than as a hole.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(innerWash, Color.Transparent),
                    center = center,
                    radius = size.minDimension / 2f,
                ),
                radius = size.minDimension / 2f - thickness,
            )

            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = thickness, cap = StrokeCap.Butt),
            )

            // -90 starts the ring at twelve o'clock, where a reader expects it to.
            var cursor = -90f
            slices.forEachIndexed { index, slice ->
                if (slice.count <= 0) return@forEachIndexed
                val full = slice.count.toFloat() / total * 360f
                // Slices arrive one after another round the ring, so the chart draws itself
                // in the same order the legend lists it.
                val grown = waveAt(reveal, index, slices.size, spread = 0.5f)
                val sweep = (full - SliceGapDegrees).coerceAtLeast(0f) * grown

                val isFocused = focused == index
                // Dim the others rather than brighten the one: the colours are already at
                // full strength, so there is nowhere brighter for the focused slice to go.
                val dim = if (focused == null || isFocused) 1f else 1f - 0.62f * emphasis
                val grow = if (isFocused) thickness * 0.22f * emphasis else 0f

                drawArc(
                    color = slice.color.copy(alpha = slice.color.alpha * dim),
                    startAngle = cursor,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = thickness + grow, cap = StrokeCap.Butt),
                )
                cursor += full
            }
        }

        Crossfade(targetState = focused, label = "RingCentre") { index ->
            val slice = index?.let(slices::getOrNull)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = slice?.count?.toString() ?: centreValue,
                    color = slice?.color ?: MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = slice?.label?.lowercase() ?: centreCaption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * The ring's key, one row per slice.
 *
 * Every slice gets a row even at zero, because the set of statuses is fixed and a legend
 * whose rows come and go is harder to read at a glance than one that always says the same
 * four things. Rows with nothing in them are not hoverable — there is no arc to point at.
 */
@Composable
internal fun RingLegend(
    slices: List<RingSlice>,
    modifier: Modifier = Modifier,
    focused: Int? = null,
    onFocusChange: (Int?) -> Unit = {},
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        slices.forEachIndexed { index, slice ->
            // Hover and tap both write the caller's selection, so the ring and its legend
            // stay two views of one thing on a phone as well as under a pointer.
            val active = focused == index && slice.count > 0

            val highlight by animateFloatAsState(
                targetValue = if (active) 1f else 0f,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "LegendHighlight",
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(slice.color.copy(alpha = 0.10f * highlight))
                    .chartRowFocus(
                        index = index,
                        focused = focused,
                        onFocusChange = onFocusChange,
                        // A slice with nothing in it has no arc to point at.
                        enabled = slice.count > 0,
                    )
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp + 2.dp * highlight)
                        .clip(CircleShape)
                        .background(slice.color),
                )
                Text(
                    text = slice.label,
                    modifier = Modifier.weight(1f),
                    color = if (active) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
                Text(
                    text = slice.count.toString(),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Wide enough to separate adjacent hues, narrow enough not to distort small slices. */
private const val SliceGapDegrees = 3f
