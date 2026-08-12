package com.coveninja.cove.ui.platform

import androidx.compose.ui.Modifier

/**
 * Hides the mouse cursor over this element while [hidden] is true.
 *
 * The player fades its controls out after a few seconds of stillness, and until now
 * left the arrow sitting in the middle of the picture — which is the one thing on
 * screen that cannot be part of the film. Hiding it is the other half of a control
 * layer that gets out of the way.
 *
 * A no-op wherever there is no cursor to hide.
 */
expect fun Modifier.hideCursorWhen(hidden: Boolean): Modifier
