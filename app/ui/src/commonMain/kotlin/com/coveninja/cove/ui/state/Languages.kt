package com.coveninja.cove.ui.state

/**
 * The one language table.
 *
 * There used to be four, none of which knew about the others: the alias map that turns a
 * preference into something mpv can match, the settings picker's nine entries, the track
 * menu's code-to-name list, and the television's cycling list with a second name list beside
 * it. Widening the choice of language meant editing all four and getting them to agree, and
 * they did not — a code offered by the phone showed as a bare "PT" in the track menu, and the
 * television's list was a different nine again.
 *
 * Everything about a language lives on one row here, and the four call sites read the column
 * they need. Adding a language is one entry.
 */
internal data class Language(
    /** ISO 639-1, or [AUDIO_LANGUAGE_ORIGINAL]. What settings store and sync. */
    val code: String,
    /** For the track menu, which names languages in the app's own language. */
    val englishName: String,
    /**
     * For the settings picker, which names each language in itself — someone looking for
     * their own language finds it faster written the way they write it.
     */
    val nativeName: String,
    /**
     * ISO 639-2/T and /B, and any other tag releases actually carry.
     *
     * Load-bearing wherever the three-letter code is not the two-letter one extended:
     * media files tag tracks in 639-2 and there are *two* codes per language, so asking
     * for "ja" never matches the "jpn" in the file. Codes that are a simple extension are
     * listed anyway, so the table reads as a fact about the language rather than as a list
     * of exceptions.
     */
    val aliases: List<String> = emptyList(),
)

/**
 * Ordered as a person would look through it: the languages most releases carry first, then
 * the rest alphabetically by English name. [AUDIO_LANGUAGE_ORIGINAL] leads, because "whatever
 * the title was made in" is what most people actually want and pinning a code instead is how
 * a subtitled film opens on an English dub.
 */
internal val LANGUAGES: List<Language> = listOf(
    Language(AUDIO_LANGUAGE_ORIGINAL, "Original", "Original"),
    Language("en", "English", "English", listOf("eng")),
    Language("es", "Spanish", "Español", listOf("spa")),
    Language("fr", "French", "Français", listOf("fra", "fre")),
    Language("de", "German", "Deutsch", listOf("deu", "ger")),
    Language("it", "Italian", "Italiano", listOf("ita")),
    Language("pt", "Portuguese", "Português", listOf("por")),
    Language("ru", "Russian", "Русский", listOf("rus")),
    Language("ja", "Japanese", "日本語", listOf("jpn", "jp")),
    Language("ko", "Korean", "한국어", listOf("kor")),
    Language("zh", "Chinese", "中文", listOf("zho", "chi", "cmn", "yue")),
    Language("ar", "Arabic", "العربية", listOf("ara")),
    Language("hi", "Hindi", "हिन्दी", listOf("hin")),
    Language("tr", "Turkish", "Türkçe", listOf("tur")),
    Language("pl", "Polish", "Polski", listOf("pol")),
    Language("nl", "Dutch", "Nederlands", listOf("nld", "dut")),
    Language("sv", "Swedish", "Svenska", listOf("swe")),
    Language("da", "Danish", "Dansk", listOf("dan")),
    Language("no", "Norwegian", "Norsk", listOf("nor", "nob", "nno")),
    Language("fi", "Finnish", "Suomi", listOf("fin")),
    Language("cs", "Czech", "Čeština", listOf("ces", "cze")),
    Language("el", "Greek", "Ελληνικά", listOf("ell", "gre")),
    Language("he", "Hebrew", "עברית", listOf("heb", "iw")),
    Language("hu", "Hungarian", "Magyar", listOf("hun")),
    Language("ro", "Romanian", "Română", listOf("ron", "rum")),
    Language("uk", "Ukrainian", "Українська", listOf("ukr")),
    Language("th", "Thai", "ไทย", listOf("tha")),
    Language("vi", "Vietnamese", "Tiếng Việt", listOf("vie")),
    Language("id", "Indonesian", "Bahasa Indonesia", listOf("ind")),
    Language("ms", "Malay", "Bahasa Melayu", listOf("msa", "may")),
    Language("tl", "Filipino", "Filipino", listOf("tgl", "fil")),
    Language("fa", "Persian", "فارسی", listOf("fas", "per")),
    Language("bn", "Bengali", "বাংলা", listOf("ben")),
    Language("ta", "Tamil", "தமிழ்", listOf("tam")),
    Language("te", "Telugu", "తెలుగు", listOf("tel")),
    Language("ml", "Malayalam", "മലയാളം", listOf("mal")),
    Language("kn", "Kannada", "ಕನ್ನಡ", listOf("kan")),
    Language("mr", "Marathi", "मराठी", listOf("mar")),
    Language("gu", "Gujarati", "ગુજરાતી", listOf("guj")),
    Language("pa", "Punjabi", "ਪੰਜਾਬੀ", listOf("pan")),
    Language("ur", "Urdu", "اردو", listOf("urd")),
    Language("ne", "Nepali", "नेपाली", listOf("nep")),
    Language("si", "Sinhala", "සිංහල", listOf("sin")),
    Language("my", "Burmese", "မြန်မာ", listOf("mya", "bur")),
    Language("km", "Khmer", "ខ្មែរ", listOf("khm")),
    Language("lo", "Lao", "ລາວ", listOf("lao")),
    Language("is", "Icelandic", "Íslenska", listOf("isl", "ice")),
    Language("sk", "Slovak", "Slovenčina", listOf("slk", "slo")),
    Language("sl", "Slovenian", "Slovenščina", listOf("slv")),
    Language("hr", "Croatian", "Hrvatski", listOf("hrv")),
    Language("sr", "Serbian", "Српски", listOf("srp")),
    Language("bs", "Bosnian", "Bosanski", listOf("bos")),
    Language("mk", "Macedonian", "Македонски", listOf("mkd", "mac")),
    Language("sq", "Albanian", "Shqip", listOf("sqi", "alb")),
    Language("bg", "Bulgarian", "Български", listOf("bul")),
    Language("lt", "Lithuanian", "Lietuvių", listOf("lit")),
    Language("lv", "Latvian", "Latviešu", listOf("lav")),
    Language("et", "Estonian", "Eesti", listOf("est")),
    Language("be", "Belarusian", "Беларуская", listOf("bel")),
    Language("ka", "Georgian", "ქართული", listOf("kat", "geo")),
    Language("hy", "Armenian", "Հայերեն", listOf("hye", "arm")),
    Language("az", "Azerbaijani", "Azərbaycan", listOf("aze")),
    Language("kk", "Kazakh", "Қазақ", listOf("kaz")),
    Language("uz", "Uzbek", "Oʻzbek", listOf("uzb")),
    Language("mn", "Mongolian", "Монгол", listOf("mon")),
    Language("ca", "Catalan", "Català", listOf("cat")),
    Language("eu", "Basque", "Euskara", listOf("eus", "baq")),
    Language("gl", "Galician", "Galego", listOf("glg")),
    Language("cy", "Welsh", "Cymraeg", listOf("cym", "wel")),
    Language("ga", "Irish", "Gaeilge", listOf("gle")),
    Language("mt", "Maltese", "Malti", listOf("mlt")),
    Language("af", "Afrikaans", "Afrikaans", listOf("afr")),
    Language("sw", "Swahili", "Kiswahili", listOf("swa")),
)

