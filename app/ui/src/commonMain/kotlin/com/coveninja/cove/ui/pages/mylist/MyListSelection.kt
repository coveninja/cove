package com.coveninja.cove.ui.pages.mylist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.model.LibraryEntry
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.components.menu.CMenuItem
import com.coveninja.cove.ui.icons.IconifyIcon
import kotlinx.coroutines.delay

/** Which titles are ticked, and whether ticking is even possible right now. */
@Stable
class MyListSelectionState {
    var active by mutableStateOf(false)
        private set

    private val selectedIds = mutableStateListOf<String>()

    val selected: List<String> get() = selectedIds
    val count: Int get() = selectedIds.size

    fun isSelected(id: String): Boolean = id in selectedIds

    fun toggle(id: String) {
        if (!selectedIds.remove(id)) selectedIds.add(id)
    }

    fun setSelectionMode(value: Boolean) {
        active = value
        if (!value) selectedIds.clear()
    }

    fun clear() = selectedIds.clear()

    /** Drops ids that are no longer in the list, so a removal cannot leave phantom picks. */
    fun retainAll(ids: Set<String>) {
        val stale = selectedIds.filterNot { it in ids }
        if (stale.isNotEmpty()) selectedIds.removeAll(stale)
    }
}

@Composable
fun rememberMyListSelection(): MyListSelectionState = remember { MyListSelectionState() }

/** What Undo needs to put things back: the entries exactly as they were before removal. */
data class MyListUndo(val entries: List<LibraryEntry>) {
    val label: String
        get() = if (entries.size == 1) {
            "Removed ${entries.first().title}"
        } else {
            "Removed ${entries.size} titles"
        }
}

/**
 * The checkbox drawn over a card while selection is active.
 *
 * It covers the whole card and swallows the click, which is what lets selection mode take
 * over without changing what a card's own click means everywhere else in the app.
 */
@Composable
fun SelectionOverlay(
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val accent = colors.tertiary

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) accent.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.35f),
            )
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) accent else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            ),
    ) {
        AnimatedVisibility(
            visible = true,
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            enter = fadeIn() + androidx.compose.animation.scaleIn(
                initialScale = 0.5f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            ),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (selected) accent else Color.Black.copy(alpha = 0.55f))
                    .border(1.5.dp, if (selected) accent else Color.White.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    IconifyIcon(
                        icon = "lucide:check",
                        modifier = Modifier.size(14.dp),
                        tint = colors.onTertiary,
                    )
                }
            }
        }
    }
}

@Composable
fun MyListSelectionBar(
    count: Int,
    onMoveTo: (MyListCategory) -> Unit,
    onRemove: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = colors.surfaceContainerHigh,
        shadowElevation = 20.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (count == 1) "1 selected" else "$count selected",
                color = colors.onSurface,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )

            Box {
                BarAction(
                    label = "Move to",
                    iconName = "lucide:bookmark-check",
                    enabled = count > 0,
                    onClick = { menuOpen = true },
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.width(220.dp),
                    shape = RoundedCornerShape(16.dp),
                    containerColor = colors.surfaceContainerHigh,
                    tonalElevation = 0.dp,
                    shadowElevation = 18.dp,
                ) {
                    MyListCategory.entries
                        .filter { it != MyListCategory.NotInterested }
                        .forEach { category ->
                            CMenuItem(
                                text = category.label,
                                iconName = category.icon,
                                accent = true,
                                accentColor = category.accentColor,
                                onClick = {
                                    menuOpen = false
                                    onMoveTo(category)
                                },
                            )
                        }
                }
            }

            BarAction(
                label = "Remove",
                iconName = "lucide:trash",
                enabled = count > 0,
                destructive = true,
                onClick = onRemove,
            )

            Spacer(Modifier.width(2.dp))

            BarAction(
                label = "Done",
                iconName = "lucide:x",
                enabled = true,
                onClick = onClear,
            )
        }
    }
}

/**
 * The undo affordance after a removal. It dismisses itself, because leaving a bar over the
 * grid until it is clicked would be worse than losing the chance to undo.
 */
@Composable
fun MyListUndoBar(
    undo: MyListUndo?,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(undo) {
        if (undo != null) {
            delay(UNDO_WINDOW_MILLIS)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = undo != null,
        modifier = modifier,
        enter = fadeIn(tween(160)) + slideInVertically(
            spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        ) { it },
        exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { it },
    ) {
        val colors = MaterialTheme.colorScheme
        Surface(
            shape = CircleShape,
            color = colors.surfaceContainerHigh,
            shadowElevation = 20.dp,
        ) {
            Row(
                modifier = Modifier.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // Held so the label does not blank out during the exit animation.
                    text = remember(undo) { undo?.label }.orEmpty(),
                    color = colors.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                )
                BarAction(
                    label = "Undo",
                    iconName = "lucide:undo-2",
                    enabled = true,
                    accent = true,
                    onClick = onUndo,
                )
            }
        }
    }
}

@Composable
private fun BarAction(
    label: String,
    iconName: String,
    enabled: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
    accent: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val contentColor = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.4f)
        destructive -> colors.error
        accent -> colors.tertiary
        else -> colors.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(
                when {
                    !enabled -> Color.Transparent
                    hovered && destructive -> colors.error.copy(alpha = 0.14f)
                    hovered && accent -> colors.tertiary.copy(alpha = 0.16f)
                    hovered -> colors.onSurface.copy(alpha = 0.10f)
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction, enabled = enabled)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconifyIcon(icon = iconName, modifier = Modifier.size(15.dp), tint = contentColor)
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

private const val UNDO_WINDOW_MILLIS = 7_000L
