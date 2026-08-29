package com.coveninja.cove.ui.components.media.card

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.components.common.CoveAsyncImage
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.components.media.drag.MediaDragPayload
import com.coveninja.cove.ui.components.menu.CMenuItem
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.platform.hasPointerHover
import com.coveninja.cove.ui.platform.onSecondaryClick

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MediaCard(
    media: Media,
    modifier: Modifier = Modifier,
    posterModifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    myListCategory: MyListCategory? = null,
    isWatched: Boolean = false,
    /** 0f..1f resume position, or null to draw no progress bar. */
    watchFraction: Float? = null,
    hasNewEpisodes: Boolean = false,
    onSetMyListCategory: (MyListCategory) -> Unit = {},
    onRemoveFromMyList: () -> Unit = {},
    onMarkAsWatched: () -> Unit = {},
    onShare: () -> Unit = {},
    onDragStart: (MediaDragPayload, Offset) -> Unit = { _, _ -> },
    onDrag: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered = if (hasPointerHover) {
        interactionSource.collectIsHoveredAsState().value
    } else {
        false
    }
    val isPressed by interactionSource.collectIsPressedAsState()

    var cardCoordinates by remember {
        mutableStateOf<LayoutCoordinates?>(null)
    }

    var isDragging by remember {
        mutableStateOf(false)
    }

    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)

    // Secondary click does not exist on the Android touch host. Avoid creating two snapshot
    // states and a dormant popup subtree for every poster in every mobile lazy list.
    val contextMenuState = if (hasPointerHover) {
        remember { MediaCardContextMenuState() }
    } else {
        null
    }

    val shape = RoundedCornerShape(12.dp)

    val scale by animateFloatAsState(
        targetValue = when {
            isDragging -> 0.97f
            isPressed -> 0.97f
            isHovered -> 1.035f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "MediaCardScale",
    )

    val cardAlpha by animateFloatAsState(
        targetValue = if (isDragging) 0.4f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "MediaCardAlpha",
    )

    val elevation = if (hasPointerHover) {
        animateDpAsState(
            targetValue = if (isHovered) 12.dp else 2.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
            label = "MediaCardElevation",
        ).value
    } else {
        2.dp
    }

    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = cardAlpha
                shadowElevation = elevation.toPx()
                this.shape = shape
                clip = true
            }
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(
                if (hasPointerHover) {
                    Modifier.hoverable(interactionSource)
                } else {
                    Modifier
                },
            )
            .onGloballyPositioned { coordinates ->
                cardCoordinates = coordinates
            }
            .pointerInput(media.posterUrl, media.title, media.type?.label, media.rating) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { localPosition ->
                        val coordinates = cardCoordinates ?: return@detectDragGesturesAfterLongPress

                        isDragging = true

                        val sourceSize = Size(
                            width = coordinates.size.width.toFloat(),
                            height = coordinates.size.height.toFloat(),
                        )

                        val grabFraction = Offset(
                            x = if (sourceSize.width > 0f) {
                                (localPosition.x / sourceSize.width)
                                    .coerceIn(0f, 1f)
                            } else {
                                0.5f
                            },
                            y = if (sourceSize.height > 0f) {
                                (localPosition.y / sourceSize.height)
                                    .coerceIn(0f, 1f)
                            } else {
                                0.5f
                            },
                        )

                        val dragPayload = MediaDragPayload(
                            mediaId = media.id,
                            posterUrl = media.posterUrl,
                            title = media.title,
                            mediaType = media.type?.label ?: "Unknown",
                            rating = media.rating,
                            sourceSize = sourceSize,
                            grabFraction = grabFraction,
                        )

                        currentOnDragStart(
                            dragPayload,
                            coordinates.localToRoot(localPosition),
                        )
                    },
                    onDrag = { change, _ ->
                        change.consume()

                        cardCoordinates?.let { coordinates ->
                            currentOnDrag(
                                coordinates.localToRoot(change.position)
                            )
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                        currentOnDragEnd()
                    },
                    onDragCancel = {
                        isDragging = false
                        currentOnDragCancel()
                    },
                )
            }
            .then(
                if (contextMenuState != null) {
                    Modifier.onSecondaryClick { position ->
                        contextMenuState.position = position
                        contextMenuState.visible = true
                    }
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        CoveAsyncImage(
            model = media.posterUrl,
            contentDescription = "${media.title} poster",
            modifier = posterModifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        if (hasPointerHover) {
            AnimatedVisibility(
                visible = isHovered,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn() + slideInVertically(
                    initialOffsetY = { height -> height / 3 },
                ),
                exit = fadeOut() + slideOutVertically(
                    targetOffsetY = { height -> height / 3 },
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.55f),
                                    Color.Black.copy(alpha = 0.92f),
                                ),
                            ),
                        )
                        .padding(
                            start = 12.dp,
                            end = 12.dp,
                            top = 40.dp,
                            bottom = 12.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    media.title?.let {
                        Text(
                            text = it,
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MediaBadge(
                            text = media.type?.label ?: "Unknown",
                            containerColor = Color.White.copy(alpha = 0.16f),
                            contentColor = Color.White,
                        )

                        media.rating?.let {
                            MediaBadge(
                                text = "★ ${"%.1f".format(it)}",
                                containerColor = Color.White.copy(alpha = 0.16f),
                                contentColor = Color.White,
                            )
                        }
                    }
                }
            }
        }

        // Drawn after the hover overlay so library state stays readable while the
        // gradient and title are on screen.
        MediaCardDecorations(
            category = myListCategory,
            watchFraction = watchFraction,
            hasNewEpisodes = hasNewEpisodes,
            modifier = Modifier.fillMaxSize(),
        )

        contextMenuState?.let { menu ->
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = menu.position.x.roundToInt(),
                            y = menu.position.y.roundToInt(),
                        )
                    }
                    .size(1.dp),
            ) {
                MediaContextMenu(
                    expanded = menu.visible,
                    title = media.title,
                    subtitle = media.type?.label ?: "Unknown",
                    rating = media.rating,
                    currentListCategory = myListCategory,
                    isWatched = isWatched,
                    onDismissRequest = { menu.visible = false },
                    onSetMyListCategory = onSetMyListCategory,
                    onRemoveFromMyList = onRemoveFromMyList,
                    onToggleWatched = onMarkAsWatched,
                )
            }
        }
    }
}

