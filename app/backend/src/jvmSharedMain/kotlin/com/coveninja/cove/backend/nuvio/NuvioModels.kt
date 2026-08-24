package com.coveninja.cove.backend.nuvio

import com.coveninja.cove.backend.addons.AddonStream
import com.coveninja.cove.backend.addons.humanSizeToBytes
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class NuvioScraper(
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "",
    val filename: String,
    val supportedTypes: List<String> = emptyList(),
    val logo: String = "",
    val contentLanguage: List<String> = emptyList(),
    val supportsExternalPlayer: Boolean = false,
    val enabled: Boolean = false,
    val code: String = "",
    val codeFetchedAt: String? = null,
    val codeErr: String = "",
)

@Serializable
data class NuvioRepo(
    val id: String,
    val owner: String,
    val repo: String,
    val branch: String,
    val url: String,
    val enabled: Boolean = false,
    val scrapers: List<NuvioScraper> = emptyList(),
    val fetchedAt: String,
    val fetchErr: String = "",
)

@Serializable
internal data class NuvioStore(
    val repos: List<NuvioRepo> = emptyList(),
    val updatedAt: String = "",
)

@Serializable
internal data class NuvioManifestEntry(
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "",
    val filename: String,
    val supportedTypes: List<String> = emptyList(),
    val logo: String = "",
    val contentLanguage: List<String> = emptyList(),
    val supportsExternalPlayer: Boolean = false,
) {
    fun toScraper(previous: NuvioScraper? = null) = NuvioScraper(
        id,
        name,
        description,
        version,
        filename,
        supportedTypes,
        logo,
        contentLanguage,
        supportsExternalPlayer,
        enabled = previous?.enabled == true,
        code = previous?.code.orEmpty(),
        codeFetchedAt = previous?.codeFetchedAt,
        codeErr = previous?.codeErr.orEmpty(),
    )
}

@Serializable
internal data class NuvioInvocation(
    val scraperId: String,
    val code: String,
    val tmdbId: Int,
    val mediaType: String,
    val title: String,
    val year: Int,
    val imdbId: String,
    val season: Int? = null,
    val episode: Int? = null,
)

/** One child process runs a whole request's worth of scrapers; this is what it is handed. */
@Serializable
internal data class NuvioBatch(
    val invocations: List<NuvioInvocation>,
    val concurrency: Int,
    val perScraperTimeoutMillis: Long,
)

/**
 * One scraper's answer. The worker emits these one JSON object per line as each finishes rather
 * than one array at the end, so a batch killed at its deadline still yields everything that
 * already came back.
 */
@Serializable
internal data class NuvioBatchOutcome(
    val scraperId: String,
    val streams: List<NuvioScrapedStream> = emptyList(),
    val error: String = "",
    /** How long this one scraper took, so a slow fan-out can be attributed rather than guessed. */
    val elapsedMillis: Long = 0,
)

@Serializable
internal data class NuvioInvocationResult(
    val streams: List<NuvioScrapedStream> = emptyList(),
    val error: String = "",
)

@Serializable
internal data class NuvioScrapedStream(
    @Serializable(LenientString::class) val name: String = "",
    @Serializable(LenientString::class) val title: String = "",
    @Serializable(LenientString::class) val quality: String = "",
    val url: String = "",
    @Serializable(LenientStringMap::class) val headers: Map<String, String> = emptyMap(),
    val size: kotlinx.serialization.json.JsonPrimitive? = null,
) {
    fun toAddonStream(): AddonStream? {
        if (url.isBlank()) return null
        val parsedSize = size?.let { raw ->
            raw.content.toLongOrNull() ?: humanSizeToBytes(raw.content)
        } ?: 0L
        return AddonStream(
            name = name.ifBlank { quality },
            title = title,
            url = url,
            addonName = "Nuvio · $name".trimEnd(),
            headers = headers,
            sizeBytes = parsedSize,
        )
    }
}


/**
 * A string field that also accepts a number or a boolean.
 *
 * Provider scripts are hand-written JavaScript with no schema between them and this: several
 * emit `"quality": 1080` where their neighbours emit `"quality": "1080p"`. Decoding is
 * all-or-nothing per scraper, so one such field discarded that provider's entire result set for
 * the title — silently, as far as the viewer could tell, since the others still answered.
 */
internal object LenientString : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("LenientString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val element = json.decodeJsonElement()) {
            is JsonNull -> ""
            is JsonPrimitive -> element.content
            else -> ""
        }
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

/** The same tolerance for header maps, whose values arrive as numbers just as readily. */
internal object LenientStringMap : KSerializer<Map<String, String>> {
    private val delegate = MapSerializer(String.serializer(), LenientString)

    override val descriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): Map<String, String> {
        val json = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val element = json.decodeJsonElement()
        if (element !is JsonObject) return emptyMap()
        return element.mapNotNull { (name, value) ->
            (value as? JsonPrimitive)?.let { name to it.content }
        }.toMap()
    }

    override fun serialize(encoder: Encoder, value: Map<String, String>) =
        delegate.serialize(encoder, value)
}
