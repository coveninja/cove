package com.coveninja.cove.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Somewhere to put an image the app has rendered.
 *
 * The two hosts mean genuinely different things by "export": a desktop writes a PNG to a
 * folder the viewer picks, while a phone hands it to the share sheet and lets them choose an
 * app. Both are the same gesture from the page's point of view — "get this picture out of
 * Cove" — but they are not the same verb, so [actionLabel] comes from the platform rather
 * than being guessed at the call site.
 */
interface ImageExporter {
    /** What the control should say: "Save image" on a desktop, "Share" on a phone. */
    val actionLabel: String

    /**
     * Hands [png] to the platform. False when the viewer backed out, or nothing could take
     * it — either way the caller has nothing to apologise for and should simply stop.
     */
    suspend fun export(png: ByteArray, suggestedName: String): Boolean
}

/**
 * The exporter for this host, or null where there is nowhere to put a file.
 *
 * Null gates the control away entirely rather than leaving a button that does nothing, which
 * is the rule [canLoadSubtitleFile] already follows. Composable because Android needs a
 * `Context` to reach either the cache directory or the share sheet, and a plain top-level
 * function has no way to ask for one.
 */
@Composable
expect fun rememberImageExporter(): ImageExporter?

/**
 * The bitmap as PNG bytes, or null if this host could not encode it.
 *
 * Platform-specific because the two hosts hold a bitmap in genuinely different objects — a
 * Skia bitmap on the desktop, an `android.graphics.Bitmap` on Android — and neither knows
 * how to encode the other.
 */
expect fun ImageBitmap.encodeToPng(): ByteArray?
