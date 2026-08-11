package com.coveninja.cove.ui.pages.profile

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.AccountState
import com.coveninja.cove.shared.data.AuthOutcome
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.data.SyncStatus
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.pages.common.ChoicePill
import com.coveninja.cove.ui.pages.common.ChoicePillRow
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.rememberSettingsEditor
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Cove account: whether you are signed in, and whether this device agrees
 * with the others.
 *
 * Signing in is what makes the library, watch progress and settings on this
 * device the same ones as on every other, so the sync line is not decoration —
 * it is the only evidence that any of that is actually happening.
 */
@Composable
fun AccountSettings(modifier: Modifier = Modifier) {
    val graph = LocalAppGraph.current
    val account by graph.account.account.collectAsState()
    val sync by graph.account.syncStatus.collectAsState()

    Column(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (val state = account) {
            AccountState.Loading -> SettingsCard {
                SettingsNotice("Checking your account…")
            }

            is AccountState.Unavailable -> SettingsCard(
                title = "Cove account",
                iconName = "iconamoon:profile-circle",
            ) {
                SettingsNotice(state.reason)
            }

            AccountState.SignedOut -> SignInCard()

            is AccountState.SignedIn -> {
                SignedInCard(state, sync)
                SyncCard(sync)
            }
        }
    }
}

// ── Signed in ────────────────────────────────────────────────────────────────

@Composable
private fun SignedInCard(state: AccountState.SignedIn, sync: SyncStatus) {
    val scope = rememberCoroutineScope()
    val graph = LocalAppGraph.current

    SettingsCard(title = "Cove account", iconName = "iconamoon:profile-circle") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                IconifyIcon(
                    icon = "lucide:badge-check",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = state.email.ifBlank { "Signed in" },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Your library, progress and settings follow this account.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            SecondaryButton(
                label = "Sign out",
                onClick = { scope.launch { graph.account.signOut() } },
                danger = true,
            )
        }
    }
}

@Composable
private fun SyncCard(sync: SyncStatus) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val settingsState by graph.settings.settings.collectAsState()

    // Re-reads the clock while mounted so "synced just now" ages into "2 minutes
    // ago" without needing anything else to change.
    var now by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(sync) {
        while (true) {
            now = Clock.System.now()
            delay(30.seconds)
        }
    }

    SettingsCard(title = "Sync", iconName = "lucide:refresh-cw") {
        SettingRow(
            title = syncSummary(sync, now),
            description = sync.lastError ?: "Changes here reach your other devices, and theirs reach this one.",
        ) {
            PrimaryButton(
                label = "Sync now",
                onClick = { scope.launch { graph.account.syncNow() } },
                busy = sync.running,
                enabled = !sync.running,
            )
        }

        (settingsState as? SettingsState.Ready)?.let { ready ->
            val editor = rememberSettingsEditor(ready.settings)
            SettingDivider()
            SettingToggle(
                title = "Sync automatically",
                description = "Sync at launch, every so often, and shortly after you change " +
                    "something. Turn off to sync only when you press Sync now.",
                checked = ready.settings.autoSyncEnabled,
                onCheckedChange = { editor.edit { copy(autoSyncEnabled = it) } },
            )
        }
    }
}

// ── Signed out ───────────────────────────────────────────────────────────────

@Composable
private fun SignInCard() {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(AuthMode.SignIn) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var profileName by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    // Set once the server has sent a code and is waiting for it back — the same
    // step for a magic-code sign-in and for confirming a new account.
    var awaitingToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val submit = submit@{
        if (busy) return@submit
        busy = true
        error = null
        scope.launch {
            val outcome = when {
                awaitingToken && mode == AuthMode.Register ->
                    graph.account.confirmRegistration(email, token, password, profileName)

                awaitingToken -> graph.account.verifyOtp(email, token)
                mode == AuthMode.SignIn -> graph.account.signIn(email, password)
                mode == AuthMode.Register -> graph.account.register(email, password, profileName)
                else -> graph.account.sendOtp(email)
            }
            busy = false
            when (outcome) {
                AuthOutcome.Success -> if (mode == AuthMode.Code && !awaitingToken) {
                    // Sending the code succeeds long before signing in does.
                    awaitingToken = true
                    notice = "Check $email for a sign-in code."
                } else {
                    password = ""
                    token = ""
                }

                AuthOutcome.ConfirmationRequired -> {
                    awaitingToken = true
                    notice = "Check $email for a confirmation code."
                }

                is AuthOutcome.Failure -> error = outcome.message
            }
        }
    }

    SettingsCard(
        title = "Cove account",
        iconName = "iconamoon:profile-circle",
        description = "Sign in to keep your library, watch progress and settings on every device.",
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChoicePillRow {
                AuthMode.entries.forEach { entry ->
                    ChoicePill(
                        label = entry.label,
                        selected = mode == entry,
                        onClick = {
                            mode = entry
                            awaitingToken = false
                            error = null
                            notice = null
                        },
                    )
                }
            }

            if (mode == AuthMode.Register) {
                SettingsTextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    placeholder = "Profile name",
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SettingsTextField(
                value = email,
                onValueChange = { email = it },
                placeholder = "you@example.com",
                modifier = Modifier.fillMaxWidth(),
                enabled = !awaitingToken,
                keyboardType = KeyboardType.Email,
                onSubmit = submit,
            )

            if (mode != AuthMode.Code) {
                SettingsTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "Password",
                    modifier = Modifier.fillMaxWidth(),
                    masked = true,
                    enabled = !awaitingToken,
                    keyboardType = KeyboardType.Password,
                    onSubmit = submit,
                )
            }

            if (awaitingToken) {
                SettingsTextField(
                    value = token,
                    onValueChange = { token = it },
                    placeholder = "Code from your email",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.NumberPassword,
                    onSubmit = submit,
                )
            }

            notice?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            PrimaryButton(
                label = when {
                    awaitingToken -> "Confirm"
                    mode == AuthMode.Code -> "Email me a code"
                    else -> mode.label
                },
                onClick = submit,
                busy = busy,
                enabled = canSubmitAuth(mode, awaitingToken, email, password, profileName, token),
            )
        }
    }
}
