package com.coveninja.cove.ui.pages.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.pages.common.ChoicePill
import com.coveninja.cove.ui.pages.common.ChoicePillRow
import com.coveninja.cove.ui.pages.common.MediaRail
import com.coveninja.cove.ui.pages.common.RailDefaults
import com.coveninja.cove.ui.platform.hasPointerHover

/**
 * What the page offers before anything has been typed.
 *
 * The old empty state's only content was a button that reopened the nav-bar overlay, which
 * made the search page a signpost pointing back the way you came. This is three answers to
 * "I do not have a title in mind" instead: what you searched for before, a theme to start
 * from, and what everyone else is watching.
 *
 * None of it costs a request. [popular] is the discover feed the app already holds in memory,
 * and the themes are a fixed list — so the richest state on the page is also the cheapest.
 */
@Composable
fun SearchIdle(
    recents: List<String>,
    popular: List<Media>,
    mediaCard: @Composable (Media, Modifier) -> Unit,
    onSearch: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearRecents: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        AnimatedVisibility(
            visible = recents.isNotEmpty(),
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(140)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeading(
                    icon = "iconamoon:history",
                    title = "Recent searches",
                    action = "Clear",
                    actionIcon = "lucide:trash",
                    onAction = onClearRecents,
                )
                ChoicePillRow(
                    modifier = Modifier.padding(horizontal = RailDefaults.HorizontalPadding),
                ) {
                    recents.forEach { entry ->
                        RecentChip(
                            label = entry,
                            onClick = { onSearch(entry) },
                            onRemove = { onRemoveRecent(entry) },
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeading(
                icon = "lucide:sparkles",
                title = "Start from a theme",
                subtitle = "Search by what a film is about rather than what it is called",
            )
            ChoicePillRow(
                modifier = Modifier.padding(horizontal = RailDefaults.HorizontalPadding),
            ) {
                SEARCH_MOODS.forEach { mood ->
                    ChoicePill(
                        label = mood.label,
                        selected = false,
                        iconName = mood.icon,
                        onClick = { onSearch(mood.query) },
                    )
                }
            }
        }

        if (popular.isNotEmpty()) {
            MediaRail(
                title = "Popular right now",
                subtitle = "In case nothing in particular comes to mind",
                icon = "lucide:flame",
                items = popular,
                key = { it.id },
                itemContent = { item, itemModifier -> mediaCard(item, itemModifier) },
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * A past search, with a way to forget it.
 *
 * The remove control appears on hover on a desktop and is pinned on a touch screen, where
 * there is no hover and a hidden control is an unreachable one — the rule the rails and the
 * cards already follow.
 */
@Composable
private fun RecentChip(
    label: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val showRemove = hovered || !hasPointerHover

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (hovered) colors.onSurface.copy(alpha = 0.10f) else colors.surfaceContainer,
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = 14.dp, end = if (showRemove) 4.dp else 14.dp, top = 7.dp, bottom = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconifyIcon(
            icon = "iconamoon:history",
            modifier = Modifier.size(15.dp),
            tint = colors.onSurfaceVariant,
        )
        Text(
            text = label,
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        AnimatedVisibility(
            visible = showRemove,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(100)),
        ) {
            val removeInteraction = remember { MutableInteractionSource() }
            val removeHovered by removeInteraction.collectIsHoveredAsState()
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (removeHovered) colors.onSurface.copy(alpha = 0.14f) else colors.surfaceContainer.copy(alpha = 0f),
                    )
                    .hoverable(removeInteraction)
                    .clickable(
                        interactionSource = removeInteraction,
                        indication = null,
                        onClick = onRemove,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                IconifyIcon(
                    icon = "lucide:x",
                    modifier = Modifier.size(12.dp),
                    tint = if (removeHovered) colors.onSurface else colors.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A section label, sized well below a rail heading.
 *
 * The rails on this page are the content; these head the chips around them, and giving both
 * the same weight would make the page read as four competing offers rather than one list of
 * starting points under a search field.
 */
@Composable
private fun SectionHeading(
    icon: String,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: String? = null,
    actionIcon: String? = null,
    onAction: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = RailDefaults.HorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconifyIcon(icon = icon, modifier = Modifier.size(15.dp), tint = colors.tertiary)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            subtitle?.let {
                Text(
                    text = it,
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (action != null) {
            SectionAction(label = action, iconName = actionIcon, onClick = onAction)
        }
    }
}

@Composable
private fun SectionAction(label: String, iconName: String?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val content = if (hovered) colors.tertiary else colors.onSurfaceVariant

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(
                if (hovered) colors.onSurface.copy(alpha = 0.10f) else colors.surfaceContainer.copy(alpha = 0f),
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        iconName?.let {
            IconifyIcon(icon = it, modifier = Modifier.size(13.dp), tint = content)
        }
        Text(
            text = label,
            color = content,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
