package com.coveninja.cove.ui.tv.focus

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.runtime.withFrameNanos
import com.coveninja.cove.ui.state.LocalMotionPolicy

object TvFocusDefaults {
    /**
     * Three pixels, not a hairline.
     *
     * The first TV round shipped a one-pixel ring and the feedback was that focus was simply
     * not findable — at three metres a hairline is below the resolving power of the situation,
     * whatever it looks like on a monitor a foot away.
     */
    val RingWidth = 3.dp

    /** The halo behind the ring. Colour plus lift is what separates focus from a mere border. */
    val GlowElevation = 14.dp

    /** A poster grows noticeably; a button in a row of buttons only nudges. */
    const val CardScale = 1.08f
    const val ControlScale = 1.04f
}

/**
 * The focus ring, halo and lift — everything that says "this one".
 *
 * Separate from [tvFocusTarget] because several surfaces already own their interaction handling
 * (the player's transport row, the side rail) and need only the appearance; having one
 * definition of it is what stops focus from looking like three different things across the app.
 *
 * [zIndex] matters as much as the ring does: a grown card sitting in a row would otherwise be
 * overdrawn by the neighbour laid out after it, clipping the very edge that just got emphasis.
 */
@Composable
fun Modifier.tvFocusVisuals(
    focused: Boolean,
    shape: Shape,
    scale: Float = TvFocusDefaults.CardScale,
    ringColor: Color = MaterialTheme.colorScheme.primary,
): Modifier {
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val animatedScale by animateFloatAsState(
        targetValue = if (focused) scale else 1f,
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
        },
        label = "TvFocusScale",
    )
    val ringAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(140),
        label = "TvFocusRing",
    )
    return this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            scaleX = animatedScale
            scaleY = animatedScale
        }
        .shadow(
            elevation = TvFocusDefaults.GlowElevation * ringAlpha,
            shape = shape,
            clip = false,
            ambientColor = ringColor,
            spotColor = ringColor,
        )
        .border(TvFocusDefaults.RingWidth, ringColor.copy(alpha = ringAlpha), shape)
}

/**
 * A focusable, selectable surface: the standard unit the TV shell is built out of.
 *
 * Uses `clickable` rather than `focusable` plus a key handler on purpose — it already makes the
 * node focusable *and* activates on the remote's centre button, and it fires on key release,
 * which is what keeps a single press from opening a screen and immediately pressing whatever
 * that screen focuses first. Indication is dropped because [tvFocusVisuals] is the feedback;
 * a ripple that follows a touch point means nothing without one.
 */
@Composable
fun Modifier.tvFocusTarget(
    shape: Shape,
    onClick: () -> Unit,
    enabled: Boolean = true,
    scale: Float = TvFocusDefaults.CardScale,
    ringColor: Color = MaterialTheme.colorScheme.primary,
    interactionSource: MutableInteractionSource? = null,
    onFocusChanged: (Boolean) -> Unit = {},
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    LaunchedEffect(focused) { onFocusChanged(focused) }
    return this
        .tvFocusVisuals(focused = focused, shape = shape, scale = scale, ringColor = ringColor)
        .clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}

/**
 * Marks a row, column or panel as one focus region that remembers where it was.
 *
 * Leaving a row and coming back to its first card rather than the card you left is the single
 * most disorienting thing a D-pad interface can do; the previous TV shell hand-rolled this as
 * "rememberFocus" and Compose now supplies it directly.
 */
fun Modifier.tvFocusGroup(): Modifier = this.focusRestorer().focusGroup()

/**
 * Requests focus once the target has actually been laid out.
 *
 * [FocusRequester.requestFocus] throws if the node is not attached yet, which is exactly the
 * state a freshly opened screen is in, so the request waits for a frame. That delay does a
 * second job: it puts the request after the key event that opened the screen has finished, so
 * a single press of the centre button cannot open a surface and activate the control the
 * surface focuses. That double-activation is the trap this shell inherited from the last one —
 * a card opened details and details' Play button fired from the same press.
 */
@Composable
fun FocusOnAppear(requester: FocusRequester, enabled: Boolean = true) {
    LaunchedEffect(requester, enabled) {
        if (!enabled) return@LaunchedEffect
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
}

/** Convenience for the common "declare a requester and attach it" pair. */
fun Modifier.tvFocusAnchor(requester: FocusRequester): Modifier = this.focusRequester(requester)
