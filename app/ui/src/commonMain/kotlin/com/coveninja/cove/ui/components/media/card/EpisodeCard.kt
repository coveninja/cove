package com.coveninja.cove.ui.components.media.card

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.coveninja.cove.ui.model.MediaEpisode
import com.coveninja.cove.ui.model.tmdbImageSize
import com.ongshok.iconify.ui.IconifyIcon
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun EpisodeCard(
    episode: MediaEpisode,
    seasonNumber: Int,
    watched: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onWatchedChange: (Boolean) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    var contextMenuVisible by remember { mutableStateOf(false) }
    var contextMenuPosition by remember { mutableStateOf(Offset.Zero) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "EpisodeCardScale",
    )
    val elevation by animateDpAsState(
        targetValue = if (isHovered) 5.dp else 0.dp,
        animationSpec = tween(140),
        label = "EpisodeCardElevation",
    )
    val containerColor by animateColorAsState(
        targetValue = if (isHovered) {
            colors.surfaceContainerHighest
        } else {
            colors.surfaceContainerHigh
        },
        animationSpec = tween(120),
        label = "EpisodeCardColor",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            watched -> colors.tertiary.copy(alpha = 0.62f)
            isHovered -> colors.tertiary.copy(alpha = 0.38f)
            else -> colors.outlineVariant.copy(alpha = 0.42f)
        },
        animationSpec = tween(120),
        label = "EpisodeCardBorder",
    )

    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .hoverable(interactionSource)
                .onPointerEvent(PointerEventType.Press) { event ->
                    if (event.buttons.isSecondaryPressed) {
                        contextMenuPosition =
                            event.changes.firstOrNull()?.position
                                ?: Offset.Zero
                        contextMenuVisible = true
                        event.changes.forEach { change ->
                            change.consume()
                        }
                    }
                }
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onLongClick = {
                        contextMenuPosition = Offset(16f, 16f)
                        contextMenuVisible = true
                    },
                    onClick = onClick,
                ),
            shape = RoundedCornerShape(14.dp),
            color = containerColor,
            shadowElevation = elevation,
            border = BorderStroke(
                width = 1.dp,
                color = borderColor,
            ),
        ) {
            if (compact) {
                Column {
                    EpisodeArtwork(
                        episode = episode,
                        watched = watched,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f),
                    )
                    EpisodeCopy(
                        episode = episode,
                        watched = watched,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EpisodeArtwork(
                        episode = episode,
                        watched = watched,
                        modifier = Modifier
                            .width(184.dp)
                            .aspectRatio(16f / 9f),
                    )
                    EpisodeCopy(
                        episode = episode,
                        watched = watched,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = contextMenuPosition.x.roundToInt(),
                        y = contextMenuPosition.y.roundToInt(),
                    )
                }
                .size(1.dp),
        ) {
            MediaContextMenu(
                expanded = contextMenuVisible,
                title = episode.title,
                mediaType = "Season $seasonNumber  •  Episode ${episode.number}",
                rating = episode.rating,
                currentListCategory = null,
                isWatched = watched,
                showMyListActions = false,
                onDismissRequest = {
                    contextMenuVisible = false
                },
                onSetMyListCategory = {},
                onRemoveFromMyList = {},
                onToggleWatched = {
                    onWatchedChange(!watched)
                },
            )
        }
    }
}

@Composable
private fun EpisodeArtwork(
    episode: MediaEpisode,
    watched: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        episode.stillUrl?.let { stillUrl ->
            AsyncImage(
                model = tmdbImageSize(stillUrl, "w500"),
                contentDescription = "${episode.title} still",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.14f)),
            )
        }

        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.62f)),
            contentAlignment = Alignment.Center,
        ) {
            IconifyIcon(
                icon = "lucide:play",
                modifier = Modifier.size(19.dp),
                tint = Color.White,
            )
        }

        if (watched) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary),
                contentAlignment = Alignment.Center,
            ) {
                IconifyIcon(
                    icon = "lucide:check",
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onTertiary,
                )
            }
        }
    }
}

@Composable
private fun EpisodeCopy(
    episode: MediaEpisode,
    watched: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "${episode.number}. ${episode.title}",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        val metadata = buildList {
            episode.airDate?.let(::add)
            episode.runtimeMinutes?.let { add("${it}m") }
            episode.rating?.let { add("★ ${"%.1f".format(it)}") }
            if (watched) add("Watched")
        }

        if (metadata.isNotEmpty()) {
            Text(
                text = metadata.joinToString("  •  "),
                modifier = Modifier.padding(top = 4.dp),
                color = if (watched) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }

        episode.overview
            ?.takeIf { it.isNotBlank() }
            ?.let { overview ->
                Text(
                    text = overview,
                    modifier = Modifier.padding(top = 7.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
    }
}
