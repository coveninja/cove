package com.coveninja.cove.ui.pages.mylist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.components.common.CoveAsyncImage
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.tmdbImageSize
import com.coveninja.cove.ui.pages.common.StaggeredAppear
import com.coveninja.cove.ui.pages.common.ToolbarIconButton
import com.coveninja.cove.ui.platform.hasPointerHover
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt

/** What a detail row can do, gathered so the signatures stay readable. */
class MyListRowActions(
    val onOpen: (MyListRow) -> Unit,
    val onResume: (MyListRow) -> Unit,
    val onRemove: (MyListRow) -> Unit,
)

@Composable
fun MyListGrid(
    rows: List<MyListRow>,
    selection: MyListSelectionState,
    mediaCard: @Composable (Media, Modifier) -> Unit,
    contentPadding: PaddingValues,
    state: LazyGridState,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        header?.let { content ->
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                content()
            }
        }

        itemsIndexed(
            items = rows,
            key = { _, row -> row.id },
            contentType = { _, _ -> "my-list-grid-item" },
        ) { index, row ->
            // animateItem is what makes sorting and filtering reflow instead of snapping.
            Box(modifier = Modifier.animateItem()) {
                StaggeredAppear(index = index) {
                    Box {
                        mediaCard(row.media, Modifier.fillMaxWidth())
                        SelectionLayer(
                            active = selection.active,
                            selected = selection.isSelected(row.id),
                            onToggle = { selection.toggle(row.id) },
                            modifier = Modifier.matchCardBounds(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MyListRows(
    rows: List<MyListRow>,
    selection: MyListSelectionState,
    actions: MyListRowActions,
    today: LocalDate,
    contentPadding: PaddingValues,
    state: LazyListState,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        header?.let { content ->
            item(key = "header") { content() }
        }

        items(
            items = rows,
            key = { it.id },
            contentType = { "my-list-row" },
        ) { row ->
            Box(modifier = Modifier.animateItem()) {
                MyListDetailRow(
                    row = row,
                    today = today,
                    selectionActive = selection.active,
                    selected = selection.isSelected(row.id),
                    onToggleSelected = { selection.toggle(row.id) },
                    actions = actions,
                )
            }
        }
    }
}

/**
 * A wide row: everything the grid hides behind a hover, laid out to be read.
 */
@Composable
private fun MyListDetailRow(
    row: MyListRow,
    today: LocalDate,
    selectionActive: Boolean,
    selected: Boolean,
    onToggleSelected: () -> Unit,
    actions: MyListRowActions,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(14.dp)

    val elevationAlpha by animateFloatAsState(
        targetValue = if (hovered) 0.10f else 0.04f,
        animationSpec = tween(160),
        label = "MyListRowSurface",
    )

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.onSurface.copy(alpha = elevationAlpha))
                .hoverable(interaction)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = {
                        if (selectionActive) onToggleSelected() else actions.onOpen(row)
                    },
                )
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(58.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceContainer),
            ) {
                CoveAsyncImage(
                    model = tmdbImageSize(row.media.posterUrl, "w185"),
                    contentDescription = "${row.displayTitle} poster",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                row.watchFraction?.let { fraction ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.Black.copy(alpha = 0.45f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    transformOrigin = TransformOrigin(0f, 0.5f)
                                    scaleX = fraction.coerceIn(0f, 1f)
                                }
                                .background(row.category.accentColor),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = row.displayTitle,
                    color = colors.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CategoryChip(category = row.category)
                    row.media.type?.label?.let { RowMeta(it) }
                    row.episodeMarker?.let { RowMeta(it) }
                    row.watchFraction?.let { RowMeta("${(it * 100).roundToInt()}%") }
                    row.entry.rating?.let { RowMeta("★ ${it.roundToInt()}") }
                    shortDateLabel(row.entry.addedAt, today)?.let { RowMeta("added $it") }
                    if (row.hasNewEpisodes) {
                        RowMeta("new episodes", accent = true)
                    }
                }
            }

            // Actions stay mounted but invisible until hover, so the row never resizes
            // under the pointer as they appear. Where nothing can hover they are always
            // shown — otherwise a touch user could never resume or remove from this row.
            AnimatedVisibility(
                visible = (hovered || !hasPointerHover) && !selectionActive,
                enter = fadeIn(tween(140)) + slideInHorizontally(tween(160)) { it / 3 },
                exit = fadeOut(tween(120)) + slideOutHorizontally(tween(140)) { it / 3 },
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolbarIconButton(
                        iconName = "lucide:play",
                        description = "Resume ${row.displayTitle}",
                        onClick = { actions.onResume(row) },
                    )
                    ToolbarIconButton(
                        iconName = "lucide:trash",
                        description = "Remove ${row.displayTitle}",
                        onClick = { actions.onRemove(row) },
                    )
                }
            }

            Spacer(Modifier.width(2.dp))
        }

        SelectionLayer(
            active = selectionActive,
            selected = selected,
            onToggle = onToggleSelected,
            modifier = Modifier.matchParentSize(),
        )
    }
}

@Composable
private fun CategoryChip(category: MyListCategory) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(category.accentColor.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconifyIcon(
            icon = category.icon,
            modifier = Modifier.size(12.dp),
            tint = category.accentColor,
        )
        Text(
            text = category.label,
            color = category.accentColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun RowMeta(text: String, accent: Boolean = false) {
    Text(
        text = text,
        color = if (accent) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
    )
}

@Composable
private fun SelectionLayer(
    active: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = active,
        modifier = modifier,
        enter = fadeIn(tween(140)),
        exit = fadeOut(tween(120)),
    ) {
        SelectionOverlay(
            selected = selected,
            onToggle = onToggle,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Cards are 2:3; the overlay has to be too, or it would cover the gap below them. */
private fun Modifier.matchCardBounds(): Modifier = fillMaxWidth().aspectRatio(2f / 3f)

