package com.coveninja.cove.ui.components.common

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * The app's one tooltip: a dark caret-pointed label over whatever it wraps.
 *
 * It grew inside the nav bar, where the five destinations are icons with no captions. That
 * is not a nav-bar problem — every icon-only control in the app has it, and a round button
 * bearing a crossed-out eye is not something anyone reads at a glance. Having one of these
 * is what keeps them all saying it the same way, in the same place, after the same pause.
 *
 * On a touch screen `TooltipBox` shows on long-press instead of hover, which is the platform
 * convention and costs nothing here: none of the controls this wraps use long-press for
 * anything of their own.
 */
/**
 * Which side of the control its caption sits on.
 *
 * Deliberately not Material's own `TooltipAnchorPosition`. That type is experimental, and a
 * default parameter of an experimental type propagates the opt-in requirement to every
 * caller — thirteen icon buttons across four pages would each have to annotate themselves
 * for a tooltip none of them configures. Keeping Material's vocabulary behind this boundary
 * means the churn lands here if it changes.
 */
enum class TooltipSide {
    Above,
    Below,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoveTooltip(
    label: String,
    modifier: Modifier = Modifier,
    /**
     * Below suits anything near the top of the window; a control sitting at the bottom of it
     * wants [TooltipSide.Above], or the label is placed off the edge of the screen.
     */
    side: TooltipSide = TooltipSide.Below,
    content: @Composable () -> Unit,
) {
    val positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
        positioning = when (side) {
            TooltipSide.Above -> TooltipAnchorPosition.Above
            TooltipSide.Below -> TooltipAnchorPosition.Below
        },
        spacingBetweenTooltipAndAnchor = 2.dp,
    )
    val caret = TooltipDefaults.caretShape(caretSize = DpSize(width = 18.dp, height = 8.dp))

    TooltipBox(
        positionProvider = positionProvider,
        tooltip = {
            PlainTooltip(
                caretShape = caret,
                shape = RoundedCornerShape(10.dp),
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 6.dp,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
        state = rememberTooltipState(),
        modifier = modifier,
        content = content,
    )
}
