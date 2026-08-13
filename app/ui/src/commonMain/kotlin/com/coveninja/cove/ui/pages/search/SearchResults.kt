package com.coveninja.cove.ui.pages.search

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.components.common.CoveAsyncImage
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.tmdbImageSize
import com.coveninja.cove.ui.pages.common.PageLayoutDefaults
import com.coveninja.cove.ui.pages.common.StaggeredAppear
import com.coveninja.cove.ui.pages.common.ToolbarIconButton

/**
 * The two ways the rest of the results are drawn.
 *
 * They answer different questions, which is why the toggle exists at all. **Grid** is for
 * recognising something you already know the look of — a poster does that in a glance and a
 * row of text cannot. **List** is for weighing up titles you have never heard of, where the
 * year, the rating and a sentence of plot are the whole decision and a poster tells you
 * nothing.
 *
 * Both animate item placement, so narrowing by genre reads as the results rearranging
 * themselves rather than as a different page arriving — the treatment `ExploreGrid` uses.
 */
@Composable
fun SearchResultGrid(
    items: List<Media>,
    state: LazyGridState,
    mediaCard: @Composable (Media, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        state = state,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = PageLayoutDefaults.HorizontalPadding,
            end = PageLayoutDefaults.HorizontalPadding,
            top = 4.dp,
            bottom = 48.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        header?.let { content ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "search-grid-header") { content() }
        }

        itemsIndexed(
            items = items,
            key = { _, item -> item.id },
            contentType = { _, _ -> "search-grid-item" },
        ) { index, item ->
            StaggeredAppear(
                index = index,
                // On the item root rather than on the card: placement animation is a property
                // of the lazy item, and nesting it a level down silently does nothing.
                modifier = Modifier.animateItem(
                    placementSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                ),
            ) {
                mediaCard(item, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun SearchResultList(
    items: List<Media>,
    state: LazyListState,
    query: String,
    inList: (Media) -> Boolean,
    onOpen: (Media) -> Unit,
    onToggleList: (Media) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = PageLayoutDefaults.HorizontalPadding,
            end = PageLayoutDefaults.HorizontalPadding,
            top = 4.dp,
            bottom = 48.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        header?.let { content ->
            item(key = "search-list-header") { content() }
        }

        itemsIndexed(
            items = items,
            key = { _, item -> item.id },
            contentType = { _, _ -> "search-list-item" },
        ) { index, item ->
            StaggeredAppear(
                index = index,
                modifier = Modifier.animateItem(
                    placementSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                ),
            ) {
                SearchResultRow(
                    media = item,
                    query = query,
                    inList = inList(item),
                    onOpen = { onOpen(item) },
                    onToggleList = { onToggleList(item) },
                )
            }
        }
    }
}

/**
 * One title as a row: the poster small, the facts large.
 *
 * The list-membership button is always drawn rather than revealed on hover — the row is a
 * comparison view, and having to hover each candidate to find out which ones are already
 * saved defeats the comparison. It is also the only affordance on a touch screen, where
 * hovering is not a thing that can happen.
 */
@Composable
private fun SearchResultRow(
    media: Media,
    query: String,
    inList: Boolean,
    onOpen: () -> Unit,
    onToggleList: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (hovered) colors.surfaceContainerHigh else colors.surfaceContainer.copy(alpha = 0.55f),
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surfaceContainerHigh),
        ) {
            media.posterUrl?.let { poster ->
                CoveAsyncImage(
                    model = tmdbImageSize(poster, "w185"),
                    contentDescription = "${media.displayTitle()} poster",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = highlightedTitle(media.displayTitle(), query, colors.tertiary),
                color = colors.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            media.metaLine().takeIf { it.isNotBlank() }?.let { meta ->
                Text(
                    text = meta,
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            media.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Text(
                    text = overview,
                    color = colors.onSurfaceVariant.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        ToolbarIconButton(
            iconName = if (inList) "lucide:bookmark-check" else "lucide:bookmark-plus",
            description = if (inList) "Remove from My List" else "Add to My List",
            active = inList,
            onClick = onToggleList,
        )
    }
}

/**
 * The title with the part that was searched for picked out.
 *
 * Says *why* this row is in the list, which matters most where it is least obvious — the
 * keyword half of the backend's search returns titles that do not contain the query at all,
 * and those simply come back unhighlighted rather than wrongly highlighted.
 */
internal fun highlightedTitle(title: String, query: String, accent: Color): AnnotatedString {
    val span = matchSpan(title, query) ?: return AnnotatedString(title)
    return buildAnnotatedString {
        append(title.substring(0, span.first))
        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) {
            append(title.substring(span.first, span.last + 1))
        }
        append(title.substring(span.last + 1))
    }
}
