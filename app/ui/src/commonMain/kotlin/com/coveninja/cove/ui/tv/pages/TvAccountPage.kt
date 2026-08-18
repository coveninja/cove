package com.coveninja.cove.ui.tv.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.AccountState
import com.coveninja.cove.shared.data.AuthOutcome
import com.coveninja.cove.shared.data.SyncStatus
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.pages.profile.AuthMode
import com.coveninja.cove.ui.pages.profile.SyncTone
import com.coveninja.cove.ui.pages.profile.canSubmitAuth
import com.coveninja.cove.ui.pages.profile.syncDetail
import com.coveninja.cove.ui.pages.profile.syncHeadline
import com.coveninja.cove.ui.pages.profile.syncTone
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.tv.components.TvButton
import com.coveninja.cove.ui.tv.components.TvTextField
import com.coveninja.cove.ui.tv.focus.tvFocusGroup
import kotlin.time.Clock
import kotlinx.coroutines.launch

/**
 * Signing a television in to a Cove account.
 *
 * Drawn as a section rather than a page: it shares the Profile destination with the settings
 * below it, and one scroll container down the whole destination is what lets the D-pad walk
 * from the sync card into the playback rows without meeting a seam between two scrollers.
 *
 * This is the screen that makes a television useful rather than merely browsable. A fresh
 * profile has no provider addons at all, and there is no realistic way to paste a manifest URL
 * with a remote — but the Android runtime is a full addon-sync participant, so signing in pulls
 * across whatever was configured on a desktop. One email address is the entire setup cost.
 *
 * The emailed code is the default path for exactly that reason. A password is a long string of
 * mixed case and symbols entered on an on-screen keyboard one arrow-press per character; a code
 * is six digits, and the address is usually the only thing that has to be typed at all.
 */
@Composable
internal fun TvAccountSection(modifier: Modifier = Modifier) {
    val graph = LocalAppGraph.current
    val account by graph.account.account.collectAsState()
    val sync by graph.account.syncStatus.collectAsState()

    Box(modifier = modifier) {
        Column(modifier = Modifier.widthIn(max = 720.dp)) {
            when (val state = account) {
                AccountState.Loading -> Text(
                    text = "Checking your account…",
                    style = MaterialTheme.typography.titleLarge,
                    color = CoveColors.Neutral.Muted,
                )

                is AccountState.Unavailable -> TvAccountUnavailable(state.reason)

                is AccountState.SignedIn -> TvSignedIn(email = state.email, sync = sync)

                AccountState.SignedOut -> TvSignInForm()
            }
        }
    }
}

