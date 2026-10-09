package com.coveninja.cove.ui.state

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * Whether decoration that never settles by itself — a drifting poster wall, a breathing dot —
 * may move right now: only while Cove is the focused window, and never under reduced motion.
 *
 * Every frame of motion repaints the whole window at the display's refresh rate. A loop kept
 * running in a window behind other work, or minimised, did that all day for nobody to see.
 */
@Composable
fun ambientMotionAllowed(): Boolean =
    !LocalMotionPolicy.current.reducedMotion && LocalWindowInfo.current.isWindowFocused

/**
 * Milliseconds of ambient motion so far, for decoration to take its position from.
 *
 * Runs only while [ambientMotionAllowed], and carries on from where it stopped rather than from
 * zero, so a drift does not jump back to its start when the viewer returns. It stops for good at
 * [limitMillis]: motion meant to finish then asks for no more frames at all.
 */
@Composable
fun rememberAmbientMillis(limitMillis: Long = Long.MAX_VALUE): LongState {
    val allowed = ambientMotionAllowed()
    val elapsed = remember { mutableLongStateOf(0L) }
    LaunchedEffect(allowed, limitMillis) {
        if (!allowed) return@LaunchedEffect
        val resumedFrom = elapsed.longValue
        val start = withFrameMillis { it }
        while (elapsed.longValue < limitMillis) {
            withFrameMillis { now ->
                elapsed.longValue = (resumedFrom + now - start).coerceAtMost(limitMillis)
            }
        }
    }
    return elapsed
}

/**
 * The slow push-in of a page's backdrop: from 1 to [scaleTo] over [durationMillis], once.
 *
 * Once rather than in and out forever. The push is there so a still frame does not read as a
 * stalled image while the page animates in around it, which is over within seconds; the loop it
 * replaces kept the whole window repainting for as long as Home stayed open, focused or not.
 */
@Composable
fun rememberBackdropPush(scaleTo: Float, durationMillis: Int): Float {
    if (LocalMotionPolicy.current.reducedMotion) return 1f
    val elapsed by rememberAmbientMillis(limitMillis = durationMillis.toLong())
    return pushScaleAt(elapsed, durationMillis, scaleTo)
}

/** [rememberBackdropPush]'s scale [elapsedMillis] into the push. */
internal fun pushScaleAt(elapsedMillis: Long, durationMillis: Int, scaleTo: Float): Float {
    val progress = (elapsedMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
    return 1f + (scaleTo - 1f) * FastOutSlowInEasing.transform(progress)
}

/** A back-and-forth of [legMillis] each way, eased: 0 at the start, 1 at the turn. */
internal fun driftAt(elapsedMillis: Long, legMillis: Int): Float {
    val phase = (elapsedMillis % (2L * legMillis)).toFloat() / legMillis
    return FastOutSlowInEasing.transform(if (phase <= 1f) phase else 2f - phase)
}

/** A steady 0..1 that starts over every [periodMillis]. */
internal fun sweepAt(elapsedMillis: Long, periodMillis: Int): Float =
    (elapsedMillis % periodMillis).toFloat() / periodMillis
