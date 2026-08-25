package com.coveninja.cove.ui.pages.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coveninja.cove.shared.data.TrackerRepository
import com.coveninja.cove.shared.data.TrackerState
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.shared.model.TrackerProvider
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.pages.common.PageLayoutDefaults
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.SettingsEditor
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tracking: one card per tracker, each owning the switches that belong to it.
 *
 * The switches used to sit in a card of their own beneath both accounts, which meant a line
 * of copy had to explain which pair belonged to which — a caption papering over a layout.
 * Putting them inside the account they act on says it structurally, and the caption goes
 * away.
 */
@Composable
fun ColumnScope.TrackingSettings(settings: AppSettings, editor: SettingsEditor) {
    val trackers = LocalAppGraph.current.trackers
    trackers.forEach { tracker -> TrackerCard(tracker, settings, editor) }
}

@Composable
private fun TrackerCard(
    tracker: TrackerRepository,
    settings: AppSettings,
    editor: SettingsEditor,
) {
    val state by tracker.state.collectAsState()
    val provider = tracker.provider
    val linked = state as? TrackerState.Linked
    val pending = state as? TrackerState.Pending

    SettingsCard {
        Column(
            modifier = Modifier.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
        ) {
            TrackerHeader(tracker, state)

            // The code panel is the only part of this page where something is actively
            // happening, so it is the only part that grows the card.
            AnimatedVisibility(
                visible = pending != null,
                enter = fadeIn(tween(220)) + expandVertically(tween(260)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(200)),
            ) {
                pending?.let { LinkPanel(provider, it) }
            }

            // Polls the tracker until the code is approved; sits on the card's edge so the
            // wait is visible without the content moving under the reader.
            ProgressHairline(active = pending != null || linked?.syncing == true)

            // An account with nothing behind it has no settings worth setting, and a wall of
            // switches that do nothing is a worse first impression than a card that grew
            // once it had earned the room.
            AnimatedVisibility(
                visible = linked != null,
                enter = fadeIn(tween(240, delayMillis = 80)) + expandVertically(tween(280)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(200)),
            ) {
                Column {
                    SettingDivider()
                    SettingRows(
                        {
                            SettingToggle(
                                title = "Scrobble to ${provider.label}",
                                description = "Report what you are watching as you watch it.",
                                checked = settings.scrobbleEnabled(provider),
                                onCheckedChange = {
                                    editor.edit { withScrobbleEnabled(provider, it) }
                                },
                            )
                        },
                        {
                            SettingToggle(
                                title = "Sync your ${provider.label} library",
                                description = "Keep your list and watch history in step with " +
                                    "${provider.label}.",
                                checked = settings.syncEnabled(provider),
                                onCheckedChange = { editor.edit { withSyncEnabled(provider, it) } },
                            )
                        },
                    )
                }
            }
        }
    }
}

// ── Header ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackerHeader(tracker: TrackerRepository, state: TrackerState) {
    val provider = tracker.provider
    val scope = rememberCoroutineScope()
    val tone = trackerTone(state)

    // Re-reads the clock while mounted so "just now" ages into "3 minutes ago" without
    // anything else having to change.
    var now by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(state) {
        while (true) {
            now = Clock.System.now()
            delay(30.seconds)
        }
    }

    val compact = PageLayoutDefaults.IsCompact

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = RowPadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackerMark(provider, tone)

            Column(
                modifier = Modifier.weight(1f).animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = provider.label,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    StatusPill(label = trackerStatusLabel(state), tone = tone)
                }
                // One line changing its mind rather than two lines swapping, which is what
                // makes the state read as live rather than redrawn.
                AnimatedContent(
                    targetState = trackerDetail(state, provider, now),
                    transitionSpec = {
                        (fadeIn(tween(200)) + slideInVertically(tween(240)) { it / 4 })
                            .togetherWith(fadeOut(tween(120)))
                    },
                    label = "TrackerDetail",
                ) { detail ->
                    Text(
                        text = detail,
                        color = if (tone == TrackerTone.Attention) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (!compact) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TrackerActions(tracker, state) { work -> scope.launch { work() } }
                }
            }
        }

        // A phone has no room for two buttons beside a two-line description, so they take
        // their own row rather than squeezing the text into a column too narrow to read.
        if (compact) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TrackerActions(tracker, state) { work -> scope.launch { work() } }
            }
        }
    }
}

