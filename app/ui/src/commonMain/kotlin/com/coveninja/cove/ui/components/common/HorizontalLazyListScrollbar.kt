package com.coveninja.cove.ui.components.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * A compact, draggable scrollbar for a horizontally scrolling lazy list.
 *
 * The scrollbar removes itself when every item fits in the viewport. Its
 * visual track stays deliberately slim while the surrounding hit target is
 * large enough for comfortable mouse and touch dragging.
 */
@Composable
fun HorizontalLazyListScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    val isScrollable = state.canScrollBackward || state.canScrollForward

    AnimatedVisibility(
        visible = isScrollable,
        modifier = modifier,
        enter = fadeIn(tween(140)) + expandVertically(
            animationSpec = tween(160),
            expandFrom = Alignment.CenterVertically,
        ),
        exit = fadeOut(tween(100)) + shrinkVertically(
            animationSpec = tween(120),
            shrinkTowards = Alignment.CenterVertically,
        ),
    ) {
        val layoutInfo = state.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        val firstVisibleItem = visibleItems.firstOrNull()
            ?: return@AnimatedVisibility
        val totalItems = layoutInfo.totalItemsCount
        val viewportSize = (
            layoutInfo.viewportEndOffset -
                layoutInfo.viewportStartOffset
        ).coerceAtLeast(0)

        if (totalItems == 0) return@AnimatedVisibility

        val averageItemSize = visibleItems
            .map { item -> item.size }
            .average()
            .toFloat()
            .coerceAtLeast(1f)
        val itemStep = averageItemSize + layoutInfo.mainAxisItemSpacing
        val estimatedContentSize = (
            itemStep * totalItems -
                layoutInfo.mainAxisItemSpacing
        ).coerceAtLeast(viewportSize.toFloat())
        val maxScroll = (
            estimatedContentSize - viewportSize
        ).coerceAtLeast(0f)
        val estimatedScroll = (
            firstVisibleItem.index * itemStep -
                firstVisibleItem.offset
        ).coerceIn(0f, maxScroll)
        val thumbFraction = if (estimatedContentSize == 0f) {
            1f
        } else {
            (viewportSize / estimatedContentSize)
                .coerceIn(0.18f, 1f)
        }
        val scrollFraction = if (maxScroll == 0f) {
            0f
        } else {
            (estimatedScroll / maxScroll).coerceIn(0f, 1f)
        }

        val interactionSource = remember { MutableInteractionSource() }
        val isHovered by interactionSource.collectIsHoveredAsState()
        var isDragging by remember { mutableStateOf(false) }
        val isActive = isHovered || isDragging

        val trackThickness by animateDpAsState(
            targetValue = when {
                isDragging -> 6.dp
                isHovered -> 5.dp
                else -> 3.dp
            },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
            label = "HorizontalScrollbarTrackThickness",
        )
        val thumbThickness by animateDpAsState(
            targetValue = when {
                isDragging -> 9.dp
                isHovered -> 7.dp
                else -> 5.dp
            },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
            label = "HorizontalScrollbarThumbThickness",
        )
        val trackColor by animateColorAsState(
            targetValue = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                alpha = if (isActive) 0.22f else 0.12f,
            ),
            animationSpec = tween(120),
            label = "HorizontalScrollbarTrackColor",
        )
        val thumbColor by animateColorAsState(
            targetValue = MaterialTheme.colorScheme.tertiary.copy(
                alpha = when {
                    isDragging -> 1f
                    isHovered -> 0.88f
                    else -> 0.68f
                },
            ),
            animationSpec = tween(120),
            label = "HorizontalScrollbarThumbColor",
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .hoverable(interactionSource)
                .pointerInput(state, maxScroll, thumbFraction) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            isDragging = true
                        },
                        onDragEnd = {
                            isDragging = false
                        },
                        onDragCancel = {
                            isDragging = false
                        },
                    ) { change, dragAmount ->
                        change.consume()

                        val thumbTravel =
                            size.width * (1f - thumbFraction)

                        if (thumbTravel > 0f && maxScroll > 0f) {
                            state.dispatchRawDelta(
                                dragAmount / thumbTravel * maxScroll
                            )
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val thumbWidth = maxWidth * thumbFraction
            val thumbOffset = (
                maxWidth - thumbWidth
            ) * scrollFraction

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackThickness)
                    .clip(CircleShape)
                    .background(trackColor),
            )

            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .width(thumbWidth)
                    .height(thumbThickness)
                    .clip(CircleShape)
                    .background(thumbColor),
            )
        }
    }
}
