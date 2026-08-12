package com.coveninja.cove.ui.components.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.model.Person

/**
 * The chrome both details sheets are built out of — the title sheet and the person
 * sheet. Kept in one place so the two cannot drift apart: they are meant to feel like
 * the same surface showing two different things.
 */

/** Below this width a sheet gives up its rounded corners and takes the whole screen. */
internal val FullHeightOverlayBreakpoint = 600.dp

@Composable
internal fun DetailsSectionTitle(
    title: String,
    iconName: String,
    count: Int? = null,
) {
    val colors = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(colors.tertiary.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            IconifyIcon(
                icon = iconName,
                modifier = Modifier.size(16.dp),
                tint = colors.tertiary,
            )
        }

        Text(
            text = title,
            modifier = Modifier.padding(start = 10.dp),
            color = colors.onSurface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        count?.let {
            Text(
                text = it.toString(),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceContainerHighest)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }

        HorizontalDivider(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp),
            color = colors.outlineVariant.copy(alpha = 0.38f),
        )
    }
}

@Composable
internal fun DetailsBadge(
    text: String,
    emphasized: Boolean = false,
) {
    val containerColor = if (emphasized) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }

    val contentColor = if (emphasized) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(containerColor)
            .padding(
                horizontal = 10.dp,
                vertical = 5.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

internal data class DetailFactData(
    val label: String,
    val value: String,
    val iconName: String,
    /**
     * When set, the value is drawn as one openable name per person rather than as flat
     * text. [value] stays as the plain-text equivalent.
     */
    val people: List<Person> = emptyList(),
)

@Composable
internal fun DetailFact(
    fact: DetailFactData,
    modifier: Modifier = Modifier,
    onPersonSelected: (Person) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.015f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "DetailFactScale",
    )
    val elevation by animateDpAsState(
        targetValue = if (isHovered) 5.dp else 0.dp,
        animationSpec = tween(140),
        label = "DetailFactElevation",
    )
    val containerColor by animateColorAsState(
        targetValue = if (isHovered) {
            colors.surfaceContainerHighest
        } else {
            colors.surfaceContainerHigh
        },
        animationSpec = tween(120),
        label = "DetailFactColor",
    )

    Surface(
        modifier = modifier
            .heightIn(min = 82.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .hoverable(interactionSource),
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        shadowElevation = elevation,
        border = BorderStroke(
            width = 1.dp,
            color = if (isHovered) {
                colors.tertiary.copy(alpha = 0.40f)
            } else {
                colors.outlineVariant.copy(alpha = 0.35f)
            },
        ),
    ) {
        Column(modifier = Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconifyIcon(
                    icon = fact.iconName,
                    modifier = Modifier.size(15.dp),
                    tint = if (isHovered) {
                        colors.tertiary
                    } else {
                        colors.onSurfaceVariant
                    },
                )
                Text(
                    text = fact.label,
                    modifier = Modifier.padding(start = 7.dp),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            if (fact.people.isEmpty()) {
                Text(
                    text = fact.value,
                    modifier = Modifier.padding(top = 7.dp),
                    color = colors.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            } else {
                Column(
                    modifier = Modifier.padding(top = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    fact.people.forEach { person ->
                        PersonNameLink(
                            person = person,
                            onClick = { onPersonSelected(person) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A name that opens a person. Tinted whether or not it is hovered: on a phone there is
 * no hover to reveal that it can be tapped, so the colour has to carry that on its own.
 */
@Composable
private fun PersonNameLink(
    person: Person,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val nameColor by animateColorAsState(
        targetValue = if (isHovered) colors.tertiary else colors.onSurface,
        animationSpec = tween(120),
        label = "PersonLinkColor",
    )

    Text(
        text = person.name,
        modifier = Modifier
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        color = nameColor,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        textDecoration = if (isHovered) TextDecoration.Underline else TextDecoration.None,
    )
}

@Composable
internal fun DetailsDismissDragHandle(
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var isDragging by remember { mutableStateOf(false) }
    val handleWidth by animateDpAsState(
        targetValue = when {
            isDragging -> 68.dp
            isHovered -> 56.dp
            else -> 44.dp
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "DetailsDismissHandleWidth",
    )
    val handleColor by animateColorAsState(
        targetValue = when {
            isDragging -> MaterialTheme.colorScheme.tertiary
            isHovered -> Color.White.copy(alpha = 0.78f)
            else -> Color.White.copy(alpha = 0.46f)
        },
        animationSpec = tween(120),
        label = "DetailsDismissHandleColor",
    )

    Box(
        modifier = modifier
            .height(64.dp)
            .hoverable(interactionSource)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                        isDragging = true
                        onDragStart()
                    },
                    onDragEnd = {
                        isDragging = false
                        onDragEnd()
                    },
                    onDragCancel = {
                        isDragging = false
                        onDragCancel()
                    },
                ) { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 11.dp)
                .width(handleWidth)
                .height(if (isDragging) 6.dp else 5.dp)
                .clip(CircleShape)
                .background(handleColor),
        )
    }
}

@Composable
internal fun OverlayCloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.88f
            isHovered -> 1.08f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "DetailsCloseScale",
    )
    val rotation by animateFloatAsState(
        targetValue = if (isHovered) 90f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "DetailsCloseRotation",
    )
    val containerColor by animateColorAsState(
        targetValue = if (isHovered) {
            colors.tertiary
        } else {
            Color.Black.copy(alpha = 0.48f)
        },
        animationSpec = tween(130),
        label = "DetailsCloseColor",
    )

    Surface(
        modifier = modifier
            .size(44.dp)
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
        shadowElevation = if (isHovered) 9.dp else 3.dp,
        border = BorderStroke(
            width = 1.dp,
            color = Color.White.copy(
                alpha = if (isHovered) 0.28f else 0.16f,
            ),
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            IconifyIcon(
                icon = "iconamoon:close",
                modifier = Modifier
                    .size(23.dp)
                    .graphicsLayer {
                        rotationZ = rotation
                    },
                tint = if (isHovered) colors.onTertiary else Color.White,
            )
        }
    }
}

internal fun formatRuntime(minutes: Int): String {
    val safeMinutes = minutes.coerceAtLeast(0)
    val hours = safeMinutes / 60
    val remainingMinutes = safeMinutes % 60

    return when {
        hours == 0 -> "${remainingMinutes}m"
        remainingMinutes == 0 -> "${hours}h"
        else -> "${hours}h ${remainingMinutes}m"
    }
}
