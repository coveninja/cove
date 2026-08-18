package com.coveninja.cove.ui.onboarding.steps

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.ProfilesState
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.onboarding.OnboardingController
import com.coveninja.cove.ui.onboarding.emblemFor
import com.coveninja.cove.ui.pages.profile.SettingsTextField
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.LocalMotionPolicy

/**
 * Naming this profile.
 *
 * The interaction *is* the emblem: it re-tints and re-letters live as the name is typed, which
 * turns a text field into something with a visible result. That is also why the emblem is
 * derived from the name rather than picked from a grid of avatars — a colour that follows what
 * you type is a reward, whereas a colour you have to choose is one more decision on a screen
 * that already asked for one.
 *
 * Pre-filled from the store rather than left blank: a device with an existing profile has a
 * name worth keeping, and clearing it would read as though the flow had forgotten it.
 */
@Composable
internal fun ProfileStep(
    controller: OnboardingController,
    modifier: Modifier = Modifier,
) {
    val profiles by LocalAppGraph.current.profiles.profiles.collectAsState()
    val reducedMotion = LocalMotionPolicy.current.reducedMotion

    val storedName = remember(profiles) {
        (profiles as? ProfilesState.Ready)?.let { ready ->
            ready.profiles.firstOrNull { it.id == ready.activeProfileId }?.name
        }.orEmpty()
    }
    // Only seeds an untouched draft. Re-applying it on every recomposition would overwrite
    // whatever the viewer had just typed the moment the profile flow emitted anything.
    LaunchedEffect(storedName) {
        if (controller.draft.profileName.isEmpty() && storedName.isNotEmpty()) {
            controller.update { copy(profileName = storedName) }
        }
    }

    val emblem = emblemFor(controller.draft.profileName)
    val emblemColor by animateColorAsState(
        targetValue = emblem.color,
        animationSpec = if (reducedMotion) snap() else tween(320),
        label = "OnboardingEmblemColor",
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(74.dp)
                    .clip(CircleShape)
                    .background(emblemColor),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = emblem.initial,
                    transitionSpec = {
                        if (reducedMotion) {
                            fadeIn(snap()) togetherWith fadeOut(snap())
                        } else {
                            (scaleIn(spring(dampingRatio = 0.45f)) + fadeIn(tween(140)))
                                .togetherWith(scaleOut(tween(100)) + fadeOut(tween(100)))
                        }
                    },
                    label = "OnboardingEmblemInitial",
                ) { initial ->
                    Text(
                        text = initial,
                        // Dark on a bright emblem, matching how the accent is treated
                        // everywhere else in the app.
                        color = CoveColors.Brand.OnAccent,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsTextField(
                    value = controller.draft.profileName,
                    onValueChange = { name -> controller.update { copy(profileName = name) } },
                    placeholder = "Your name",
                    leadingIcon = "lucide:user-round",
                    imeAction = ImeAction.Done,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Add more profiles any time from Settings. Each one keeps its own " +
                        "library, progress and recommendations.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
