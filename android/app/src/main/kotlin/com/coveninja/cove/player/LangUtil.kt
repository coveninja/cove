package com.coveninja.cove.player

/**
 * Language-tag normalization. Mirrors web/src/lib/lang.ts exactly.
 *
 * mpv reports track languages from container metadata in ISO 639-2 (three-letter,
 * e.g. "jpn"), while Cove settings and TMDB use ISO 639-1 ("ja"). A naive exact
 * match silently fails for almost every non-English track — this normalizes both
 * sides to ISO 639-1 before comparing.
 *
 * Covers both bibliographic (B) and terminological (T) 639-2 forms where they
 * differ (e.g. "ger"/"deu" for German) — mpv/ffmpeg tagging varies by muxer.
 */
object LangUtil {

    val ISO_639_2_TO_1: Map<String, String> = mapOf(
        "eng" to "en",
        "spa" to "es",
        "fre" to "fr", "fra" to "fr",
        "ger" to "de", "deu" to "de",
        "por" to "pt",
        "ita" to "it",
        "jpn" to "ja",
        "kor" to "ko",
        "chi" to "zh", "zho" to "zh",
        "ara" to "ar",
        "rus" to "ru",
        "hin" to "hi",
        "tur" to "tr",
        "pol" to "pl",
        "dut" to "nl", "nld" to "nl",
        "ces" to "cs", "cze" to "cs",
        "swe" to "sv",
        "nor" to "no",
        "dan" to "da",
        "fin" to "fi",
        "ell" to "el", "gre" to "el",
        "heb" to "he",
        "tha" to "th",
        "vie" to "vi",
        "ind" to "id",
        "ukr" to "uk",
        "hun" to "hu",
        "ron" to "ro", "rum" to "ro",
    )

    /**
     * Normalizes a language tag to lowercase ISO 639-1 where possible.
     * "en-US" → "en", "jpn" → "ja", "JA" → "ja".
     * Unknown 3-letter codes and anything else pass through lowercased.
     * Returns "" for null/empty input.
     */
    fun normalizeLang(tag: String?): String {
        if (tag.isNullOrEmpty()) return ""
        // Strip region/script subtags: "pt-BR" → "pt", "zh-Hans" → "zh"
        val base = tag.lowercase().split(Regex("[-_]"))[0]
        return if (base.length == 3) ISO_639_2_TO_1[base] ?: base else base
    }

    /**
     * True if two language tags refer to the same language once normalized
     * to ISO 639-1 (handles 639-1 vs 639-2, case, and region-subtag mismatches).
     */
    fun langMatches(a: String?, b: String?): Boolean {
        val na = normalizeLang(a)
        val nb = normalizeLang(b)
        return na.isNotEmpty() && na == nb
    }
}
