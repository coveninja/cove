package com.coveninja.cove.ui.pages.common

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
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.components.common.HorizontalLazyListScrollbar
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.platform.hasPointerHover
import kotlinx.coroutines.launch

/**
 * One horizontal rail: a heading, a scrolling row of cards, and the controls for moving
 * along it.
 *
 * The arrows and the trailing link are revealed on hover on a desktop and pinned on a touch
 * screen, where hovering is impossible and a hidden control is an unreachable one. The edge
 * fades appear only on the side that can actually still scroll, so they say something rather
 * than merely decorating.
 *
 * Generic over the item type because the two callers carry different things: Explore rails
 * hold [com.coveninja.cove.ui.model.Media] drawn as posters, Home's continue-watching rail
 * holds rows drawn as wide cards. Everything around the items — the heading, the arrows, the
 * fades, the scrollbar — is the same rail either way, and having one of it is the point.
 */
@Composable
fun <T> MediaRail(
    title: String,
    subtitle: String,
    icon: String,
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    itemWidth: Dp = RailDefaults.CardWidth,
    itemHeight: Dp = RailDefaults.CardHeight,
    /** Null when there is nowhere honest for a "see all" to lead. */
    onSeeAll: (() -> Unit)? = null,
    seeAllLabel: String = "See all",
    itemContent: @Composable (T, Modifier) -> Unit,
) {
    val horizontalPadding = PageLayoutDefaults.HorizontalPadding
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rowHover = remember { MutableInteractionSource() }
    val hovered by rowHover.collectIsHoveredAsState()

    // Touch has no hover, so anything gated on it is pinned there instead.
    val controlsVisible = hovered || !hasPointerHover

    Column(
        modifier = modifier.fillMaxWidth().hoverable(rowHover),
    ) {
        RailHeader(
            title = title,
            subtitle = subtitle,
            icon = icon,
            showSeeAll = controlsVisible,
            seeAllLabel = seeAllLabel,
            onSeeAll = onSeeAll,
            modifier = Modifier.padding(horizontal = horizontalPadding),
        )

        Box(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(itemHeight),
                contentPadding = PaddingValues(horizontal = horizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
                    StaggeredAppear(index = index) {
                        itemContent(item, Modifier.width(itemWidth))
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

            RailArrow(
                visible = controlsVisible && listState.canScrollBackward,
                iconName = "lucide:chevron-left",
                description = "Scroll $title left",
                onClick = {
                    scope.launch { listState.animateScrollBy(-listState.viewportStride()) }
                },
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
            )
            RailArrow(
                visible = controlsVisible && listState.canScrollForward,
                iconName = "lucide:chevron-right",
                description = "Scroll $title right",
                onClick = {
                    scope.launch { listState.animateScrollBy(listState.viewportStride()) }
                },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            )
        }

        HorizontalLazyListScrollbar(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = 4.dp),
        )
    }
}

@Composable
private fun RailHeader(
    title: String,
    subtitle: String,
    icon: String,
    showSeeAll: Boolean,
    seeAllLabel: String,
    onSeeAll: (() -> Unit)?,
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
                icon = icon,
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.tertiary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (onSeeAll != null) {
            AnimatedVisibility(
                visible = showSeeAll,
                enter = fadeIn(tween(140)),
                exit = fadeOut(tween(120)),
            ) {
                SeeAllLink(label = seeAllLabel, onClick = onSeeAll)
            }
        }
    }
}

@Composable
private fun SeeAllLink(label: String, onClick: () -> Unit) {
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
            text = label,
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
private fun RailArrow(
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
                .background(Color.Black.copy(alpha = if (hovered) 0.86f else 0.62f))
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
                .width(RailDefaults.EdgeFadeWidth)
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
private fun LazyListState.viewportStride(): Float {
    val viewport = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()
    return if (viewport <= 0f) 600f else viewport * 0.8f
}

/** Rail metrics, shared so Home and Explore line up down the page. */
object RailDefaults {
    val HorizontalPadding = 24.dp

    val CardWidth = 158.dp

    /** 2:3 posters plus the room the card's hover lift needs so it is not clipped. */
    val CardHeight = 250.dp

    /** 16:9 art at the same visual weight, for rails that lead with a backdrop or still. */
    val WideCardWidth = 300.dp

    val WideCardHeight = 214.dp

    val EdgeFadeWidth = 56.dp
}