/** By [Language.code], for the lookups below. */
private val BY_CODE: Map<String, Language> = LANGUAGES.associateBy { it.code }

/**
 * Every code the table knows, in either form, for [knownLanguageTag].
 *
 * Declared after [LANGUAGES]: top-level properties initialise in file order, and reading
 * the list from above it would leave this empty.
 */
private val ALL_CODES: Set<String> =
    LANGUAGES.flatMapTo(mutableSetOf()) { listOf(it.code) + it.aliases }

/** Everything but [AUDIO_LANGUAGE_ORIGINAL], which is a rule rather than a language. */
internal val SELECTABLE_LANGUAGES: List<Language> =
    LANGUAGES.filter { it.code != AUDIO_LANGUAGE_ORIGINAL }

/**
 * Every code a track might carry for one language, most specific first.
 *
 * TMDB reports a language as ISO 639-1 — "ja", "de" — and media files tag their tracks with
 * ISO 639-2, which has two codes per language and releases use both. Handing a player only
 * the two-letter code silently fails wherever it is not a prefix of the tag actually in the
 * file: "jpn" does not start with "ja", so asking for Japanese original audio picked the
 * English dub instead — which is exactly the case people turn the setting on for.
 *
 * A code the table has never heard of passes through unchanged, which covers one that is
 * already 639-2 and every language not listed.
 */
internal fun languageAliases(code: String): List<String> {
    val normalised = code.trim().lowercase()
    if (normalised.isEmpty()) return emptyList()
    return (listOf(normalised) + BY_CODE[normalised]?.aliases.orEmpty()).distinct()
}

/**
 * [segment] unchanged when it names a language, null when it is just a word.
 *
 * Asked of the segment before a subtitle file's extension, which is a language tag in
 * `Movie.2024.en.srt` and part of the release name in `Movie.2024.web.srt`. Nothing but this
 * table separates the two: a "two or three letters" rule files the second one under a
 * language called WEB. A region subtag is kept — `pt-BR` is worth showing — and only the part
 * before it has to be a language.
 */
internal fun knownLanguageTag(segment: String): String? {
    val trimmed = segment.trim()
    val base = trimmed.substringBefore('-').lowercase()
    return trimmed.takeIf { base in ALL_CODES }
}

/**
 * What to call [code] in the app's own language, for the track menu and the television.
 *
 * An unrecognised code shows as itself rather than as a guess or a blank: a value synced from
 * a newer build reads as understood-but-unfamiliar instead of as a control that lost its
 * value. Matches on the 639-2 forms too, since that is what a media file carries.
 */
internal fun languageName(code: String): String {
    val normalised = code.trim().lowercase()
    if (normalised.isEmpty()) return UNKNOWN_LANGUAGE
    BY_CODE[normalised]?.let { return it.englishName }
    return LANGUAGES.firstOrNull { normalised in it.aliases }?.englishName
        ?: normalised.uppercase()
}

/** As [languageName], but written the way the language writes itself. For the settings picker. */
internal fun languageNativeName(code: String): String {
    val normalised = code.trim().lowercase()
    if (normalised.isEmpty()) return UNKNOWN_LANGUAGE
    BY_CODE[normalised]?.let { return it.nativeName }
    return LANGUAGES.firstOrNull { normalised in it.aliases }?.nativeName
        ?: normalised.uppercase()
}

internal const val UNKNOWN_LANGUAGE = "Unknown"
