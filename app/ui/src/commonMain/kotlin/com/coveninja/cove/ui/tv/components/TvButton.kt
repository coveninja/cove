package com.coveninja.cove.ui.tv.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.state.LocalMotionPolicy
import com.coveninja.cove.ui.tv.focus.TvFocusDefaults
import com.coveninja.cove.ui.tv.focus.tvFocusTarget

/**
 * The one button shape the TV shell uses.
 *
 * Focus is a fill rather than a ring here, matching the rail: buttons sit over artwork, where
 * an outline has to compete with whatever is behind it, and a solid block does not. The primary
 * button is already accent-filled at rest, so it deepens to white-on-accent's inverse instead —
 * the point is that focus is never ambiguous between two adjacent buttons.
 */
@Composable
internal fun TvButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    primary: Boolean = false,
    /** Marks the current choice in a strip of alternatives — a season, a filter. */
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val shape = RoundedCornerShape(12.dp)

    val background by animateColorAsState(
        targetValue = when {
            focused -> CoveColors.Neutral.Text
            primary -> CoveColors.Brand.Accent
            selected -> CoveColors.Brand.AccentContainer
            else -> CoveColors.Neutral.SurfaceRaised.copy(alpha = 0.92f)
        },
        animationSpec = if (reducedMotion) snap() else tween(140),
        label = "TvButtonBackground",
    )
    val content by animateColorAsState(
        targetValue = when {
            focused -> CoveColors.Neutral.Background
            primary -> CoveColors.Brand.OnAccent
            selected -> CoveColors.Brand.Accent
            else -> CoveColors.Neutral.Text
        },
        animationSpec = if (reducedMotion) snap() else tween(140),
        label = "TvButtonContent",
    )

    Row(
        modifier = modifier
            .height(50.dp)
            .tvFocusTarget(
                shape = shape,
                onClick = onClick,
                enabled = enabled,
                scale = TvFocusDefaults.ControlScale,
                ringColor = Color.Transparent,
                interactionSource = interactionSource,
            )
            .background(background, shape)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            IconifyIcon(icon = it, tint = content, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
        )
    }
}
