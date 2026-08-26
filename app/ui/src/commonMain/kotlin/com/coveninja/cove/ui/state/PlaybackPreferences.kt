package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.data.TrackMemory
import com.coveninja.cove.shared.model.AppSettings

/**
 * How subtitles are drawn, in the terms a player takes.
 *
 * Split out of [PlaybackPreferences] because it is the half that is safe to re-apply while
 * something is playing. The rest of the preferences decide which tracks to select and whether
 * to open muted, and sending those again mid-file would reset `sid`/`aid` and undo whatever
 * the viewer just picked in the player's own menu. Appearance has no such side effect, so a
 * settings change can reach the picture immediately.
 *
 * Every value here is already in mpv's units and vocabulary; the conversions happen once, in
 * [playbackPreferences], where they can be tested.
 */
data class SubtitleStyle(
    /** 1.0 is the player's own default size. */
    val scale: Double,
    /** mpv's sub-pos: 0 is the top of the frame, 100 the bottom. */
    val position: Int,
    /** Empty leaves mpv's own sans-serif alone. */
    val font: String,
    /** `#AARRGGBB`, alpha FF being opaque. */
    val textColor: String,
    val outlineColor: String,
    val outlineSize: Double,
    val shadowOffset: Double,
    /** The opaque box behind the text, and the shadow's colour — mpv makes them one option. */
    val backColor: String,
    val bold: Boolean,
    val italic: Boolean,
    val blur: Double,
    /** no|yes|scale|force|strip. */
    val assOverride: String,
    /** left|center|right. */
    val align: String,
    /** outline-and-shadow|opaque-box|background-box. Always resolved; never empty. */
    val borderStyle: String,
)

/**
 * What to do about a soundtrack that whispers and then shouts.
 *
 * The filter strings are mpv's, and they live here for the same reason the subtitle unit
 * conversions do: one place, testable, rather than spelled out in each host. Only a host that
 * reports [VideoPlayerHost.supportsAudioFilters] may be handed one — the Android libmpv ships
 * a libavfilter with no audio filters in it, and mpv answers a filter it cannot build by
 * ending the file rather than by playing on without it.
 */
enum class AudioNormalization(val setting: String, val filter: String) {
    Off("off", ""),

    /** Evens the level out. Quiet dialogue comes up without the loud parts being squashed. */
    Normalize("normalize", "lavfi=[dynaudnorm=f=250:g=15:p=0.9]"),

    /**
     * The same, then a compressor over it: loud peaks are pulled down hard as well as quiet
     * parts brought up. For listening late without a hand on the volume.
     */
    Night(
        "night",
        "lavfi=[dynaudnorm=f=200:g=11:p=0.7," +
            "acompressor=ratio=4:threshold=0.08:attack=20:release=250]",
    ),

    ;

    companion object {
        /** Anything unrecognised — a mode from a newer build — is [Off], which is always safe. */
        fun from(value: String?): AudioNormalization =
            entries.firstOrNull { it.setting == value?.trim()?.lowercase() } ?: Off
    }
}

/**
 * The viewer's playback preferences, resolved for one title.
 *
 * Built here rather than read from AppSettings at the point of use so the awkward parts —
 * Original resolving against the title's own language, an ordered preference falling back to
 * the single-language setting behind it, and the unit differences between what the settings
 * store and what a player wants — are decided once and can be tested.
 */
data class PlaybackPreferences(
    /** Language codes in preference order; empty means let the file decide. */
    val audioLanguages: List<String>,
    val subtitleLanguages: List<String>,
    val subtitlesEnabled: Boolean,
    val startMuted: Boolean,
    val subtitleStyle: SubtitleStyle,
    /**
     * Whether to decode on the GPU. Carried with the per-title preferences rather than fixed
     * when the player is built, so turning it off takes effect on the next thing played
     * instead of on the next launch.
     */
    val hardwareDecoding: Boolean,
    val audioNormalization: AudioNormalization,
    /** mpv's audio-channels. Empty is auto-safe; "stereo" or "mono" force a downmix. */
    val audioDownmix: String,
    val audioNormalizeDownmix: Boolean,
)

