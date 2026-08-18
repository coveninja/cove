package com.coveninja.cove.ui.tv.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.state.LocalMotionPolicy
import com.coveninja.cove.ui.tv.focus.tvFocusVisuals

/**
 * A text field a remote can reach.
 *
 * The field is never focused for the viewer. Landing on it is what opens the television's
 * on-screen keyboard, which takes over the entire panel, so it has to be something they chose
 * to do rather than something that happens to them on arrival — the previous TV shell auto-
 * focused its search box and the keyboard swallowed the screen before anyone had asked for it.
 *
 * Focus is a ring rather than the fill the buttons use: a filled text field would either hide
 * the text or fight it for contrast, and unlike the buttons this sits on a plain surface where
 * an outline reads perfectly well.
 */
@Composable
internal fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    onSubmit: () -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val shape = RoundedCornerShape(12.dp)
    val background by animateColorAsState(
        targetValue = if (focused) {
            CoveColors.Neutral.SurfaceHighest
        } else {
            CoveColors.Neutral.SurfaceHigh
        },
        animationSpec = if (reducedMotion) snap() else tween(140),
        label = "TvTextFieldBackground",
    )

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (focused) CoveColors.Brand.Accent else CoveColors.Neutral.Muted,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // A field that grew on focus would shift every control below it, and the one
                // below is usually the button the viewer is heading for.
                .tvFocusVisuals(
                    focused = focused,
                    shape = shape,
                    scale = 1f,
                    ringColor = CoveColors.Brand.Accent,
                )
                .background(background, shape)
                .padding(horizontal = 18.dp, vertical = 15.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                interactionSource = interactionSource,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = CoveColors.Neutral.Text,
                ),
                cursorBrush = SolidColor(CoveColors.Brand.Accent),
                visualTransformation = if (secret) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = CoveColors.Neutral.MutedDim,
                )
            }
        }
    }
}
