package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.model.AppSettings

/**
 * The viewer's playback preferences, resolved for one title.
 *
 * Built here rather than read from AppSettings at the point of use so the
 * awkward parts — Original resolving against the title's own language, and the
 * unit differences between what the settings store and what a player wants — are
 * decided once and can be tested.
 */
data class PlaybackPreferences(
    /** Language codes in preference order; empty means let the file decide. */
    val audioLanguages: List<String>,
    val subtitleLanguages: List<String>,
    val subtitlesEnabled: Boolean,
    val startMuted: Boolean,
    /** 1.0 is the player's own default size. */
    val subtitleScale: Double,
    /** 0 is the bottom of the frame, 100 the top — mpv's convention. */
    val subtitlePosition: Int,
    val subtitleBackground: Boolean,
    /**
     * Whether to decode on the GPU. Carried with the per-title preferences rather
     * than fixed when the player is built, so turning it off takes effect on the
     * next thing played instead of on the next launch.
     */
    val hardwareDecoding: Boolean,
)

/**
 * Every code a track might carry for one language, most specific first.
 *
 * TMDB reports a language as ISO 639-1 — "ja", "de" — and media files tag their
 * tracks with ISO 639-2, which has *two* codes per language: a terminological one
 * ("deu") and a bibliographic one ("ger"), and releases use both. Handing a player
 * only the two-letter code silently fails wherever it is not a prefix of the tag
 * actually in the file: "jpn" does not start with "ja", so asking for Japanese
 * original audio picked the English dub instead — which is exactly the case people
 * turn the setting on for.
 *
 * Unlisted codes pass through unchanged, which covers a code that is already 639-2
 * and every language not in the table.
 */
internal fun languageAliases(code: String): List<String> {
    val normalised = code.trim().lowercase()
    if (normalised.isEmpty()) return emptyList()
    return (listOf(normalised) + LANGUAGE_ALIASES[normalised].orEmpty()).distinct()
}

/**
 * ISO 639-1 to its 639-2/T and 639-2/B forms, for the languages that actually turn
 * up in releases. Only entries where the three-letter code is not simply the
 * two-letter one extended are load-bearing, but the rest are listed too so the
 * table reads as a fact about the languages rather than a list of exceptions.
 */
private val LANGUAGE_ALIASES: Map<String, List<String>> = mapOf(
    "en" to listOf("eng"),
    "ja" to listOf("jpn", "jp"),
    "zh" to listOf("zho", "chi", "cmn", "yue"),
    "de" to listOf("deu", "ger"),
    "fr" to listOf("fra", "fre"),
    "es" to listOf("spa"),
    "it" to listOf("ita"),
    "ko" to listOf("kor"),
    "pt" to listOf("por"),
    "ru" to listOf("rus"),
    "ar" to listOf("ara"),
    "hi" to listOf("hin"),
    "nl" to listOf("nld", "dut"),
    "sv" to listOf("swe"),
    "no" to listOf("nor", "nob"),
    "da" to listOf("dan"),
    "fi" to listOf("fin"),
    "pl" to listOf("pol"),
    "tr" to listOf("tur"),
    "th" to listOf("tha"),
    "vi" to listOf("vie"),
    "id" to listOf("ind"),
    "he" to listOf("heb", "iw"),
    "cs" to listOf("ces", "cze"),
    "el" to listOf("ell", "gre"),
    "hu" to listOf("hun"),
    "ro" to listOf("ron", "rum"),
    "uk" to listOf("ukr"),
    "fa" to listOf("fas", "per"),
    "ms" to listOf("msa", "may"),
    "tl" to listOf("tgl", "fil"),
    "bn" to listOf("ben"),
    "ta" to listOf("tam"),
    "te" to listOf("tel"),
    "is" to listOf("isl", "ice"),
    "sk" to listOf("slk", "slo"),
    "hr" to listOf("hrv"),
    "sr" to listOf("srp"),
    "bg" to listOf("bul"),
    "ca" to listOf("cat"),
)

/**
 * @param originalLanguage the title's own language, used to resolve Original.
 */
fun AppSettings.playbackPreferences(originalLanguage: String?): PlaybackPreferences {
    fun resolve(preference: String): List<String> = when {
        preference.isBlank() -> emptyList()
        preference == AUDIO_LANGUAGE_ORIGINAL ->
            originalLanguage?.takeIf { it.isNotBlank() }?.let(::languageAliases).orEmpty()

        else -> languageAliases(preference)
    }

    return PlaybackPreferences(
        audioLanguages = resolve(defaultAudioLang),
        subtitleLanguages = resolve(defaultSubtitleLang),
        subtitlesEnabled = subtitlesEnabled,
        startMuted = openOnMute,
        // The setting is a percentage where 100 means "normal"; players take a
        // multiplier. Clamped so a stored extreme cannot make subtitles unusable.
        subtitleScale = (subtitleSize / 100.0).coerceIn(0.25, 4.0),
        // The setting measures distance up from the bottom of the frame; mpv's
        // sub-pos measures down from the top, so the two are inverses.
        subtitlePosition = (100 - subtitlePosition.toInt()).coerceIn(0, 100),
        subtitleBackground = subtitleBackground,
        hardwareDecoding = hardwareDecoding,
    )
}
