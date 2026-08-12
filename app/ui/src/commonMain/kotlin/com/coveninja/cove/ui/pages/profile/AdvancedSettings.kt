package com.coveninja.cove.ui.pages.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.state.LocalAppGraph
import kotlinx.coroutines.launch

/**
 * The escape hatches: mpv's own configuration file, and which build this is.
 *
 * Both belong to the installation rather than the profile, so neither travels
 * with sync — a config tuned for this machine's GPU would be wrong on the next
 * one.
 */
@Composable
fun AdvancedSettings(modifier: Modifier = Modifier) {
    val device = LocalAppGraph.current.device
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(device) {
        if (device.available) {
            val loaded = runCatching { device.readMpvConfig() }
            config = loaded.getOrDefault("")
            draft = config.orEmpty()
            error = loaded.exceptionOrNull()?.message
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!device.available) {
            SettingsCard(title = "Advanced", iconName = "lucide:settings") {
                SettingsNotice(
                    "The player configuration lives on the machine running the backend, " +
                        "and is not editable from here.",
                )
            }
            return@Column
        }

        SettingsCard(
            title = "mpv configuration",
            iconName = "lucide:settings",
            description = "Passed straight to the player. An invalid option is ignored "
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (config == null) {
                    Text(
                        text = "Loading…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    ConfigEditor(
                        value = draft,
                        onValueChange = {
                            draft = it
                            saved = false
                        },
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PrimaryButton(
                            label = "Save",
                            enabled = draft != config,
                            onClick = {
                                scope.launch {
                                    val result = runCatching { device.writeMpvConfig(draft) }
                                    error = result.exceptionOrNull()?.message
                                    if (result.isSuccess) {
                                        config = draft
                                        saved = true
                                    }
                                }
                            },
                        )
                        if (draft != config) {
                            SecondaryButton(
                                label = "Revert",
                                onClick = { draft = config.orEmpty() },
                            )
                        }
                        // Saying it takes a restart matters: mpv reads this file
                        // when a player is created, not while one is running.
                        if (saved) StatusPill("Saved — applies to the next playback")
                    }
                    error?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        SettingsCard(title = "About", iconName = "lucide:info") {
            SettingRow(title = "Version", description = "The build you are running.") {
                StatusPill(device.appVersion.ifBlank { "unknown" })
            }
            Text(
                text = "Made with love by arcady@coveninja",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ConfigEditor(value: String, onValueChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 320.dp)
            .background(colors.onSurface.copy(alpha = 0.06f), RoundedCornerShape(11.dp))
            .border(
                1.dp,
                colors.outlineVariant.copy(alpha = 0.35f),
                RoundedCornerShape(11.dp),
            )
            .padding(12.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodySmall.copy(
                color = colors.onSurface,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(colors.tertiary),
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        text = "# hwdec=auto-copy",
                        color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
                inner()
            },
        )
    }
}
