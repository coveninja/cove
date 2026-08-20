package com.coveninja.cove.ui.pages.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.CacheEntry
import com.coveninja.cove.shared.data.CacheKind
import com.coveninja.cove.shared.data.StorageUsageState
import com.coveninja.cove.ui.components.common.formatUpdateBytes
import com.coveninja.cove.ui.state.CacheAgeChoices
import com.coveninja.cove.ui.state.CacheLimitChoices
import com.coveninja.cove.ui.state.DownloadAheadChoices
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.cacheAgeLabel
import com.coveninja.cove.ui.state.cacheKindDescription
import com.coveninja.cove.ui.state.cacheKindItems
import com.coveninja.cove.ui.state.cacheKindLabel
import com.coveninja.cove.ui.state.cacheLimitLabel
import com.coveninja.cove.ui.state.downloadAheadLabel
import com.coveninja.cove.ui.state.withCurrent
import kotlinx.coroutines.launch

/**
 * What Cove keeps on this device, and the rules for letting go of it.
 *
 * Streaming a torrent is also downloading it, and until this screen existed nothing ever deleted
 * the result — a single episode could leave a gigabyte behind in the config directory, where no
 * operating system will ever reclaim it. So the page leads with the numbers: whatever the policy
 * says, the honest answer to "how much is this costing me" has to be visible before any of the
 * controls mean anything.
 *
 * Like [AdvancedSettings] this draws from its own repository rather than from AppSettings, because
 * a disk budget belongs to the machine and must not roam to a phone through profile sync.
 */
@Composable
fun StorageSettings(modifier: Modifier = Modifier) {
    val graph = LocalAppGraph.current
    val storage = graph.storage
    val scope = rememberCoroutineScope()
    val policy by storage.policy.collectAsState()
    val usage by storage.usage.collectAsState()

    // Armed rather than confirmed inline: there is no undo behind any of these buttons, and the
    // rows sit close enough together that a mis-aimed click would otherwise delete the wrong one.
    var armed by remember { mutableStateOf<CacheKind?>(null) }
    var lastResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(storage) { if (storage.available) storage.refresh() }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!storage.available) {
            SettingsCard(title = "Storage", iconName = "lucide:hard-drive") {
                SettingsNotice(
                    "The caches live on the machine running the backend, and are not managed " +
                        "from here.",
                )
            }
            return@Column
        }

        SettingsCard(
            title = "On this device",
            iconName = "lucide:hard-drive",
            description = "Measured when this page opened.",
        ) {
            when (val state = usage) {
                StorageUsageState.Loading -> SettingsNotice("Measuring…")

                is StorageUsageState.Failed -> SettingsNotice(state.message, isError = true)

                is StorageUsageState.Ready -> Column {
                    SettingRows(
                        *state.usage.entries.map { entry ->
                            @Composable {
                                CacheRow(
                                    entry = entry,
                                    armed = armed == entry.kind,
                                    onArm = { armed = if (armed == entry.kind) null else entry.kind },
                                    onClear = {
                                        armed = null
                                        scope.launch {
                                            val result = storage.clear(entry.kind)
                                            lastResult = buildString {
                                                append("Freed ")
                                                append(formatUpdateBytes(result.freedBytes))
                                                if (result.keptInUse > 0) {
                                                    append(" — ")
                                                    append(result.keptInUse)
                                                    append(" kept, still playing")
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }.toTypedArray(),
                    )
                    TotalRow(
                        totalBytes = state.usage.totalBytes,
                        freeBytes = state.usage.freeDiskBytes,
                        note = lastResult,
                    )
                }
            }
        }

        SettingsCard(
            title = "Keeping downloads",
            iconName = "lucide:trash",
            description = "Applies to this device only — these never travel to your other devices.",
        ) {
            SettingRows(
                {
                    SettingChoice(
                        title = "Keep at most",
                        description = "Once downloads pass this, the ones you watched longest " +
                            "ago are removed first.",
                        options = withCurrent(CacheLimitChoices, policy.limitBytes, 0)
                            .map { it.toString() to cacheLimitLabel(it) },
                        selected = policy.limitBytes.toString(),
                        onSelect = { value ->
                            scope.launch {
                                storage.setPolicy(policy.copy(limitBytes = value.toLong()))
                            }
                        },
                    )
                },
                {
                    SettingChoice(
                        title = "Download ahead",
                        description = "How far past what you are watching a torrent may run. " +
                            "Whole file keeps fetching the rest of an episode after you stop.",
                        options = withCurrent(DownloadAheadChoices, policy.downloadAheadBytes, 0)
                            .map { it.toString() to downloadAheadLabel(it) },
                        selected = policy.downloadAheadBytes.toString(),
                        onSelect = { value ->
                            scope.launch {
                                storage.setPolicy(policy.copy(downloadAheadBytes = value.toLong()))
                            }
                        },
                    )
                },
                {
                    SettingChoice(
                        title = "Keep downloads for",
                        description = "Anything you have not played in this long is removed.",
                        options = withCurrent(CacheAgeChoices, policy.maxAgeDays, 0)
                            .map { it.toString() to cacheAgeLabel(it) },
                        selected = policy.maxAgeDays.toString(),
                        onSelect = { value ->
                            scope.launch {
                                storage.setPolicy(policy.copy(maxAgeDays = value.toInt()))
                            }
                        },
                    )
                },
                {
                    SettingToggle(
                        title = "Delete after watching",
                        description = "Removes a download a few minutes after you stop. Frees " +
                            "the most space; watching the same thing again downloads it again.",
                        checked = policy.deleteAfterWatching,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                storage.setPolicy(policy.copy(deleteAfterWatching = enabled))
                            }
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun CacheRow(
    entry: CacheEntry,
    armed: Boolean,
    onArm: () -> Unit,
    onClear: () -> Unit,
) {
    SettingRow(
        title = cacheKindLabel(entry.kind),
        description = cacheKindDescription(entry.kind),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatUpdateBytes(entry.bytes),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = cacheKindItems(entry.kind, entry.items),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (armed) {
                SecondaryButton(label = "Confirm", onClick = onClear, danger = true)
            } else if (entry.bytes > 0) {
                SettingsIconAction(icon = "lucide:trash", onClick = onArm, danger = true)
            }
        }
    }
}

@Composable
private fun TotalRow(totalBytes: Long, freeBytes: Long, note: String?) {
    SettingDivider(inset = false)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RowPadding, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Total",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
            )
            // Free space beside the total, because a number on its own says nothing about
            // whether it is a problem — 18 GiB is a lot on a laptop and nothing on a NAS.
            Text(
                text = if (freeBytes > 0) {
                    "${formatUpdateBytes(freeBytes)} free on this disk"
                } else {
                    "Free space unknown"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        note?.let { StatusPill(text = it) }
        Text(
            text = formatUpdateBytes(totalBytes),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
