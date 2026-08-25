package com.coveninja.cove.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A phone has no drag gesture to bring a file in with, and a television remote has no
 * pointer at all. Reaching Android's document picker from here would mean an activity
 * result and a content URI mpv cannot open by path, which is a separate piece of work
 * rather than a smaller version of this one — so the control is absent rather than
 * present and inert.
 */
actual val canLoadSubtitleFile: Boolean = false

actual fun chooseSubtitleFile(): String? = null

@Composable
actual fun Modifier.subtitleFileDropTarget(
    onDragChange: (Boolean) -> Unit,
    onFiles: (List<String>) -> Unit,
): Modifier = this
