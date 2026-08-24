package com.coveninja.cove.ui.pages.profile

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.AddonRepository
import com.coveninja.cove.shared.data.AddonsState
import com.coveninja.cove.shared.model.Addon
import com.coveninja.cove.shared.model.AddonCatalogDescriptor
import com.coveninja.cove.shared.model.AddonKind
import com.coveninja.cove.shared.model.NuvioRepoSummary
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.state.LocalAppGraph
import kotlinx.coroutines.launch

/**
 * Addon and scraper management.
 *
 * This is what makes playback work at all: nothing is seeded, so until a provider
 * addon is added here every Watch ends in "no sources found".
 */
@Composable
fun AddonSettings(modifier: Modifier = Modifier) {
    val repository = LocalAppGraph.current.addons
    val state by repository.state.collectAsState()
    val lastError by repository.lastError.collectAsState()
    val scope = rememberCoroutineScope()

    val ready = state as? AddonsState.Ready
    val managed = ready?.addons.orEmpty().filter(Addon::managed)
    val sharedFrom = ready?.sharing?.primaryName.orEmpty().ifBlank { "the primary profile" }

    Column(modifier = modifier.fillMaxWidth()) {
        // Inherited addons get their own card rather than a lock badge in the
        // list below: what can and cannot be changed here is the whole point,
        // and a mixed list makes that a per-row detail the eye has to check.
        if (managed.isNotEmpty()) {
            SettingsCard(
                title = "Managed by $sharedFrom",
                iconName = "lucide:lock",
                description = "Shared with every profile. Only $sharedFrom can change them.",
            ) {
                Column(modifier = Modifier.animateContentSize()) {
                    managed.forEach { addon ->
                        key(addon.id) {
                            SettingDivider()
                            AddonRow(
                                addon = addon,
                                onToggle = {},
                                onRefresh = {},
                                onRemove = {},
                                onToggleCatalog = { _, _ -> },
                                locked = true,
                            )
                        }
                    }
                }
            }
        }

        SettingsCard(
            title = if (managed.isEmpty()) "Provider addons" else "Your addons",
            iconName = "lucide:blocks",
            description = "Addons that supply streams.",
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingLabels(
                    title = "Add an addon",
                    description = "Paste a manifest URL"
                )
                UrlInput(
                    placeholder = "https://…/manifest.json",
                    onSubmit = { url -> scope.launch { repository.addAddon(url) } },
                )
                lastError?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Column(modifier = Modifier.animateContentSize()) {
                when (val current = state) {
                    AddonsState.Loading -> SettingsNotice("Loading addons…")
                    is AddonsState.Failed -> SettingsNotice(current.message, isError = true)
                    is AddonsState.Ready -> if (current.addons.none { !it.managed }) {
                        SettingsNotice(
                            if (managed.isEmpty()) {
                                "No addons yet. Paste a manifest URL above to get streams."
                            } else {
                                // Streams already work here, so the stock copy would
                                // read as a fault rather than as an empty own-list.
                                "No addons of your own yet. Anything you add here is " +
                                    "yours alone, alongside the shared ones above."
                            },
                        )
                    } else {
                        // Keyed by id: matched by position instead, a list that
                        // reorders leaves the switch you just flipped sitting on a
                        // different addon's row.
                        current.addons.filterNot(Addon::managed).forEach { addon ->
                            key(addon.id) {
                                SettingDivider()
                                AddonRow(
                                    addon = addon,
                                    onToggle = { enabled ->
                                        scope.launch { repository.setAddonEnabled(addon.id, enabled) }
                                    },
                                    onRefresh = { scope.launch { repository.refreshAddon(addon.id) } },
                                    onRemove = { scope.launch { repository.removeAddon(addon.id) } },
                                    onToggleCatalog = { catalogKey, enabled ->
                                        scope.launch {
                                            repository.setCatalogEnabled(
                                                addon.id,
                                                catalogKey,
                                                enabled,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (repository.supportsNuvio) {
            SettingsCard(
                title = "Nuvio scrapers",
                iconName = "lucide:triangle-alert",
                description = "Community JS Scrapers",
                modifier = Modifier.padding(top = 14.dp),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Not boilerplate: these scrapers are community JavaScript executed
                    // in-process. Saying so plainly is the point of this block.
                    Text(
                        text = "Scrapers are community-written JavaScript that Cove runs " +
                            "on your machine in a sandbox. Only add repositories you trust.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SettingLabels(
                        title = "Add a scraper repository",
                        description = "A GitHub repository URL.",
                    )
                    UrlInput(
                        placeholder = "https://github.com/owner/repo",
                        onSubmit = { url -> scope.launch { repository.addNuvioRepo(url) } },
                    )
                }

                Column(modifier = Modifier.animateContentSize()) {
                    val ready = state as? AddonsState.Ready
                    if (ready == null || ready.nuvioRepos.isEmpty()) {
                        SettingsNotice("No scraper repositories.")
                    } else {
                        ready.nuvioRepos.forEach { repo ->
                            key(repo.id) {
                                SettingDivider()
                                NuvioRepoRow(
                                    repo = repo,
                                    onToggleRepo = { enabled ->
                                        scope.launch { repository.setNuvioRepoEnabled(repo.id, enabled) }
                                    },
                                    onRemove = { scope.launch { repository.removeNuvioRepo(repo.id) } },
                                    onToggleScraper = { scraperId, enabled ->
                                        scope.launch {
                                            repository.setNuvioScraperEnabled(repo.id, scraperId, enabled)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddonRow(
    addon: Addon,
    onToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    onToggleCatalog: (String, Boolean) -> Unit,
    locked: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        AddonRowBody(addon, onToggle, onRefresh, onRemove, locked)
        // Below the body rather than inside it: the body has a compact and a wide
        // arrangement, and the catalog list is the same either way.
        if (addon.catalogs.isNotEmpty()) {
            AddonCatalogList(addon.catalogs, locked, onToggleCatalog)
        }
    }
}

@Composable
private fun AddonRowBody(
    addon: Addon,
    onToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
    locked: Boolean,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < 520.dp) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = addon.displayName,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (locked) {
                        LockedState(addon.enabled)
                    } else {
                        Switch(checked = addon.enabled, onCheckedChange = onToggle)
                    }
                }
                KindBadge(addon.kind)
                Text(
                    text = addon.manifest.description.ifBlank { addon.url },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (addon.source == "stremio" && !locked) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    ) {
                        SettingsIconAction(icon = "lucide:refresh-cw", onClick = onRefresh)
                        SettingsIconAction(icon = "lucide:trash", onClick = onRemove, danger = true)
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = addon.displayName,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        KindBadge(addon.kind)
                    }
                    Text(
                        text = addon.manifest.description.ifBlank { addon.url },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (addon.source == "stremio" && !locked) {
                    SettingsIconAction(icon = "lucide:refresh-cw", onClick = onRefresh)
                    SettingsIconAction(icon = "lucide:trash", onClick = onRemove, danger = true)
                }
                if (locked) {
                    LockedState(addon.enabled)
                } else {
                    Switch(checked = addon.enabled, onCheckedChange = onToggle)
                }
            }
        }
    }
}

/**
 * The catalogs one addon offers, each with its own switch.
 *
 * Separate from the addon's own switch because they answer different questions: the addon
 * switch decides whether it resolves streams at all, and these decide which of its rows
 * appear on Home and Explore. An addon can easily offer half a dozen catalogs, and every
 * one drawn costs a metadata request per title, so being able to keep the one that is
 * wanted and drop the rest is what makes a busy addon usable.
 *
 * A [locked] addon is one inherited from the primary profile. Its catalogs show their
 * state and no control: the flag is stored against the addon's owner, and writing to it
 * from here would silently give this profile a private copy of the primary's addon.
 */
@Composable
private fun AddonCatalogList(
    catalogs: List<AddonCatalogDescriptor>,
    locked: Boolean,
    onToggle: (String, Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 18.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Catalogs",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        catalogs.forEach { catalog ->
            key(catalog.key) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = catalog.name.ifBlank { catalog.catalogId },
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (catalog.type == "series") "Series" else "Films",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (locked) {
                        LockedState(catalog.enabled)
                    } else {
                        Switch(
                            checked = catalog.enabled,
                            onCheckedChange = { onToggle(catalog.key, it) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * What a managed addon shows where its switch would be. A disabled Switch would
 * be the obvious substitute, but it still reads as a control that failed to
 * respond; a label states the primary's setting without inviting the tap.
 */
@Composable
private fun LockedState(enabled: Boolean) {
    Text(
        text = if (enabled) "On" else "Off",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun NuvioRepoRow(
    repo: NuvioRepoSummary,
    onToggleRepo: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onToggleScraper: (String, Boolean) -> Unit,
) {
    Column(modifier = Modifier.padding(18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = repo.displayName,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = repo.fetchError.ifBlank {
                        "${repo.scrapers.size} scraper${if (repo.scrapers.size == 1) "" else "s"}"
                    },
                    color = if (repo.fetchError.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            SettingsIconAction(icon = "lucide:trash", onClick = onRemove, danger = true)
            Switch(checked = repo.enabled, onCheckedChange = onToggleRepo)
        }

        // Each scraper is enabled individually — enabling the repository alone
        // runs nothing.
        repo.scrapers.forEach { scraper ->
            key(scraper.id) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = scraper.name.ifBlank { scraper.id },
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        scraper.codeError.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    Switch(
                        checked = scraper.enabled,
                        onCheckedChange = { onToggleScraper(scraper.id, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun KindBadge(kind: AddonKind) {
    val label = when (kind) {
        AddonKind.Provider -> "Streams"
        AddonKind.Subtitle -> "Subtitles"
        AddonKind.Timestamps -> "Timestamps"
    }
    Box(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Clears itself on submit so the next URL starts from empty. */
@Composable
private fun UrlInput(placeholder: String, onSubmit: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    val submit = {
        val trimmed = value.trim()
        if (trimmed.isNotEmpty()) {
            onSubmit(trimmed)
            value = ""
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsTextField(
            value = value,
            onValueChange = { value = it },
            placeholder = placeholder,
            modifier = Modifier.weight(1f),
            keyboardType = KeyboardType.Uri,
            onSubmit = submit,
        )
        PrimaryButton(label = "Add", onClick = submit, enabled = value.isNotBlank())
    }
}
