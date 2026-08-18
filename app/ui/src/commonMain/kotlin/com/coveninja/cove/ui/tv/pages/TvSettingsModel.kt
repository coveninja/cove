package com.coveninja.cove.ui.tv.pages

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

/** On a television every one of these reads better as a word than as a switch graphic. */
internal fun onOff(value: Boolean): String = if (value) "On" else "Off"
