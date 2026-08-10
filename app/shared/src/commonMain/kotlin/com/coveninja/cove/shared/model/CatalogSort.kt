package com.coveninja.cove.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a browsed page of the catalog is ordered.
 *
 * Deliberately provider-neutral: [wireName] is what crosses the HTTP boundary and what a
 * query parameter carries, while the translation to a provider's own sort vocabulary
 * (TMDB's `sort_by`, which spells the same idea differently for films and series) belongs
 * to the client that talks to it. Presentation labels belong to the UI.
 */
@Serializable
enum class CatalogSort(val wireName: String) {
    @SerialName("popularity") Popularity("popularity"),
    @SerialName("rating") Rating("rating"),
    @SerialName("newest") Newest("newest"),
    @SerialName("oldest") Oldest("oldest"),
    @SerialName("title") Title("title"),
    ;

    companion object {
        /** Unknown and absent values both fall back to [Popularity] rather than failing a request. */
        fun fromWire(value: String?): CatalogSort =
            entries.firstOrNull { it.wireName == value } ?: Popularity
    }
}
