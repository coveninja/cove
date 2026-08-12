package com.coveninja.cove.ui.pages.mylist

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.model.tmdbImageSize
import com.coveninja.cove.ui.platform.hasPointerHover
import kotlin.math.roundToInt

/**
 * The spotlight at the top of My List: the one title the viewer is most likely to want to
 * carry on with, with a Resume that goes straight to playback.
 *
 * Which episode that resumes is deliberately not decided here — [onResume] hands the title
 * to the playback session, which already resolves the next episode from library state.
 */
@Composable
fun MyListHero(
    row: MyListRow,
    onOpen: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onOpen),
    ) {
        // Crossfade rather than a hard swap: finishing something changes which title is
        // here, and a cut makes the whole page look like it reloaded.
        AnimatedContent(
            targetState = row.media.backdropUrl ?: row.media.posterUrl,
            transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(320)) },
            label = "MyListHeroBackdrop",
        ) { image ->
            // A slow drift keeps a still frame from reading as a broken image while the
            // rest of the page animates around it.
            val drift = rememberInfiniteTransition(label = "MyListHeroDrift")
            val scale by drift.animateFloat(
                initialValue = 1f,
                targetValue = 1.06f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 22_000, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "MyListHeroScale",
            )

            AsyncImage(
                model = tmdbImageSize(image, "w1280"),
                contentDescription = "${row.displayTitle} backdrop",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 0.4f)
                    },
                contentScale = ContentScale.Crop,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.92f),
                            Color.Black.copy(alpha = 0.62f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .widthIn(max = 520.dp)
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Continue watching",
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (row.media.logoUrl != null) {
                AsyncImage(
                    model = tmdbImageSize(row.media.logoUrl, "w500"),
                    contentDescription = "${row.displayTitle} logo",
                    modifier = Modifier.width(240.dp),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High,
                )
            } else {
                Text(
                    text = row.displayTitle,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = heroSubtitle(row),
                color = Color.White.copy(alpha = 0.80f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )

            row.watchFraction?.let { fraction ->
                HeroProgress(fraction = fraction)
            }

            Spacer(Modifier.height(2.dp))

            HeroResumeButton(
                label = if (row.watchFraction != null) "Resume" else "Play",
                onClick = onResume,
            )
        }
    }
}

private fun heroSubtitle(row: MyListRow): String {
    val parts = mutableListOf<String>()
    row.episodeMarker?.let(parts::add)
    row.watchFraction?.let { parts += "${(it * 100).roundToInt()}% watched" }
    if (row.hasNewEpisodes) parts += "new episodes aired"
    row.media.type?.label?.takeIf { parts.isEmpty() }?.let(parts::add)
    return parts.joinToString("  ·  ")
}

@Composable
private fun HeroProgress(fraction: Float) {
    Box(
        modifier = Modifier
            .width(240.dp)
            .height(4.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.22f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(pivotFractionX = 0f, pivotFractionY = 0.5f)
                    scaleX = fraction.coerceIn(0f, 1f)
                }
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiary),
        )
    }
}

@Composable
private fun HeroResumeButton(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (hovered) 1.04f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "HeroResumeScale",
    )

    Row(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiary)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .heightIn(min = if (hasPointerHover) 0.dp else 48.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconifyIcon(
            icon = "lucide:play",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onTertiary,
        )
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onTertiary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
