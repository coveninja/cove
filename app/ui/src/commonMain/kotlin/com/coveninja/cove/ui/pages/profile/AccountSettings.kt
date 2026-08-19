package com.coveninja.cove.ui.pages.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.AccountState
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.data.SyncStatus
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.pages.common.SegmentedControl
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
 * The sync mark carries this screen. It turns while a sync runs, throws one ring
 * when a sync lands, and takes on the error tint when one does not — so the
 * answer to "are we in step?" is legible before a word is read. Nothing else
 * here animates without cause: no ambient motion, and nothing at all while the
 * screen is idle.
 */
@Composable
fun AccountSettings(modifier: Modifier = Modifier) {
    val graph = LocalAppGraph.current
    val account by graph.account.account.collectAsState()
    val sync by graph.account.syncStatus.collectAsState()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Signing in replaces the form with the account, which is a change of
        // subject rather than of content: it rises into place instead of
        // cross-fading in position.
        AnimatedContent(
            targetState = account,
            transitionSpec = {
                (fadeIn(tween(240, delayMillis = 60)) +
                    slideInVertically(tween(320, delayMillis = 60)) { it / 12 })
                    .togetherWith(fadeOut(tween(140)))
                    .using(SizeTransform(clip = false))
            },
            contentKey = { it::class },
            label = "AccountState",
        ) { state ->
            when (state) {
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

                is AccountState.SignedIn -> Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SignedInCard(state)
                    SyncCard(sync)
                }
            }
        }
    }
}

// ── Signed in ────────────────────────────────────────────────────────────────

