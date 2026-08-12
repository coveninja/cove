package com.coveninja.cove.ui.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The keyboard map, on `?`.
 *
 * The player answers to two dozen keys and advertised none of them, which made most
 * of them dead weight — a shortcut nobody can discover is a shortcut nobody uses.
 * Grouped by what the key does rather than alphabetically, because the reason to open
 * this is "how do I change the audio track", never "what does A do".
 */
@Composable
internal fun ShortcutSheet(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.86f), RoundedCornerShape(18.dp))
            .padding(horizontal = 26.dp, vertical = 22.dp)
            .widthIn(max = 560.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = "Keyboard",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            SHORTCUT_GROUPS.forEach { group ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = group.title,
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    group.entries.forEach { (keys, description) ->
                        ShortcutRow(keys = keys, description = description)
                    }
                }
            }
            Text(
                text = "Press ? or Esc to close",
                color = Color.White.copy(alpha = 0.45f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ShortcutRow(keys: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(140.dp)) {
            Text(
                text = keys,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = description,
            color = Color.White.copy(alpha = 0.76f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private class ShortcutGroup(val title: String, val entries: List<Pair<String, String>>)

private val SHORTCUT_GROUPS = listOf(
    ShortcutGroup(
        "Playback",
        listOf(
            "Space / K" to "Play or pause",
            ", / ." to "Step one frame back or forward",
            "[ / ]" to "Slower or faster",
            "Backspace" to "Back to normal speed",
        ),
    ),
    ShortcutGroup(
        "Moving around",
        listOf(
            "← / → , J / L" to "Jump back or forward",
            "Shift + ← / →" to "Nudge one second",
            "Page Up / Down" to "Previous or next chapter",
            "0 – 9" to "Jump to that tenth of the file",
            "Home / End" to "Start or end",
        ),
    ),
    ShortcutGroup(
        "Sound and subtitles",
        listOf(
            "↑ / ↓" to "Volume",
            "M" to "Mute",
            "C" to "Cycle subtitle track",
            "A" to "Cycle audio track",
        ),
    ),
    ShortcutGroup(
        "Window",
        listOf(
            "F" to "Fullscreen",
            "S" to "Save a screenshot",
            "I" to "Playback statistics",
            "Esc" to "Leave fullscreen, then close",
        ),
    ),
    // The gestures belong here for the same reason the keys do: nothing on screen
    // says the picture can be clicked, and they are the quickest way to seek.
    ShortcutGroup(
        "Pointer",
        listOf(
            "Click" to "Play or pause",
            "Double-click edges" to "Jump back or forward",
            "Double-click middle" to "Fullscreen",
            "Wheel" to "Volume",
        ),
    ),
)
