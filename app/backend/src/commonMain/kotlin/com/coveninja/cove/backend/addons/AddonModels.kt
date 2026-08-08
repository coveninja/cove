package com.coveninja.cove.backend.addons

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AddonManifest(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val version: String = "",
    val resources: List<JsonElement> = emptyList(),
    val types: List<String> = emptyList(),
    val catalogs: List<AddonCatalog> = emptyList(),
)

@Serializable
data class AddonCatalog(
    val type: String = "",
    val id: String = "",
    val name: String = "",
    val extra: List<AddonCatalogExtra> = emptyList(),
    val extraRequired: List<String> = emptyList(),
    val extraSupported: List<String> = emptyList(),
)

@Serializable
data class AddonCatalogExtra(
    val name: String = "",
    val isRequired: Boolean = false,
)

@Serializable
data class AddonEntry(
    val id: String,
    val url: String,
    val manifest: AddonManifest,
    val kind: AddonKind,
    val source: String = "stremio",
    val enabled: Boolean = true,
    val disabledCatalogs: Map<String, Boolean> = emptyMap(),
)

@Serializable
enum class AddonKind {
    @SerialName("provider") Provider,
    @SerialName("subtitle") Subtitle,
    @SerialName("timestamps") Timestamps,
}

@Serializable
data class AddonSubtitle(
    val id: String = "",
    val url: String = "",
    val lang: String = "",
)

@Serializable
data class AddonStreamBehaviorHints(
    val notWebReady: Boolean = false,
    val bingeGroup: String = "",
    val videoSize: Long = 0,
    val filename: String = "",
)

@Serializable
data class AddonStream(
    val name: String = "",
    val title: String = "",
    val url: String = "",
    val infoHash: String = "",
    val addonName: String = "",
    val subtitles: List<AddonSubtitle> = emptyList(),
    val sizeBytes: Long = 0,
    val headers: Map<String, String> = emptyMap(),
    val behaviorHints: AddonStreamBehaviorHints? = null,
    val fileIdx: Int? = null,
    val cached: Boolean = false,
    val debrid: String = "",
)

@Serializable
internal data class AddonStreamsResponse(val streams: List<AddonStream> = emptyList())

@Serializable
internal data class LegacyAddonStore(
    val stremioAddons: List<AddonEntry> = emptyList(),
    val officialEnabled: Map<String, Boolean> = emptyMap(),
    val updatedAt: String = "",
)

@Serializable
data class AddonCatalogRef(
    val addonId: String,
    val addonName: String,
    val addonUrl: String,
    val catalogType: String,
    val catalogId: String,
    val name: String,
)

@Serializable
data class AddonCatalogItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String = "",
    val description: String = "",
    val releaseInfo: String = "",
)

@Serializable
data class AddonCatalogPage(
    val medias: List<com.coveninja.cove.shared.model.Media> = emptyList(),
    val nextSkip: Int = 0,
)

@Serializable
data class TimestampSegment(
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
)

@Serializable
data class TimestampData(
    val intro: List<TimestampSegment> = emptyList(),
    val recap: List<TimestampSegment> = emptyList(),
    val credits: List<TimestampSegment> = emptyList(),
    val preview: List<TimestampSegment> = emptyList(),
)

@Serializable
data class WatchOption(
    val providerId: Int,
    val providerName: String,
    val logoPath: String,
    val type: String,
    val link: String,
)

@Serializable
internal data class AddonSubtitlesResponse(val subtitles: List<AddonSubtitle> = emptyList())

@Serializable
internal data class AddonCatalogResponse(val metas: List<AddonCatalogItem> = emptyList())
