package com.coveninja.cove.ui.components.menu

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.icons.IconifyIcon

@Composable
fun CMenuItem(
    text: String,
    iconName: String,
    accent: Boolean = false,
    accentColor: Color = MaterialTheme.colorScheme.tertiary,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Transparent,
    hoverContainerColor: Color = if (accent) {
        accentColor.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    },
    pressedContainerColor: Color = if (accent) {
        accentColor.copy(alpha = 0.24f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    },
    /**
     * Short words qualifying the entry, drawn as pills after it — "Forced", "SDH".
     *
     * Beside the label rather than folded into it because they answer a different question:
     * the label says which track this is, and these say what it is for. Run together as one
     * string they read as part of the name.
     */
    badges: List<String> = emptyList(),
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }

    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val defaultContentColor = if (accent) {
        accentColor
    } else {
        colors.onSurface
    }

    val contentColor by animateColorAsState(
        targetValue = when {
            isPressed && accent -> accentColor
            isHovered && accent -> accentColor
            isPressed -> colors.onSurface
            isHovered -> colors.onSurface
            else -> defaultContentColor
        },
        animationSpec = tween(durationMillis = 120),
        label = "ContextMenuItemContentColor",
    )

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isPressed -> pressedContainerColor
            isHovered -> hoverContainerColor
            else -> containerColor
        },
        animationSpec = tween(durationMillis = 120),
        label = "ContextMenuItemBackgroundColor",
    )

    val shape = RoundedCornerShape(10.dp)

    DropdownMenuItem(
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                badges.forEach { badge ->
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.75f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(contentColor.copy(alpha = 0.12f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
        },
        leadingIcon = {
            IconifyIcon(
                icon = iconName,
                modifier = Modifier.size(20.dp),
                tint = contentColor,
            )
        },
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .padding(horizontal = 4.dp)
            .clip(shape)
            .background(backgroundColor),
        colors = MenuDefaults.itemColors(
            textColor = contentColor,
            leadingIconColor = contentColor,
            disabledTextColor = colors.onSurface.copy(alpha = 0.38f),
            disabledLeadingIconColor =
                colors.onSurface.copy(alpha = 0.38f),
        ),
        contentPadding = PaddingValues(
            horizontal = 8.dp,
            vertical = 2.dp,
        ),
    )
}
