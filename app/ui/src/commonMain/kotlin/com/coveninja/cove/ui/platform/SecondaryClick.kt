package com.coveninja.cove.ui.platform

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/** Adds the platform's secondary-pointer gesture without leaking it into shared UI code. */
expect fun Modifier.onSecondaryClick(onClick: (Offset) -> Unit): Modifier
