package com.coveninja.cove.backend.addons

import com.coveninja.cove.shared.model.MediaType
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
    // Blank for the two built-in integrations, which are dispatched by id and
    // have no manifest to fetch. This build writes them as "official:<id>", but
    // rows synced from an older one carry no url field at all — and a missing
    // value here used to fail the decode, which aborted the entire sync.
    val url: String = "",
    val manifest: AddonManifest,
    val kind: AddonKind,
    val source: String = "stremio",
    val enabled: Boolean = true,
    val disabledCatalogs: Map<String, Boolean> = emptyMap(),
    // Inherited from the primary profile rather than stored against this one.
    // Never written by persist() and never read back by toModel(), so every
    // stored row is false and only AddonManager.entries() ever sets it.
    val managed: Boolean = false,
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

/**
 * How a catalog's enable flag is keyed, in `disabledCatalogs` and over the wire.
 *
 * A catalog id is only unique within its addon and within its type, so the type is part
 * of the key. Spelled once here because the manager, the repository mapping and the
 * shared descriptor all have to agree on it.
 */
fun AddonCatalog.key(): String = "$type/$id"

/**
 * The app's own media type for a catalog entry, or null when the addon named one this
 * app does not model. Stremio says "series" where this app says [MediaType.Tv], and
 * some addons say "tv" instead.
 */
fun AddonCatalogItem.mediaType(): MediaType? = when (type) {
    "movie" -> MediaType.Movie
    "series", "tv" -> MediaType.Tv
    else -> null
}

/**
 * The TMDB id an entry names directly, or null when it names something else.
 *
 * Only the `tmdb:` form is direct. The trailing segments of an episode id
 * (`tmdb:1396:1:1`) are dropped: the row shows the show, not the episode. An IMDB
 * `tt…` id is *not* handled here — that needs a lookup, so it belongs to whichever
 * implementation can make one.
 */
fun AddonCatalogItem.tmdbId(): Int? = id.takeIf { it.startsWith("tmdb:") }
    ?.removePrefix("tmdb:")
    ?.substringBefore(':')
    ?.toIntOrNull()
    ?.takeIf { it > 0 }

/** An entry naming an IMDB title, which needs an external lookup to resolve. */
fun AddonCatalogItem.imdbId(): String? = id.takeIf { it.startsWith("tt") }

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
