package com.coveninja.cove.ui.components.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.coveninja.cove.shared.data.AppUpdateState
import com.coveninja.cove.shared.data.UpdateApplyResult
import com.coveninja.cove.shared.data.UpdateRepository
import kotlinx.coroutines.launch

@Composable
fun AppUpdateOverlay(
    updates: UpdateRepository,
    state: AppUpdateState,
    playbackActive: Boolean,
    onExitRequired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    when (state) {
        is AppUpdateState.MeteredApprovalRequired -> if (!playbackActive) AlertDialog(
            modifier = modifier,
            onDismissRequest = updates::dismissReadyForSession,
            title = { Text("Download Cove ${state.release.version}?") },
            text = {
                Text(
                    "This network is metered. The verified update is " +
                        "${formatUpdateBytes(state.release.downloadSizeBytes)}.",
                )
            },
            confirmButton = {
                TextButton(onClick = { scope.launch { updates.approveMeteredDownload() } }) {
                    Text("Download")
                }
            },
            dismissButton = {
                TextButton(onClick = updates::dismissReadyForSession) { Text("Later") }
            },
        )

        is AppUpdateState.Ready -> if (state.promptVisible && !playbackActive) {
            AlertDialog(
                modifier = modifier,
                onDismissRequest = updates::dismissReadyForSession,
                title = { Text("Cove ${state.release.version} is ready") },
                text = { Text("Install the verified update now? Cove may close and restart.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                if (updates.applyReadyUpdate() == UpdateApplyResult.ExitRequired) {
                                    onExitRequired()
                                }
                            }
                        },
                    ) { Text("Install now") }
                },
                dismissButton = {
                    TextButton(onClick = updates::dismissReadyForSession) { Text("Later") }
                },
            )
        }

        is AppUpdateState.PermissionRequired -> AlertDialog(
            modifier = modifier,
            onDismissRequest = updates::dismissReadyForSession,
            title = { Text("Allow Cove to install updates") },
            text = {
                Text("Enable installation from Cove in Android settings, then return to continue.")
            },
            confirmButton = {
                TextButton(onClick = { scope.launch { updates.applyReadyUpdate() } }) {
                    Text("Open settings")
                }
            },
            dismissButton = {
                TextButton(onClick = updates::dismissReadyForSession) { Text("Later") }
            },
        )

        is AppUpdateState.Installing -> AlertDialog(
            modifier = modifier,
            onDismissRequest = {},
            title = { Text("Installing update") },
            text = {
                Text(
                    "Follow the system confirmation if it appears. Reopen Cove when installation finishes.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {},
        )

        else -> Unit
    }
}

fun formatUpdateBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GiB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes bytes"
}