@Composable
private fun SignedInCard(state: AccountState.SignedIn) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val graph = LocalAppGraph.current
    val initial = state.email.trim().firstOrNull()?.uppercase() ?: "C"

    SettingsCard(title = "Cove account", iconName = "iconamoon:profile-circle") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = RowPadding, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Lands with the card rather than appearing in it — a small arrival
            // for the one element that says the sign-in worked.
            val entrance = remember { Animatable(0.7f) }
            LaunchedEffect(Unit) {
                entrance.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow,
                    ),
                )
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .graphicsLayer {
                        scaleX = entrance.value
                        scaleY = entrance.value
                    }
                    .background(colors.tertiary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initial,
                    color = colors.onTertiary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                )
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = state.email.ifBlank { "Signed in" },
                    color = colors.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Your library, progress and settings follow this account.",
                    color = colors.onSurfaceVariant,
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

    // Re-reads the clock while mounted so "just now" ages into "3 minutes ago"
    // without anything else having to change.
    var now by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(sync.lastSyncedAt) {
        while (true) {
            now = Clock.System.now()
            delay(30.seconds)
        }
    }

    // A sync that lands while the page is open is worth acknowledging; one that
    // landed before it was opened is history, so the timestamp already on screen
    // at first composition is recorded rather than celebrated.
    var flash by remember { mutableStateOf(false) }
    var lastSeen by remember { mutableStateOf(sync.lastSyncedAt) }
    LaunchedEffect(sync.lastSyncedAt) {
        val landed = sync.lastSyncedAt
        if (landed == null || landed == lastSeen) return@LaunchedEffect
        lastSeen = landed
        flash = true
        delay(1_800)
        flash = false
    }

    SettingsCard {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = RowPadding, vertical = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SyncMark(tone = syncTone(sync), pulseKey = sync.lastSyncedAt.takeIf { !sync.failed })

                Column(
                    modifier = Modifier.weight(1f).animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    // Headline and detail move as one line of text changing its
                    // mind, which is what makes the state read as live.
                    AnimatedContent(
                        targetState = syncHeadline(sync),
                        transitionSpec = {
                            (fadeIn(tween(200)) + slideInVertically(tween(240)) { it / 3 })
                                .togetherWith(fadeOut(tween(120)) + slideOutVertically { -it / 3 })
                        },
                        label = "SyncHeadline",
                    ) { headline ->
                        Text(
                            text = headline,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Text(
                        text = syncDetail(sync, now),
                        color = if (sync.failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                PrimaryButton(
                    label = "Sync now",
                    onClick = { scope.launch { graph.account.syncNow() } },
                    busy = sync.running,
                    done = flash,
                    doneLabel = "Synced",
                    enabled = !sync.running,
                )
            }

            // Sits on the card's edge: activity without the content shifting.
            ProgressHairline(active = sync.running)

            (settingsState as? SettingsState.Ready)?.let { ready ->
                val editor = rememberSettingsEditor(ready.settings)
                SettingDivider()
                SettingToggle(
                    title = "Sync automatically",
                    description = "At launch, every so often, and shortly after you change " +
                        "something. Off means only when you press Sync now.",
                    checked = ready.settings.autoSyncEnabled,
                    onCheckedChange = { editor.edit { copy(autoSyncEnabled = it) } },
                )
            }
        }
    }
}

/**
 * The signature of this screen: one mark that carries the whole sync state.
 *
 * Turning means working, a ring means a sync just landed, and the error tint
 * means the last one did not. Each piece of motion is tied to an event — nothing
 * here moves on its own while the screen sits idle.
 */
@Composable
private fun SyncMark(tone: SyncTone, pulseKey: Any?) {
    val colors = MaterialTheme.colorScheme
    val accent = when (tone) {
        SyncTone.Attention -> colors.error
        SyncTone.Idle -> colors.onSurfaceVariant
        else -> colors.tertiary
    }
    val tint by animateColorAsState(accent, tween(300), label = "SyncMarkTint")
    val container by animateColorAsState(
        targetValue = accent.copy(alpha = if (tone == SyncTone.Idle) 0.10f else 0.16f),
        animationSpec = tween(300),
        label = "SyncMarkContainer",
    )

    // One ring, thrown outward the moment a sync completes and gone within the
    // second. It is the only celebratory thing on the screen.
    val pulse = remember { Animatable(1f) }
    var primed by remember { mutableStateOf(false) }
    LaunchedEffect(pulseKey) {
        if (!primed) {
            primed = true
            return@LaunchedEffect
        }
        if (pulseKey != null) {
            pulse.snapTo(0f)
            pulse.animateTo(1f, tween(900, easing = LinearEasing))
        }
    }

    // Only exists while a sync runs. An infinite transition kept around for the
    // other 99% of the time would request frames forever on a settings page that
    // is, by then, perfectly still.
    val rotation = if (tone == SyncTone.Working) spinAngle() else 0f

    Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        if (pulse.value < 1f) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .graphicsLayer {
                        val progress = pulse.value
                        scaleX = 1f + progress * 0.7f
                        scaleY = 1f + progress * 0.7f
                        alpha = (1f - progress) * 0.55f
                    }
                    .border(1.5.dp, tint, CircleShape),
            )
        }

        Box(
            modifier = Modifier.size(40.dp).background(container, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = tone,
                transitionSpec = {
                    (fadeIn(tween(180)) + scaleIn(initialScale = 0.7f, animationSpec = tween(180)))
                        .togetherWith(fadeOut(tween(140)))
                },
                label = "SyncMarkGlyph",
            ) { current ->
                IconifyIcon(
                    icon = when (current) {
                        SyncTone.Working -> "lucide:refresh-cw"
                        SyncTone.Attention -> "lucide:triangle-alert"
                        SyncTone.Settled -> "lucide:cloud-check"
                        SyncTone.Idle -> "lucide:cloud"
                    },
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer {
                            rotationZ = if (current == SyncTone.Working) rotation else 0f
                        },
                    tint = tint,
                )
            }
        }
    }
}

