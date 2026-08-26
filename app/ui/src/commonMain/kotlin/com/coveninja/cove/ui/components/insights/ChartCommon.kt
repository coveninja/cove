package com.coveninja.cove.ui.components.insights

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.state.LocalMotionPolicy
import kotlin.math.min

/**
 * The one-off grow-in every chart on the insights page shares.
 *
 * A single progress value drives all of them so the section reads as one object settling
 * rather than seven independent widgets each doing their own thing. It runs once on first
 * composition and then holds at 1f — these are numbers to read, not an animation to watch,
 * and a chart that keeps moving is a chart nobody can compare values in.
 *
 * The duration is longer than a normal UI transition on purpose. Paired with [waveAt] it
 * has to cover a whole chart's stagger, and the last bar arriving 600ms after the first is
 * what makes the reveal read as a sweep rather than as lag.
 *
 * Returns 1f immediately under reduced motion, so the charts are drawn complete rather than
 * drawn empty.
 */
@Composable
internal fun rememberChartReveal(key: Any? = Unit): Float {
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val progress = remember(key) { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(key, reducedMotion) {
        if (reducedMotion) {
            progress.snapTo(1f)
        } else {
            progress.animateTo(1f, tween(durationMillis = 1_100, easing = FastOutSlowInEasing))
        }
    }
    return progress.value
}

/**
 * One element's share of a staggered reveal, from a single driving [progress].
 *
 * The obvious way to stagger a chart is one `Animatable` per element, which for a heatmap
 * means 371 of them and a recomposition storm to match. This computes the same effect
 * arithmetically: the drive runs slightly longer than the animation, and each element reads
 * a window of it offset by its own index.
 *
 * [spread] is how much of the drive is given over to the offsetting — 0f makes everything
 * move together, 1f means the last element only starts as the first one finishes. Around a
 * third reads as a sweep while still feeling like one gesture.
 */
internal fun waveAt(progress: Float, index: Int, count: Int, spread: Float = 0.35f): Float {
    if (count <= 1 || spread <= 0f) return progress
    val step = spread / (count - 1)
    val start = step * index
    val span = 1f - spread
    if (span <= 0f) return progress
    return ((progress - start) / span).coerceIn(0f, 1f)
}

/**
 * A number that climbs to its value the first time it is shown.
 *
 * Reserved for the handful of figures the page is actually about — the hero total and the
 * stat tiles. Counting up every number on screen would turn a dashboard into a slot machine;
 * counting up the two or three that matter is what makes them feel arrived at.
 */
@Composable
internal fun rememberCountUp(target: Long, durationMillis: Int = 1_000): Long {
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val animated = remember { Animatable(0f) }
    LaunchedEffect(target, reducedMotion) {
        if (reducedMotion) {
            animated.snapTo(target.toFloat())
        } else {
            animated.animateTo(
                targetValue = target.toFloat(),
                animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
            )
        }
    }
    return animated.value.toLong()
}

/**
 * A slow back-and-forth 0f..1f, for decorative drift that never resolves.
 *
 * Held at the midpoint under reduced motion rather than stopped at an end, so a page that
 * opts out of motion still gets the composition the effect was designed around instead of
 * its extreme.
 */
@Composable
internal fun rememberDrift(durationMillis: Int, label: String): Float {
    if (LocalMotionPolicy.current.reducedMotion) return 0.5f
    val transition = rememberInfiniteTransition(label = label)
    val value by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "${label}Value",
    )
    return value
}

/** A continuously advancing 0f..1f, for anything that sweeps or rotates in one direction. */
@Composable
internal fun rememberSweep(durationMillis: Int, label: String): Float {
    if (LocalMotionPolicy.current.reducedMotion) return 0f
    val transition = rememberInfiniteTransition(label = label)
    val value by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "${label}Value",
    )
    return value
}

