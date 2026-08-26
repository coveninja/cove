package com.coveninja.cove.ui.pages.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.components.menu.CMenuItem
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.state.AUDIO_LANGUAGE_ORIGINAL
import com.coveninja.cove.ui.state.LANGUAGES
import com.coveninja.cove.ui.state.languageNativeName

/**
 * An ordered language preference: what to look for first, and what to settle for after that.
 *
 * A single language was never quite the question being asked. Someone who wants the Japanese
 * original will take the original over an English dub, but would still rather have English
 * than Polish — and a one-value control makes them choose which half of that to express. mpv
 * has taken an ordered list on `alang`/`slang` the whole time; only the settings were the
 * limit.
 *
 * Reordered with buttons rather than by dragging. A drag inside a scrolling settings page has
 * to fight the scroll for the same gesture, and it leaves someone using a finger with a
 * target they must hold precisely; two arrows work identically with a mouse and a thumb.
 */
@Composable
internal fun SettingLanguageOrder(
    title: String,
    description: String?,
    languages: List<String>,
    /** Whether "Original" is offered — meaningful for audio, meaningless for subtitles. */
    allowOriginal: Boolean,
    onChange: (List<String>) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.padding(horizontal = RowPadding, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingLabels(title, description)

        languages.forEachIndexed { index, code ->
            LanguageRow(
                position = index + 1,
                label = languageNativeName(code),
                canMoveUp = index > 0,
                canMoveDown = index < languages.lastIndex,
                // The list is never emptied from here. An empty list means "no order has been
                // expressed", which sends the resolution back to the single-language setting
                // behind it — not at all what removing the last row looks like it should do.
                canRemove = languages.size > 1,
                onMoveUp = { onChange(languages.swapped(index, index - 1)) },
                onMoveDown = { onChange(languages.swapped(index, index + 1)) },
                onRemove = { onChange(languages.filterIndexed { at, _ -> at != index }) },
            )
        }

        val remaining = remember(languages, allowOriginal) {
            LANGUAGES.filter { it.code !in languages }
                .filter { allowOriginal || it.code != AUDIO_LANGUAGE_ORIGINAL }
        }
        if (remaining.isNotEmpty()) {
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { adding = true }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IconifyIcon(
                        icon = "lucide:plus",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        text = "Add language",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                DropdownMenu(
                    expanded = adding,
                    onDismissRequest = { adding = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.heightIn(max = 380.dp),
                ) {
                    remaining.forEach { language ->
                        CMenuItem(
                            text = language.nativeName,
                            iconName = "lucide:plus",
                            onClick = {
                                adding = false
                                onChange(languages + language.code)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(
    position: Int,
    label: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canRemove: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "$position",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        RowAction("lucide:arrow-up", canMoveUp, onMoveUp)
        RowAction("lucide:arrow-down", canMoveDown, onMoveDown)
        RowAction("lucide:x", canRemove, onRemove)
    }
}

/**
 * Disabled rather than hidden at the ends of the list: buttons that come and go make the row
 * change width as it moves, and the arrow you were aiming at ends up somewhere else.
 */
@Composable
private fun RowAction(icon: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.75f else 0.22f)
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(7.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        IconifyIcon(icon = icon, modifier = Modifier.size(16.dp), tint = tint)
    }
}

/** [a] and [b] exchanged. Out-of-range indices leave the list alone rather than throwing. */
internal fun List<String>.swapped(a: Int, b: Int): List<String> {
    if (a !in indices || b !in indices || a == b) return this
    return toMutableList().apply {
        val held = this[a]
        this[a] = this[b]
        this[b] = held
    }
}
