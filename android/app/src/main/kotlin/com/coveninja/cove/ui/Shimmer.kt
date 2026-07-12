package com.coveninja.cove.ui

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/**
 * Animating shimmer brush that sweeps surfaceVariant ↔ surfaceContainerHighest
 * left-to-right in ~1200 ms.  Callers that render multiple skeleton elements
 * should call this once and pass [brush] to each child so they share the same
 * animation phase.
 */
@Composable
fun shimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-x",
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(x - 400f, 0f),
        end = Offset(x + 400f, 0f),
    )
}

/**
 * Single skeleton card matching [PosterCard] dimensions exactly:
 * 110 dp wide, 2:3 aspect ratio image, short text line below.
 */
@Composable
fun PosterCardSkeleton(brush: Brush = shimmerBrush()) {
    Column(modifier = Modifier.width(110.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(MaterialTheme.shapes.small)
                .background(brush),
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(12.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(brush),
        )
    }
}

/**
 * Horizontal row of [count] shimmer poster cards with the same padding/spacing
 * as real [MediaRow] instances.
 */
@Composable
fun MediaRowSkeleton(count: Int = 6) {
    val brush = shimmerBrush()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(count) { PosterCardSkeleton(brush) }
    }
}

/**
 * 3-column grid of [count] shimmer poster cards for Search/Library loading states.
 * Uses [Modifier.fillMaxSize] so it must be placed inside a size-constrained parent.
 */
@Composable
fun PosterGridSkeleton(count: Int = 12) {
    val brush = shimmerBrush()
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(count) { PosterCardSkeleton(brush) }
    }
}
