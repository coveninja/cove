package com.coveninja.cove.ui.platform

import androidx.compose.runtime.Composable

/** Android consumes system Back here; desktop keeps its existing keyboard handling. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/** Kept as the player-facing name while the same platform hook now serves the whole app. */
@Composable
fun PlaybackBackHandler(enabled: Boolean, onBack: () -> Unit) {
    PlatformBackHandler(enabled = enabled, onBack = onBack)
}
