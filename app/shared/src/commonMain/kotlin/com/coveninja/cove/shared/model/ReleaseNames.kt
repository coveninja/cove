package com.coveninja.cove.shared.model

/**
 * The names a release of one title can legitimately carry, and the year it came out.
 *
 * Release groups name files after the English or the original title, almost never after the
 * viewer's own language, so the localized title on a card is not enough to tell whether a
 * stream is the thing that was asked for. Automatic source selection checks release names
 * against these before it plays anything on the viewer's behalf.
 */
data class ReleaseNames(
    val titles: List<String>,
    /** First release (a film) or first air date (a series); null when the catalog has none. */
    val year: Int? = null,
    /** ISO 639-1, for resolving an "original audio" preference without another lookup. */
    val originalLanguage: String? = null,
    /**
     * The title in other languages, each with the ISO 639-1 language it is in. A release named
     * "I Griffin" is still Family Guy; what its name says is that it is probably Italian.
     */
    val translatedTitles: Map<String, String> = emptyMap(),
)
