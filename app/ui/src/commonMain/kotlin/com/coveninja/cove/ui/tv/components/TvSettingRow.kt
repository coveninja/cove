package com.coveninja.cove.ui.tv.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.state.LocalMotionPolicy
import com.coveninja.cove.ui.tv.focus.TvFocusDefaults
import com.coveninja.cove.ui.tv.focus.tvFocusTarget

/**
 * A full-width settings row: a name, an explanation, and its current value on the right.
 *
 * One shape for every setting, whether it is a switch or a choice among several, because on a
 * remote they are the same gesture — the row is focused and centre changes it. Splitting them
 * into switches and dropdowns the way a pointer UI does would add a second interaction with no
 * second input to justify it, and dropdowns in particular were what fought the focus engine
 * hardest in the previous TV shell.
 *
 * The row is the focus target rather than the control inside it, so the whole width is the
 * target and nothing has to be aimed at.
 */
@Composable
internal fun TvSettingRow(
    label: String,
    detail: String?,
    value: String,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
    /** Drawn as an accent pill when the setting is on, plain text when it is a choice. */
    highlighted: Boolean = false,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val shape = RoundedCornerShape(14.dp)

    val background by animateColorAsState(
        targetValue = when {
            focused -> CoveColors.Neutral.Text
            else -> CoveColors.Neutral.Surface
        },
        animationSpec = if (reducedMotion) snap() else tween(140),
        label = "TvSettingRowBackground",
    )
    val primary by animateColorAsState(
        targetValue = if (focused) CoveColors.Neutral.Background else CoveColors.Neutral.Text,
        animationSpec = if (reducedMotion) snap() else tween(140),
        label = "TvSettingRowLabel",
    )
    val secondary by animateColorAsState(
        targetValue = if (focused) {
            CoveColors.Neutral.Background.copy(alpha = 0.7f)
        } else {
            CoveColors.Neutral.MutedDim
        },
        animationSpec = if (reducedMotion) snap() else tween(140),
        label = "TvSettingRowDetail",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .tvFocusTarget(
                shape = shape,
                onClick = onActivate,
                enabled = enabled,
                scale = TvFocusDefaults.ControlScale,
                ringColor = Color.Transparent,
                interactionSource = interactionSource,
            )
            .background(background, shape)
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.fillMaxWidth(0.68f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            if (highlighted) {
                Row(
                    modifier = Modifier
                        .background(CoveColors.Brand.Accent, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconifyIcon(
                        icon = "lucide:check",
                        tint = CoveColors.Brand.OnAccent,
                        modifier = Modifier.size(15.dp),
                    )
                    Box(modifier = Modifier.width(6.dp))
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelMedium,
                        color = CoveColors.Brand.OnAccent,
                    )
                }
            } else {
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelLarge,
                    color = secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A heading above a block of settings rows. */
@Composable
internal fun TvSettingsHeading(
    title: String,
    detail: String?,
    icon: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconifyIcon(
                icon = icon,
                tint = CoveColors.Brand.Accent,
                modifier = Modifier.size(20.dp),
            )
            Box(modifier = Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = CoveColors.Neutral.Text,
            )
        }
        detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = CoveColors.Neutral.MutedDim,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