/**
 * A bar standing on [baseline], with a pill-rounded top.
 *
 * The rounded rect deliberately extends past the baseline by its own corner radius so the
 * bottom corners fall outside the canvas: the caller clips to bounds, which leaves a square
 * foot sitting on the axis and a rounded cap on top. Rounding both ends makes short bars
 * look like floating lozenges rather than bars with a value.
 *
 * Bars shorter than their own radius are drawn as a stub rather than skipped — a month with
 * twenty minutes in it should not look identical to a month with nothing.
 */
internal fun DrawScope.drawColumn(
    x: Float,
    width: Float,
    height: Float,
    baseline: Float,
    color: Color,
) {
    if (height <= 0f || width <= 0f) return
    val radius = min(width / 2f, height)
    val drawn = height.coerceAtLeast(MinimumBarHeightPx)
    drawRoundRect(
        color = color,
        topLeft = Offset(x, baseline - drawn),
        size = Size(width, drawn + radius),
        cornerRadius = CornerRadius(radius, radius),
    )
}

/** Enough that a non-zero value is always visible, small enough not to read as data. */
private const val MinimumBarHeightPx = 2f

/** The five heatmap shades, index 0 meaning "nothing watched that day". */
@Composable
internal fun heatLevelColors(accent: Color, empty: Color): List<Color> = listOf(
    empty,
    accent.copy(alpha = 0.22f),
    accent.copy(alpha = 0.44f),
    accent.copy(alpha = 0.70f),
    accent,
)

/** Corner radius shared by the small cells and swatches so they read as one family. */
internal val CellCorner = 2.5.dp

/**
 * Row-level emphasis a finger can reach.
 *
 * The charts on this page were built with `hoverable` alone, which on a phone means the
 * weekday bars, the ranked taste bars and the ring's legend reveal precisely nothing: there
 * is no pointer to hover, so the emphasis animation never runs and the numbers behind it are
 * unreachable. `MonthBars` had already solved this by pairing hover with a tap that writes
 * the same state; this is that pattern, extracted, so the remaining charts cannot each
 * solve it differently or forget to.
 *
 * Hover and tap both drive one caller-owned selection rather than a local flag, because on a
 * chart with a shared readout the row and the readout are two views of the same thing.
 * Tapping the focused row clears it, which is the only way to dismiss a selection on a
 * device with no pointer to move away.
 *
 * A row only ever clears *its own* focus. Moving a pointer between two rows fires one row's
 * exit and the other's entry in no guaranteed order, and an unguarded clear lands after the
 * entry and blanks the selection that just arrived.
 */
@Composable
internal fun Modifier.chartRowFocus(
    index: Int,
    focused: Int?,
    onFocusChange: (Int?) -> Unit,
    enabled: Boolean = true,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // pointerInput only restarts when its key changes, so without these it would go on
    // reading whichever selection was current when the chart first composed.
    val current by rememberUpdatedState(focused)
    val notify by rememberUpdatedState(onFocusChange)

    LaunchedEffect(hovered, enabled) {
        if (!enabled) return@LaunchedEffect
        if (hovered) notify(index) else if (current == index) notify(null)
    }

    return this
        .hoverable(interaction, enabled = enabled)
        .pointerInput(index) {
            detectTapGestures {
                if (enabled) notify(if (current == index) null else index)
            }
        }
}

/**
 * Whether a pointer event is a finger letting go.
 *
 * The heatmap and the clock read raw pointer events so they can track a position rather than
 * a hit target, which means they see a touch as a press and never see it end. Without this
 * the readout latches to whatever was tapped last and stays there for good — the value under
 * a finger that lifted an hour ago is not what the chart is showing.
 *
 * Restricted to touch on purpose: a mouse click is also a release, and clearing on that
 * would blank the readout every time a desktop viewer clicked the chart.
 */
internal fun PointerEvent.isTouchRelease(): Boolean =
    type == PointerEventType.Release && changes.lastOrNull()?.type == PointerType.Touch

