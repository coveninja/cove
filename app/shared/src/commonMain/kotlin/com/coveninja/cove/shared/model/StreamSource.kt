package com.coveninja.cove.shared.model

import kotlinx.serialization.Serializable

// Maps to the addons.Stream shape returned by /api/streams.
@Serializable
data class StreamSource(
    val name: String? = null,
    val title: String? = null,
    val url: String? = null,
    val infoHash: String? = null,
    val addonName: String? = null,
)
