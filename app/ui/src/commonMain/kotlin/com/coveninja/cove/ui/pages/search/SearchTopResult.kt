package com.coveninja.cove.ui.pages.search

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.components.common.CoveAsyncImage
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.MediaType
import com.coveninja.cove.ui.model.tmdbImageSize
import com.coveninja.cove.ui.platform.hasPointerHover

/**
 * The best match, presented as a match rather than as tile #1.
 *
 * A poster wall is a good way to recognise something you already know the look of and a poor
 * way to answer "is this the thing I meant?" — that question wants a year, a rating, a
 * sentence of plot and a way to act on the answer. This is the one result that gets them.
 *
 * Two shapes, chosen by what art the title actually has. Backdrop-led where there is one;
 * poster-led where there is not, because a 2:3 poster stretched into a 16:9 frame renders as
 * a smear behind unreadable text. Both carry identical copy and identical actions, so the
 * fallback is a different frame rather than a lesser card.
 */
@Composable
fun SearchTopResult(
    media: Media,
    query: String,
    inList: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onToggleList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = media,
        modifier = modifier.fillMaxWidth(),
        contentKey = { it.id },
        transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(220)) },
        label = "SearchTopResult",
    ) { current ->
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compact = maxWidth < COMPACT_TOP_RESULT_WIDTH
            if (current.backdropUrl.isNullOrBlank()) {
                PosterLedCard(
                    media = current,
                    query = query,
                    compact = compact,
                    inList = inList,
                    onOpen = onOpen,
                    onPlay = onPlay,
                    onToggleList = onToggleList,
                )
            } else {
                BackdropLedCard(
                    media = current,
                    query = query,
                    compact = compact,
                    inList = inList,
                    onOpen = onOpen,
                    onPlay = onPlay,
                    onToggleList = onToggleList,
                )
            }
        }
    }
}

@Composable
private fun BackdropLedCard(
    media: Media,
    query: String,
    compact: Boolean,
    inList: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onToggleList: () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val zoom by animateFloatAsState(
        targetValue = if (hovered && hasPointerHover) 1.04f else 1f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow),
        label = "SearchTopResultZoom",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 260.dp else 300.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .hoverable(hover)
            .clickable(interactionSource = hover, indication = null, onClick = onOpen),
    ) {
        CoveAsyncImage(
            model = tmdbImageSize(media.backdropUrl, "w1280"),
            contentDescription = "${media.displayTitle()} backdrop",
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                transformOrigin = TransformOrigin(0.5f, 0.4f)
            },
            contentScale = ContentScale.Crop,
        )

        // Two gradients rather than one: the horizontal carries the copy column, the vertical
        // keeps the kicker legible against a bright sky at the top of a lot of backdrops.
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.92f),
                        Color.Black.copy(alpha = 0.55f),
                        Color.Transparent,
                    ),
                ),
            ),
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.38f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.55f),
                    ),
                ),
            ),
        )

        TopResultCopy(
            media = media,
            query = query,
            compact = compact,
            hovered = hovered,
            inList = inList,
            onDark = true,
            onOpen = onOpen,
            onPlay = onPlay,
            onToggleList = onToggleList,
            modifier = Modifier.align(Alignment.BottomStart).padding(
                start = if (compact) 20.dp else 32.dp,
                end = 20.dp,
                bottom = if (compact) 20.dp else 28.dp,
            ),
        )
    }
}

