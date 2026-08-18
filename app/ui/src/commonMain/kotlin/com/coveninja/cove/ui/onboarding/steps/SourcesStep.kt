package com.coveninja.cove.ui.onboarding.steps

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.AddonsState
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.onboarding.OnboardingController
import com.coveninja.cove.ui.onboarding.OnboardingGhostButton
import com.coveninja.cove.ui.onboarding.manifestUrlProblem
import com.coveninja.cove.ui.onboarding.manifestUrlSubmittable
import com.coveninja.cove.ui.pages.profile.SettingsTextField
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.LocalMotionPolicy
import kotlinx.coroutines.launch

/**
 * The one step whose absence leaves a broken app.
 *
 * A fresh profile has no provider addons, which means the Watch button fails on every title
 * with a message about having no sources — accurate, and completely opaque to someone who has
 * never been told Cove does not host anything. Saying it here, once, before it can be hit, is
 * the entire point.
 *
 * The step writes as the viewer works rather than at commit, because the answer is the whole
 * value: a manifest URL is only known to be good once the repository has fetched it, and
 * finding that out three screens later would be useless.
 */
@Composable
internal fun SourcesStep(
    controller: OnboardingController,
    modifier: Modifier = Modifier,
) {
    val repository = LocalAppGraph.current.addons
    val addonsState by repository.state.collectAsState()
    val repositoryError by repository.lastError.collectAsState()
    val scope = rememberCoroutineScope()
    val reducedMotion = LocalMotionPolicy.current.reducedMotion

    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // Held separately from the repository's own error so a stale message from a previous
    // attempt cannot outlive the field being retyped.
    var attempted by remember { mutableStateOf(false) }

    val hint = remember(url) { manifestUrlProblem(url) }
    val submittable = manifestUrlSubmittable(url) && !busy

    val installed = remember(addonsState) {
        (addonsState as? AddonsState.Ready)?.addons?.map { it.manifest.name.ifBlank { it.url } }
            .orEmpty()
    }
    val unreachable = addonsState is AddonsState.Failed

    val submit: () -> Unit = submit@{
        if (!submittable) return@submit
        val candidate = url.trim()
        busy = true
        attempted = true
        scope.launch {
            repository.addAddon(candidate)
            busy = false
            // The repository reports a bad manifest through lastError rather than by throwing,
            // so success is "it added something and did not complain".
            if (repository.lastError.value == null) {
                controller.rememberAddon(candidate)
                url = ""
                attempted = false
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SourcesExplainer()

        if (unreachable) {
            SourcesNotice(
                icon = "lucide:triangle-alert",
                text = "This device can't reach its addon store right now. You can add " +
                    "sources later from Settings → Addons.",
                tone = CoveColors.Status.Warning,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        attempted = false
                    },
                    placeholder = "https://…/manifest.json",
                    leadingIcon = "lucide:blocks",
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onSubmit = submit,
                    trailing = {
                        AnimatedContent(
                            targetState = fieldStatus(busy, attempted, repositoryError, url),
                            transitionSpec = {
                                if (reducedMotion) {
                                    fadeIn(snap()) togetherWith fadeOut(snap())
                                } else {
                                    (scaleIn(spring(dampingRatio = 0.5f)) + fadeIn(tween(140)))
                                        .togetherWith(scaleOut(tween(90)) + fadeOut(tween(90)))
                                }
                            },
                            label = "SourcesFieldStatus",
                        ) { status ->
                            when (status) {
                                FieldStatus.Busy -> CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.tertiary,
                                    strokeWidth = 2.dp,
                                )
                                FieldStatus.Rejected -> IconifyIcon(
                                    icon = "lucide:triangle-alert",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(17.dp),
                                )
                                FieldStatus.Ready -> IconifyIcon(
                                    icon = "lucide:check",
                                    tint = CoveColors.Brand.Accent,
                                    modifier = Modifier.size(17.dp),
                                )
                                FieldStatus.Empty -> Box(modifier = Modifier.size(17.dp))
                            }
                        }
                    },
                )
                OnboardingGhostButton(label = "Add", icon = "lucide:plus", onClick = submit)
            }

            // The repository's message wins: it is the one that knows *why* a fetch failed,
            // and it only appears once something has actually been tried.
            val message = repositoryError?.takeIf { attempted } ?: hint?.takeIf { url.isNotEmpty() }
            AnimatedVisibility(
                visible = message != null,
                enter = if (reducedMotion) fadeIn(snap()) else fadeIn(tween(160)) + expandVertically(),
                exit = if (reducedMotion) fadeOut(snap()) else fadeOut(tween(120)) + shrinkVertically(),
            ) {
                Text(
                    text = message.orEmpty(),
                    color = if (repositoryError != null && attempted) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        AnimatedVisibility(
            visible = installed.isNotEmpty(),
            enter = if (reducedMotion) fadeIn(snap()) else fadeIn(tween(200)) + expandVertically(),
            exit = if (reducedMotion) fadeOut(snap()) else fadeOut(tween(140)) + shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(
                    text = "Installed",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    installed.forEach { name -> InstalledAddonChip(name) }
                }
            }
        }

        Text(
            text = "No sources yet? Skip this — Cove still browses, tracks and syncs " +
                "everything. Add a provider from Settings → Addons whenever you're ready.",
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SourcesExplainer(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(CoveColors.Brand.Accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            IconifyIcon(
                icon = "lucide:shield-check",
                tint = CoveColors.Brand.Accent,
                modifier = Modifier.size(17.dp),
            )
        }
        Text(
            text = "Cove doesn't host or index any media. Providers are addons you choose " +
                "and install yourself — paste a manifest URL below and Cove will ask it for " +
                "streams whenever you press play.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SourcesNotice(
    icon: String,
    text: String,
    tone: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(tone.copy(alpha = 0.10f))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconifyIcon(icon = icon, tint = tone, modifier = Modifier.size(17.dp))
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun InstalledAddonChip(name: String) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(CoveColors.Brand.AccentContainer)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconifyIcon(
            icon = "lucide:check",
            tint = CoveColors.Brand.Accent,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = name,
            color = CoveColors.Brand.Accent,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

private enum class FieldStatus { Empty, Busy, Rejected, Ready }

/**
 * What the trailing glyph should be.
 *
 * Pulled out as a value so the [AnimatedContent] has something stable to key on — passing the
 * four inputs directly would restart the transition every keystroke, and the check mark would
 * pop in again on every character typed after the URL became valid.
 */
private fun fieldStatus(
    busy: Boolean,
    attempted: Boolean,
    repositoryError: String?,
    url: String,
): FieldStatus = when {
    busy -> FieldStatus.Busy
    attempted && repositoryError != null -> FieldStatus.Rejected
    url.isBlank() -> FieldStatus.Empty
    manifestUrlSubmittable(url) -> FieldStatus.Ready
    else -> FieldStatus.Rejected
}
