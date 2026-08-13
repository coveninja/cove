package com.coveninja.cove.ui.pages.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.coveninja.cove.shared.model.CalendarItem
import com.coveninja.cove.shared.model.MediaType
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.pages.mylist.calendar.calendarImageUrl
import com.coveninja.cove.ui.pages.mylist.calendar.countdownLabel
import com.coveninja.cove.ui.pages.mylist.calendar.dayLabel
import com.coveninja.cove.ui.pages.mylist.calendar.episodeMarker
import com.coveninja.cove.ui.pages.mylist.calendar.parsedDate
import kotlinx.datetime.LocalDate

/**
 * One tile on the "coming this week" rail.
 *
 * Compact on purpose. Nothing here is watchable yet, so it earns far less room than the rails
 * above it — the useful content is a name and a countdown, and a full card for something you
 * cannot press would be the page shouting about a title it cannot deliver.
 */
@Composable
fun UpcomingTile(
    item: CalendarItem,
    today: LocalDate,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = MaterialTheme.colorScheme

    val container by animateColorAsState(
        targetValue = if (hovered) {
            colors.onSurface.copy(alpha = 0.10f)
        } else {
            colors.surfaceContainer
        },
        animationSpec = tween(140),
        label = "UpcomingTileColor",
    )

    val date = item.parsedDate()

    Row(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(44.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            val artwork = calendarImageUrl(item, "w185")
            if (artwork == null) {
                IconifyIcon(
                    icon = if (item.type == MediaType.Movie) "lucide:film" else "lucide:tv",
                    modifier = Modifier.size(15.dp),
                    tint = colors.onSurfaceVariant.copy(alpha = 0.6f),
                )
            } else {
                CoveAsyncImage(
                    model = artwork,
                    contentDescription = "${item.title} poster",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = item.title,
                color = colors.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // The wire name would render "Tv" here, so an undated-format fallback says
                // what the item actually is rather than what the enum is called.
                text = item.episodeMarker()
                    ?: item.episodeName.takeIf { it.isNotBlank() }
                    ?: if (item.type == MediaType.Movie) "Film" else "New episode",
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = date?.let { "${dayLabel(it, today)}  ·  ${countdownLabel(it, today)}" }
                    .orEmpty(),
                color = colors.tertiary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Tile metrics, kept next to the tile so the rail cannot size it wrongly. */
object UpcomingTileDefaults {
    val Width = 250.dp
    val Height = 84.dp
}
