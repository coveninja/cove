package com.coveninja.cove.ui.pages.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.components.common.CoveAsyncImage
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.model.tmdbImageSize
import com.coveninja.cove.ui.pages.common.ToolbarIconButton
import com.coveninja.cove.ui.pages.search.displayTitle
import com.coveninja.cove.ui.pages.search.metaLine

/**
 * One title in Explore's list layout: the whole title, what it is, and enough of its story to
 * decide whether to open it.
 *
 * The poster grid is for recognising things; this is for weighing up things the viewer does
 * not know yet, which is most of what Explore shows. A poster cuts a long title off after two
 * lines on hover and shows no description at all, so this layout exists to answer "what is
 * this?" without opening every card.
 *
 * The list button is always drawn rather than revealed on hover, as in Search: it is the only
 * affordance on a touch screen, and having to hover a row to see whether it is saved defeats a
 * view made for comparing.
 */
@Composable
internal fun ExploreListRow(
    media: Media,
    inList: Boolean,
    onOpen: () -> Unit,
    onToggleList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val title = media.displayTitle()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (hovered) colors.surfaceContainerHigh else colors.surfaceContainer.copy(alpha = 0.55f),
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(84.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surfaceContainerHigh),
        ) {
            media.posterUrl?.let { poster ->
                CoveAsyncImage(
                    model = tmdbImageSize(poster, "w185"),
                    contentDescription = "$title poster",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = title,
                color = colors.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            media.metaLine(maxGenres = 3).takeIf { it.isNotBlank() }?.let { meta ->
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
                    color = colors.onSurfaceVariant.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
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
