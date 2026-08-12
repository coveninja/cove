package com.coveninja.cove.backend.nuvio

import com.coveninja.cove.backend.addons.AddonStream
import kotlinx.serialization.Serializable

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

@Serializable
internal data class NuvioInvocationResult(
    val streams: List<NuvioScrapedStream> = emptyList(),
    val error: String = "",
)

@Serializable
internal data class NuvioScrapedStream(
    val name: String = "",
    val title: String = "",
    val quality: String = "",
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
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

internal fun humanSizeToBytes(value: String): Long {
    val match = Regex("(?i)([\\d.]+)\\s*(TB|GB|MB|KB)").find(value) ?: return 0
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