@Composable
private fun TrackerActions(
    tracker: TrackerRepository,
    state: TrackerState,
    launch: (suspend () -> Unit) -> Unit,
) {
    // A sync that lands while the card is open is worth a tick on the button that started
    // it; one that landed before it was opened is history, so the cursor already on screen
    // at first composition is recorded rather than celebrated.
    var flash by remember { mutableStateOf(false) }
    var lastSeen by remember { mutableStateOf((state as? TrackerState.Linked)?.lastSyncAt) }
    val linked = state as? TrackerState.Linked
    LaunchedEffect(linked?.lastSyncAt) {
        val landed = linked?.lastSyncAt
        if (landed == null || landed == lastSeen) return@LaunchedEffect
        lastSeen = landed
        flash = true
        delay(1_800)
        flash = false
    }

    when (state) {
        TrackerState.Loading, is TrackerState.Unconfigured -> Unit

        is TrackerState.Unlinked -> PrimaryButton(
            label = "Connect",
            onClick = { launch { tracker.startLink() } },
        )

        is TrackerState.Pending -> SecondaryButton(
            label = "Cancel",
            onClick = { launch { tracker.cancelLink() } },
        )

        is TrackerState.Linked -> {
            PrimaryButton(
                label = "Sync now",
                onClick = { launch { tracker.syncNow() } },
                busy = state.syncing,
                done = flash,
                doneLabel = "Synced",
                enabled = !state.syncing,
            )
            SecondaryButton(
                label = "Disconnect",
                onClick = { launch { tracker.unlink() } },
                danger = true,
            )
        }
    }
}

/**
 * The mark: whose account this is, lit by how it is doing.
 *
 * The glyph is each tracker's own logo and never changes, so identity has one home and
 * state has another — the pill beside it. An earlier pass swapped the glyph for a state
 * icon, which meant the card stopped saying *which* tracker it was at exactly the moment it
 * had something to report.
 *
 * The tint is Cove's tone rather than the brand's colour, and that part is deliberate:
 * Trakt's red sits almost exactly on [CoveColors.Status.Danger], so a red mark beside
 * "Trakt" would read as something being wrong with it. The silhouette carries the brand;
 * the colour carries the state.
 */
