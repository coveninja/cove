package com.coveninja.cove.shared.model

import kotlinx.serialization.Serializable

/**
 * A subtitle file offered by an addon, separate from any embedded in the media.
 *
 * Maps to the addons.AddonSubtitle shape returned by /api/subtitles.
 */
@Serializable
data class SubtitleSource(
    val id: String = "",
    val url: String = "",
    val lang: String = "",
) {
    /** Shown in the player's subtitle menu, which groups by language itself. */
    val displayName: String
        get() = id.takeIf { it.isNotBlank() }?.substringAfterLast('/') ?: "Subtitle"
}