private class MediaCardContextMenuState {
    var visible by mutableStateOf(false)
    var position by mutableStateOf(Offset.Zero)
}

/**
 * The menu behind a secondary click or a long press, wherever one is offered.
 *
 * [leadingActions] is what lets a card add its own entries — Home's wide cards name a single
 * episode and can act on it — without a second copy of the chrome, the header and the My List
 * block. A caller supplying its own watched action turns [showWatchedAction] off rather than
 * ending up with two, since the one here is about the whole title.
 */
@Composable
internal fun MediaContextMenu(
    expanded: Boolean,
    title: String?,
    subtitle: String,
    rating: Double?,
    currentListCategory: MyListCategory?,
    isWatched: Boolean,
    showMyListActions: Boolean = true,
    showWatchedAction: Boolean = true,
    onDismissRequest: () -> Unit,
    onSetMyListCategory: (MyListCategory) -> Unit,
    onRemoveFromMyList: () -> Unit,
    onToggleWatched: () -> Unit,
    leadingActions: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.width(240.dp),
        shape = RoundedCornerShape(16.dp),
        containerColor = colors.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 18.dp,
        border = BorderStroke(
            width = 1.dp,
            color = colors.outlineVariant.copy(alpha = 0.65f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = 14.dp,
                vertical = 10.dp,
            ),
        ) {
            title?.let {
                Text(
                    text = it,
                    color = colors.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = buildString {
                    append(subtitle)

                    rating?.let {
                        append("  •  ★ ")
                        append("%.1f".format(it))
                    }
                },
                modifier = Modifier.padding(top = 3.dp),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }

        MenuSectionDivider()

        leadingActions()

        if (showWatchedAction) {
            CMenuItem(
                text = if (isWatched) {
                    "Mark as unwatched"
                } else {
                    "Mark as watched"
                },
                iconName = if (isWatched) {
                    "lucide:eye-off"
                } else {
                    "iconamoon:history"
                },
                onClick = {
                    onDismissRequest()
                    onToggleWatched()
                },
            )
        }

        if (showMyListActions) {
            MenuSectionDivider()

            MyListCategory.entries.forEach { category ->
                CMenuItem(
                    text = if (category == currentListCategory) {
                        "${category.label}  •  Current"
                    } else {
                        category.label
                    },
                    iconName = category.icon,
                    onClick = {
                        onDismissRequest()
                        onSetMyListCategory(category)
                    },
                    accent = true,
                    accentColor = category.accentColor,
                )
            }

            if (currentListCategory != null) {
                MenuSectionDivider()

                CMenuItem(
                    text = "Remove from My List",
                    iconName = "iconamoon:close",
                    onClick = {
                        onDismissRequest()
                        onRemoveFromMyList()
                    },
                )
            }
        }
    }
}


/** The rule between two blocks of a context menu, so every menu spaces them alike. */
@Composable
internal fun MenuSectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
}

