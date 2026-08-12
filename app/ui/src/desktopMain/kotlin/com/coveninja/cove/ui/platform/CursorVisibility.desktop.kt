package com.coveninja.cove.ui.platform

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

/**
 * AWT has no "no cursor", so the cursor becomes a single transparent pixel.
 *
 * Built once and reused: createCustomCursor goes out to the windowing system, and
 * this is toggled every time the controls come and go.
 */
private val blankCursor by lazy {
    Toolkit.getDefaultToolkit().createCustomCursor(
        BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
        Point(0, 0),
        "cove-blank-cursor",
    )
}

private val blankPointerIcon by lazy { PointerIcon(blankCursor) }

// overrideDescendants stays false so a child that asks for its own cursor — a button,
// a text field — still gets it. Nothing is hidden while the controls are up anyway.
actual fun Modifier.hideCursorWhen(hidden: Boolean): Modifier =
    if (hidden) pointerHoverIcon(blankPointerIcon) else this
