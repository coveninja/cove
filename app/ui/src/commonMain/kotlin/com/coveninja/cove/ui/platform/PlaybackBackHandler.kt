package com.coveninja.cove.ui.platform

import androidx.compose.runtime.Composable

/** Android consumes system Back here; desktop uses its existing keyboard handler. */
@Composable
expect fun PlaybackBackHandler(enabled: Boolean, onBack: () -> Unit)
