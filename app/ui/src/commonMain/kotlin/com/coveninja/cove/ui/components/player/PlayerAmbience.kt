package com.coveninja.cove.ui.components.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
 * The title's logo, fading in and out, with a glow behind it.
 *
 * Stands in for a progress spinner on the pre-playback screens: it says which
 * title you are waiting on as well as that something is happening, which a
 * spinner cannot. Falls back to the title text when the logo was never fetched —
 * only enriched media carries logoUrl.
 *
 * Opacity is the only thing that moves. Scaling the logo or swelling the glow
 * made it look like it was being zoomed rather than breathing.
 */
@Composable
fun PulsingLogo(
    logoUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "LogoPulse")
    val pulse by transition.animateFloat(
        initialValue = MIN_LOGO_ALPHA,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "LogoAlpha",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (logoUrl != null) {
            AsyncImage(
                model = logoUrl,
                contentDescription = title,
                modifier = Modifier
                    .heightIn(max = 96.dp)
                    .widthIn(max = 340.dp)
                    .graphicsLayer { alpha = pulse },
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                text = title,
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .graphicsLayer { alpha = pulse },
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Low enough to read as a pulse, high enough that the title stays legible. */
private const val MIN_LOGO_ALPHA = 0.45f