@Composable
private fun TrackerMark(provider: TrackerProvider, tone: TrackerTone) {
    val accent = toneAccent(tone)
    val tint by animateColorAsState(accent, tween(300), label = "TrackerMarkTint")
    val container by animateColorAsState(
        targetValue = accent.copy(alpha = if (tone == TrackerTone.Off) 0.10f else 0.16f),
        animationSpec = tween(300),
        label = "TrackerMarkContainer",
    )
    val ring by animateColorAsState(
        targetValue = accent.copy(alpha = if (tone == TrackerTone.Off) 0f else 0.30f),
        animationSpec = tween(300),
        label = "TrackerMarkRing",
    )

    Box(
        modifier = Modifier
            .size(40.dp)
            .background(container, CircleShape)
            .border(1.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        IconifyIcon(
            icon = providerGlyph(provider),
            modifier = Modifier.size(providerGlyphSize(provider)),
            tint = tint,
        )
    }
}

/**
 * Each tracker's own logo, from Simple Icons.
 *
 * Brand marks rather than stand-ins because these are somebody else's product and the
 * viewer already knows what they look like — a generic clock beside "Trakt" makes the
 * reader do work the logo does for free. Both are single-path monochrome glyphs, which is
 * what lets them take Cove's tint like any other icon in the table.
 */
private fun providerGlyph(provider: TrackerProvider): String = when (provider) {
    TrackerProvider.Trakt -> "simple-icons:trakt"
    TrackerProvider.Simkl -> "simple-icons:simkl"
}

/**
 * An optical correction, not a layout one.
 *
 * Simkl's mark is a filled tile and Trakt's is linework, so at a shared box size the solid
 * one reads noticeably heavier — the two cards would look like they were drawn to different
 * scales. Giving the filled glyph a couple of dp less brings them to the same apparent
 * weight.
 */
private fun providerGlyphSize(provider: TrackerProvider) = when (provider) {
    TrackerProvider.Trakt -> 19.dp
    TrackerProvider.Simkl -> 17.dp
}

@Composable
private fun StatusPill(label: String, tone: TrackerTone) {
    val tint by animateColorAsState(toneAccent(tone), tween(260), label = "StatusPillTint")

    Row(
        modifier = Modifier
            .background(tint.copy(alpha = 0.13f), RoundedCornerShape(7.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(5.dp).background(tint, CircleShape))
        Text(
            text = label,
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun toneAccent(tone: TrackerTone) = when (tone) {
    TrackerTone.Linked -> MaterialTheme.colorScheme.tertiary
    TrackerTone.Waiting -> CoveColors.Status.Warning
    TrackerTone.Attention -> MaterialTheme.colorScheme.error
    TrackerTone.Off -> MaterialTheme.colorScheme.onSurfaceVariant
}

// ── The link panel ───────────────────────────────────────────────────────────

/**
 * The code, while the tracker waits to be told it was approved.
 *
 * This is the one screen in Cove that asks someone to copy something *off* it by hand, onto
 * another device, against a deadline — so it is built around that job rather than around
 * reporting a state. The code is set large and split into pairs because an unbroken run of
 * characters is where transcription goes wrong; the address sits above it because that is
 * the order the two are used in; and the deadline is shown because the tracker gave Cove a
 * window and the screen was previously the only thing that knew it.
 */
@Composable
private fun LinkPanel(provider: TrackerProvider, pending: TrackerState.Pending) {
    val clipboard = LocalClipboardManager.current

    // A second is the coarsest tick that still counts down honestly in the final minute —
    // and only while there is a deadline to count, so a tracker that named no window costs
    // no frames.
    var now by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(pending.expiresAt) {
        if (pending.expiresAt == null) return@LaunchedEffect
        while (true) {
            now = Clock.System.now()
            delay(1.seconds)
        }
    }
    val countdown = codeCountdown(pending.expiresAt, now)
    val urgent = codeExpiringSoon(pending.expiresAt, now)

    var copied by remember(pending.userCode) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(1_600)
        copied = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = RowPadding, end = RowPadding, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StepLine(index = 1, text = "Open this address on any device")
        Text(
            text = pending.verificationUrl,
            modifier = Modifier.padding(start = StepIndent),
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )

        StepLine(index = 2, text = "Enter this code and approve Cove")
        Row(
            modifier = Modifier.padding(start = StepIndent).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                groupUserCode(pending.userCode).forEach { group ->
                    Box(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
                                RoundedCornerShape(10.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        Text(
                            text = group,
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            // Transcription, not prose: the extra tracking is what stops a
                            // reader from losing their place mid-group.
                            letterSpacing = 2.sp,
                            maxLines = 1,
                        )
                    }
                }
            }

            // Present because on a desktop the address goes in the browser on the same
            // machine, and retyping a code you can see is a pointless step.
            SettingsIconAction(
                icon = if (copied) "lucide:check" else "lucide:copy",
                onClick = {
                    clipboard.setText(AnnotatedString(pending.userCode))
                    copied = true
                },
            )
        }

        Row(
            modifier = Modifier.padding(start = StepIndent),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconifyIcon(
                icon = "lucide:clock-3",
                modifier = Modifier.size(13.dp),
                tint = if (urgent) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = countdown?.let { "$it — ${provider.label} is waiting" }
                    ?: "${provider.label} is waiting",
                color = if (urgent) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** The numbered steps, indented so the content under each lines up with its own text. */
@Composable
private fun StepLine(index: Int, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(19.dp)
                .background(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$index",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private val StepIndent = 28.dp
