package com.coveninja.cove.ui.onboarding.steps

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.coveninja.cove.shared.data.AccountState
import com.coveninja.cove.shared.data.AuthOutcome
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.onboarding.OnboardingController
import com.coveninja.cove.ui.onboarding.OnboardingGhostButton
import com.coveninja.cove.ui.onboarding.OnboardingPrimaryButton
import com.coveninja.cove.ui.onboarding.normalizedProfileName
import com.coveninja.cove.ui.pages.common.ChoicePill
import com.coveninja.cove.ui.pages.profile.AuthMode
import com.coveninja.cove.ui.pages.profile.SettingsTextField
import com.coveninja.cove.ui.pages.profile.canSubmitAuth
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.LocalMotionPolicy
import kotlinx.coroutines.launch

/**
 * Signing in, optionally.
 *
 * The form is the settings page's form: [AuthMode] and [canSubmitAuth] are imported rather than
 * re-derived, so the three sign-in paths behave identically in both places and a fix to either
 * lands in both. What differs is the framing — here it is an offer with a visible way past it,
 * not a settings card.
 *
 * The profile name from step two pre-fills registration. Asking for it twice in the same flow
 * is the kind of thing that makes an onboarding feel like paperwork.
 */
@Composable
internal fun SyncStep(
    controller: OnboardingController,
    modifier: Modifier = Modifier,
) {
    val repository = LocalAppGraph.current.account
    val account by repository.account.collectAsState()
    val scope = rememberCoroutineScope()
    val reducedMotion = LocalMotionPolicy.current.reducedMotion

    var mode by remember { mutableStateOf(AuthMode.SignIn) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var awaitingToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val profileName = normalizedProfileName(controller.draft.profileName).orEmpty()

    when (val current = account) {
        is AccountState.SignedIn -> {
            SyncSignedIn(email = current.email)
            return
        }
        is AccountState.Unavailable -> {
            SyncUnavailable(reason = current.reason)
            return
        }
        else -> Unit
    }

    val canSubmit = canSubmitAuth(
        mode = mode,
        awaitingToken = awaitingToken,
        email = email,
        password = password,
        profileName = profileName.ifEmpty { email.substringBefore('@') },
        token = token,
    )

    val submit: () -> Unit = submit@{
        if (!canSubmit || busy) return@submit
        busy = true
        message = null
        scope.launch {
            // A registration with no name from step two falls back to the local part of the
            // address, which is the only other thing the viewer has told us.
            val name = profileName.ifEmpty { email.substringBefore('@') }
            val outcome = when {
                awaitingToken && mode == AuthMode.Register ->
                    repository.confirmRegistration(email, token, password, name)
                awaitingToken -> repository.verifyOtp(email, token)
                mode == AuthMode.SignIn -> repository.signIn(email, password)
                mode == AuthMode.Register -> repository.register(email, password, name)
                else -> repository.sendOtp(email)
            }
            busy = false
            when (outcome) {
                AuthOutcome.Success -> {
                    awaitingToken = false
                    controller.update { copy(signedIn = true) }
                }
                AuthOutcome.ConfirmationRequired -> {
                    awaitingToken = true
                    message = "Check your email — we've sent a code to confirm it's you."
                }
                is AuthOutcome.Failure -> message = outcome.message
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuthMode.entries.forEach { candidate ->
                ChoicePill(
                    label = candidate.label,
                    selected = mode == candidate,
                    onClick = {
                        mode = candidate
                        awaitingToken = false
                        message = null
                    },
                )
            }
        }

        SettingsTextField(
            value = email,
            onValueChange = { email = it },
            placeholder = "you@example.com",
            leadingIcon = "lucide:mail",
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
            enabled = !awaitingToken,
            modifier = Modifier.fillMaxWidth(),
        )

        AnimatedVisibility(
            visible = mode != AuthMode.Code && !awaitingToken,
            enter = if (reducedMotion) fadeIn(snap()) else fadeIn(tween(160)) + expandVertically(),
            exit = if (reducedMotion) fadeOut(snap()) else fadeOut(tween(120)) + shrinkVertically(),
        ) {
            SettingsTextField(
                value = password,
                onValueChange = { password = it },
                placeholder = "Password",
                leadingIcon = "lucide:lock",
                masked = true,
                imeAction = ImeAction.Done,
                modifier = Modifier.fillMaxWidth(),
                onSubmit = submit,
            )
        }

        AnimatedVisibility(
            visible = awaitingToken,
            enter = if (reducedMotion) fadeIn(snap()) else fadeIn(tween(160)) + expandVertically(),
            exit = if (reducedMotion) fadeOut(snap()) else fadeOut(tween(120)) + shrinkVertically(),
        ) {
            SettingsTextField(
                value = token,
                onValueChange = { token = it },
                placeholder = "Code from your email",
                leadingIcon = "lucide:key",
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
                modifier = Modifier.fillMaxWidth(),
                onSubmit = submit,
            )
        }

        message?.let { text ->
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OnboardingPrimaryButton(
                label = if (awaitingToken) "Confirm" else mode.label,
                onClick = submit,
                enabled = canSubmit,
                busy = busy,
            )
            if (awaitingToken) {
                OnboardingGhostButton(
                    label = "Start over",
                    onClick = {
                        awaitingToken = false
                        token = ""
                        message = null
                    },
                )
            }
        }

        Text(
            text = "Skipping is fine. Everything stays on this device, and you can sign in " +
                "later from Settings → Account without losing any of it.",
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SyncSignedIn(email: String, modifier: Modifier = Modifier) {
    SyncBanner(
        icon = "lucide:cloud-check",
        tone = CoveColors.Brand.Accent,
        title = "Signed in as $email",
        body = "Your library, progress and settings will follow you to your other devices.",
        modifier = modifier,
    )
}

/**
 * A build with no account backend says so, rather than showing a form that cannot succeed.
 *
 * The step is dropped entirely in this case by `stepsFor`, so this is the narrow window where
 * the account repository becomes unavailable *while* the flow is open — worth rendering
 * honestly rather than leaving as a blank panel.
 */
@Composable
private fun SyncUnavailable(reason: String, modifier: Modifier = Modifier) {
    SyncBanner(
        icon = "lucide:cloud-off",
        tone = CoveColors.Neutral.Muted,
        title = "Sync isn't available here",
        body = reason,
        modifier = modifier,
    )
}

@Composable
private fun SyncBanner(
    icon: String,
    tone: androidx.compose.ui.graphics.Color,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(tone.copy(alpha = 0.10f))
            .padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tone.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            IconifyIcon(icon = icon, tint = tone, modifier = Modifier.size(18.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
