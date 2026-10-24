package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.model.StreamSource
import com.coveninja.cove.shared.model.describedText

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
    /** Two-letter codes, in the order the release mentions them. */
    val languages: List<String>,
    /** Marked dual-audio or multi, so the wanted language is probably in there. */
    val multi: Boolean,
) {
    val isEmpty: Boolean get() = languages.isEmpty() && !multi
}

internal fun StreamSource.audioHints(): AudioHints = parseAudioHints(describedText())

internal fun parseAudioHints(text: String): AudioHints {
    // Brazilian Portuguese is spelled with a hyphen that tokenising would split into two
    // letters too short to read; join it first.
    val tokens = text.lowercase()
        .replace(PT_BR, "ptbr")
        .split(*TOKEN_SEPARATORS)
        .filter { it.isNotEmpty() }
    val languages = mutableListOf<String>()
    var multi = false

    tokens.forEachIndexed { index, token ->
        // Three characters minimum: "de", "it" and "en" are ordinary words in film titles,
        // and a two-letter entry would start matching them the day someone adds one.
        if (token.length < 3) return@forEachIndexed
        when {
            // "Multi Subs" and "MultiSub" describe subtitles, which say nothing about the audio.
            token.startsWith("multisub") -> Unit
            token == "multi" && tokens.getOrNull(index + 1)?.startsWith("sub") == true -> Unit
            token in MULTI_MARKERS || MULTI_AUDIO.matches(token) -> multi = true
            else -> AUDIO_TOKENS[token]?.let { if (it !in languages) languages += it }
        }
    }
    // Torrentio and its relatives append the release's languages as flags on a line of
    // their own ("🇬🇧 / 🇮🇹"), which is the most reliable language signal there is.
    flagLanguages(text).forEach { if (it !in languages) languages += it }
    return AudioHints(languages, multi)
}

/**
 * How well a source's audio matches what the viewer asked for, as a score adjustment.
 *
 * [preferred] are two-letter codes in the viewer's order, with "original" already resolved.
 * An unmarked release is neutral: most releases say nothing, and demoting all of them would
 * rank by noise. A release that names only other languages is almost certainly a dub, and is
 * pushed well down; one that names a wanted language alongside others is a dual-audio release
 * whose default track may still be the other one, so it gives up a little.
 */
internal fun languageScore(hints: AudioHints, preferred: List<String>): Double {
    if (preferred.isEmpty() || hints.languages.isEmpty()) return 0.0
    val wanted = hints.languages.filter { it in preferred }
    return when {
        wanted.size == hints.languages.size -> WANTED_LANGUAGE_BONUS
        wanted.isNotEmpty() -> MIXED_LANGUAGE_PENALTY
        hints.multi -> UNNAMED_MULTI_PENALTY
        else -> DUB_PENALTY
    }
}

/** Two-letter codes from flag emoji, in order. */
internal fun flagLanguages(text: String): List<String> {
    val found = mutableListOf<String>()
    var index = 0
    while (index < text.length) {
        val first = text.codePointAtOrNull(index)
        if (first == null) {
            index++
            continue
        }
        val firstWidth = if (first > 0xFFFF) 2 else 1
        val second = text.codePointAtOrNull(index + firstWidth)
        if (first in REGIONAL_INDICATORS && second != null && second in REGIONAL_INDICATORS) {
            val country = "${'A' + (first - REGIONAL_INDICATORS.first)}${'A' + (second - REGIONAL_INDICATORS.first)}"
            FLAG_LANGUAGES[country]?.let { if (it !in found) found += it }
            index += firstWidth * 2
        } else {
            index += firstWidth
        }
    }
    return found
}

/** A code point from UTF-16, joining a surrogate pair; common code has no String.codePointAt. */
private fun String.codePointAtOrNull(index: Int): Int? {
    if (index !in indices) return null
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) {
            return ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000
        }
    }
    return high.code
}