/**
 * The border style to use, given both settings.
 *
 * [borderStyle] is the newer, three-valued field and wins when it says something this build
 * understands. Empty means no build has ever set it for this profile, and a value from a
 * newer one means this build does not know it — both fall back to [background], which every
 * version of Cove has written and understood.
 */
internal fun resolveBorderStyle(borderStyle: String, background: Boolean): String =
    when (val normalised = borderStyle.trim().lowercase()) {
        "outline-and-shadow", "opaque-box", "background-box" -> normalised
        else -> if (background) "opaque-box" else "outline-and-shadow"
    }

/**
 * [value] if mpv will read it as a colour, [fallback] otherwise.
 *
 * mpv takes `#RRGGBB` or `#AARRGGBB` and answers anything else by ignoring the whole
 * property — no error, no complaint, just subtitles that stay the colour they were. A stored
 * value can be malformed by a hand-edited settings file or a newer build's format, and
 * falling back to a colour that works beats a control that appears to do nothing.
 */
internal fun resolveSubtitleColor(value: String, fallback: String): String {
    val trimmed = value.trim()
    val digits = trimmed.removePrefix("#")
    val wellFormed = trimmed.startsWith("#") &&
        (digits.length == 6 || digits.length == 8) &&
        digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    return if (wellFormed) "#${digits.uppercase()}" else fallback
}

/**
 * The opacity of an mpv colour, 0 transparent to 255 opaque.
 *
 * A six-digit colour carries no alpha and is fully opaque, which is what mpv does with one.
 */
fun subtitleColorAlpha(value: String): Int {
    val digits = resolveSubtitleColor(value, "#FFFFFFFF").removePrefix("#")
    if (digits.length != 8) return 255
    return digits.take(2).toIntOrNull(16) ?: 255
}

/** [value] with its opacity replaced, keeping the colour. */
fun withSubtitleColorAlpha(value: String, alpha: Int): String {
    val digits = resolveSubtitleColor(value, "#FFFFFFFF").removePrefix("#")
    val rgb = if (digits.length == 8) digits.drop(2) else digits
    val clamped = alpha.coerceIn(0, 255)
    val hex = clamped.toString(16).uppercase().padStart(2, '0')
    return "#$hex$rgb"
}

/** mpv's sub-ass-override values. Anything else falls back to its default. */
private val ASS_OVERRIDES = setOf("no", "yes", "scale", "force", "strip")

private val SUB_ALIGNMENTS = setOf("left", "center", "right")

/** mpv's audio-channels values Cove offers. Empty means auto-safe: leave the layout alone. */
private val DOWNMIX_CHOICES = setOf("", "stereo", "mono")

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

    // Each entry brings its own three-letter forms, so the flattened result is what mpv's
    // alang/slang want: most specific preference first, aliases trailing each one.
    //
    // Which entries those are is orderedAudioLanguages'/orderedSubtitleLanguages' business
    // and not repeated here. It was, briefly, and that is precisely the shape of bug this
    // change exists to remove: two implementations of one fallback rule, agreeing until one
    // of them is edited.
    fun expand(ordered: List<String>): List<String> = ordered.flatMap(::resolve).distinct()

    return PlaybackPreferences(
        audioLanguages = expand(orderedAudioLanguages()),
        subtitleLanguages = expand(orderedSubtitleLanguages()),
        subtitlesEnabled = subtitlesEnabled,
        startMuted = openOnMute,
        subtitleStyle = subtitleStyle(),
        hardwareDecoding = hardwareDecoding,
        // Gated again at the host: a host that cannot run filters must never be handed one,
        // and resolving it here only decides which filter a host that can would use.
        audioNormalization = AudioNormalization.from(audioNormalization),
        audioDownmix = audioDownmix.trim().lowercase().takeIf { it in DOWNMIX_CHOICES }.orEmpty(),
        audioNormalizeDownmix = audioNormalizeDownmix,
    )
}

