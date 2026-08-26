package com.coveninja.cove.ui.pages.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.components.common.ColorSwatchRow
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

        ColorSwatchRow(
            colors = presets,
            selected = value,
            // The chosen opacity is carried onto the new colour rather than reset, so picking
            // a different colour does not undo the slider below.
            onSelect = { onSelect(withSubtitleColorAlpha(it, subtitleColorAlpha(value))) },
        )

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