@Composable
private fun TvAccountUnavailable(reason: String) {
    Column {
        Text(
            text = "Sync is not available",
            style = MaterialTheme.typography.headlineMedium,
            color = CoveColors.Neutral.Text,
        )
        Text(
            text = reason,
            style = MaterialTheme.typography.bodyLarge,
            color = CoveColors.Neutral.Muted,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = "Providers have to be added on a desktop and this device cannot reach an " +
                "account to copy them from.",
            style = MaterialTheme.typography.bodyMedium,
            color = CoveColors.Neutral.MutedDim,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}

@Composable
private fun TvSignedIn(email: String, sync: SyncStatus) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val now = remember(sync) { Clock.System.now() }
    val tone = syncTone(sync)

    Column {
        Text(
            text = "Signed in",
            style = MaterialTheme.typography.headlineMedium,
            color = CoveColors.Neutral.Text,
        )
        Text(
            text = email,
            style = MaterialTheme.typography.bodyLarge,
            color = CoveColors.Neutral.Muted,
            modifier = Modifier.padding(top = 6.dp),
        )

        Column(
            modifier = Modifier
                .padding(top = 24.dp)
                .fillMaxWidth()
                .background(CoveColors.Neutral.Surface, RoundedCornerShape(16.dp))
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(tone.color(), CircleShape),
                )
                Spacer(modifier = Modifier.size(12.dp))
                Text(
                    text = syncHeadline(sync),
                    style = MaterialTheme.typography.titleLarge,
                    color = CoveColors.Neutral.Text,
                )
            }
            Text(
                text = syncDetail(sync, now),
                style = MaterialTheme.typography.bodyMedium,
                color = CoveColors.Neutral.MutedDim,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Row(
            modifier = Modifier
                .padding(top = 24.dp)
                .tvFocusGroup(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TvButton(
                label = "Sync now",
                onClick = { scope.launch { graph.account.syncNow() } },
                icon = "lucide:refresh-cw",
                primary = true,
                enabled = !sync.running,
            )
            TvButton(
                label = "Sign out",
                onClick = { scope.launch { graph.account.signOut() } },
                icon = "lucide:key",
            )
        }
    }
}

@Composable
private fun TvSignInForm() {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()

    // The emailed code first: it is the only path that does not require typing a password on a
    // television keyboard.
    var mode by remember { mutableStateOf(AuthMode.Code) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var profileName by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var awaitingToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val submit = {
        if (!busy) {
            busy = true
            message = null
            scope.launch {
                val outcome = when {
                    awaitingToken && mode == AuthMode.Register ->
                        graph.account.confirmRegistration(email, token, password, profileName)

                    awaitingToken -> graph.account.verifyOtp(email, token)
                    mode == AuthMode.SignIn -> graph.account.signIn(email, password)
                    mode == AuthMode.Register ->
                        graph.account.register(email, password, profileName)

                    else -> graph.account.sendOtp(email)
                }
                busy = false
                when (outcome) {
                    AuthOutcome.Success -> {
                        awaitingToken = false
                        token = ""
                        message = null
                    }
                    // Both the emailed code and a new registration land here: the account
                    // exists but the address has not been proved yet, and the next thing the
                    // form needs is the code rather than anything already typed.
                    AuthOutcome.ConfirmationRequired -> {
                        awaitingToken = true
                        message = "Check $email for a code, then enter it below."
                    }

                    is AuthOutcome.Failure -> message = outcome.message
                }
            }
        }
    }

    Column {
        Text(
            text = "Sign in to Cove",
            style = MaterialTheme.typography.headlineMedium,
            color = CoveColors.Neutral.Text,
        )
        Text(
            text = "Your library, your progress and the providers you set up elsewhere all " +
                "follow your account onto this television.",
            style = MaterialTheme.typography.bodyLarge,
            color = CoveColors.Neutral.Muted,
            modifier = Modifier.padding(top = 8.dp),
        )

        Row(
            modifier = Modifier
                .padding(top = 22.dp)
                .tvFocusGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Deliberately not `entries` order. The code path leads on a television because it
            // is the one that avoids the on-screen keyboard; the phone's form can keep its own
            // ordering, where typing a password costs nothing.
            listOf(AuthMode.Code, AuthMode.SignIn, AuthMode.Register).forEach { entry ->
                TvButton(
                    label = entry.label,
                    onClick = {
                        mode = entry
                        awaitingToken = false
                        token = ""
                        message = null
                    },
                    selected = entry == mode,
                )
            }
        }

        TvTextField(
            value = email,
            onValueChange = { email = it },
            label = "Email",
            placeholder = "you@example.com",
            keyboardType = KeyboardType.Email,
            modifier = Modifier.padding(top = 20.dp),
        )

        if (mode == AuthMode.Register) {
            TvTextField(
                value = profileName,
                onValueChange = { profileName = it },
                label = "Profile name",
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        if (mode != AuthMode.Code) {
            TvTextField(
                value = password,
                onValueChange = { password = it },
                label = "Password",
                secret = true,
                keyboardType = KeyboardType.Password,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        if (awaitingToken) {
            TvTextField(
                value = token,
                onValueChange = { token = it },
                label = "Code from your email",
                keyboardType = KeyboardType.Number,
                onSubmit = { submit() },
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        message?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = CoveColors.Status.Warning,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        Box(modifier = Modifier.padding(top = 22.dp)) {
            TvButton(
                label = when {
                    busy -> "Working…"
                    awaitingToken -> "Verify code"
                    mode == AuthMode.Code -> "Email me a code"
                    else -> mode.label
                },
                onClick = submit,
                icon = "lucide:mail",
                primary = true,
                enabled = !busy && canSubmitAuth(
                    mode = mode,
                    awaitingToken = awaitingToken,
                    email = email,
                    password = password,
                    profileName = profileName,
                    token = token,
                ),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

private fun SyncTone.color() = when (this) {
    SyncTone.Settled -> CoveColors.Brand.Accent
    SyncTone.Working -> CoveColors.Status.Info
    SyncTone.Attention -> CoveColors.Status.Warning
    SyncTone.Idle -> CoveColors.Neutral.MutedDim
}
