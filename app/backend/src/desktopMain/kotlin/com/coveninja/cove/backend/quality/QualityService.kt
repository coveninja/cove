package com.coveninja.cove.backend.quality

import com.coveninja.cove.backend.addons.AddonManager
import com.coveninja.cove.backend.addons.AddonStream
import com.coveninja.cove.backend.content.TmdbClient
import com.coveninja.cove.shared.model.MediaType
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable

fun interface QualityLookup {
    fun lookup(rawIds: String): Flow<QualityEntry>
}

class QualityService(
    private val catalog: TmdbClient,
    private val addons: AddonManager,
    private val clock: Clock = Clock.systemUTC(),
) : QualityLookup {
    private val cache = ConcurrentHashMap<String, CachedQuality>()

    override fun lookup(rawIds: String): Flow<QualityEntry> = channelFlow {
        val ids = parseQualityIds(rawIds)
        val semaphore = Semaphore(5)
        ids.map { media ->
            launch {
                semaphore.withPermit {
                    val now = clock.millis()
                    cache[media.typedId]?.takeIf { it.expiresAt > now }?.let { cached ->
                        if (cached.quality.isNotBlank()) send(QualityEntry(media.typedId, cached.quality))
                        return@withPermit
                    }
                    val quality = runCatching {
                        val imdbId = catalog.imdbId(media.tmdbId, media.type)
                        val stremioId = if (media.type == MediaType.Tv) "$imdbId:1:1" else imdbId
                        maxQuality(addons.streams(media.type, stremioId))
                    }.getOrDefault("")
                    val ttl = if (quality.isBlank()) EMPTY_TTL_MILLIS else HIT_TTL_MILLIS
                    cache[media.typedId] = CachedQuality(quality, now + ttl)
                    if (quality.isNotBlank()) send(QualityEntry(media.typedId, quality))
                }
            }
        }.joinAll()
    }

    private data class CachedQuality(val quality: String, val expiresAt: Long)

    private companion object {
        const val HIT_TTL_MILLIS = 15 * 60 * 1_000L
        const val EMPTY_TTL_MILLIS = 5 * 60 * 1_000L
    }
}

@Serializable
data class QualityEntry(val id: String, val quality: String)

internal data class QualityMediaId(val typedId: String, val type: MediaType, val tmdbId: Int)

internal fun parseQualityIds(raw: String): List<QualityMediaId> {
    require(raw.isNotBlank()) { "missing ids" }
    val tokens = raw.split(',')
    require(tokens.size <= 100) { "at most 100 ids may be checked at once" }
    return tokens.mapNotNull { token ->
        val value = token.trim()
        val parts = value.split(':', limit = 2)
        val (type, numeric) = when {
            parts.size == 1 -> MediaType.Movie to parts[0]
            parts[0] == "movie" -> MediaType.Movie to parts[1]
            parts[0] == "tv" -> MediaType.Tv to parts[1]
            else -> return@mapNotNull null
        }
        val id = numeric.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        QualityMediaId("${type.wireName}:$id", type, id)
    }.distinctBy(QualityMediaId::typedId)
}

internal fun maxQuality(streams: List<AddonStream>): String {
    val found = streams.mapNotNull(::inferQuality).toSet()
    return QUALITY_ORDER.firstOrNull(found::contains).orEmpty()
}

private fun inferQuality(stream: AddonStream): String? {
    val secondLine = stream.name.lineSequence().drop(1).firstOrNull()?.trim()?.lowercase().orEmpty()
    QUALITY_ORDER.firstOrNull(secondLine::contains)?.let { return it }
    val text = "${stream.name} ${stream.title}".lowercase()
    return when {
        "dolby vision" in text || "4k dv" in text -> "4k dv"
        "hdr" in text -> "4k hdr"
        "2160" in text || "4k" in text || "uhd" in text -> "4k"
        "fhd" in text || "full hd" in text || "1080" in text -> "1080p"
        "720" in text -> "720p"
        "480" in text -> "480p"
        "telesync" in text || "ts " in text || "[ts]" in text -> "ts"
        "hdcam" in text || "cam" in text -> "cam"
        else -> null
    }
}

private val QUALITY_ORDER = listOf("4k dv", "4k hdr", "4k", "1080p", "720p", "480p", "ts", "cam")
