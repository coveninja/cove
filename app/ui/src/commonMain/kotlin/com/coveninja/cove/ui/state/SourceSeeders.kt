package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.model.StreamSource

/**
 * How many peers a torrent source claims to have.
 *
 * Stremio's stream shape has no field for this, so every provider that reports
 * it writes it into the display text instead. Torrentio, Comet and AIOStreams
 * all use the same convention — a second line reading
 * `👤 109 💾 54.33 GB ⚙️ TorrentGalaxy` — which [StreamSource.displayLabel]
 * throws away when it takes the first line as the release name.
 *
 * Only an explicit marker counts. A release name is mostly numbers (`2160p`,
 * `x265`, `5.1`, the year), so a bare integer is never read as a peer count.
 */
internal fun StreamSource.seederCount(): Int? =
    parseSeederCount("${name.orEmpty()} ${title.orEmpty()}")

internal fun parseSeederCount(text: String): Int? =
    SEEDER_PATTERNS.firstNotNullOfOrNull { pattern ->
        pattern.find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
    }

private val SEEDER_PATTERNS = listOf(
    // 👤 / 👥 immediately followed by the count. What Torrentio, Comet and
    // AIOStreams emit, and unambiguous enough to try first.
    Regex("(?:👤|👥)\\s*([\\d,]+)"),
    // "Seeders: 109", "seeds 109".
    Regex("(?i)\\bseed(?:er)?s?\\s*[:=]?\\s*([\\d,]+)"),
    // "109 seeders".
    Regex("(?i)\\b([\\d,]+)\\s*seed(?:er)?s?\\b"),
)

/**
 * How a peer count reads at a glance.
 *
 * The thresholds are about what the number means for the next ten minutes, not
 * about swarm size: nothing at all will never start, single digits will start
 * and then stall, and past that the count stops being the deciding factor.
 */
internal enum class SeederHealth { Dead, Thin, Healthy }

internal fun seederHealth(count: Int): SeederHealth = when {
    count <= 0 -> SeederHealth.Dead
    count < 10 -> SeederHealth.Thin
    else -> SeederHealth.Healthy
}
