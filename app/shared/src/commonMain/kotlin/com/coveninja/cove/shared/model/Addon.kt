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

/**
 * One catalog an addon offers, as the app needs to talk about it.
 *
 * Only catalogs that can be drawn without the viewer supplying something first reach
 * here — a catalog whose manifest marks `search` or `genre` required has no "just show
 * me it" answer and is not a row. [catalogId] is the addon's own id for it and is only
 * unique within that addon, which is why [addonId] travels alongside; the pair spelled
 * `"$type/$catalogId"` is the key the enable flag is stored under.
 */
@Serializable
data class AddonCatalogDescriptor(
    val addonId: String,
    val addonName: String,
    val addonUrl: String,
    /**
     * Stremio's own word: `movie` or `series`. Spelled `catalogType` on the wire, which
     * is what `GET /catalogs` has always called it — renaming it here would silently
     * empty the list for every compatibility client.
     */
    @SerialName("catalogType") val type: String,
    val catalogId: String,
    val name: String,
    val enabled: Boolean = true,
) {
    /** How the enable flag is keyed, both in the store and over the wire. */
    val key: String get() = "$type/$catalogId"

    /** What the row is called. Addons name catalogs "Popular" and leave the rest to us. */
    val displayTitle: String get() = if (name.isBlank()) addonName else "$addonName · $name"
}

/**
 * One page of an addon catalog, already resolved onto [Media].
 *
 * [nextSkip] advances by the entries the source *consumed*, not the ones that survived
 * resolution — an entry naming a title this app cannot key on is dropped, and paging by
 * the survivors would ask for the dropped ones again forever.
 */
@Serializable
data class AddonCatalogPage(
    val medias: List<Media> = emptyList(),
    val nextSkip: Int = 0,
)

@Serializable
data class Addon(
    val id: String,
    val url: String,
    val manifest: AddonManifestSummary = AddonManifestSummary(),
    val kind: AddonKind = AddonKind.Provider,
    val source: String = "stremio",
    val enabled: Boolean = true,
    /**
     * The catalogs this addon offers, drawable ones only. Carried on the addon rather
     * than fetched separately so the settings screen can draw a switch per catalog
     * without a second round trip.
     */
    val catalogs: List<AddonCatalogDescriptor> = emptyList(),
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
