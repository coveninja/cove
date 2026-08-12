package com.coveninja.cove.ui.platform

import androidx.compose.ui.Modifier

/** A finger leaves nothing on screen to hide. */
actual fun Modifier.hideCursorWhen(hidden: Boolean): Modifier = this
