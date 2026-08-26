package com.coveninja.cove.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.icons.IconifyIcon

/**
 * A row of colours, one of them chosen.
 *
 * Shared because it is drawn in two very different places — a settings row and a dropdown
 * inside the player — and the interesting part is not the circles but [toComposeColor]
 * underneath them. Left in the settings file it would have been copied into the player menu,
 * and two hex parsers is one more than the number that can be relied on to agree.
 *
 * [swatchSize] is the only thing the two call sites disagree about: the player's menu is a
 * denser surface than a settings card.
 */
@Composable
fun ColorSwatchRow(
    colors: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    swatchSize: Dp = 30.dp,
    spacing: Dp = 9.dp,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(spacing)) {
        colors.forEach { hex ->
            ColorSwatch(
                color = hex.toComposeColor(),
                // Compared without the alpha: the swatch stands for the colour, and an opacity
                // control alongside owns the other half. Matching the whole string would leave
                // every swatch unselected the moment that opacity moved.
                selected = hex.opaquePart() == selected.opaquePart(),
                size = swatchSize,
                onClick = { onSelect(hex) },
            )
        }
    }
}

@Composable
private fun ColorSwatch(color: Color, selected: Boolean, size: Dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            // A ring rather than a fill change, so a light swatch and a dark one read as
            // selected the same way.
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
                },
                shape = CircleShape,
            )
            .padding(size * 0.13f)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            IconifyIcon(
                icon = "lucide:check",
                modifier = Modifier.size(size * 0.47f),
                // Drawn on the swatch itself, so the tick has to contrast with whatever colour
                // it lands on rather than with the surface behind it.
                tint = if (color.luminanceIsLight()) Color.Black else Color.White,
            )
        }
    }
}

/** The colour without its alpha, for comparing a swatch against a stored value. */
internal fun String.opaquePart(): String {
    val digits = trim().removePrefix("#").uppercase()
    return if (digits.length == 8) digits.drop(2) else digits
}

/** `#AARRGGBB` or `#RRGGBB` as Compose sees it. Anything unreadable draws as transparent. */
internal fun String.toComposeColor(): Color {
    val digits = trim().removePrefix("#")
    val argb = when (digits.length) {
        // A six-digit colour carries no alpha and is opaque, which is what mpv makes of one.
        6 -> digits.toLongOrNull(16)?.or(0xFF000000L)
        8 -> digits.toLongOrNull(16)
        else -> null
    } ?: return Color.Transparent
    return Color(argb.toInt())
}

/** Whether black text would read better on this than white. Rec. 709 luma. */
internal fun Color.luminanceIsLight(): Boolean =
    (0.2126 * red + 0.7152 * green + 0.0722 * blue) > 0.55
