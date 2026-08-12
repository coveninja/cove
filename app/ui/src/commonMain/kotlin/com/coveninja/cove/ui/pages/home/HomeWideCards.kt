package com.coveninja.cove.ui.pages.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.coveninja.cove.shared.model.CalendarItem
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.model.tmdbImageSize
import com.coveninja.cove.ui.pages.mylist.calendar.episodeMarker
import com.coveninja.cove.ui.platform.hasPointerHover
import com.coveninja.cove.ui.model.MediaType as UiMediaType

/**
 * The wide cards Home uses for things the viewer already owns.
 *
 * Landscape rather than the poster shape used everywhere else, and that is the point: a
 * poster is how you advertise something unseen, while a still frame is how you say "you were
 * here". The two rails that use these — carry on watching, and episodes waiting — are the
 * only places in the app showing content the viewer has already committed to.
 */

/**
 * A title to carry on with: where you got to, and one press to keep going.
 *
 * [stillUrl] is the frame from the episode itself and always wins when it has arrived — a
 * still says "you were here" in a way a show's promotional backdrop cannot. It is fetched
 * rather than read, so it is null on the first frame and for every film; the backdrop and
 * then the poster stand in until and unless it lands.
 */
@Composable
fun ContinueCard(
    row: ContinueRow,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    stillUrl: String? = null,
) {
    WideCard(
        artUrl = stillUrl ?: row.artUrl,
        // A still is 16:9 like the frame it goes in, so it needs no crop bias even when the
        // row's own art would have.
        wideArt = stillUrl != null || row.hasWideArt,
        contentDescription = "${row.displayTitle} artwork",
        fallbackIcon = if (row.media.type == UiMediaType.Movie) "lucide:film" else "lucide:tv",
        title = row.displayTitle,
        caption = row.caption,
        badge = if (row.hasNewEpisodes) "NEW" else null,
        watchFraction = row.watchFraction,
        playDescription = "Resume ${row.displayTitle}",
        onOpen = onOpen,
        onPlay = onPlay,
        modifier = modifier,
    )
}

/**
 * An episode that aired while the viewer was away.
 *
 * The badge counts the backlog rather than saying "new": with four unwatched episodes the
 * useful fact is how far behind you are, not that something happened.
 */
@Composable
fun BacklogCard(
    row: BacklogRow,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WideCard(
        artUrl = calendarWideImageUrl(row.item),
        // Already sized by the helper, and calendar paths are bare rather than proxied, so
        // this must not be run through the resizer again.
        resize = false,
        wideArt = row.item.stillPath.isNotBlank(),
        contentDescription = "${row.displayTitle} artwork",
        fallbackIcon = if (row.item.type == MediaType.Movie) "lucide:film" else "lucide:tv",
        title = row.displayTitle,
        caption = row.caption,
        badge = row.badge,
        watchFraction = null,
        playDescription = "Play ${row.displayTitle}",
        onOpen = onOpen,
        onPlay = onPlay,
        modifier = modifier,
    )
}

@Composable
private fun WideCard(
    artUrl: String?,
    wideArt: Boolean,
    contentDescription: String,
    fallbackIcon: String,
    title: String,
    caption: String,
    badge: String?,
    watchFraction: Float?,
    playDescription: String,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    resize: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.98f
            hovered -> 1.03f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "WideCardScale",
    )
    val elevation by animateDpAsState(
        targetValue = if (hovered) 14.dp else 2.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "WideCardElevation",
    )

    val shape = RoundedCornerShape(14.dp)
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .scale(scale)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .shadow(elevation = elevation, shape = shape, clip = false)
                .clip(shape)
                .background(colors.surfaceContainer),
        ) {
            if (artUrl == null) {
                // A tinted glyph rather than an empty box, so "no art for this title" does
                // not read as "the image failed to load".
                IconifyIcon(
                    icon = fallbackIcon,
                    modifier = Modifier.align(Alignment.Center).size(28.dp),
                    tint = colors.onSurfaceVariant.copy(alpha = 0.55f),
                )
            } else {
                AsyncImage(
                    model = if (resize) tmdbImageSize(artUrl, "w780") else artUrl,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    // A poster dropped into a landscape frame is cropped hard; biasing the
                    // crop upwards keeps faces and titles rather than centring on a torso.
                    alignment = if (wideArt) Alignment.Center else Alignment.TopCenter,
                )
            }

            // A permanent floor under the badge and the progress bar, so neither depends on
            // how light the frame behind it happens to be.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.35f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.55f),
                            ),
                        ),
                    ),
            )

            badge?.let {
                CardBadge(
                    text = it,
                    modifier = Modifier.align(Alignment.TopStart).padding(9.dp),
                )
            }

            PlayAffordance(
                // Pinned on touch, where there is no hover and a hidden control is an
                // unreachable one.
                visible = hovered || !hasPointerHover,
                description = playDescription,
                onClick = onPlay,
                modifier = Modifier.align(Alignment.BottomEnd).padding(9.dp),
            )

            watchFraction?.let { fraction ->
                CardProgress(
                    fraction = fraction,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                color = colors.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = caption,
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CardBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiary)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onTertiary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun PlayAffordance(
    visible: Boolean,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(140)) + scaleIn(
            initialScale = 0.6f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        ),
        exit = fadeOut(tween(110)) + scaleOut(targetScale = 0.6f),
    ) {
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        val colors = MaterialTheme.colorScheme

        Box(
            modifier = Modifier
                .size(if (hasPointerHover) 38.dp else 48.dp)
                .clip(CircleShape)
                .background(if (hovered) colors.tertiary else Color.Black.copy(alpha = 0.72f))
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                // IconifyIcon draws with a null contentDescription, so the label has to live
                // on the button itself for a screen reader to find anything here.
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            IconifyIcon(
                icon = "lucide:play",
                modifier = Modifier.size(17.dp),
                tint = if (hovered) colors.onTertiary else Color.White,
            )
        }
    }
}

@Composable
private fun CardProgress(fraction: Float, modifier: Modifier = Modifier) {
    val grown = remember { Animatable(0f) }
    LaunchedEffect(fraction) {
        grown.animateTo(
            targetValue = fraction.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Color.Black.copy(alpha = 0.45f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(pivotFractionX = 0f, pivotFractionY = 0.5f)
                    scaleX = grown.value
                }
                .background(MaterialTheme.colorScheme.tertiary),
        )
    }
}
