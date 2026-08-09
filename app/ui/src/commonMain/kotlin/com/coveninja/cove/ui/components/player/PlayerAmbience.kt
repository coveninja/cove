package com.coveninja.cove.ui.components.player

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * The title's own backdrop, blurred out behind whatever the player is doing
 * before playback starts.
 *
 * A flat black rectangle is what made these screens feel like an error dialog.
 * Blurring the artwork keeps the sense that you are already inside the title,
 * and gives the panels something to sit on.
 */
@Composable
fun PlayerBackdrop(
    backdropUrl: String?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (backdropUrl != null) {
            // Slow drift, so a static image does not read as a frozen screen.
            val transition = rememberInfiniteTransition(label = "BackdropDrift")
            val drift by transition.animateFloat(
                initialValue = 1.06f,
                targetValue = 1.14f,
                animationSpec = infiniteRepeatable(
                    animation = tween(18_000),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "BackdropScale",
            )

            AsyncImage(
                model = backdropUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = drift
                        scaleY = drift
                    }
                    .blur(42.dp),
                contentScale = ContentScale.Crop,
            )
        }

        // Two scrims: a flat one to guarantee contrast for text at any artwork
        // brightness, and a radial one to pull attention to the middle.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f)),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                    ),
                ),
        )
    }
}

/**
 * Sweeping highlight for placeholder content.
 *
 * Used while sources are still being resolved: skeleton rows in the shape of the
 * list that is coming say "results are on their way here" far better than a
 * spinner floating in the middle of a black screen.
 */
@Composable
fun shimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "Shimmer")
    val offset by transition.animateFloat(
        initialValue = -700f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(animation = tween(1500)),
        label = "ShimmerOffset",
    )
    return Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.05f),
            Color.White.copy(alpha = 0.14f),
            Color.White.copy(alpha = 0.05f),
        ),
        start = Offset(offset, 0f),
        end = Offset(offset + 320f, 220f),
    )
}
