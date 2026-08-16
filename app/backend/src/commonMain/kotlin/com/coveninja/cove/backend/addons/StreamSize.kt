package com.coveninja.cove.backend.addons

/**
 * Reads a byte count out of provider display text.
 *
 * Stremio's stream shape has a size field, but the busiest providers do not fill
 * it in: Torrentio answers with neither `sizeBytes` nor `behaviorHints.videoSize`
 * and writes the size into the title instead, as
 * `👤 109 💾 54.33 GB ⚙️ TorrentGalaxy`. Without this the picker had nothing to
 * show and the ranking tie-break on size compared zero against zero.
 *
 * The 💾 marker is tried first because it is unambiguous; the bare form is the
 * fallback for providers that write a plain "1.4 GB", which is also what the
 * Nuvio scrapers hand over in their own size field.
 */
internal fun humanSizeToBytes(value: String): Long {
    val match = MARKED_SIZE.find(value) ?: BARE_SIZE.find(value) ?: return 0
    val amount = match.groupValues[1].toDoubleOrNull() ?: return 0
    val multiplier = when (match.groupValues[2].uppercase()) {
        "TB" -> 1L shl 40
        "GB" -> 1L shl 30
        "MB" -> 1L shl 20
        "KB" -> 1L shl 10
        else -> return 0
    }
    return (amount * multiplier).toLong()
}

private const val SIZE_BODY = "([\\d.]+)\\s*(TB|GB|MB|KB)"
private val MARKED_SIZE = Regex("(?i)💾\\s*$SIZE_BODY")
private val BARE_SIZE = Regex("(?i)$SIZE_BODY")
