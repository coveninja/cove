package com.coveninja.cove.ui.pages.explore

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.pages.common.PageLayoutDefaults
import com.coveninja.cove.ui.pages.common.ShimmerBlock
import com.coveninja.cove.ui.pages.common.StaggeredAppear
import com.coveninja.cove.ui.state.LocalMotionPolicy
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * The browse-everything grid: an adaptive poster wall that keeps loading as it is scrolled.
 *
 * Items animate to their new positions when a filter changes rather than cutting, which is
 * what makes narrowing by genre read as the grid rearranging itself rather than as a
 * different page appearing.
 */
@Composable
fun ExploreGrid(
    items: List<Media>,
    state: LazyGridState,
    loading: Boolean,
    exhausted: Boolean,
    error: String?,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    mediaCard: @Composable (Media, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues? = null,
    header: (@Composable () -> Unit)? = null,
) {
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val resolvedContentPadding = contentPadding ?: PaddingValues(
        start = PageLayoutDefaults.HorizontalPadding,
        end = PageLayoutDefaults.HorizontalPadding,
        top = 8.dp,
        bottom = 48.dp + PageLayoutDefaults.BottomClearance,
    )
    InfiniteScrollTrigger(state = state, itemCount = items.size, onLoadMore = onLoadMore)

    LazyVerticalGrid(
        columns = GridCells.Adaptive(PageLayoutDefaults.PosterGridMinWidth),
        state = state,
        modifier = modifier.fillMaxWidth(),
        contentPadding = resolvedContentPadding,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        header?.let { content ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "explore-grid-header") { content() }
        }

        itemsIndexed(
            items = items,
            key = { _, item -> item.id },
            contentType = { _, _ -> "media-grid-item" },
        ) { index, item ->
            StaggeredAppear(
                index = index,
                enabled = index < INITIAL_GRID_STAGGER_ITEMS,
                // On the item root, not on the card: placement animation is a property of
                // the lazy item, and nesting it a level down silently does nothing.
                modifier = if (reducedMotion) {
                    Modifier
                } else {
                    Modifier.animateItem(
                        placementSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    )
                },
            ) {
                mediaCard(item, Modifier.fillMaxWidth())
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }, key = "explore-grid-footer") {
            GridFooter(
                loading = loading,
                exhausted = exhausted,
                error = error,
                empty = items.isEmpty(),
                onRetry = onRetry,
            )
        }
    }
}

private const val INITIAL_GRID_STAGGER_ITEMS = 8

/**
 * Asks for the next page once the end of the loaded ones is in sight.
 *
 * The threshold is counted in items rather than pixels so it holds at any column count,
 * and the flow is distinct-until-changed so a scroll that jitters across the boundary does
 * not fire once per frame. The controller declines duplicate requests anyway; this keeps
 * it from having to.
 */
@Composable
private fun InfiniteScrollTrigger(
    state: LazyGridState,
    itemCount: Int,
    onLoadMore: () -> Unit,
) {
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    val nearEnd by remember(state, itemCount) {
        derivedStateOf {
            if (itemCount == 0) return@derivedStateOf false
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                ?: return@derivedStateOf false
            last >= itemCount - LOAD_AHEAD_ITEMS
        }
    }

    LaunchedEffect(state, itemCount) {
        snapshotFlow { nearEnd }
            .distinctUntilChanged()
            .filter { it }
            .collect { currentOnLoadMore() }
    }
}

@Composable
private fun GridFooter(
    loading: Boolean,
    exhausted: Boolean,
    error: String?,
    empty: Boolean,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            error != null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                RetryChip(onClick = onRetry)
            }

            // Poster-shaped rather than a spinner: the placeholders occupy the space the
            // next row is about to take, so nothing shifts when it arrives.
            // Sized by weight rather than a fixed 150.dp each: three fixed blocks plus their
            // spacing came to 478.dp and ran off both edges of a phone.
            loading -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(3) {
                    ShimmerBlock(
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(max = 150.dp)
                            .height(225.dp),
                    )
                }
            }

            exhausted && !empty -> Text(
                text = "That's everything",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun RetryChip(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (hovered) colors.tertiary else colors.surfaceContainer)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val content = if (hovered) colors.onTertiary else colors.onSurfaceVariant
        IconifyIcon(icon = "lucide:rotate-cw", modifier = Modifier.size(14.dp), tint = content)
        Text(
            text = "Try again",
            color = content,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** How many items before the end the next page is requested. */
private const val LOAD_AHEAD_ITEMS = 8
