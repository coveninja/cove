package com.coveninja.cove.ui.pages.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.ProfilesState
import com.coveninja.cove.shared.model.Profile
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.platform.hasPointerHover
import com.coveninja.cove.ui.state.LocalAppGraph
import kotlinx.coroutines.launch

/**
 * Profiles on this device.
 *
 * Each one is a separate library, watch history and set of preferences, which is
 * why switching is a deliberate act here rather than a menu item somewhere: the
 * whole app answers differently afterwards. The active one wears a ring that
 * travels with the switch, so the change is visible where it happened.
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
                            // Starts hidden and is flipped visible on first
                            // composition, which is what makes a newly added
                            // profile expand into the list — AnimatedVisibility
                            // that is born visible animates nothing. Removal is
                            // covered by the container's animateContentSize:
                            // a composable already gone from the tree cannot
                            // animate itself out.
                            val appear = remember {
                                MutableTransitionState(false).apply { targetState = true }
                            }
                            AnimatedVisibility(
                                visibleState = appear,
                                enter = expandVertically(tween(240)) + fadeIn(tween(240)),
                            ) {
                                Column {
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
                }
            }

            SettingDivider()
            Column(
                modifier = Modifier.padding(horizontal = RowPadding, vertical = 18.dp),
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
                        leadingIcon = "lucide:user-round",
                        onSubmit = create,
                    )
                    PrimaryButton(label = "Add", onClick = create, enabled = newName.isNotBlank())
                }
                AnimatedVisibility(
                    visible = error != null,
                    enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                    exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
                ) {
                    Text(
                        text = error.orEmpty(),
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

    val background by animateColorAsState(
        targetValue = when {
            active -> colors.tertiary.copy(alpha = 0.06f)
            hovered -> colors.onSurface.copy(alpha = 0.04f)
            else -> Color.Transparent
        },
        animationSpec = tween(160),
        label = "ProfileRowBackground",
    )
    // The avatar leans forward under the cursor: enough to say the whole row is
    // the target, not enough to be a bounce.
    val avatarScale by animateFloatAsState(
        targetValue = if (hovered && !active) 1.06f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "ProfileAvatarScale",
    )
    val ring by animateDpAsState(
        targetValue = if (active) 2.dp else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "ProfileActiveRing",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = !active,
                onClick = onActivate,
            )
            .padding(horizontal = RowPadding, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .border(ring, colors.tertiary.copy(alpha = 0.5f), CircleShape),
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .graphicsLayer {
                        scaleX = avatarScale
                        scaleY = avatarScale
                    }
                    .background(
                        if (active) colors.tertiary else colors.onSurface.copy(alpha = 0.10f),
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
            // The subtitle answers a different question per row, so it swaps
            // rather than being rewritten in place.
            AnimatedContent(
                targetState = when {
                    active -> "Active on this device"
                    profile.supabaseUid != null -> "Synced · tap to switch"
                    else -> "Tap to switch"
                },
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "ProfileSubtitle",
            ) { subtitle ->
                Text(
                    text = subtitle,
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        AnimatedVisibility(
            visible = active,
            enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.7f),
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.7f),
        ) {
            StatusPill("ACTIVE")
        }

        // Revealed on hover where there is a pointer, always present where there
        // is not — on a phone there is no hover to reveal them with.
        AnimatedVisibility(
            visible = hovered || !hasPointerHover,
            enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.85f),
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.85f),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingsIconAction(icon = "lucide:pen-line", onClick = onRename)
                if (deletable) {
                    SettingsIconAction(icon = "lucide:trash", onClick = onDelete, danger = true)
                }
            }
        }
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = RowPadding, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = "Profile name",
            modifier = Modifier.weight(1f),
            leadingIcon = "lucide:pen-line",
            onSubmit = onSave,
        )
        PrimaryButton(label = "Save", onClick = onSave, enabled = value.isNotBlank())
        SecondaryButton(label = "Cancel", onClick = onCancel)
    }
}
