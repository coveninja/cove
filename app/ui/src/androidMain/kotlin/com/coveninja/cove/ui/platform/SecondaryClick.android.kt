package com.coveninja.cove.ui.platform

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

// Touch uses the shared long-press/drag gestures already attached to cards.
actual fun Modifier.onSecondaryClick(onClick: (Offset) -> Unit): Modifier = this
