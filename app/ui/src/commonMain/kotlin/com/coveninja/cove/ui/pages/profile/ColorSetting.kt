package com.coveninja.cove.ui.pages.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.state.subtitleColorAlpha
import com.coveninja.cove.ui.state.withSubtitleColorAlpha
import kotlin.math.roundToInt

/**
 * A colour, chosen from a short list of them.
 *
 * Swatches rather than a colour wheel on purpose. Subtitle colour is not a design decision
 * with a right answer somewhere in sixteen million — it is a legibility one with about five,
 * which is why every player that offers it offers a list. A wheel would also need a hex field
 * beside it to be usable with a remote or a thumb, for a choice nobody makes twice.
 *
 * [showOpacity] adds the one continuous part that does matter: how much of the picture the
 * panel behind the text hides.
 */
@Composable
internal fun SettingColor(
    title: String,
    description: String? = null,
    value: String,
    /** The colours offered, as `#AARRGGBB` or `#RRGGBB`. */
    presets: List<String>,
    showOpacity: Boolean = false,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = RowPadding, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        SettingLabels(title, description)

        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            presets.forEach { hex ->
                // Compared without the alpha: the swatch stands for the colour, and the
                // opacity slider below owns the other half. Matching the whole string would
                // leave every swatch unselected the moment the slider moved.
                val selected = hex.opaquePart() == value.opaquePart()
                Swatch(
                    color = hex.toComposeColor(),
                    selected = selected,
                    // The chosen opacity is carried onto the new colour rather than reset,
                    // so picking a different colour does not undo the slider.
                    onClick = { onSelect(withSubtitleColorAlpha(hex, subtitleColorAlpha(value))) },
                )
            }
        }

        if (showOpacity) {
            val alpha = subtitleColorAlpha(value)
            var dragged by remember(alpha) { mutableStateOf<Float?>(null) }
            val shown = dragged ?: alpha.toFloat()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Opacity",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = shown,
                    valueRange = 0f..255f,
                    onValueChange = { dragged = it },
                    // Committed on release, not per frame: every write is a whole-object
                    // settings replace, so a drag would otherwise fire dozens of them.
                    onValueChangeFinished = {
                        dragged?.let {
                            onSelect(withSubtitleColorAlpha(value, it.roundToInt()))
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${(shown / 255f * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
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
            .padding(4.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            IconifyIcon(
                icon = "lucide:check",
                modifier = Modifier.size(14.dp),
                // Against the swatch itself, so the tick has to contrast with whatever it is on.
                tint = if (color.luminanceIsLight()) Color.Black else Color.White,
            )
        }
    }
}

/** The colour without its alpha, for comparing a swatch against a stored value. */
private fun String.opaquePart(): String {
    val digits = trim().removePrefix("#").uppercase()
    return if (digits.length == 8) digits.drop(2) else digits
}

/** `#AARRGGBB` or `#RRGGBB` as Compose sees it. Anything unreadable draws as transparent. */
private fun String.toComposeColor(): Color {
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
private fun Color.luminanceIsLight(): Boolean =
    (0.2126 * red + 0.7152 * green + 0.0722 * blue) > 0.55
