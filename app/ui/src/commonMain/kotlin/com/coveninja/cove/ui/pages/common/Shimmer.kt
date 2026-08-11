package com.coveninja.cove.ui.pages.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A sweeping highlight over the placeholder surface.
 *
 * Deliberately slow and low-contrast. A shimmer is meant to say "content is coming", and
 * a fast bright one says "something is wrong" instead.
 */
@Composable
fun rememberShimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "Shimmer")
    val sweep by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ShimmerSweep",
    )
    val base = MaterialTheme.colorScheme.surfaceContainer
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)

    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(sweep * SWEEP_SPAN, 0f),
        end = Offset(sweep * SWEEP_SPAN + SWEEP_SPAN, 0f),
    )
}

@Composable
fun ShimmerBlock(
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(rememberShimmerBrush()),
    )
}

private const val SWEEP_SPAN = 600f
