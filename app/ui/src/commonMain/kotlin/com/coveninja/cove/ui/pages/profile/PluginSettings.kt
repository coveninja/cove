package com.coveninja.cove.ui.pages.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.InstalledPlugin
import com.coveninja.cove.shared.data.PluginCapability
import com.coveninja.cove.shared.data.PluginCatalogEntry
import com.coveninja.cove.shared.data.PluginRuntimeStatus
import com.coveninja.cove.shared.data.PluginSettingDefinition
import com.coveninja.cove.shared.data.PluginSettingType
import com.coveninja.cove.shared.data.PluginsState
import com.coveninja.cove.ui.state.LocalAppGraph
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

@Composable
fun PluginSettings(modifier: Modifier = Modifier) {
    val repository = LocalAppGraph.current.plugins
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    var operationError by remember { mutableStateOf<String?>(null) }
    val runAction: (suspend () -> Unit) -> Unit = { action ->
        scope.launch {
            operationError = null
            runCatching { action() }.onFailure {
                operationError = it.message ?: "The plugin operation failed."
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (val current = state) {
            PluginsState.Loading -> SettingsCard { SettingsNotice("Loading desktop plugins…") }
            is PluginsState.Failed -> SettingsCard {
                SettingsNotice(current.message, isError = true)
            }
            is PluginsState.Ready -> {
                PluginCatalogSettings(current, runAction)
                current.installed.forEach { plugin ->
                    InstalledPluginSettings(plugin, runAction)
                }
                DeveloperPluginSettings(current.developerMode, runAction)
                operationError?.let { message ->
                    SettingsCard { SettingsNotice(message, isError = true) }
                }
            }
        }
    }
}

@Composable
private fun PluginCatalogSettings(
    state: PluginsState.Ready,
    runAction: (suspend () -> Unit) -> Unit,
) {
    val repository = LocalAppGraph.current.plugins
    val installedIds = state.installed.mapTo(mutableSetOf()) { it.manifest.id }
    val available = state.catalog.filter { it.manifest.id !in installedIds }
    SettingsCard(
        title = "Plugin catalog",
        iconName = "lucide:sparkles",
        description = "Packages and catalog metadata are verified before installation.",
    ) {
        state.catalogError?.let { error ->
            SettingsNotice(error, isError = true)
            SettingDivider()
        }
        if (available.isEmpty()) {
            SettingsNotice(
                if (state.catalog.isEmpty()) "No signed catalog is available yet."
                else "Every catalog plugin is installed.",
            )
        } else {
            available.forEachIndexed { index, entry ->
                if (index > 0) SettingDivider()
                CatalogPluginRow(entry) { runAction { repository.install(entry.manifest.id) } }
            }
        }
        SettingDivider()
        SettingRow(
            title = "Check for updates",
            description = "Refresh the signed catalog and stage compatible updates.",
            control = {
                SecondaryButton("Refresh", onClick = { runAction { repository.refreshCatalog() } })
            },
        )
    }
}

@Composable
private fun CatalogPluginRow(entry: PluginCatalogEntry, onInstall: () -> Unit) {
    SettingRow(
        title = "${entry.manifest.name} ${entry.manifest.version}",
        description = entry.manifest.description.ifBlank { "Published by ${entry.manifest.publisher}" },
        control = { PrimaryButton("Install", onClick = onInstall) },
    )
}

@Composable
private fun InstalledPluginSettings(
    plugin: InstalledPlugin,
    runAction: (suspend () -> Unit) -> Unit,
) {
    val repository = LocalAppGraph.current.plugins
    var grants by remember(
        plugin.manifest.id,
        plugin.manifest.version,
        plugin.approvedCapabilities,
    ) { mutableStateOf(plugin.approvedCapabilities) }
    val allApproved = grants.containsAll(plugin.requestedCapabilities)

    SettingsCard(
        title = "${plugin.manifest.name} ${plugin.manifest.version}",
        iconName = "lucide:settings",
        description = plugin.manifest.description,
    ) {
        SettingRow(
            title = "Status",
            description = plugin.statusMessage,
            control = {
                StatusPill(statusLabel(plugin.status), tone = statusTone(plugin.status))
            },
        )
        if (plugin.unsigned) {
            SettingDivider()
            SettingsNotice("Unsigned local plugin. Only use packages you trust.", isError = true)
        }
        SettingDivider()
        SettingToggle(
            title = "Enabled for this profile",
            description = "Profiles opt in independently.",
            checked = plugin.enabled,
            onCheckedChange = { enabled -> runAction { repository.setEnabled(plugin.manifest.id, enabled) } },
        )
        SettingDivider()
        PluginPermissionSettings(
            plugin = plugin,
            grants = grants,
            onGrantsChange = { grants = it },
            onSave = { runAction { repository.approve(plugin.manifest.id, grants) } },
            allApproved = allApproved,
        )
        if (plugin.manifest.settings.isNotEmpty()) {
            SettingDivider()
            PluginDefinedSettings(plugin, runAction)
        }
        SettingDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = RowPadding, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (plugin.status == PluginRuntimeStatus.Failed || plugin.status == PluginRuntimeStatus.Waiting) {
                SecondaryButton("Retry", onClick = { runAction { repository.retry(plugin.manifest.id) } })
            }
            SecondaryButton(
                label = "Uninstall",
                danger = true,
                onClick = { runAction { repository.uninstall(plugin.manifest.id) } },
            )
        }
    }
}

@Composable
private fun PluginPermissionSettings(
    plugin: InstalledPlugin,
    grants: Set<PluginCapability>,
    onGrantsChange: (Set<PluginCapability>) -> Unit,
    onSave: () -> Unit,
    allApproved: Boolean,
) {
    Column {
        SettingRow(
            title = "Permissions",
            description = if (allApproved) {
                "Every requested capability is approved."
            } else {
                "Review each capability. The plugin stays off until all required permissions are granted."
            },
            control = {
                PrimaryButton(
                    label = "Save",
                    onClick = onSave,
                    enabled = grants != plugin.approvedCapabilities,
                )
            },
        )
        plugin.requestedCapabilities.sortedBy { it.name }.forEach { capability ->
            SettingDivider()
            SettingToggle(
                title = capabilityTitle(capability),
                description = capabilityDescription(capability),
                checked = capability in grants,
                onCheckedChange = { granted ->
                    onGrantsChange(if (granted) grants + capability else grants - capability)
                },
            )
        }
    }
}

@Composable
private fun PluginDefinedSettings(
    plugin: InstalledPlugin,
    runAction: (suspend () -> Unit) -> Unit,
) {
    val repository = LocalAppGraph.current.plugins
    Column {
        SettingRow(
            title = "Plugin settings",
            description = "These values apply only to the active profile.",
            control = {},
        )
        plugin.manifest.settings.forEach { definition ->
            SettingDivider()
            PluginDefinedSetting(
                definition = definition,
                value = plugin.settings[definition.key],
                onValue = { value ->
                    runAction { repository.updateSetting(plugin.manifest.id, definition.key, value) }
                },
                onAction = { runAction { repository.invokeAction(plugin.manifest.id, definition.key) } },
            )
        }
    }
}

@Composable
private fun PluginDefinedSetting(
    definition: PluginSettingDefinition,
    value: JsonElement?,
    onValue: (JsonPrimitive) -> Unit,
    onAction: () -> Unit,
) {
    val primitive = value as? JsonPrimitive
    when (definition.type) {
        PluginSettingType.Boolean -> SettingToggle(
            title = definition.label,
            description = definition.description,
            checked = primitive?.booleanOrNull ?: false,
            onCheckedChange = { onValue(JsonPrimitive(it)) },
        )
        PluginSettingType.String -> SettingTextRow(
            title = definition.label,
            description = definition.description,
            value = primitive?.contentOrNull.orEmpty(),
            placeholder = definition.label,
            onCommit = { onValue(JsonPrimitive(it)) },
        )
        PluginSettingType.Number -> SettingTextRow(
            title = definition.label,
            description = definition.description,
            value = primitive?.doubleOrNull?.toString().orEmpty(),
            placeholder = definition.minimum?.toString() ?: "0",
            onCommit = { entered -> entered.toDoubleOrNull()?.let { onValue(JsonPrimitive(it)) } },
        )
        PluginSettingType.Select -> SettingChoice(
            title = definition.label,
            description = definition.description,
            options = definition.options.map { it.value to it.label },
            selected = primitive?.contentOrNull.orEmpty(),
            onSelect = { onValue(JsonPrimitive(it)) },
        )
        PluginSettingType.Action -> SettingRow(
            title = definition.label,
            description = definition.description,
            control = { SecondaryButton("Run", onClick = onAction) },
        )
    }
}

@Composable
private fun DeveloperPluginSettings(
    developerMode: Boolean,
    runAction: (suspend () -> Unit) -> Unit,
) {
    val repository = LocalAppGraph.current.plugins
    var localPath by remember { mutableStateOf("") }
    SettingsCard(
        title = "Developer plugins",
        iconName = "lucide:gauge",
        description = "Local packages are unsigned and bypass the official catalog.",
    ) {
        SettingToggle(
            title = "Developer mode",
            description = "Allow installing a local .zip plugin package.",
            checked = developerMode,
            onCheckedChange = { runAction { repository.setDeveloperMode(it) } },
        )
        if (developerMode) {
            SettingDivider()
            Column(
                modifier = Modifier.padding(horizontal = RowPadding, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Local package",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsTextField(
                        value = localPath,
                        onValueChange = { localPath = it },
                        placeholder = "/path/to/plugin.zip",
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton(
                        label = "Install",
                        enabled = localPath.isNotBlank(),
                        onClick = { runAction { repository.installLocal(localPath.trim()) } },
                    )
                }
            }
        }
    }
}

private fun statusLabel(status: PluginRuntimeStatus): String = when (status) {
    PluginRuntimeStatus.PermissionRequired -> "Permission required"
    PluginRuntimeStatus.UpdateStaged -> "Update staged"
    else -> status.name
}

@Composable
private fun statusTone(status: PluginRuntimeStatus): Color = when (status) {
    PluginRuntimeStatus.Failed -> MaterialTheme.colorScheme.error
    PluginRuntimeStatus.PermissionRequired,
    PluginRuntimeStatus.Waiting,
    -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.tertiary
}

private fun capabilityTitle(capability: PluginCapability): String = when (capability) {
    PluginCapability.PlaybackObserve -> "Read playback activity"
    PluginCapability.PlaybackTransport -> "Control playback"
    PluginCapability.MediaStreams -> "Provide streams"
    PluginCapability.MediaSubtitles -> "Provide subtitles"
    PluginCapability.MediaTimestamps -> "Provide skip timestamps"
    PluginCapability.MetadataAugment -> "Augment metadata"
    PluginCapability.NetworkHttp -> "Access approved internet hosts"
    PluginCapability.NetworkLan -> "Access the local network"
    PluginCapability.StorageProfile -> "Store profile data"
    PluginCapability.UiSettings -> "Show settings"
    PluginCapability.DiscordPresence -> "Set Discord activity"
}

private fun capabilityDescription(capability: PluginCapability): String = when (capability) {
    PluginCapability.PlaybackObserve -> "Receives title, episode, playback phase, and coarse progress."
    PluginCapability.PlaybackTransport -> "May pause, resume, seek, or stop the active player."
    PluginCapability.MediaStreams -> "May add attributed stream results without replacing built-in addons."
    PluginCapability.MediaSubtitles -> "May add subtitle tracks."
    PluginCapability.MediaTimestamps -> "May fill missing intro, recap, credits, and preview segments."
    PluginCapability.MetadataAugment -> "May fill missing text, images, and external links only."
    PluginCapability.NetworkHttp -> "Network requests are proxied and limited to manifest hosts."
    PluginCapability.NetworkLan -> "Allows private-address requests when Cove also permits LAN sources."
    PluginCapability.StorageProfile -> "May keep up to 1 MiB of data for the active profile."
    PluginCapability.UiSettings -> "Uses Cove's native declarative settings controls."
    PluginCapability.DiscordPresence -> "May publish activity through Discord's documented local IPC protocol."
}
