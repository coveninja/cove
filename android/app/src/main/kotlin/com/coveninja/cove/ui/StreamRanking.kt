package com.coveninja.cove.ui

import com.coveninja.cove.api.Stream

// inferQuality checks the second line of stream.name first (structured quality tag),
// then falls back to full name+title text scan — mirrors streamSelection.ts.
fun inferQuality(stream: Stream): String? {
    val qualityLine = stream.name.split("\n").getOrNull(1)?.trim()?.lowercase()
    if (qualityLine != null) {
        for (q in QUALITY_ORDER) if (qualityLine.contains(q)) return q
    }
    val text = "${stream.name} ${stream.title}".lowercase()
    return when {
        text.contains("dolby vision") || text.contains("4k dv") -> "4k dv"
        text.contains("hdr") -> "4k hdr"
        text.contains("2160") || text.contains("4k") -> "4k"
        text.contains("1080") -> "1080p"
        text.contains("720") -> "720p"
        text.contains("480") -> "480p"
        text.contains("telesync") || text.contains("ts ") || text.contains("[ts]") -> "ts"
        text.contains("hdcam") || text.contains("cam") -> "cam"
        else -> null
    }
}

val QUALITY_ORDER = listOf("4k dv", "4k hdr", "4k", "1080p", "720p", "480p", "ts", "cam")

private val QUALITY_RANK = mapOf(
    "4k dv" to 7, "4k hdr" to 6, "4k" to 5,
    "1080p" to 4, "720p" to 3, "480p" to 2, "ts" to 1, "cam" to 0
)

fun qualityRank(q: String?): Int = QUALITY_RANK[q] ?: -1

fun isTorrentStream(s: Stream) = s.infoHash.isNotEmpty()

fun getSeeders(s: Stream): Int =
    Regex("""👤\s*(\d+)""").find(s.title)?.groupValues?.get(1)?.toIntOrNull() ?: 0

fun getSizeBytes(s: Stream): Long {
    if (s.sizeBytes > 0) return s.sizeBytes
    val m = Regex("""💾\s*([\d.]+)\s*(TB|GB|MB)""", RegexOption.IGNORE_CASE).find(s.title)
        ?: return 0L
    val v = m.groupValues[1].toDoubleOrNull() ?: return 0L
    return when (m.groupValues[2].uppercase()) {
        "TB" -> (v * 1024.0.pow(4)).toLong()
        "GB" -> (v * 1024.0.pow(3)).toLong()
        "MB" -> (v * 1024.0.pow(2)).toLong()
        else -> 0L
    }
}

private fun Double.pow(n: Int): Double = Math.pow(this, n.toDouble())

// Balanced rank: seeders * 0.6 + (1 - size/maxSize) * 0.4
fun rankStreams(streams: List<Stream>): List<Stream> {
    if (streams.isEmpty()) return emptyList()
    data class Scored(val s: Stream, val seeders: Int, val size: Long, val quality: String?)
    val scored = streams.map { Scored(it, getSeeders(it), getSizeBytes(it), inferQuality(it)) }
    val maxSeeders = scored.maxOf { it.seeders }.coerceAtLeast(1)
    val knownSizes = scored.map { it.size }.filter { it > 0 }
    val maxSize = knownSizes.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    return scored.sortedByDescending { c ->
        val rel = if (isTorrentStream(c.s)) c.seeders.toDouble() / maxSeeders else 0.6
        val sz = if (c.size > 0) 1.0 - c.size.toDouble() / maxSize else 0.5
        rel * 0.6 + sz * 0.4
    }.map { it.s }
}
