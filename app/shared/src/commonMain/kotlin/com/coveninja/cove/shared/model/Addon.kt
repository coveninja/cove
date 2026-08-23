package com.coveninja.cove.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Client-side views of the addon and Nuvio payloads.
 *
 * Deliberately narrower than the backend's own models — CoveJson sets
 * ignoreUnknownKeys, so the parts the UI never shows (catalog descriptors,
 * scraper source code) are simply not modelled here. :shared cannot see
 * :backend in any case; that dependency runs the other way.
 */
@Serializable
data class AddonManifestSummary(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val version: String = "",
    val types: List<String> = emptyList(),
)

@Serializable
enum class AddonKind {
    @SerialName("provider") Provider,
    @SerialName("subtitle") Subtitle,
    @SerialName("timestamps") Timestamps,
}

@Serializable
data class Addon(
    val id: String,
    val url: String,
    val manifest: AddonManifestSummary = AddonManifestSummary(),
    val kind: AddonKind = AddonKind.Provider,
    val source: String = "stremio",
    val enabled: Boolean = true,
    /**
     * Inherited from the primary profile rather than owned by this one, and so
     * read-only here. Carried over the wire deliberately: the compatibility HTTP
     * client is the only thing between [AddonRepository] and the backend, so a
     * transient marker would leave that client offering controls that fail.
     */
    val managed: Boolean = false,
) {
    val displayName: String
        get() = manifest.name.ifBlank { url.substringAfter("://").substringBefore('/') }
}

@Serializable
data class NuvioScraperSummary(
    val id: String,
    val name: String = "",
    val description: String = "",
    val version: String = "",
    val enabled: Boolean = false,
    @SerialName("codeErr") val codeError: String = "",
)

@Serializable
data class NuvioRepoSummary(
    val id: String,
    val owner: String = "",
    val repo: String = "",
    val branch: String = "",
    val url: String = "",
    val enabled: Boolean = false,
    val scrapers: List<NuvioScraperSummary> = emptyList(),
    @SerialName("fetchErr") val fetchError: String = "",
) {
    val displayName: String
        get() = if (owner.isNotBlank() && repo.isNotBlank()) "$owner/$repo" else url
}
