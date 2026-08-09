package com.coveninja.cove.shared.model

import kotlinx.serialization.Serializable

// Maps to the addons.Stream shape returned by /api/streams.
//
// The backend answers with the richer addons.AddonStream; CoveJson sets
// ignoreUnknownKeys, so this stays a deliberate subset. The second group exists
// because the source picker and the torrent play URL need it — sizeBytes and
// cached to rank and label candidates, fileIdx because a torrent carrying
// several files cannot be narrowed to one without it.
@Serializable
data class StreamSource(
    val name: String? = null,
    val title: String? = null,
    val url: String? = null,
    val infoHash: String? = null,
    val addonName: String? = null,
    val sizeBytes: Long = 0,
    val fileIdx: Int? = null,
    val cached: Boolean = false,
)
