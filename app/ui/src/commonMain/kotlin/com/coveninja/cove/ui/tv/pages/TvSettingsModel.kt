package com.coveninja.cove.ui.tv.pages

import com.coveninja.cove.ui.state.AUDIO_LANGUAGE_ORIGINAL
import com.coveninja.cove.ui.state.SUBTITLE_BORDER_STYLES
import com.coveninja.cove.ui.state.SUBTITLE_TEXT_COLORS
import com.coveninja.cove.ui.state.WATCH_REMINDER_HOURS

/**
 * Steps a setting on to its next value, wrapping at the end.
 *
 * The choice-among-several control on a television is a button you press repeatedly, not a list
 * you open. A dropdown costs a surface, a focus trap and a way back out of it for something with
 * three values — and open menus were what fought the focus engine hardest in the previous TV
 * shell. Pressing the row until it says what you want costs one interaction and no new surface.
 *
 * A value the app does not recognise resolves to the first option rather than staying put. That
 * case is reachable: settings sync between devices and versions, so a newer build can write a
 * mode this one has never heard of, and a control that silently refused to move would look
 * broken with no way to find out why.
 */
internal fun <T> cycleOption(options: List<T>, current: T): T {
    if (options.isEmpty()) return current
    val index = options.indexOf(current)
    if (index < 0) return options.first()
    return options[(index + 1) % options.size]
}

/** How Cove ranks sources when it picks one itself. Wire values, shared with the phone. */
internal val StreamSelectionModes = listOf("balanced", "quality", "seeders")

internal fun streamSelectionLabel(mode: String): String = when (mode) {
    "quality" -> "Quality first"
    "seeders" -> "Most seeded"
    "balanced" -> "Balanced"
    // Named rather than hidden: a mode from a newer build is worth showing as itself, so the
    // setting reads as understood-but-unfamiliar rather than as a control that lost its value.
    else -> mode.ifBlank { "Balanced" }
}

/** Seconds a single left or right press moves. */
internal val SeekStepChoices = listOf(5.0, 10.0, 15.0, 30.0)

/** Watch-reminder intervals in hours, with 0 standing for off. */
internal val WatchReminderSteps = listOf(0) + WATCH_REMINDER_HOURS

internal fun watchReminderLabel(enabled: Boolean, hours: Int): String =
    if (!enabled) "Off" else if (hours == 1) "Every hour" else "Every $hours hours"

internal fun seekStepLabel(seconds: Double): String = "${seconds.toInt()} seconds"

/** Subtitle size, as the percentage the player stores. */
internal val SubtitleSizeChoices = listOf(75.0, 100.0, 125.0, 150.0, 200.0)

internal fun subtitleSizeLabel(size: Double): String = "${size.toInt()}%"

/**
 * Subtitle position, as the percentage up from the bottom the player stores.
 *
 * Higher options than a monitor needs, because a television overscans: on plenty of sets the
 * bottom of the picture is behind the bezel, and subtitles at the default sit in it.
 */
internal val SubtitlePositionChoices = listOf(2.0, 8.0, 14.0, 20.0)

internal fun subtitlePositionLabel(position: Double): String = when (position) {
    2.0 -> "At the edge"
    8.0 -> "Standard"
    14.0 -> "Raised"
    20.0 -> "High"
    // A value from a newer build, or one set on a desktop slider this shell has no step for.
    else -> "${position.toInt()}% up"
}

/**
 * The languages the cycling control offers.
 *
 * [AUDIO_LANGUAGE_ORIGINAL] leads it, for the same reason it leads the phone's list: it means
 * "whatever the title was made in", resolved per title from its TMDB original language rather
 * than pinned to one code, and it is the option most people actually want — a subtitled film
 * stays subtitled instead of opening on an English dub.
 *
 * Its absence here was not merely a missing option. `cycleOption` resolves a value it does not
 * recognise to the first entry, so a profile that had chosen Original on a phone showed
 * "ORIGINAL" on the television and was silently converted to English by one press of the row.
 *
 * Deliberately a short list rather than every language Cove knows, which is now some seventy
 * of them in `Languages.kt`: cycling one press at a time through seventy entries is not a
 * control. These are the ones addon releases actually carry. Names come from that same table,
 * so a code chosen on the phone is never shown here under a different name — or, as it was,
 * under no name at all.
 */
internal val LanguageChoices = listOf(
    AUDIO_LANGUAGE_ORIGINAL,
    "en",
    "es",
    "fr",
    "de",
    "it",
    "pt",
    "ja",
    "ko",
    "zh",
    "ru",
)

// The border styles and colour presets, and the words for them, come from
// `ui/state/SubtitleStyleOptions.kt`. They used to be declared here as well as on the settings
// screen, which is how this shell ended up calling `opaque-box` "Panel behind" while the phone
// called it "Panel" — the same stored value under two names, with nothing to say so.
internal val SubtitleBorderStyleChoices = SUBTITLE_BORDER_STYLES.map { it.value }

internal val SubtitleColorChoices = SUBTITLE_TEXT_COLORS.map { it.value }

/** mpv's own default is 1.65; a television at three metres wants more of it. */
internal val SubtitleOutlineSizeChoices = listOf(0.0, 1.65, 3.0, 4.5)

internal fun subtitleOutlineSizeLabel(size: Double): String = when (size) {
    0.0 -> "None"
    1.65 -> "Standard"
    3.0 -> "Heavy"
    4.5 -> "Heaviest"
    else -> size.toString()
}

internal val SubtitleAlignChoices = listOf("center", "left", "right")

internal fun subtitleAlignLabel(align: String): String = when (align) {
    "left" -> "Left"
    "right" -> "Right"
    "center" -> "Centre"
    else -> align.ifBlank { "Centre" }
}

/**
 * How much of a styled subtitle's own formatting to keep.
 *
 * Only meaningful for ASS/SSA subtitles, which carry fonts, positions and colours of their
 * own — the signs and karaoke of a fansubbed release. The appearance settings reach a track
 * like that only as far as this allows.
 */
internal val SubtitleAssOverrideChoices = listOf("scale", "no", "yes", "force", "strip")

internal fun subtitleAssOverrideLabel(value: String): String = when (value) {
    "no" -> "Keep the release's styling"
    "yes" -> "Apply mine where it can"
    "scale" -> "Keep styling, scale it"
    "force" -> "Force mine"
    "strip" -> "Strip styling entirely"
    else -> value.ifBlank { "Keep styling, scale it" }
}

internal val AudioNormalizationChoices = listOf("off", "normalize", "night")

internal fun audioNormalizationLabel(value: String): String = when (value) {
    "normalize" -> "Even out"
    "night" -> "Night mode"
    "off" -> "Off"
    else -> value.ifBlank { "Off" }
}

/** Empty is mpv's auto-safe: leave whatever the track has alone. */
internal val AudioDownmixChoices = listOf("", "stereo", "mono")

internal fun audioDownmixLabel(value: String): String = when (value) {
    "stereo" -> "Stereo"
    "mono" -> "Mono"
    "" -> "As recorded"
    else -> value
}

/** On a television every one of these reads better as a word than as a switch graphic. */
internal fun onOff(value: Boolean): String = if (value) "On" else "Off"
