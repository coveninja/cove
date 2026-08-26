package com.coveninja.cove.ui.tv.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.TrackerRepository
import com.coveninja.cove.shared.data.TrackerState
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.pages.profile.TrackerTone
import com.coveninja.cove.ui.pages.profile.codeCountdown
import com.coveninja.cove.ui.pages.profile.codeExpiringSoon
import com.coveninja.cove.ui.pages.profile.groupUserCode
import com.coveninja.cove.ui.pages.profile.scrobbleEnabled
import com.coveninja.cove.ui.pages.profile.syncEnabled
import com.coveninja.cove.ui.pages.profile.trackerDetail
import com.coveninja.cove.ui.pages.profile.trackerStatusLabel
import com.coveninja.cove.ui.pages.profile.trackerTone
import com.coveninja.cove.ui.pages.profile.unlinkNote
import com.coveninja.cove.ui.pages.profile.withScrobbleEnabled
import com.coveninja.cove.ui.pages.profile.withSyncEnabled
import com.coveninja.cove.ui.state.SettingsEditor
import com.coveninja.cove.ui.tv.components.TvButton
import com.coveninja.cove.ui.tv.components.TvSettingRow
import com.coveninja.cove.ui.tv.focus.tvFocusGroup
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Trakt and Simkl, on the one device where their login is the easy one.
 *
 * Both use OAuth device flow: the tracker issues a short code, the viewer types it into a
 * browser on whatever device is already in their hand, and Cove polls until it is approved.
 * That is the only sign-in shape a remote handles *well* — nothing is typed here at all — and
 * yet it was the shell that had no way to reach it, so a household whose only Cove is a
 * television could not scrobble at all.
 *
 * Everything decided here is [com.coveninja.cove.ui.pages.profile.TrackerModel]'s, unchanged:
 * the same tone, the same status wording, the same code grouping and countdown. What differs is
 * only that the code is drawn large enough to read from a sofa.
 */
@Composable
internal fun ColumnScope.TvTrackerRows(
    trackers: List<TrackerRepository>,
    settings: AppSettings,
    editor: SettingsEditor,
) {
    trackers.forEach { tracker ->
        TvTrackerCard(tracker = tracker, settings = settings, editor = editor)
    }
}

@Composable
private fun TvTrackerCard(
    tracker: TrackerRepository,
    settings: AppSettings,
    editor: SettingsEditor,
) {
    val scope = rememberCoroutineScope()
    val state by tracker.state.collectAsState()
    val provider = tracker.provider
    val tone = trackerTone(state)

    // Ticked only while a code is on screen. The countdown is the one thing here that changes
    // on its own, and a clock running behind a settled card would recompose it for nothing.
    var now by remember { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(state is TrackerState.Pending) {
        while (state is TrackerState.Pending) {
            now = Clock.System.now()
            delay(1.seconds)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CoveColors.Neutral.Surface, RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = provider.label,
                style = MaterialTheme.typography.titleLarge,
                color = CoveColors.Neutral.Text,
            )
            Text(
                text = trackerStatusLabel(state),
                style = MaterialTheme.typography.labelMedium,
                color = tone.accent(),
                modifier = Modifier
                    .padding(start = 12.dp)
                    .background(tone.accent().copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        Text(
            text = trackerDetail(state, provider, now),
            style = MaterialTheme.typography.bodyMedium,
            color = CoveColors.Neutral.MutedDim,
            modifier = Modifier.padding(top = 6.dp),
        )

        (state as? TrackerState.Pending)?.let { pending ->
            TvDeviceCode(
                userCode = pending.userCode,
                verificationUrl = pending.verificationUrl,
                countdown = codeCountdown(pending.expiresAt, now),
                expiringSoon = codeExpiringSoon(pending.expiresAt, now),
            )
        }

        Row(
            modifier = Modifier.padding(top = 14.dp).tvFocusGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (state) {
                TrackerState.Loading, is TrackerState.Unconfigured -> Unit

                is TrackerState.Unlinked -> TvButton(
                    label = "Connect",
                    onClick = { scope.launch { tracker.startLink() } },
                    icon = "lucide:key",
                    primary = true,
                )

                is TrackerState.Pending -> TvButton(
                    label = "Cancel",
                    onClick = { scope.launch { tracker.cancelLink() } },
                    icon = "lucide:x",
                )

                is TrackerState.Linked -> {
                    TvButton(
                        label = "Sync now",
                        onClick = { scope.launch { tracker.syncNow() } },
                        icon = "lucide:refresh-cw",
                        primary = true,
                        enabled = !(state as TrackerState.Linked).syncing,
                    )
                    TvButton(
                        label = "Disconnect",
                        onClick = { scope.launch { tracker.unlink() } },
                        icon = "lucide:x",
                    )
                }
            }
        }

        if (state is TrackerState.Linked) {
            unlinkNote(provider)?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelMedium,
                    color = CoveColors.Neutral.MutedDim,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Column(
                modifier = Modifier.padding(top = 12.dp).tvFocusGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TvSettingRow(
                    label = "Scrobble what you watch",
                    detail = "Tell ${provider.label} what is playing, as it plays.",
                    value = onOff(settings.scrobbleEnabled(provider)),
                    highlighted = settings.scrobbleEnabled(provider),
                    onActivate = {
                        editor.edit {
                            withScrobbleEnabled(provider, !scrobbleEnabled(provider))
                        }
                    },
                )
                TvSettingRow(
                    label = "Keep lists in step",
                    detail = "Reconcile your library and watch history both ways.",
                    value = onOff(settings.syncEnabled(provider)),
                    highlighted = settings.syncEnabled(provider),
                    onActivate = {
                        editor.edit { withSyncEnabled(provider, !syncEnabled(provider)) }
                    },
                )
            }
        }
    }
}

/**
 * The code, at a size somebody can read across a room and copy onto a phone.
 *
 * No copy button, unlike the pointer shells. A television's clipboard has nowhere to go — the
 * device that needs this code is a different one — so the only useful thing the screen can do
 * is show the code and the address plainly and hold still while they are transcribed.
 */
@Composable
private fun TvDeviceCode(
    userCode: String,
    verificationUrl: String,
    countdown: String?,
    expiringSoon: Boolean,
) {
    Column(
        modifier = Modifier
            .padding(top = 14.dp)
            .fillMaxWidth()
            .background(CoveColors.Neutral.SurfaceRaised, RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text(
            text = "Go to",
            style = MaterialTheme.typography.labelMedium,
            color = CoveColors.Neutral.MutedDim,
        )
        Text(
            text = verificationUrl,
            style = MaterialTheme.typography.headlineSmall,
            color = CoveColors.Brand.Accent,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = "and enter",
            style = MaterialTheme.typography.labelMedium,
            color = CoveColors.Neutral.MutedDim,
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Grouped by the same rule the pointer shells use: an unbroken run of characters
            // is where transcription goes wrong, and this one is being read at a distance.
            groupUserCode(userCode).forEach { group ->
                Text(
                    text = group,
                    style = MaterialTheme.typography.displaySmall,
                    color = CoveColors.Neutral.Text,
                )
            }
        }
        countdown?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = if (expiringSoon) CoveColors.Status.Warning else CoveColors.Neutral.Muted,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

private fun TrackerTone.accent(): Color = when (this) {
    TrackerTone.Linked -> CoveColors.Brand.Accent
    TrackerTone.Waiting -> CoveColors.Status.Info
    TrackerTone.Attention -> CoveColors.Status.Warning
    TrackerTone.Off -> CoveColors.Neutral.MutedDim
}
