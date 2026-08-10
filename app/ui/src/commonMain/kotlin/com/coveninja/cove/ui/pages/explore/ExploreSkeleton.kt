package com.coveninja.cove.ui.pages.explore

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

/**
 * What Explore shows before its first rail resolves.
 *
 * The placeholder mirrors the real layout — hero, then headed rows of posters — so the
 * page does not visibly jump when content replaces it. A centred spinner would be less
 * code and a worse answer: it tells the viewer to wait without telling them for what.
 */
@Composable
fun ExploreSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        ShimmerBlock(
            modifier = Modifier.fillMaxWidth().height(360.dp),
            corner = 28.dp,
        )

        repeat(2) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = HORIZONTAL_PADDING),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ShimmerBlock(modifier = Modifier.width(180.dp).height(20.dp), corner = 6.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    repeat(SKELETON_CARDS) {
                        ShimmerBlock(modifier = Modifier.width(158.dp).height(237.dp))
                    }
                }
            }
        }
    }
}

/** Enough to fill a wide window; the row clips rather than wrapping on a narrow one. */
private const val SKELETON_CARDS = 7

private const val SWEEP_SPAN = 600f