/**
 * The library's opinion of a title, drawn over its poster: which list it is in, how far
 * through it the viewer is, and whether episodes have aired since they last watched.
 *
 * Deliberately non-interactive — no clickable, no hoverable. Anything that captured
 * pointer events here would punch holes in the card's own hover state.
 */
@Composable
private fun MediaCardDecorations(
    category: MyListCategory?,
    watchFraction: Float?,
    hasNewEpisodes: Boolean,
    modifier: Modifier = Modifier,
) {
    val categoryVisibility = remember {
        MutableTransitionState(category != null)
    }.apply {
        targetState = category != null
    }
    val newEpisodeVisibility = remember {
        MutableTransitionState(hasNewEpisodes)
    }.apply {
        targetState = hasNewEpisodes
    }

    Box(modifier = modifier) {
        AnimatedVisibility(
            visibleState = categoryVisibility,
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
            enter = fadeIn() + scaleIn(initialScale = 0.4f),
            exit = fadeOut() + scaleOut(targetScale = 0.4f),
        ) {
            // AnimatedContent so re-categorising swaps the colour with a beat of its own
            // rather than cutting; the dot is small enough that a cut reads as a glitch.
            AnimatedContent(
                targetState = category,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                label = "MediaCardCategoryDot",
            ) { current ->
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(
                            current?.accentColor ?: MaterialTheme.colorScheme.tertiary,
                        ),
                )
            }
        }

        AnimatedVisibility(
            visibleState = newEpisodeVisibility,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            enter = fadeIn() + scaleIn(
                initialScale = 0.6f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            ),
            exit = fadeOut() + scaleOut(targetScale = 0.6f),
        ) {
            MediaBadge(
                text = "NEW",
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
            )
        }

        if (watchFraction != null) {
            val accent = category?.accentColor ?: MaterialTheme.colorScheme.tertiary
            val targetFraction = watchFraction.coerceIn(0f, 1f)
            // A card entering a lazy viewport should paint its existing progress immediately.
            // Only a real progress change after that first composition needs animation.
            val grown = remember { Animatable(targetFraction) }
            LaunchedEffect(targetFraction) {
                grown.animateTo(
                    targetValue = targetFraction,
                    animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.Black.copy(alpha = 0.45f)),
            ) {
                // scaleX on a full-width bar rather than a width animation: no relayout
                // per frame, and it matches how the details sheet draws its scroll bar.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(pivotFractionX = 0f, pivotFractionY = 0.5f)
                            scaleX = grown.value
                        }
                        .background(accent),
                )
            }
        }
    }
}

@Composable
private fun MediaBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(containerColor)
            .padding(
                horizontal = 8.dp,
                vertical = 4.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}
