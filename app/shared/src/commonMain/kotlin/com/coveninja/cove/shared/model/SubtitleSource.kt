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
    /**
     * A provider-supplied title that is safe to show in the player.
     *
     * Stremio only requires an id, and some subtitle addons use that field for an encoded
     * download URL or an internal database key. Those values are useful for identity but are
     * not titles. Returning null lets the caller assign a short numbered fallback while still
     * preserving genuinely descriptive ids and filenames.
     */
    val displayName: String?
        get() {
            val value = id.trim()
            if (
                value.isEmpty() ||
                value.looksLikeUrl() ||
                value.looksLikeOpaqueSubtitleId()
            ) return null

            val leaf = value
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .trim()
            return leaf.takeUnless(String::looksLikeOpaqueSubtitleId)
        }
}

private fun String.looksLikeUrl(): Boolean =
    startsWith("//") ||
        ABSOLUTE_URL_SCHEME.containsMatchIn(this) ||
        startsWith("data:", ignoreCase = true) ||
        startsWith("urn:", ignoreCase = true) ||
        contains("%3a%2f%2f", ignoreCase = true)

private fun String.looksLikeOpaqueSubtitleId(): Boolean =
    isEmpty() ||
        VERSIONED_TOKEN.matches(this) ||
        LONG_TOKEN.matches(this) ||
        NUMERIC_TOKEN.matches(this) ||
        PROVIDER_NUMERIC_KEY.matches(this)

private val ABSOLUTE_URL_SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.-]*://""")
private val VERSIONED_TOKEN = Regex(
    """^v\d+_[A-Za-z0-9_+/\-]+={0,2}$""",
    RegexOption.IGNORE_CASE,
)
private val LONG_TOKEN = Regex("""^[A-Za-z0-9_+/\-]{32,}={0,2}$""")
private val NUMERIC_TOKEN = Regex("""^\d{4,}$""")
private val PROVIDER_NUMERIC_KEY = Regex(
    """^[A-Za-z][A-Za-z0-9._-]{1,32}[:#]\d{4,}$""",
    RegexOption.IGNORE_CASE,
)
