package com.coveninja.cove.ui.tv.pages

import com.coveninja.cove.ui.state.AUDIO_LANGUAGE_ORIGINAL

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
 * The rest is deliberately short: the full list is a hundred-odd entries and cycling one press
 * at a time through it is not a control. These are the ones addon releases actually carry.
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

internal fun languageLabel(code: String): String = when (code.lowercase()) {
    AUDIO_LANGUAGE_ORIGINAL -> "Original"
    "en" -> "English"
    "es" -> "Spanish"
    "fr" -> "French"
    "de" -> "German"
    "it" -> "Italian"
    "pt" -> "Portuguese"
    "ja" -> "Japanese"
    "ko" -> "Korean"
    "zh" -> "Chinese"
    "ru" -> "Russian"
    // Shown as itself rather than hidden, so a code synced from another device reads as
    // understood-but-unlisted instead of as a control that lost its value.
    else -> code.uppercase().ifBlank { "English" }
}

/** On a television every one of these reads better as a word than as a switch graphic. */
internal fun onOff(value: Boolean): String = if (value) "On" else "Off"
