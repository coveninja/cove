package com.coveninja.cove.ui.tv.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.components.navigation.NavDestination
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.coveninja.cove.ui.icons.CoveLogo
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.state.LocalMotionPolicy
import com.coveninja.cove.ui.tv.TvTheme
import com.coveninja.cove.ui.tv.focus.tvFocusGroup
import com.coveninja.cove.ui.tv.focus.tvFocusTarget

/**
 * The left rail: Cove's navigation with no pointer to hover it.
 *
 * It rests as a column of icons and widens to show labels the moment focus enters it — the one
 * interaction a rail can offer a remote, since there is no hover and "reveal on approach" has to
 * mean "reveal on focus". It widens *over* the page rather than pushing it: a rail that reflowed
 * the rows every time it was entered would move the very cards the viewer was aiming at. The
 * caller keeps the page still by leaving the collapsed width as a gutter.
 *
 * Selection is deliberate. Focus alone moves the highlight; pressing centre switches the page.
 * Walking past My List on the way to Explore should not load My List — which is exactly why
 * focus and selection are drawn as two different things here.
 */
@Composable
internal fun TvSideRail(
    selected: NavDestination,
    onSelect: (NavDestination) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    selectedFocusRequester: FocusRequester? = null,
) {
    val dimens = TvTheme.dimens
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val destinations = NavDestination.entries

    // Remembered rather than derived from `selected`: the highlight follows the viewer's
    // attention, and attention moves through the rail without committing to anything.
    var focusedIndex by remember { mutableStateOf(destinations.indexOf(selected)) }

    val width by animateDpAsState(
        targetValue = if (expanded) dimens.railExpandedWidth else dimens.railCollapsedWidth,
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
        },
        label = "TvRailWidth",
    )
    // The highlight travels between rows instead of each row lighting up on its own. One thing
    // moving reads as the viewer moving through a list; five things blinking reads as five
    // separate events, and at a distance the difference is the whole feel of the control.
    val highlightOffset by animateDpAsState(
        targetValue = (RowHeight + RowSpacing) * focusedIndex,
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMedium)
        },
        label = "TvRailHighlight",
    )
    // The pill is measured, not guessed. It was a fixed width before and overhung the rail on
    // to the page — a slab of accent with nothing in it — because the rows are only as wide as
    // their longest label and the rail is only as wide as the rows.
    var rowsWidth by remember { mutableStateOf(0) }
    val measuredWidth = with(LocalDensity.current) { rowsWidth.toDp() }
    val highlightWidth by animateDpAsState(
        targetValue = if (measuredWidth > RowHeight) measuredWidth else RowHeight,
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
        },
        label = "TvRailHighlightWidth",
    )
    val highlightAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(160),
        label = "TvRailHighlightAlpha",
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(width)
            // Labels appear the instant the rail is entered while its width springs open, so
            // for a few frames the content is wider than the container. Clipping turns that
            // from an overhang into a reveal.
            .clipToBounds()
            // The scrim only earns its place while the rail is open over content; at rest the
            // icons sit on the page background and a permanent panel edge would be a seam.
            .background(
                Brush.horizontalGradient(
                    0f to CoveColors.Neutral.Background.copy(alpha = if (expanded) 0.98f else 0f),
                    0.7f to CoveColors.Neutral.Background.copy(alpha = if (expanded) 0.88f else 0f),
                    1f to Color.Transparent,
                ),
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            modifier = Modifier.padding(start = RailInset, end = 10.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            TvRailMark(expanded = expanded)
            Spacer(modifier = Modifier.height(18.dp))

            Box {
                // Behind the rows, so the icon and label sit on top of it as it arrives.
                Box(
                    modifier = Modifier
                        .offset(y = highlightOffset)
                        .height(RowHeight)
                        .width(highlightWidth)
                        .graphicsLayer { alpha = highlightAlpha }
                        .background(CoveColors.Brand.Accent, RoundedCornerShape(16.dp)),
                )

                Column(
                    modifier = Modifier
                        // Every row the width of the widest, so the pill is one size that
                        // travels rather than one that resizes under each destination.
                        .width(IntrinsicSize.Max)
                        .onSizeChanged { rowsWidth = it.width }
                        .tvFocusGroup()
                        // hasFocus, not isFocused: the rail itself is never the focused node —
                        // one of its buttons is — so the narrower flag reports false throughout
                        // and the rail would never open.
                        .onFocusChanged { state -> onExpandedChange(state.hasFocus) },
                    verticalArrangement = Arrangement.spacedBy(RowSpacing),
                ) {
                    destinations.forEachIndexed { index, destination ->
                        TvRailButton(
                            destination = destination,
                            index = index,
                            selected = destination == selected,
                            focusedIndex = focusedIndex,
                            railFocused = expanded,
                            expanded = expanded,
                            onSelect = { onSelect(destination) },
                            onFocused = { focusedIndex = index },
                            modifier = if (
                                destination == selected && selectedFocusRequester != null
                            ) {
                                Modifier.focusRequester(selectedFocusRequester)
                            } else {
                                Modifier
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Cove's mark, at the head of the rail.
 *
 * Small, and the only thing here that is not a control. A rail that is nothing but five icons
 * reads as a toolbar; one thing that says whose app this is turns it into a place.
 */
@Composable
private fun TvRailMark(expanded: Boolean) {
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val labelAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = 180, delayMillis = 60),
        label = "TvRailMarkLabel",
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(RowHeight),
            contentAlignment = Alignment.Center,
        ) {
            CoveLogo(modifier = Modifier.size(30.dp))
        }
        if (expanded) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Cove",
                style = MaterialTheme.typography.titleMedium,
                color = CoveColors.Neutral.Text,
                modifier = Modifier.graphicsLayer { alpha = labelAlpha },
            )
        }
    }
}

@Composable
private fun TvRailButton(
    destination: NavDestination,
    index: Int,
    selected: Boolean,
    focusedIndex: Int,
    railFocused: Boolean,
    expanded: Boolean,
    onSelect: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val onHighlight = railFocused && focusedIndex == index

    val content by animateColorAsState(
        targetValue = when {
            onHighlight -> CoveColors.Brand.OnAccent
            selected -> CoveColors.Brand.Accent
            else -> CoveColors.Neutral.Muted
        },
        animationSpec = if (reducedMotion) snap() else tween(160),
        label = "TvRailButtonContent",
    )
    // A small pop as the highlight arrives. It lands slightly after the highlight settles,
    // which is what makes the two read as one movement rather than a colour change.
    val iconScale by animateFloatAsState(
        targetValue = if (onHighlight) 1.16f else 1f,
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)
        },
        label = "TvRailIconScale",
    )
    // Labels arrive one after another rather than all at once. The delay is per position, so
    // the rail unfolds downwards and the eye follows it to where focus already is.
    val labelAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            tween(durationMillis = 170, delayMillis = 70 + index * 28)
        },
        label = "TvRailLabelAlpha",
    )

    LaunchedEffectOnFocus(focused, onFocused)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(RowHeight)
            .tvFocusTarget(
                shape = RoundedCornerShape(16.dp),
                onClick = onSelect,
                // The travelling highlight is the focus signal; a ring and a scale on top of it
                // would be three answers to one question.
                scale = 1f,
                ringColor = Color.Transparent,
                interactionSource = interactionSource,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(RowHeight),
            contentAlignment = Alignment.Center,
        ) {
            IconifyIcon(
                icon = destination.iconName,
                tint = content,
                modifier = Modifier
                    .size(26.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
            )
        }
        if (expanded) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = destination.label,
                style = MaterialTheme.typography.labelLarge,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Trailing room inside the row, so the pill carries on past the word rather
                // than stopping hard against its last letter.
                modifier = Modifier
                    .padding(end = 26.dp)
                    .graphicsLayer { alpha = labelAlpha },
            )
        }
    }
}

/** Reports focus arriving without the caller having to hold a second piece of state. */
@Composable
private fun LaunchedEffectOnFocus(focused: Boolean, onFocused: () -> Unit) {
    LaunchedEffect(focused) { if (focused) onFocused() }
}

private val RowHeight = 56.dp
private val RowSpacing = 6.dp
private val RailInset = 14.dp
