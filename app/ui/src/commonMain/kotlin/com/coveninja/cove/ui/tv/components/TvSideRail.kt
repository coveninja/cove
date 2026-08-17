package com.coveninja.cove.ui.tv.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.components.navigation.NavDestination
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.state.LocalMotionPolicy
import com.coveninja.cove.ui.tv.TvTheme
import com.coveninja.cove.ui.tv.focus.TvFocusDefaults
import com.coveninja.cove.ui.tv.focus.tvFocusGroup
import com.coveninja.cove.ui.tv.focus.tvFocusTarget

/**
 * The left rail: Cove's navigation with no pointer to hover it.
 *
 * It rests as a column of icons and widens to show labels the moment focus enters it, which is
 * the one interaction a rail can offer a remote — there is no hover, so "reveal on approach"
 * has to mean "reveal on focus". It widens *over* the page rather than pushing it: a rail that
 * reflowed the rows every time it was entered would move the very cards the viewer was aiming
 * at. The caller is what keeps the page still, by leaving the collapsed width as a gutter.
 *
 * Selection is deliberate. Focus alone moves the highlight; pressing centre switches the page.
 * Walking past My List on the way to Explore should not load My List.
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
    val width by animateDpAsState(
        targetValue = if (expanded) dimens.railExpandedWidth else dimens.railCollapsedWidth,
        animationSpec = if (reducedMotion) snap() else tween(180),
        label = "TvRailWidth",
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(width)
            // The scrim only earns its place while the rail is open over content; at rest the
            // icons sit on the page background and a permanent panel edge would be a seam.
            .background(
                Brush.horizontalGradient(
                    listOf(
                        CoveColors.Neutral.Background.copy(alpha = if (expanded) 0.97f else 0f),
                        CoveColors.Neutral.Background.copy(alpha = if (expanded) 0.86f else 0f),
                        Color.Transparent,
                    ),
                ),
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            modifier = Modifier
                .padding(start = 14.dp, end = 10.dp)
                .tvFocusGroup()
                // hasFocus, not isFocused: the rail itself is never the focused node — one of
                // its buttons is — so the narrower flag reports false throughout and the rail
                // would never open.
                .onFocusChanged { state -> onExpandedChange(state.hasFocus) },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NavDestination.entries.forEach { destination ->
                TvRailButton(
                    destination = destination,
                    selected = destination == selected,
                    expanded = expanded,
                    onSelect = { onSelect(destination) },
                    modifier = if (destination == selected && selectedFocusRequester != null) {
                        Modifier.focusRequester(selectedFocusRequester)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

@Composable
private fun TvRailButton(
    destination: NavDestination,
    selected: Boolean,
    expanded: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val shape = RoundedCornerShape(14.dp)

    // Focus here is a filled accent tint rather than another ring. The rail sits against the
    // page's own artwork, where an outline competes with every card edge behind it; a solid
    // block does not. Selection stays legible underneath as a quieter fill and an accent glyph.
    val background by animateColorAsState(
        targetValue = when {
            focused -> CoveColors.Brand.Accent
            selected -> CoveColors.Neutral.SurfaceRaised
            else -> Color.Transparent
        },
        animationSpec = if (reducedMotion) snap() else tween(140),
        label = "TvRailButtonBackground",
    )
    val content by animateColorAsState(
        targetValue = when {
            focused -> CoveColors.Brand.OnAccent
            selected -> CoveColors.Brand.Accent
            else -> CoveColors.Neutral.Muted
        },
        animationSpec = if (reducedMotion) snap() else tween(140),
        label = "TvRailButtonContent",
    )

    Row(
        modifier = modifier
            .height(56.dp)
            .tvFocusTarget(
                shape = shape,
                onClick = onSelect,
                scale = TvFocusDefaults.ControlScale,
                // The fill is the focus signal; a ring on top of it would be two answers to
                // the same question.
                ringColor = Color.Transparent,
                interactionSource = interactionSource,
            )
            .background(background, shape)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconifyIcon(
            icon = destination.iconName,
            tint = content,
            modifier = Modifier.size(26.dp),
        )
        if (expanded) {
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = destination.label,
                style = MaterialTheme.typography.labelLarge,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
