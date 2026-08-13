package com.coveninja.cove.ui.pages.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.components.common.CoveTooltip
import com.coveninja.cove.ui.components.common.TooltipSide
import com.coveninja.cove.ui.components.menu.CMenuItem
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.state.LocalMotionPolicy
import com.coveninja.cove.ui.platform.hasPointerHover

/**
 * Toolbar and list primitives shared by every page.
 *
 * These grew inside My List and now serve Explore too. They live here rather than there so
 * neither page owns the other's controls — a segmented switch and a round icon button are
 * not library concepts.
 */

/**
 * A two-or-more-way switch with an indicator that slides between segments.
 *
 * Segments are equal width by construction — the indicator is positioned by index alone,
 * which is what keeps the animation a cheap offset rather than a per-frame measure.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: (T) -> String? = { null },
    showLabels: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val selectedIndex = options.indexOf(selected).coerceAtLeast(0)

    BoxWithConstraints(
        modifier = modifier
            .height(if (hasPointerHover) 36.dp else 48.dp)
            .clip(CircleShape)
            .background(colors.surfaceContainer),
    ) {
        val segmentWidth = maxWidth / options.size
        val indicatorOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = spring(
                dampingRatio = 0.78f,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "SegmentedIndicator",
        )

        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .padding(3.dp)
                .clip(CircleShape)
                .background(colors.onSurface.copy(alpha = 0.12f)),
        )

        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEach { option ->
                val isSelected = option == selected
                val interaction = remember { MutableInteractionSource() }

                // The label already exists whether or not it is drawn, so a labelless switch
                // can say what its icons mean on hover instead of leaving them to be guessed.
                // Where the labels *are* drawn a tooltip would only repeat what is on screen.
                OptionalTooltip(label = label(option), enabled = !showLabels) {
                    Row(
                        modifier = Modifier
                            .width(segmentWidth)
                            .fillMaxHeight()
                            .semantics {
                                contentDescription = label(option)
                                this.selected = isSelected
                            }
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                                onClick = { onSelect(option) },
                            ),
                        horizontalArrangement = Arrangement.spacedBy(
                            6.dp,
                            Alignment.CenterHorizontally,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val contentColor =
                            if (isSelected) colors.onSurface else colors.onSurfaceVariant
                        icon(option)?.let { name ->
                            IconifyIcon(
                                icon = name,
                                modifier = Modifier.size(15.dp),
                                tint = contentColor,
                            )
                        }
                        if (showLabels) {
                            Text(
                                text = label(option),
                                color = contentColor,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Medium
                                },
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * [CoveTooltip] when there is something worth saying, and nothing at all when there is not.
 *
 * Wrapping unconditionally and passing an empty label would still put a `TooltipBox` in the
 * tree, which shows an empty black bubble on a long press.
 */
@Composable
private fun OptionalTooltip(
    label: String,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (enabled) CoveTooltip(label = label, content = content) else content()
}

/**
 * A pill that opens a menu of options and shows the current one.
 *
 * The current option is repeated inside the menu rather than merely ticked, because a menu
 * that only ticks makes the viewer find the tick to answer "what is it set to now?" — the
 * question they opened the menu with.
 */
@Composable
fun <T> MenuPicker(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    iconName: String = "lucide:arrow-up-down",
    menuWidth: Dp = 230.dp,
) {
    var open by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .height(if (hasPointerHover) 36.dp else 48.dp)
                .clip(CircleShape)
                .background(
                    if (hovered) colors.onSurface.copy(alpha = 0.10f) else colors.surfaceContainer,
                )
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null) { open = true }
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconifyIcon(
                icon = iconName,
                modifier = Modifier.size(15.dp),
                tint = colors.onSurfaceVariant,
            )
            Text(
                text = label(selected),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            IconifyIcon(
                icon = "lucide:chevron-down",
                modifier = Modifier.size(14.dp),
                tint = colors.onSurfaceVariant,
            )
        }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.width(menuWidth),
            shape = RoundedCornerShape(16.dp),
            containerColor = colors.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 18.dp,
        ) {
            options.forEach { option ->
                val current = option == selected
                CMenuItem(
                    text = if (current) "${label(option)}  •  Current" else label(option),
                    iconName = if (current) "lucide:check" else iconName,
                    accent = current,
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/**
 * A round icon button, captioned by [description] on hover.
 *
 * The caption is not optional, and that is the point: every one of these is an icon with no
 * label beside it, and several — a crossed-out eye, a funnel with a cross through it — are
 * only obvious once you already know what they do. [description] was always there for screen
 * readers; showing it to everyone costs nothing and answers the same question.
 */
@Composable
fun ToolbarIconButton(
    iconName: String,
    description: String,
    onClick: () -> Unit,
    active: Boolean = false,
    modifier: Modifier = Modifier,
    /** Rotation in degrees, for buttons whose icon spins as feedback. */
    rotation: Float = 0f,
    /** Which side the caption sits on; see [CoveTooltip]. */
    tooltipSide: TooltipSide = TooltipSide.Below,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    CoveTooltip(label = description, side = tooltipSide) {
        Box(
            modifier = modifier
                .size(if (hasPointerHover) 36.dp else 48.dp)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                // IconifyIcon draws with a null contentDescription, so the label has to live
                // on the button itself for a screen reader to find anything here.
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            active -> colors.tertiary.copy(alpha = 0.18f)
                            hovered -> colors.onSurface.copy(alpha = 0.10f)
                            else -> colors.surfaceContainer
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                IconifyIcon(
                    icon = iconName,
                    modifier = Modifier
                        .size(17.dp)
                        .graphicsLayer { rotationZ = rotation },
                    tint = if (active) colors.tertiary else colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ScrollToTopButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(160)) + scaleIn(
            initialScale = 0.7f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        ),
        exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.7f),
    ) {
        ToolbarIconButton(
            iconName = "lucide:arrow-up",
            description = "Scroll to top",
            onClick = onClick,
            // This one floats at the bottom of the window, where a caption below it would be
            // placed off the edge of the screen.
            tooltipSide = TooltipSide.Above,
        )
    }
}

/**
 * Fades and lifts an item in on first composition, offset by its position so a screenful
 * arrives as a ripple rather than all at once. The offset is capped: past the first row or
 * two the delay stops reading as intent and starts reading as lag.
 */
@Composable
fun StaggeredAppear(
    index: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (!enabled || LocalMotionPolicy.current.reducedMotion) {
        Box(modifier = modifier) { content() }
        return
    }

    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 260,
                delayMillis = (index.coerceIn(0, MAX_STAGGER_STEPS) * STAGGER_STEP_MILLIS),
                easing = FastOutSlowInEasing,
            ),
        )
    }

    // The modifier lands on this Box rather than on the caller's content because this Box
    // is the item's root inside a lazy layout, and `Modifier.animateItem` only animates
    // placement when it sits there.
    Box(
        modifier = modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 16.dp.toPx()
        },
    ) {
        content()
    }
}

private const val STAGGER_STEP_MILLIS = 24
private const val MAX_STAGGER_STEPS = 9
