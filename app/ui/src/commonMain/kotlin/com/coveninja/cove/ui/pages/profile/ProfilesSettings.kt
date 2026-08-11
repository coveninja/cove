package com.coveninja.cove.ui.pages.profile

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.ProfilesState
import com.coveninja.cove.shared.model.Profile
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.state.LocalAppGraph
import kotlinx.coroutines.launch

/**
 * Profiles on this device.
 *
 * Each one is a separate library, watch history and set of preferences, which is
 * why switching is a deliberate act here rather than a menu item somewhere: the
 * whole app answers differently afterwards.
 */
@Composable
fun ProfilesSettings(modifier: Modifier = Modifier) {
    val repository = LocalAppGraph.current.profiles
    val state by repository.profiles.collectAsState()
    val scope = rememberCoroutineScope()

    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun run(block: suspend () -> Unit) {
        scope.launch {
            error = runCatching { block() }.exceptionOrNull()?.message
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        SettingsCard(
            title = "Profiles",
            iconName = "lucide:users",
            description = "Separate libraries and preferences on this device.",
        ) {
            Column(modifier = Modifier.animateContentSize()) {
                when (val current = state) {
                    ProfilesState.Loading -> SettingsNotice("Loading profiles…")
                    is ProfilesState.Failed -> SettingsNotice(current.message, isError = true)
                    is ProfilesState.Ready -> current.profiles.forEach { profile ->
                        key(profile.id) {
                            SettingDivider()
                            if (renaming == profile.id) {
                                RenameRow(
                                    value = renameValue,
                                    onValueChange = { renameValue = it },
                                    onCancel = { renaming = null },
                                    onSave = {
                                        val name = renameValue.trim()
                                        if (name.isNotEmpty()) {
                                            run { repository.rename(profile.id, name) }
                                        }
                                        renaming = null
                                    },
                                )
                            } else {
                                ProfileRow(
                                    profile = profile,
                                    active = profile.id == current.activeProfileId,
                                    // The last profile cannot go: something has to be active.
                                    deletable = current.profiles.size > 1 &&
                                        profile.id != current.activeProfileId,
                                    onActivate = { run { repository.activate(profile.id) } },
                                    onRename = {
                                        renaming = profile.id
                                        renameValue = profile.name
                                    },
                                    onDelete = { run { repository.delete(profile.id) } },
                                )
                            }
                        }
                    }
                }
            }

            SettingDivider()
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingLabels(
                    title = "Add a profile",
                    description = "Starts empty — its own library and settings.",
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val create = {
                        val name = newName.trim()
                        if (name.isNotEmpty()) {
                            run { repository.create(name) }
                            newName = ""
                        }
                    }
                    SettingsTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = "Profile name",
                        modifier = Modifier.weight(1f),
                        onSubmit = create,
                    )
                    PrimaryButton(label = "Add", onClick = create, enabled = newName.isNotBlank())
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
}

@Composable
private fun ProfileRow(
    profile: Profile,
    active: Boolean,
    deletable: Boolean,
    onActivate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (hovered && !active) colors.onSurface.copy(alpha = 0.04f) else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = !active,
                onClick = onActivate,
            )
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    if (active) colors.tertiary else colors.onSurface.copy(alpha = 0.08f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = profile.name.trim().firstOrNull()?.uppercase() ?: "?",
                color = if (active) colors.onTertiary else colors.onSurfaceVariant,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = profile.name,
                color = colors.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    active -> "Active on this device"
                    profile.supabaseUid != null -> "Synced"
                    else -> "Tap to switch"
                },
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (active) StatusPill("ACTIVE")
        SettingsIconAction(icon = "lucide:pen-line", onClick = onRename)
        if (deletable) SettingsIconAction(icon = "lucide:trash", onClick = onDelete, danger = true)
    }
}

@Composable
private fun RenameRow(
    value: String,
    onValueChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = "Profile name",
            modifier = Modifier.weight(1f),
            onSubmit = onSave,
        )
        PrimaryButton(label = "Save", onClick = onSave, enabled = value.isNotBlank())
        SecondaryButton(label = "Cancel", onClick = onCancel)
    }
}