/**
 * The ordered audio preference as anything reading it should see it.
 *
 * A profile that has never opened the reorder editor still has the single-language setting,
 * and that is its order — of one. Everything asking "what does this viewer want" goes through
 * here rather than reading either field, so the fallback is decided once instead of per call
 * site.
 *
 * Empty only when both are blank, which is a real state and means what it says: no preference
 * at all, so the file's own choice stands. Callers must handle it rather than assume a first
 * element.
 */
fun AppSettings.orderedAudioLanguages(): List<String> {
    val ordered = audioLanguages.map { it.trim() }.filter { it.isNotEmpty() }
    if (ordered.isNotEmpty()) return ordered
    return listOfNotNull(defaultAudioLang.trim().takeIf { it.isNotEmpty() })
}

fun AppSettings.orderedSubtitleLanguages(): List<String> {
    val ordered = subtitleLanguages.map { it.trim() }.filter { it.isNotEmpty() }
    if (ordered.isNotEmpty()) return ordered
    return listOfNotNull(defaultSubtitleLang.trim().takeIf { it.isNotEmpty() })
}

/**
 * Writes the ordered audio preference, keeping [AppSettings.defaultAudioLang] in step.
 *
 * The one place that invariant is maintained, and it has to be maintained: the scalar is what
 * a build without the list reads, and what the television's cycling row shows. Left to drift,
 * a phone would sync an order whose first entry disagreed with the language every other device
 * displayed. Clearing the list on purpose leaves the scalar alone rather than blanking it —
 * "no order expressed" is exactly the state the scalar exists to answer.
 */
fun AppSettings.withAudioLanguages(languages: List<String>): AppSettings {
    val cleaned = languages.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    return copy(
        audioLanguages = cleaned,
        defaultAudioLang = cleaned.firstOrNull() ?: defaultAudioLang,
    )
}

fun AppSettings.withSubtitleLanguages(languages: List<String>): AppSettings {
    val cleaned = languages.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    return copy(
        subtitleLanguages = cleaned,
        defaultSubtitleLang = cleaned.firstOrNull() ?: defaultSubtitleLang,
    )
}

/**
 * The handful of appearance values worth changing with the picture in front of you.
 *
 * A subset of [SubtitleStyle] and a different thing from it. [SubtitleStyle] is everything a
 * player needs, already converted into mpv's units and vocabulary; this is what a *control*
 * edits, in the units the settings store — so the player's steppers and the settings sliders
 * are moving the same numbers rather than two representations of them.
 *
 * The four are the ones a viewer reaches for because of what is on screen: text too small, text
 * behind a subtitle burnt into the picture, text lost against a bright scene. Font, blur,
 * shadow and the rest are set once and left, and stay in settings.
 */
data class SubtitleAppearance(
    /** Percent, where 100 is normal — the same scale [AppSettings.subtitleSize] stores. */
    val sizePercent: Double,
    /** Percent up from the bottom of the frame. Inverted for mpv by [subtitleStyle]. */
    val position: Double,
    /** Always one of [SUBTITLE_BORDER_STYLES]; never empty, unlike the stored field. */
    val borderStyle: String,
    val textColor: String,
)

/** What the controls should currently show. */
fun AppSettings.subtitleAppearance(): SubtitleAppearance = SubtitleAppearance(
    sizePercent = subtitleSize.coerceIn(SUBTITLE_SIZE_MIN, SUBTITLE_SIZE_MAX),
    position = subtitlePosition.coerceIn(SUBTITLE_POSITION_MIN, SUBTITLE_POSITION_MAX),
    // Resolved here so a control never has to deal with the empty case, and so the player and
    // the settings screen agree about what an unset border style means.
    borderStyle = resolveBorderStyle(subtitleBorderStyle, subtitleBackground),
    textColor = resolveSubtitleColor(subtitleTextColor, "#FFFFFFFF"),
)

