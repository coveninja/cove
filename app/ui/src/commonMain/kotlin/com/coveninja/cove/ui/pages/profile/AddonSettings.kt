package com.coveninja.cove.ui.pages.profile

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.AddonRepository
import com.coveninja.cove.shared.data.AddonsState
import com.coveninja.cove.shared.model.Addon
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

    Column(modifier = modifier.fillMaxWidth()) {
        SettingsCard(
            title = "Provider addons",
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
                    is AddonsState.Ready -> if (current.addons.isEmpty()) {
                        SettingsNotice("No addons yet. Paste a manifest URL above to get streams.")
                    } else {
                        // Keyed by id: matched by position instead, a list that
                        // reorders leaves the switch you just flipped sitting on a
                        // different addon's row.
                        current.addons.forEach { addon ->
                            key(addon.id) {
                                SettingDivider()
                                AddonRow(
                                    addon = addon,
                                    onToggle = { enabled ->
                                        scope.launch { repository.setAddonEnabled(addon.id, enabled) }
                                    },
                                    onRefresh = { scope.launch { repository.refreshAddon(addon.id) } },
                                    onRemove = { scope.launch { repository.removeAddon(addon.id) } },
                                )
                            }
                        }
                    }
                }
            }
        }

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

@Composable
private fun AddonRow(
    addon: Addon,
    onToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
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
        IconAction(icon = "lucide:refresh-cw", onClick = onRefresh)
        IconAction(icon = "lucide:trash", onClick = onRemove, danger = true)
        Switch(checked = addon.enabled, onCheckedChange = onToggle)
    }
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
            IconAction(icon = "lucide:trash", onClick = onRemove, danger = true)
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
        )
    }
}

@Composable
private fun IconAction(icon: String, onClick: () -> Unit, danger: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()

    val accent = if (danger) colors.error else colors.onSurface
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else if (hovered) 1.06f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "IconActionScale",
    )
    val container by animateColorAsState(
        targetValue = accent.copy(alpha = if (hovered) 0.14f else 0.06f),
        animationSpec = tween(140),
        label = "IconActionContainer",
    )

    Box(
        modifier = Modifier
            .size(34.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(container, RoundedCornerShape(9.dp))
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        IconifyIcon(
            icon = icon,
            modifier = Modifier.size(15.dp),
            tint = if (hovered) accent else colors.onSurfaceVariant,
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
        var focused by remember { mutableStateOf(false) }
        val fieldBorder by animateColorAsState(
            targetValue = if (focused) {
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            },
            animationSpec = tween(160),
            label = "UrlFieldBorder",
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .background(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                    RoundedCornerShape(11.dp),
                )
                .border(1.dp, fieldBorder, RoundedCornerShape(11.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    inner()
                },
            )
        }

        val buttonInteraction = remember { MutableInteractionSource() }
        val buttonHovered by buttonInteraction.collectIsHoveredAsState()
        val buttonPressed by buttonInteraction.collectIsPressedAsState()
        val buttonScale by animateFloatAsState(
            targetValue = when {
                buttonPressed -> 0.95f
                buttonHovered && value.isNotBlank() -> 1.04f
                else -> 1f
            },
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "AddButtonScale",
        )
        // Dimmed until there is something to submit.
        val buttonColor by animateColorAsState(
            targetValue = MaterialTheme.colorScheme.tertiary.copy(
                alpha = if (value.isBlank()) 0.45f else 1f,
            ),
            animationSpec = tween(160),
            label = "AddButtonColor",
        )

        Box(
            modifier = Modifier
                .height(42.dp)
                .graphicsLayer {
                    scaleX = buttonScale
                    scaleY = buttonScale
                }
                .background(buttonColor, RoundedCornerShape(11.dp))
                .hoverable(buttonInteraction)
                .clickable(
                    interactionSource = buttonInteraction,
                    indication = null,
                    onClick = submit,
                )
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Add",
                color = MaterialTheme.colorScheme.onTertiary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
