package com.coveninja.cove.ui.pages.explore

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.components.common.HorizontalLazyListScrollbar
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.pages.common.StaggeredAppear
import com.coveninja.cove.ui.platform.hasPointerHover
import kotlinx.coroutines.launch

/**
 * One horizontal rail: a heading, a scrolling row of cards, and the controls for moving
 * along it.
 *
 * The arrows and the "See all" link are revealed on hover on a desktop and pinned on a
 * touch screen, where hovering is impossible and a hidden control is an unreachable one.
 * The edge fades appear only on the side that can actually still scroll, so they say
 * something rather than merely decorating.
 */
@Composable
fun ExploreShelfRow(
    shelf: ExploreShelf,
    mediaCard: @Composable (Media, Modifier) -> Unit,
    onSeeAll: (ExploreShelf) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rowHover = remember { MutableInteractionSource() }
    val hovered by rowHover.collectIsHoveredAsState()

    // Touch has no hover, so anything gated on it is pinned there instead.
    val controlsVisible = hovered || !hasPointerHover

    Column(
        modifier = modifier.fillMaxWidth().hoverable(rowHover),
    ) {
        ShelfHeader(
            shelf = shelf,
            showSeeAll = controlsVisible,
            onSeeAll = { onSeeAll(shelf) },
            modifier = Modifier.padding(horizontal = HORIZONTAL_PADDING),
        )

        Box(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(CARD_HEIGHT),
                contentPadding = PaddingValues(horizontal = HORIZONTAL_PADDING),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(shelf.media, key = { _, item -> item.id }) { index, item ->
                    StaggeredAppear(index = index) {
                        mediaCard(item, Modifier.width(CARD_WIDTH))
                    }
                }
            }

            EdgeFade(
                visible = listState.canScrollBackward,
                alignment = Alignment.CenterStart,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            EdgeFade(
                visible = listState.canScrollForward,
                alignment = Alignment.CenterEnd,
                modifier = Modifier.align(Alignment.CenterEnd),
            )

            ShelfArrow(
                visible = controlsVisible && listState.canScrollBackward,
                iconName = "lucide:chevron-left",
                description = "Scroll ${shelf.title} left",
                onClick = {
                    scope.launch {
                        listState.animateScrollBy(-listState.viewportStride())
                    }
                },
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
            )
            ShelfArrow(
                visible = controlsVisible && listState.canScrollForward,
                iconName = "lucide:chevron-right",
                description = "Scroll ${shelf.title} right",
                onClick = {
                    scope.launch {
                        listState.animateScrollBy(listState.viewportStride())
                    }
                },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            )
        }

        HorizontalLazyListScrollbar(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HORIZONTAL_PADDING, vertical = 4.dp),
        )
    }
}

@Composable
private fun ShelfHeader(
    shelf: ExploreShelf,
    showSeeAll: Boolean,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            IconifyIcon(
                icon = shelf.icon,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = shelf.title,
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = shelf.subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Only rails the grid can actually reproduce offer it. A personalized rail has no
        // equivalent filter, and a "See all" that quietly showed something else would be
        // worse than no link.
        if (shelf.genreId != null || shelf.kind != ShelfKind.BecauseYouWatched) {
            AnimatedVisibility(
                visible = showSeeAll,
                enter = fadeIn(tween(140)),
                exit = fadeOut(tween(120)),
            ) {
                SeeAllLink(onClick = onSeeAll)
            }
        }
    }
}

@Composable
private fun SeeAllLink(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (hovered) colors.onSurface.copy(alpha = 0.10f) else Color.Transparent,
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "See all",
            color = if (hovered) colors.tertiary else colors.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        IconifyIcon(
            icon = "lucide:arrow-right",
            modifier = Modifier.size(14.dp),
            tint = if (hovered) colors.tertiary else colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShelfArrow(
    visible: Boolean,
    iconName: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(120)),
    ) {
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    Color.Black.copy(alpha = if (hovered) 0.86f else 0.62f),
                )
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            IconifyIcon(icon = iconName, modifier = Modifier.size(20.dp), tint = Color.White)
        }
    }
}

/**
 * The soft edge that says "there is more this way".
 *
 * Drawn with the page background rather than a neutral black so it dissolves into the
 * page instead of banding against it.
 */
@Composable
private fun EdgeFade(
    visible: Boolean,
    alignment: Alignment,
    modifier: Modifier = Modifier,
) {
    val background = MaterialTheme.colorScheme.background
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(140)),
    ) {
        val colors = if (alignment == Alignment.CenterStart) {
            listOf(background, background.copy(alpha = 0f))
        } else {
            listOf(background.copy(alpha = 0f), background)
        }
        Box(
            modifier = Modifier
                .width(EDGE_FADE_WIDTH)
                .fillMaxHeight()
                .background(Brush.horizontalGradient(colors)),
        )
    }
}

/**
 * How far an arrow press travels: most of the viewport, but not all of it.
 *
 * Leaving a card of overlap is what keeps the viewer's place — a full-viewport jump
 * replaces everything on screen at once and reads as teleporting rather than scrolling.
 */
private fun androidx.compose.foundation.lazy.LazyListState.viewportStride(): Float {
    val viewport = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()
    return if (viewport <= 0f) 600f else viewport * 0.8f
}

private val CARD_WIDTH = 158.dp

/** 2:3 posters plus the room the card's hover lift needs so it is not clipped. */
private val CARD_HEIGHT = 250.dp

private val EDGE_FADE_WIDTH = 56.dp

internal val HORIZONTAL_PADDING = 24.dp
