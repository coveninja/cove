package com.coveninja.cove.ui.components.insights

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.pages.profile.formatHour
import com.coveninja.cove.ui.pages.profile.formatWatchDuration
import com.coveninja.cove.ui.pages.profile.peakHour
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The day as a dial: twenty-four spokes, midnight at the top.
 *
 * A twenty-four hour face rather than a twelve hour one, because the question is "when do
 * you watch", and a twelve hour dial would fold 9am and 9pm onto the same spoke — merging
 * the two halves of the day that differ most. Midnight sits at the top so the small hours
 * fall on the right and evening on the left, which puts the busy part of most people's day
 * in the lower-left quadrant rather than split across the seam.
 *
 * Spokes are drawn as radial lines from a hollow centre rather than as filled wedges. A
 * wedge's area grows with the square of its length and so exaggerates the peak; a line of
 * constant width is honest about the ratio, and leaves the middle free for the one number
 * worth reading off this chart — the hour itself.
 *
 * Pointing anywhere in the ring reads back the hour under the pointer and what was watched
 * in it, so the middle of the dial doubles as its own readout. The geometry is worked out
 * from the pointer's angle rather than from twenty-four hit targets, which would be
 * twenty-four overlapping wedges of layout for something one `atan2` answers.
 */
@Composable
internal fun ViewingClock(
    byHourOfDay: List<Long>,
    modifier: Modifier = Modifier,
) {
    val reveal = rememberChartReveal(byHourOfDay)
    // A highlight travelling slowly round the rim. It is the one thing on this page that
    // never stops, which is what keeps the dial feeling like an instrument rather than a
    // picture of one — at 11 seconds a lap it is barely perceptible until you watch it.
    val sheen = rememberSweep(durationMillis = 11_000, label = "ClockSheen")
    // The busiest hour breathes. Slow and shallow enough to read as emphasis, not motion.
    val pulse = rememberDrift(durationMillis = 2_400, label = "ClockPulse")

    val accent = MaterialTheme.colorScheme.tertiary
    val quiet = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f)
    val guide = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
    val peak = (byHourOfDay.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val busiest = peakHour(byHourOfDay)
    var hovered by remember(byHourOfDay) { mutableStateOf<Int?>(null) }

    val summary = busiest?.let {
        "Viewing clock. You watch most around ${formatHour(it)}, " +
            formatWatchDuration(byHourOfDay.getOrElse(it) { 0L }) + " in that hour."
    } ?: "Viewing clock. Nothing recorded."

    Box(
        modifier = modifier
            .widthIn(max = 260.dp)
            .aspectRatio(1f)
            .semantics { contentDescription = summary },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(byHourOfDay) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Exit) {
                                hovered = null
                                continue
                            }
                            val position = event.changes.lastOrNull()?.position ?: continue
                            hovered = hourAt(
                                position = position,
                                width = size.width.toFloat(),
                                height = size.height.toFloat(),
                            )
                        }
                    }
                },
        ) {
            val radius = size.minDimension / 2f
            val centre = Offset(size.width / 2f, size.height / 2f)
            // Leaves room for the four clock labels sitting outside the dial.
            val outer = radius * OuterFraction
            val inner = radius * InnerFraction
            val spokeWidth = (2f * PI.toFloat() * inner / 24f) * 0.62f

            drawCircle(
                color = guide,
                radius = outer,
                center = centre,
                style = Stroke(width = 1f),
            )

            // Drawn under the spokes so it reads as light behind the dial rather than a
            // band laid over the data.
            rotate(degrees = sheen * 360f, pivot = centre) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.06f to accent.copy(alpha = 0.16f),
                            0.14f to Color.Transparent,
                            1f to Color.Transparent,
                        ),
                        center = centre,
                    ),
                    radius = (outer + inner) / 2f,
                    center = centre,
                    style = Stroke(width = outer - inner),
                )
            }

            byHourOfDay.take(24).forEachIndexed { hour, value ->
                val fraction = (value.toFloat() / peak).coerceIn(0f, 1f)
                // The wave runs round the dial from midnight, so the reveal sweeps like a
                // hand rather than every spoke inflating at once.
                val grown = waveAt(reveal, hour, 24, spread = 0.55f)
                val angle = (hour / 24f) * 2f * PI.toFloat() - PI.toFloat() / 2f
                val direction = Offset(cos(angle), sin(angle))
                val start = centre + direction * inner

                val isPeak = hour == busiest
                val isHovered = hour == hovered
                val breath = if (isPeak) 1f + 0.05f * pulse else 1f
                // Every hour keeps a visible stub, so the dial reads as a full clock face
                // rather than as a shape with pieces missing where nothing was watched.
                val length = (outer - inner) * (0.06f + 0.94f * fraction) * grown * breath
                val tint = when {
                    isHovered -> Color.White
                    else -> lerp(quiet, accent, fraction)
                }

                if (isPeak) {
                    // A soft halo under the winning spoke; it is what the eye lands on
                    // before any label has been read.
                    drawLine(
                        color = accent.copy(alpha = 0.16f + 0.10f * pulse),
                        start = start,
                        end = start + direction * length,
                        strokeWidth = spokeWidth * 2.4f,
                        cap = StrokeCap.Round,
                    )
                }
                drawLine(
                    color = tint,
                    start = start,
                    end = start + direction * length,
                    strokeWidth = if (isHovered) spokeWidth * 1.35f else spokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        }

        // The dial's own labels, positioned by the box rather than measured into the canvas.
        ClockLabel("12a", Alignment.TopCenter)
        ClockLabel("6a", Alignment.CenterEnd)
        ClockLabel("12p", Alignment.BottomCenter)
        ClockLabel("6p", Alignment.CenterStart)

        val shown = hovered ?: busiest
        shown?.let { hour ->
            Crossfade(targetState = hour, label = "ClockCentre") { value ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = formatHour(value),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (hovered != null) {
                            formatWatchDuration(byHourOfDay.getOrElse(value) { 0L })
                        } else {
                            "peak"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/**
 * Which hour a point in the dial falls on, or null if it falls outside the ring.
 *
 * The centre and the margin outside the rim both return null so that drifting across the
 * middle of the chart does not fire wildly between opposite hours, which is what makes a
 * radial hit test feel broken.
 */
private fun hourAt(position: Offset, width: Float, height: Float): Int? {
    val centre = Offset(width / 2f, height / 2f)
    val delta = position - centre
    val distance = sqrt(delta.x * delta.x + delta.y * delta.y)
    val radius = minOf(width, height) / 2f
    if (distance < radius * InnerFraction * 0.8f || distance > radius * OuterFraction * 1.1f) {
        return null
    }
    // atan2 measures from the positive x axis; the dial starts at twelve o'clock.
    var turns = (atan2(delta.y, delta.x) + PI.toFloat() / 2f) / (2f * PI.toFloat())
    if (turns < 0f) turns += 1f
    return (turns * 24f).roundToInt() % 24
}

@Composable
private fun BoxScope.ClockLabel(text: String, alignment: Alignment) {
    Text(
        text = text,
        modifier = Modifier.align(alignment).padding(2.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
    )
}

private const val OuterFraction = 0.80f
private const val InnerFraction = 0.38f
