package com.coveninja.cove.ui.pages.profile

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.TraktState
import com.coveninja.cove.ui.state.LocalAppGraph
import kotlinx.coroutines.launch

/**
 * Linking a Trakt account.
 *
 * Trakt's device flow is deliberately shown in full: the code has to be typed on
 * another device, so hiding it behind a spinner would leave nothing to act on.
 * The scrobble and library toggles below this do nothing until it says Linked,
 * which is why this card comes first.
 */
@Composable
fun TraktConnectCard(modifier: Modifier = Modifier) {
    val graph = LocalAppGraph.current
    val state by graph.trakt.state.collectAsState()
    val scope = rememberCoroutineScope()

    SettingsCard(
        modifier = modifier,
        title = "Trakt account",
        iconName = "iconamoon:history",
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            when (val current = state) {
                TraktState.Loading -> SettingsNotice("Checking Trakt…")

                is TraktState.Unconfigured -> SettingsNotice(current.reason)

                is TraktState.Unlinked -> {
                    SettingRow(
                        title = "Not connected",
                        description = current.error
                            ?: "Connect to scrobble what you watch and keep your list in step.",
                    ) {
                        PrimaryButton(
                            label = "Connect",
                            onClick = { scope.launch { graph.trakt.startLink() } },
                        )
                    }
                }

                is TraktState.Pending -> PendingRow(
                    userCode = current.userCode,
                    verificationUrl = current.verificationUrl,
                    onCancel = { scope.launch { graph.trakt.cancelLink() } },
                )

                is TraktState.Linked -> {
                    SettingRow(
                        title = "Connected as ${current.username}",
                        description = "Cove reports playback to Trakt as you watch.",
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SecondaryButton(
                                label = "Sync now",
                                onClick = { scope.launch { graph.trakt.syncNow() } },
                            )
                            SecondaryButton(
                                label = "Disconnect",
                                onClick = { scope.launch { graph.trakt.unlink() } },
                                danger = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingRow(userCode: String, verificationUrl: String, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingLabels(
            title = "Waiting for Trakt",
            description = "Open $verificationUrl on any device and enter this code. " +
                "Cove finishes on its own once you approve it.",
        )
        Box(
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Text(
                text = userCode,
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        SecondaryButton(label = "Cancel", onClick = onCancel)
    }
}
