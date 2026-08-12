package com.coveninja.cove.ui.components.media.details

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.components.common.HorizontalLazyListScrollbar
import com.coveninja.cove.ui.components.media.card.MediaVideoCard
import com.coveninja.cove.ui.model.MediaVideo
import com.coveninja.cove.ui.model.VideoCategory
import com.coveninja.cove.ui.model.inCategory
import com.coveninja.cove.ui.model.videoCategories

/**
 * The extras row: trailers, teasers, featurettes and whatever else TMDB lists,
 * filtered by kind.
 *
 * A popular film carries thirty or more of these and they arrive interleaved, so
 * finding the trailer among the clips is otherwise a horizontal scroll. The chips
 * are built from what is actually present — see [videoCategories] — so a title
 * with nothing but trailers gets no filter row at all.
 */
@Composable
fun MediaVideosSection(
    videos: List<MediaVideo>,
    onVideoSelected: (MediaVideo) -> Unit,
    /**
     * The embedded player, when one of these videos is playing. A slot rather than
     * a dependency: playback belongs to the app shell, and the details package has
     * no business knowing what a player is.
     */
    player: @Composable ((Modifier) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (videos.isEmpty()) return

    val listState = rememberLazyListState()
    val categories = remember(videos) { videoCategories(videos) }
    var selected by remember(videos) { mutableStateOf<VideoCategory?>(null) }
    val shown = remember(videos, selected) { videos.inCategory(selected) }

    // Filtering leaves the row scrolled to wherever the longer list had it, which
    // on a short category is past the end and looks like an empty section.
    LaunchedEffect(selected) { listState.scrollToItem(0) }

    Column(modifier = modifier.fillMaxWidth()) {
        DetailsSectionTitle(
            title = "Videos",
            iconName = "lucide:film",
            count = shown.size,
        )

        // Above the chips and the row, so choosing another video swaps what is
        // playing without the list moving under the pointer.
        player?.invoke(Modifier.padding(top = 12.dp))

        // One category is not a choice, and "All" alongside it says nothing.
        if (categories.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VideoCategoryChip(
                    label = "All",
                    count = videos.size,
                    selected = selected == null,
                    onClick = { selected = null },
                )
                categories.forEach { category ->
                    VideoCategoryChip(
                        label = category.label,
                        count = videos.count { it.category == category },
                        selected = selected == category,
                        // Tapping the active chip clears it, so the row can be
                        // returned to All without reaching back for that chip.
                        onClick = {
                            selected = if (selected == category) null else category
                        },
                    )
                }
            }
        }

        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(224.dp)
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(
                items = shown,
                key = { video -> video.id },
            ) { video ->
                MediaVideoCard(
                    video = video,
                    onClick = { onVideoSelected(video) },
                )
            }
        }
        HorizontalLazyListScrollbar(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }
}

/** Sibling of GenreChip, with a selected state and a count. */
@Composable
private fun VideoCategoryChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        targetValue = if (isHovered && !selected) 1.055f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "VideoCategoryChipScale",
    )
    val containerColor by animateColorAsState(
        targetValue = when {
            selected -> colors.tertiary
            isHovered -> colors.tertiary.copy(alpha = 0.14f)
            else -> colors.surfaceContainerHighest
        },
        animationSpec = tween(120),
        label = "VideoCategoryChipColor",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            selected -> colors.onTertiary
            isHovered -> colors.tertiary
            else -> colors.onSurfaceVariant
        },
        animationSpec = tween(120),
        label = "VideoCategoryChipContent",
    )

    Surface(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        shape = CircleShape,
        color = containerColor,
        border = BorderStroke(
            width = 1.dp,
            color = when {
                selected -> colors.tertiary
                isHovered -> colors.tertiary.copy(alpha = 0.48f)
                else -> colors.outlineVariant.copy(alpha = 0.5f)
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                color = contentColor,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = count.toString(),
                color = contentColor.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