/**
 * Writes one back.
 *
 * Two things live here rather than at each control, because there are now three of them — the
 * settings screen, the player's menu and the television's panel — and a rule enforced in three
 * places is a rule that holds in two of them.
 *
 * **Clamping** to the range the settings sliders already offer. A stepper can be pressed
 * indefinitely, and without this it would walk the value somewhere the settings screen could
 * not represent and could not bring back.
 *
 * **`subtitleBackground` kept in step with `borderStyle`.** That boolean is the whole of what
 * older builds and older profiles understand about the backdrop, and it syncs. A writer that
 * set only the three-way would leave this device drawing a panel and the phone in the next room
 * drawing none, with nothing anywhere to say why.
 */
fun AppSettings.withSubtitleAppearance(appearance: SubtitleAppearance): AppSettings {
    val borderStyle = resolveBorderStyle(appearance.borderStyle, subtitleBackground)
    return copy(
        subtitleSize = appearance.sizePercent.coerceIn(SUBTITLE_SIZE_MIN, SUBTITLE_SIZE_MAX),
        subtitlePosition = appearance.position
            .coerceIn(SUBTITLE_POSITION_MIN, SUBTITLE_POSITION_MAX),
        subtitleBorderStyle = borderStyle,
        subtitleBackground = borderStyle != "outline-and-shadow",
        subtitleTextColor = resolveSubtitleColor(appearance.textColor, subtitleTextColor),
    )
}

/** The appearance half on its own, for re-applying a settings change to a playing file. */
fun AppSettings.subtitleStyle(): SubtitleStyle = SubtitleStyle(
    // The setting is a percentage where 100 means "normal"; players take a multiplier.
    // Clamped so a stored extreme cannot make subtitles unusable.
    scale = (subtitleSize / 100.0).coerceIn(0.25, 4.0),
    // The setting measures distance up from the bottom of the frame; mpv's sub-pos measures
    // down from the top, so the two are inverses.
    position = (100 - subtitlePosition.toInt()).coerceIn(0, 100),
    font = subtitleFont.trim(),
    textColor = resolveSubtitleColor(subtitleTextColor, "#FFFFFFFF"),
    outlineColor = resolveSubtitleColor(subtitleOutlineColor, "#FF000000"),
    outlineSize = subtitleOutlineSize.coerceIn(0.0, 10.0),
    shadowOffset = subtitleShadowOffset.coerceIn(0.0, 10.0),
    backColor = resolveSubtitleColor(subtitleBackColor, "#AF000000"),
    bold = subtitleBold,
    italic = subtitleItalic,
    blur = subtitleBlur.coerceIn(0.0, 20.0),
    assOverride = subtitleAssOverride.trim().lowercase().takeIf { it in ASS_OVERRIDES } ?: "scale",
    align = subtitleAlign.trim().lowercase().takeIf { it in SUB_ALIGNMENTS } ?: "center",
    borderStyle = resolveBorderStyle(subtitleBorderStyle, subtitleBackground),
)

/**
 * The viewer's remembered choice for this title, laid over the settings defaults.
 *
 * The memory wins wherever it has an opinion, because it is the more specific of the two: the
 * settings say what to do with a title nothing is known about, and this says what was actually
 * chosen the last time this one was open. Where the memory is silent the settings still decide
 * — a viewer who picked an audio track but never touched the subtitles has not thereby
 * expressed a subtitle preference.
 *
 * Subtitles are the awkward one, because "off" and "never chose" are different facts that a
 * language string alone cannot tell apart, which is why [TrackMemory] carries a flag for it.
 */
fun PlaybackPreferences.withMemory(memory: TrackMemory): PlaybackPreferences = copy(
    audioLanguages = memory.audioLanguage
        .takeIf { it.isNotBlank() }
        ?.let(::languageAliases)
        ?: audioLanguages,
    subtitleLanguages = memory.subtitleLanguage
        .takeIf { it.isNotBlank() }
        ?.let(::languageAliases)
        ?: subtitleLanguages,
    subtitlesEnabled = when {
        memory.subtitlesOff -> false
        memory.subtitleLanguage.isNotBlank() -> true
        else -> subtitlesEnabled
    },
)
