package com.coveninja.cove.ui.components.player

import com.coveninja.cove.ui.state.MediaTrack

/** Tracks sharing a language, in the order the file lists them. */
internal data class TrackGroup(
    val languageLabel: String,
    val tracks: List<MediaTrack>,
)

/**
 * Groups tracks by language for the picker.
 *
 * A release can carry several subtitles in one language — full, signs-and-songs,
 * SDH, or regional cuts like es-419 next to es-ES — and a flat list of those is
 * unreadable. Regional variants collapse into their base language so they sit
 * together, with whatever distinguishes them left on the individual entries.
 *
 * Ordering is alphabetical with unknown languages last, deliberately independent
 * of what is currently selected: a menu that reorders itself between openings is
 * harder to use than one that is merely long.
 */
internal fun groupTracksByLanguage(tracks: List<MediaTrack>): List<TrackGroup> =
    tracks
        .groupBy { it.baseLanguage() }
        .map { (code, grouped) -> TrackGroup(languageLabel(code), grouped) }
        .sortedWith(
            compareBy(
                { it.languageLabel == UNKNOWN_LANGUAGE },
                { it.languageLabel.lowercase() },
            ),
        )

/**
 * The part before any region subtag, lowercased. `es-419` and `es-ES` are both
 * Spanish; keeping them apart would defeat the grouping.
 */
internal fun MediaTrack.baseLanguage(): String =
    language
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.substringBefore('-')
        ?.lowercase()
        .orEmpty()

/**
 * What to show for a track inside its language group.
 *
 * The track's own title is the useful thing — "Signs & Songs", "Latin America",
 * "SDH". Without one there is nothing to distinguish it by except its position
 * in the file.
 */
internal fun MediaTrack.detailLabel(): String =
    title.trim().takeIf { it.isNotEmpty() }
        ?: language?.trim()?.takeIf { it.contains('-') }
        ?: "Track $id"

private const val UNKNOWN_LANGUAGE = "Unknown"

/**
 * Common two- and three-letter codes. mpv passes through whatever the container
 * declares, so both forms turn up; anything unrecognised shows as the code
 * itself, which beats hiding it behind a guess.
 */
private fun languageLabel(code: String): String = when (code) {
    "" -> UNKNOWN_LANGUAGE
    "en", "eng" -> "English"
    "ja", "jpn" -> "Japanese"
    "es", "spa" -> "Spanish"
    "fr", "fre", "fra" -> "French"
    "de", "ger", "deu" -> "German"
    "it", "ita" -> "Italian"
    "pt", "por" -> "Portuguese"
    "ru", "rus" -> "Russian"
    "ar", "ara" -> "Arabic"
    "zh", "chi", "zho" -> "Chinese"
    "ko", "kor" -> "Korean"
    "tr", "tur" -> "Turkish"
    "nl", "dut", "nld" -> "Dutch"
    "pl", "pol" -> "Polish"
    "sv", "swe" -> "Swedish"
    "da", "dan" -> "Danish"
    "no", "nor" -> "Norwegian"
    "fi", "fin" -> "Finnish"
    "cs", "cze", "ces" -> "Czech"
    "el", "gre", "ell" -> "Greek"
    "he", "heb" -> "Hebrew"
    "hi", "hin" -> "Hindi"
    "th", "tha" -> "Thai"
    "vi", "vie" -> "Vietnamese"
    "id", "ind" -> "Indonesian"
    "uk", "ukr" -> "Ukrainian"
    "hu", "hun" -> "Hungarian"
    "ro", "rum", "ron" -> "Romanian"
    else -> code.uppercase()
}
