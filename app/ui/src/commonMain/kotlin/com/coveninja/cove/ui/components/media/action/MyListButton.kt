package com.coveninja.cove.ui.components.media.action

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.components.media.MyListCategory
import com.coveninja.cove.ui.components.menu.CMenuItem
import com.ongshok.iconify.ui.IconifyIcon
@Composable
fun MyListButton(
    modifier: Modifier = Modifier,
    currentStatus: MyListCategory? = null,
    onStatusSelected: (MyListCategory) -> Unit,
) {
    var expanded by remember {
        mutableStateOf(false)
    }

    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val isActive = expanded || isHovered

    val buttonScale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.92f
            expanded -> 0.96f
            isHovered -> 1.05f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "MyListButtonScale",
    )
    val buttonRotation by animateFloatAsState(
        targetValue = when {
            expanded -> 3f
            isHovered -> -2f
            else -> 0f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "MyListButtonRotation",
    )
    val elevation by animateDpAsState(
        targetValue = when {
            expanded -> 8.dp
            isHovered -> 6.dp
            else -> 2.dp
        },
        animationSpec = tween(140),
        label = "MyListButtonElevation",
    )
    val buttonColor by animateColorAsState(
        targetValue = if (isActive) {
            colors.surfaceContainerHighest
        } else {
            colors.surfaceContainerHigh
        },
        animationSpec = tween(120),
        label = "MyListButtonColor",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            currentStatus != null -> colors.tertiary.copy(
                alpha = if (isActive) 0.72f else 0.42f,
            )
            isActive -> colors.onSurface.copy(alpha = 0.28f)
            else -> colors.outlineVariant.copy(alpha = 0.65f)
        },
        animationSpec = tween(120),
        label = "MyListButtonBorder",
    )
    val iconScale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.86f
            isActive -> 1.12f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "MyListButtonIconScale",
    )
    val iconColor by animateColorAsState(
        targetValue = when {
            currentStatus != null -> colors.tertiary
            isActive -> colors.onSurface
            else -> colors.onSurfaceVariant
        },
        animationSpec = tween(120),
        label = "MyListButtonIconColor",
    )
    val haloColor by animateColorAsState(
        targetValue = when {
            currentStatus != null && isActive ->
                colors.tertiary.copy(alpha = 0.14f)
            isActive -> colors.onSurface.copy(alpha = 0.06f)
            else -> Color.Transparent
        },
        animationSpec = tween(120),
        label = "MyListButtonHalo",
    )

    Box(modifier = modifier) {

        Surface(
            onClick = {
                expanded = true
            },
            modifier = Modifier
                .size(48.dp)
                .graphicsLayer {
                    scaleX = buttonScale
                    scaleY = buttonScale
                    rotationZ = buttonRotation
                }
                .hoverable(interactionSource),
            shape = RoundedCornerShape(14.dp),
            color = buttonColor,
            contentColor = colors.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = elevation,
            border = BorderStroke(
                width = 1.dp,
                color = borderColor,
            ),
            interactionSource = interactionSource,
        ) {
            Box(
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(haloColor),
                )
                IconifyIcon(
                    icon = if (currentStatus == null) {
                        "lucide:bookmark-plus"
                    } else {
                        "lucide:bookmark-check"
                    },
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        },
                    tint = iconColor,
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
            },
            modifier = Modifier.width(220.dp),
            shape = RoundedCornerShape(16.dp),
            containerColor = colors.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 18.dp,
            border = BorderStroke(
                width = 1.dp,
                color = colors.outlineVariant.copy(alpha = 0.65f),
            ),
        ) {

            // Same type of header treatment as MediaContextMenu
            Column(
                modifier = Modifier.padding(
                    horizontal = 14.dp,
                    vertical = 10.dp,
                ),
            ) {
                Text(
                    text = "Add to My List",
                    color = colors.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )

                Text(
                    text = currentStatus?.label ?: "Choose a category",
                    modifier = Modifier.padding(top = 3.dp),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(
                    horizontal = 8.dp,
                    vertical = 4.dp,
                ),
                color = colors.outlineVariant.copy(alpha = 0.55f),
            )

            MyListCategory.entries.forEach { status ->

                CMenuItem(
                    text = status.label,
                    iconName = status.icon,
                    accent = true,
                    accentColor = status.accentColor,
                    onClick = {
                        expanded = false
                        onStatusSelected(status)
                    },
                )
            }
        }
    }
}
