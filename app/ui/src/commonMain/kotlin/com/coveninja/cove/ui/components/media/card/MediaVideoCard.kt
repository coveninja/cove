package com.coveninja.cove.ui.components.media.card

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.components.common.CoveAsyncImage
import com.coveninja.cove.ui.model.MediaVideo
import com.coveninja.cove.ui.model.tmdbImageSize
import com.coveninja.cove.ui.icons.IconifyIcon

@Composable
fun MediaVideoCard(
    video: MediaVideo,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val cardScale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.975f
            isHovered -> 1.025f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "VideoCardScale",
    )
    val imageScale by animateFloatAsState(
        targetValue = if (isHovered) 1.07f else 1f,
        animationSpec = tween(260),
        label = "VideoCardImageScale",
    )
    val playScale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.88f
            isHovered -> 1.14f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "VideoCardPlayScale",
    )
    val elevation by animateDpAsState(
        targetValue = if (isHovered) 12.dp else 3.dp,
        animationSpec = tween(150),
        label = "VideoCardElevation",
    )
    val scrimColor by animateColorAsState(
        targetValue = Color.Black.copy(
            alpha = if (isHovered) 0.08f else 0.22f,
        ),
        animationSpec = tween(180),
        label = "VideoCardScrim",
    )
    val playColor by animateColorAsState(
        targetValue = if (isHovered) {
            colors.tertiary
        } else {
            Color.Black.copy(alpha = 0.66f)
        },
        animationSpec = tween(140),
        label = "VideoCardPlayColor",
    )
    val titleColor by animateColorAsState(
        targetValue = if (isHovered) {
            colors.tertiary
        } else {
            colors.onSurface
        },
        animationSpec = tween(120),
        label = "VideoCardTitleColor",
    )

    Column(
        modifier = modifier
            .width(252.dp)
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
            }
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        val imageShape = RoundedCornerShape(14.dp)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .shadow(
                    elevation = elevation,
                    shape = imageShape,
                    clip = false,
                )
                .clip(imageShape)
                .background(colors.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            video.thumbnailUrl?.let { thumbnailUrl ->
                CoveAsyncImage(
                    model = tmdbImageSize(thumbnailUrl, "w500"),
                    contentDescription = video.title,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = imageScale
                            scaleY = imageScale
                        },
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(scrimColor),
                )
            }

            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .graphicsLayer {
                        scaleX = playScale
                        scaleY = playScale
                    },
                shape = CircleShape,
                color = playColor,
                shadowElevation = if (isHovered) 8.dp else 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    IconifyIcon(
                        icon = "lucide:play",
                        modifier = Modifier.size(22.dp),
                        tint = if (isHovered) {
                            colors.onTertiary
                        } else {
                            Color.White
                        },
                    )
                }
            }

            video.type?.let { type ->
                Text(
                    text = type.uppercase(),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.60f),
                            shape = CircleShape,
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            video.duration?.let { duration ->
                Text(
                    text = duration,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.72f),
                            shape = RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Text(
            text = video.title,
            modifier = Modifier.padding(top = 10.dp),
            color = titleColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