@Composable
private fun spinAngle(): Float {
    val spin = rememberInfiniteTransition(label = "SyncMarkSpin")
    val rotation by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing)),
        label = "SyncMarkRotation",
    )
    return rotation
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
    var revealPassword by remember { mutableStateOf(false) }
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
            // Sending a code succeeds long before signing in does, which is the distinction
            // authFollowUp exists to make; see AccountModel.kt.
            when (val followUp = authFollowUp(mode, awaitingToken, outcome)) {
                AuthFollowUp.SignedIn -> {
                    password = ""
                    token = ""
                }

                AuthFollowUp.AwaitToken -> {
                    awaitingToken = true
                    notice = if (mode == AuthMode.Code) {
                        "Enter the code sent to $email."
                    } else {
                        "Enter the code sent to $email to finish setting up."
                    }
                }

                is AuthFollowUp.Failed -> error = followUp.message
            }
        }
    }

    SettingsCard(
        title = "Cove account",
        iconName = "iconamoon:profile-circle",
        description = "Sign in to keep your library, watch progress and settings on every device.",
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = RowPadding, vertical = 18.dp)
                .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SegmentedControl(
                options = AuthMode.entries,
                selected = mode,
                label = { it.label },
                onSelect = {
                    mode = it
                    awaitingToken = false
                    error = null
                    notice = null
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // Each field appears with the mode that needs it. Expanding rather
            // than snapping keeps the card from jumping under the cursor.
            AnimatedVisibility(
                visible = mode == AuthMode.Register,
                enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
            ) {
                SettingsTextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    placeholder = "Profile name",
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = "lucide:user-round",
                    onSubmit = submit,
                )
            }

            SettingsTextField(
                value = email,
                onValueChange = { email = it },
                placeholder = "you@example.com",
                modifier = Modifier.fillMaxWidth(),
                enabled = !awaitingToken,
                keyboardType = KeyboardType.Email,
                leadingIcon = "lucide:mail",
                onSubmit = submit,
            )

            AnimatedVisibility(
                visible = mode != AuthMode.Code,
                enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
            ) {
                SettingsTextField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "Password",
                    modifier = Modifier.fillMaxWidth(),
                    masked = !revealPassword,
                    enabled = !awaitingToken,
                    keyboardType = KeyboardType.Password,
                    leadingIcon = "lucide:lock",
                    trailing = {
                        FieldIconToggle(
                            icon = if (revealPassword) "lucide:eye-off" else "lucide:eye",
                            onClick = { revealPassword = !revealPassword },
                        )
                    },
                    onSubmit = submit,
                )
            }

            AnimatedVisibility(
                visible = awaitingToken,
                enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
            ) {
                SettingsTextField(
                    value = token,
                    onValueChange = { token = it },
                    placeholder = "6-digit code",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.NumberPassword,
                    leadingIcon = "lucide:key",
                    onSubmit = submit,
                )
            }

            InlineMessage(text = notice, isError = false)
            InlineMessage(text = error, isError = true)

            PrimaryButton(
                label = when {
                    awaitingToken -> "Verify code"
                    mode == AuthMode.Code -> "Email me a code"
                    else -> mode.label
                },
                onClick = submit,
                modifier = Modifier.fillMaxWidth(),
                busy = busy,
                enabled = canSubmitAuth(mode, awaitingToken, email, password, profileName, token),
            )
        }
    }
}

/** A notice or a failure, arriving in place rather than shoving the form down. */
@Composable
private fun InlineMessage(text: String?, isError: Boolean) {
    AnimatedVisibility(
        visible = text != null,
        enter = expandVertically(tween(200)) + fadeIn(tween(200)),
        exit = shrinkVertically(tween(160)) + fadeOut(tween(120)),
    ) {
        val colors = MaterialTheme.colorScheme
        val tone = if (isError) colors.error else colors.onSurfaceVariant
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isError) colors.error.copy(alpha = 0.10f) else Color.Transparent,
                    androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                )
                .padding(
                    horizontal = if (isError) 12.dp else 0.dp,
                    vertical = if (isError) 10.dp else 0.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (isError) {
                IconifyIcon(
                    icon = "lucide:triangle-alert",
                    modifier = Modifier.size(15.dp),
                    tint = tone,
                )
            }
            Text(
                text = text.orEmpty(),
                color = tone,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
