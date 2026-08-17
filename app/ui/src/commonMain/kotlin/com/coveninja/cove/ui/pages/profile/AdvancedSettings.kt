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
import androidx.compose.runtime.collectAsState
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
import com.coveninja.cove.shared.data.AppUpdateState
import com.coveninja.cove.ui.components.common.formatUpdateBytes
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
    val graph = LocalAppGraph.current
    val device = graph.device
    val updates = graph.updates
    val scope = rememberCoroutineScope()
    val performance by device.performance.collectAsState()
    val updateState by updates.state.collectAsState()
    val automaticUpdates by updates.automaticUpdatesEnabled.collectAsState()

    var config by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var performanceError by remember { mutableStateOf<String?>(null) }

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

        if (
            performance.lowPerformanceRecommended &&
            !performance.recommendationDismissed &&
            !performance.lowPerformanceMode
        ) {
            SettingsCard(
                title = "Performance suggestion",
                iconName = "lucide:gauge",
                description = "Android reports that this device has limited working memory.",
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = RowPadding, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Low-performance mode reduces nonessential page, card, and " +
                            "hero motion. Artwork, shadows, playback, and loading feedback " +
                            "stay unchanged.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PrimaryButton(
                            label = "Enable",
                            onClick = {
                                scope.launch {
                                    val result = runCatching {
                                        device.setLowPerformanceMode(true)
                                        device.dismissLowPerformanceRecommendation()
                                    }
                                    performanceError = result.exceptionOrNull()?.message
                                }
                            },
                        )
                        SecondaryButton(
                            label = "Not now",
                            onClick = {
                                scope.launch {
                                    val result = runCatching {
                                        device.dismissLowPerformanceRecommendation()
                                    }
                                    performanceError = result.exceptionOrNull()?.message
                                }
                            },
                        )
                    }
                }
            }
        }

        SettingsCard(
            title = "Performance",
            iconName = "lucide:gauge",
            description = "Rendering choices for this device only. This does not sync.",
        ) {
            SettingToggle(
                title = "Low-performance mode",
                description = "Reduce nonessential page, card, and hero motion while keeping " +
                    "loading and interaction feedback.",
                checked = performance.lowPerformanceMode,
                onCheckedChange = { enabled ->
                    scope.launch {
                        val result = runCatching {
                            device.setLowPerformanceMode(enabled)
                            if (enabled) device.dismissLowPerformanceRecommendation()
                        }
                        performanceError = result.exceptionOrNull()?.message
                    }
                },
            )
            performanceError?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.padding(horizontal = RowPadding, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SettingsCard(
            title = "mpv configuration",
            iconName = "lucide:settings",
            description = "Passed straight to the player. An invalid option is ignored "
        ) {
            Column(
                modifier = Modifier.padding(horizontal = RowPadding, vertical = 18.dp),
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

        if (updates.available) {
            SettingsCard(
                title = "Updates",
                iconName = "lucide:refresh-cw",
                description = "Verified application updates for this device only.",
            ) {
                SettingToggle(
                    title = "Automatic updates",
                    description = "Check in Cove, download verified releases, and ask before restarting.",
                    checked = automaticUpdates,
                    onCheckedChange = { enabled ->
                        scope.launch { updates.setAutomaticUpdatesEnabled(enabled) }
                    },
                )
                SettingRow(
                    title = updateStatusTitle(updateState),
                    description = updateStatusDescription(updateState),
                ) {
                    StatusPill(updateStatusPill(updateState))
                }
                Row(
                    modifier = Modifier.padding(horizontal = RowPadding, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PrimaryButton(
                        label = "Check now",
                        enabled = updateState !is AppUpdateState.Checking &&
                            updateState !is AppUpdateState.Downloading &&
                            updateState !is AppUpdateState.Installing &&
                            updateState !is AppUpdateState.Ready &&
                            updateState !is AppUpdateState.PermissionRequired &&
                            updateState !is AppUpdateState.MeteredApprovalRequired,
                        onClick = { scope.launch { updates.checkNow() } },
                    )
                    if (updateState is AppUpdateState.Failed) {
                        Text(
                            text = (updateState as AppUpdateState.Failed).message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        } else if (updateState is AppUpdateState.ManagedExternally) {
            SettingsCard(title = "Updates", iconName = "lucide:refresh-cw") {
                SettingsNotice((updateState as AppUpdateState.ManagedExternally).message)
            }
        }

        SettingsCard(title = "About", iconName = "lucide:info") {
            SettingRow(title = "Version", description = "The build you are running.") {
                StatusPill(device.appVersion.ifBlank { "unknown" })
            }
            Text(
                text = "Made with love by arcady@coveninja",
                modifier = Modifier.padding(horizontal = RowPadding, vertical = 18.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun updateStatusTitle(state: AppUpdateState): String = when (state) {
    AppUpdateState.Idle -> "Automatic update status"
    is AppUpdateState.Checking -> "Checking for updates"
    is AppUpdateState.UpToDate -> "Cove is up to date"
    is AppUpdateState.MeteredApprovalRequired -> "Update ${state.release.version} available"
    is AppUpdateState.Downloading -> "Downloading ${state.release.version}"
    is AppUpdateState.Ready -> "Update ${state.release.version} ready"
    is AppUpdateState.PermissionRequired -> "Android permission required"
    is AppUpdateState.Installing -> "Installing ${state.release.version}"
    is AppUpdateState.Failed -> "Update failed"
    is AppUpdateState.ManagedExternally -> "Managed externally"
}

private fun updateStatusDescription(state: AppUpdateState): String? = when (state) {
    is AppUpdateState.Downloading ->
        "${formatUpdateBytes(state.downloadedBytes)} of ${formatUpdateBytes(state.totalBytes)}"
    is AppUpdateState.Ready -> "Verified and staged. Install when the prompt appears."
    is AppUpdateState.MeteredApprovalRequired ->
        "Waiting for approval to use this metered network."
    is AppUpdateState.PermissionRequired -> "Allow installs from Cove in Android settings."
    is AppUpdateState.Failed -> state.message
    else -> null
}

private fun updateStatusPill(state: AppUpdateState): String = when (state) {
    AppUpdateState.Idle -> "Ready"
    is AppUpdateState.Checking -> "Checking"
    is AppUpdateState.UpToDate -> "Current"
    is AppUpdateState.MeteredApprovalRequired -> "Approval needed"
    is AppUpdateState.Downloading -> {
        val percent = if (state.totalBytes > 0L) state.downloadedBytes * 100 / state.totalBytes else 0L
        "$percent%"
    }
    is AppUpdateState.Ready -> "Install ready"
    is AppUpdateState.PermissionRequired -> "Permission"
    is AppUpdateState.Installing -> "Installing"
    is AppUpdateState.Failed -> "Retry"
    is AppUpdateState.ManagedExternally -> "External"
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