private const val WANTED_LANGUAGE_BONUS = 0.3
private const val MIXED_LANGUAGE_PENALTY = -0.25
private const val UNNAMED_MULTI_PENALTY = -1.0
private const val DUB_PENALTY = -2.5

private val REGIONAL_INDICATORS = 0x1F1E6..0x1F1FF

private val PT_BR = Regex("pt[-_ ]?br")

private val TOKEN_SEPARATORS = charArrayOf(
    ' ', '.', '-', '_', '+', '[', ']', '(', ')', '{', '}', '/', ',', '|', ':',
    '\n', '\r', '\t', '*', '~', '!', ';', '"', '\'', '&', '#', '@', '<', '>', '=',
)

private val MULTI_MARKERS = setOf("dual", "multi", "dualaudio", "multiaudio")

/** "MULTi3", "Multi4" — a count of audio tracks. */
private val MULTI_AUDIO = Regex("multi\\d{1,2}")

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
    "vff" to "fr", "vfq" to "fr", "vfi" to "fr", "vf2" to "fr",
    "ger" to "de", "deu" to "de", "german" to "de",
    "ita" to "it", "italian" to "it",
    "rus" to "ru", "russian" to "ru",
    "kor" to "ko", "korean" to "ko",
    "chi" to "zh", "zho" to "zh", "chinese" to "zh", "mandarin" to "zh",
    "por" to "pt", "portuguese" to "pt", "brazilian" to "pt", "ptbr" to "pt", "dublado" to "pt",
    "hin" to "hi", "hindi" to "hi",
    "tam" to "ta", "tamil" to "ta",
    "tel" to "te", "telugu" to "te",
    "ara" to "ar", "arabic" to "ar",
    "tur" to "tr", "turkish" to "tr",
    // A Polish "lektor" is a voice-over on top of the original, and it is what plays first.
    "pol" to "pl", "polish" to "pl", "lektor" to "pl",
    "dut" to "nl", "nld" to "nl", "dutch" to "nl",
    "swe" to "sv", "swedish" to "sv",
    "dan" to "da", "danish" to "da",
    "nor" to "no", "norwegian" to "no",
    "fin" to "fi", "finnish" to "fi",
    "ukr" to "uk", "ukrainian" to "uk",
    "tha" to "th", "thai" to "th",
    "vie" to "vi", "vietnamese" to "vi",
    "ind" to "id", "indonesian" to "id",
    "cze" to "cs", "ces" to "cs", "czech" to "cs",
    "hun" to "hu", "hungarian" to "hu",
    "gre" to "el", "greek" to "el",
    "heb" to "he", "hebrew" to "he",
    "romanian" to "ro",
)

private val FLAG_LANGUAGES: Map<String, String> = buildMap {
    listOf("GB", "US", "AU", "CA", "NZ", "IE").forEach { put(it, "en") }
    listOf("ES", "MX", "AR", "CO", "CL", "PE", "VE").forEach { put(it, "es") }
    listOf("PT", "BR").forEach { put(it, "pt") }
    listOf("CN", "TW", "HK").forEach { put(it, "zh") }
    listOf("SA", "AE", "EG").forEach { put(it, "ar") }
    putAll(
        mapOf(
            "IT" to "it", "FR" to "fr", "DE" to "de", "AT" to "de", "RU" to "ru", "UA" to "uk",
            "PL" to "pl", "IN" to "hi", "JP" to "ja", "KR" to "ko", "SE" to "sv", "NO" to "no",
            "DK" to "da", "FI" to "fi", "NL" to "nl", "TR" to "tr", "GR" to "el", "CZ" to "cs",
            "HU" to "hu", "RO" to "ro", "IL" to "he", "TH" to "th", "VN" to "vi", "ID" to "id",
            "BG" to "bg", "RS" to "sr", "HR" to "hr", "SK" to "sk", "SI" to "sl", "LT" to "lt",
            "LV" to "lv", "EE" to "et", "IR" to "fa", "PH" to "tl", "MY" to "ms",
        ),
    )
}