@Composable
private fun PosterLedCard(
    media: Media,
    query: String,
    compact: Boolean,
    inList: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onToggleList: () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .hoverable(hover)
            .clickable(interactionSource = hover, indication = null, onClick = onOpen)
            .padding(if (compact) 16.dp else 20.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 20.dp),
    ) {
        Box(
            modifier = Modifier
                .width(if (compact) 100.dp else 132.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            media.posterUrl?.let { poster ->
                CoveAsyncImage(
                    model = tmdbImageSize(poster, "w500"),
                    contentDescription = "${media.displayTitle()} poster",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        TopResultCopy(
            media = media,
            query = query,
            compact = compact,
            hovered = hovered,
            inList = inList,
            onDark = false,
            onOpen = onOpen,
            onPlay = onPlay,
            onToggleList = onToggleList,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The copy and the actions, identical either side of the two frames.
 *
 * [onDark] is the only difference: over a backdrop everything is white on an image, on the
 * poster-led card it is theme colours on a surface. Passing a flag rather than writing the
 * block twice is what keeps the two cards from drifting apart.
 */
@Composable
private fun TopResultCopy(
    media: Media,
    query: String,
    compact: Boolean,
    hovered: Boolean,
    inList: Boolean,
    onDark: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onToggleList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val titleColor = if (onDark) Color.White else colors.onSurface
    val bodyColor = if (onDark) Color.White.copy(alpha = 0.84f) else colors.onSurfaceVariant
    val metaColor = if (onDark) Color.White.copy(alpha = 0.72f) else colors.onSurfaceVariant

    Column(
        modifier = modifier.widthIn(max = 620.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconifyIcon(
                icon = "lucide:sparkles",
                modifier = Modifier.size(14.dp),
                tint = colors.tertiary,
            )
            Text(
                text = "Top result",
                color = colors.tertiary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Text(
            text = highlightedTitle(media.displayTitle(), query, colors.tertiary),
            color = titleColor,
            style = if (compact) {
                MaterialTheme.typography.headlineSmall
            } else {
                MaterialTheme.typography.headlineMedium
            },
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        media.metaLine().takeIf { it.isNotBlank() }?.let { meta ->
            Text(
                text = meta,
                color = metaColor,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        media.overview?.takeIf { it.isNotBlank() }?.let { overview ->
            Text(
                text = overview,
                color = bodyColor,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (compact) 2 else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Films play; series do not. "Watch" on an unstarted show would have to invent an
            // episode to start from, and the details sheet — which knows the seasons — is the
            // honest destination for one.
            if (media.type == MediaType.Movie) {
                TopResultButton(
                    label = "Watch",
                    iconName = "lucide:play",
                    primary = true,
                    lifted = hovered,
                    onDark = onDark,
                    onClick = onPlay,
                )
                TopResultButton(
                    label = "Details",
                    iconName = "lucide:info",
                    primary = false,
                    lifted = hovered,
                    onDark = onDark,
                    onClick = onOpen,
                )
            } else {
                TopResultButton(
                    label = "Details",
                    iconName = "lucide:info",
                    primary = true,
                    lifted = hovered,
                    onDark = onDark,
                    onClick = onOpen,
                )
            }
            TopResultButton(
                label = if (inList) "In My List" else "My List",
                iconName = if (inList) "lucide:bookmark-check" else "lucide:bookmark-plus",
                primary = false,
                lifted = hovered,
                onDark = onDark,
                onClick = onToggleList,
            )
        }
    }
}

@Composable
private fun TopResultButton(
    label: String,
    iconName: String,
    primary: Boolean,
    lifted: Boolean,
    onDark: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (hovered) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "SearchTopResultButtonScale",
    )
    // The whole row rises before any one button is pointed at, so the card reads as offering
    // them rather than as merely reacting.
    val lift by animateDpAsState(
        targetValue = if (lifted && hasPointerHover) 2.dp else 0.dp,
        animationSpec = tween(180),
        label = "SearchTopResultButtonLift",
    )

    val container = when {
        primary -> colors.tertiary
        onDark -> Color.White.copy(alpha = 0.16f)
        else -> colors.onSurface.copy(alpha = 0.10f)
    }
    val content = when {
        primary -> colors.onTertiary
        onDark -> Color.White
        else -> colors.onSurface
    }

    Row(
        modifier = Modifier
            .padding(bottom = lift)
            // The primary Watch action on the top result: padding alone left it around
            // 35 dp tall, short of a comfortable tap target.
            .heightIn(min = if (hasPointerHover) 0.dp else 48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .clip(CircleShape)
            .background(container)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconifyIcon(icon = iconName, modifier = Modifier.size(15.dp), tint = content)
        Text(
            text = label,
            color = content,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** Under this the card stacks its copy tighter and the backdrop frame comes in. */
private val COMPACT_TOP_RESULT_WIDTH = 720.dp
