package com.coveninja.cove.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Whether this platform can hand the player a subtitle file from local storage.
 *
 * The shared UI offers the control only where this is true, rather than offering it
 * everywhere and having it do nothing on the platforms without a file to point at — the
 * same rule [hasPointerHover] and [hasHardwareKeyboard] answer for their own gestures.
 */
expect val canLoadSubtitleFile: Boolean

/**
 * Asks the platform for a subtitle file. Null when the viewer dismissed the chooser, or
 * picked something that is not a subtitle after all.
 *
 * Blocks until they answer, and is only ever called where [canLoadSubtitleFile] is true.
 */
expect fun chooseSubtitleFile(): String?

/**
 * Accepts subtitle files dragged onto this element from outside the app.
 *
 * [onDragChange] reports whether such a drag is currently over it, which is what puts the
 * "drop a subtitle file here" panel on screen — a gesture nobody can see is a gesture
 * nobody discovers. [onFiles] receives absolute local paths, unfiltered: deciding which
 * of them is a subtitle is [subtitleFilesAmong]'s job, not the platform's.
 *
 * A no-op wherever there is nothing to drag with.
 */
@Composable
expect fun Modifier.subtitleFileDropTarget(
    onDragChange: (Boolean) -> Unit,
    onFiles: (List<String>) -> Unit,
): Modifier
