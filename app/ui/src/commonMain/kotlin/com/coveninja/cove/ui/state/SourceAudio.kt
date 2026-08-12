package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.model.StreamSource

/** Settings value meaning "whatever the title was made in". */
const val AUDIO_LANGUAGE_ORIGINAL = "original"

/**
 * What a release name says about its audio.
 *
 * Nothing else is available before playing: the stream list carries no track
 * information, so the release name is the only signal for whether a source is
 * the original audio or a dub. It is a hint and treated as one — an unmarked
 * source scores neutral rather than being pushed down.
 */
data class AudioHints(
    val languages: List<String>,
    /** Marked dual-audio or multi, so the wanted language is probably in there. */
    val multi: Boolean,
) {
    val isEmpty: Boolean get() = languages.isEmpty() && !multi
}

internal fun StreamSource.audioHints(): AudioHints = parseAudioHints("${name.orEmpty()} ${title.orEmpty()}")

internal fun parseAudioHints(text: String): AudioHints {
    // Tokens only, so a language name inside another word cannot match, and
    // three characters minimum.
    //
    // The length filter is defence in depth rather than load-bearing today: no
    // entry in AUDIO_TOKENS is shorter than three characters, so removing it
    // changes nothing on its own. It is what makes "never add two-letter codes"
    // enforceable — "de", "it" and "en" are ordinary words in film titles, and a
    // two-letter entry would start matching them the day someone adds one.
    val tokens = text.lowercase().split(*TOKEN_SEPARATORS).filter { it.length >= 3 }
    val languages = mutableListOf<String>()
    var multi = false

    tokens.forEach { token ->
        when {
            token in MULTI_MARKERS -> multi = true
            else -> AUDIO_TOKENS[token]?.let { if (it !in languages) languages += it }
        }
    }
    return AudioHints(languages, multi)
}

/**
 * How well a source's audio matches what the viewer asked for.
 *
 * Positive prefers, negative demotes, zero is "no idea" — which an unmarked
 * release must score, since most releases say nothing and demoting them all
 * would rank by nothing but noise.
 */
internal fun audioScore(
    hints: AudioHints,
    preferredLanguage: String?,
    originalLanguage: String?,
): Int {
    val wanted = when {
        preferredLanguage == AUDIO_LANGUAGE_ORIGINAL -> originalLanguage?.lowercase()
        else -> preferredLanguage?.lowercase()
    }?.takeIf { it.isNotBlank() } ?: return 0

    if (hints.isEmpty) return 0
    return when {
        wanted in hints.languages -> 2
        hints.multi -> 1
        // Named languages, none of them the one asked for: most likely a dub.
        hints.languages.isNotEmpty() -> -2
        else -> 0
    }
}

/**
 * Ranks candidates so the first row is the one most likely to be wanted:
 * matching audio first, then already-cached debrid links, then bigger files,
 * which in practice track higher bitrates.
 */
fun rankSources(
    sources: List<StreamSource>,
    preferredAudioLanguage: String? = null,
    originalLanguage: String? = null,
): List<StreamSource> =
    sources.sortedWith(
        compareByDescending<StreamSource> {
            audioScore(it.audioHints(), preferredAudioLanguage, originalLanguage)
        }
            .thenByDescending { it.cached }
            .thenByDescending { it.sizeBytes },
    )

private val TOKEN_SEPARATORS = charArrayOf(
    ' ', '.', '-', '_', '+', '[', ']', '(', ')', '{', '}', '/', ',', '|', ':',
)

private val MULTI_MARKERS = setOf("dual", "multi", "multisubs", "dualaudio")

/**
 * Three letters and up only. Release names use both ISO 639-2 codes and casual
 * abbreviations, so both are listed.
 */
private val AUDIO_TOKENS: Map<String, String> = mapOf(
    "jpn" to "ja", "jap" to "ja", "japanese" to "ja",
    "eng" to "en", "english" to "en",
    "spa" to "es", "esp" to "es", "spanish" to "es", "castellano" to "es", "latino" to "es",
    // Deliberately no "vostfr"/"vose": those mark original audio with foreign
    // subtitles, and reading them as French audio would demote exactly the
    // sources a viewer wanting original audio is after.
    "fre" to "fr", "fra" to "fr", "french" to "fr", "truefrench" to "fr",
    "ger" to "de", "deu" to "de", "german" to "de",
    "ita" to "it", "italian" to "it",
    "rus" to "ru", "russian" to "ru",
    "kor" to "ko", "korean" to "ko",
    "chi" to "zh", "zho" to "zh", "chinese" to "zh", "mandarin" to "zh",
    "por" to "pt", "portuguese" to "pt", "brazilian" to "pt",
    "hin" to "hi", "hindi" to "hi",
    "ara" to "ar", "arabic" to "ar",
    "tur" to "tr", "turkish" to "tr",
    "pol" to "pl", "polish" to "pl",
    "dut" to "nl", "nld" to "nl", "dutch" to "nl",
    "swe" to "sv", "swedish" to "sv",
    "dan" to "da", "danish" to "da",
    "nor" to "no", "norwegian" to "no",
    "fin" to "fi", "finnish" to "fi",
    "ukr" to "uk", "ukrainian" to "uk",
    "tha" to "th", "thai" to "th",
    "vie" to "vi", "vietnamese" to "vi",
    "ind" to "id", "indonesian" to "id",
)
