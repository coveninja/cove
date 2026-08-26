package com.coveninja.cove.ui.state

/**
 * The subtitle appearance choices Cove offers, and what to call them.
 *
 * One table, because there were two and they had already drifted: the settings screen and the
 * television each carried their own copy of the same four colours and three border styles, and
 * the television called `opaque-box` "Panel behind" where the settings screen called it
 * "Panel". Nothing failed — the two screens simply named the same stored value differently,
 * which is the kind of difference nobody notices until they are looking at both.
 *
 * The player's subtitle menu needs these as well, so the alternative to consolidating was a
 * third copy. Same reasoning as the language table in `Languages.kt`.
 *
 * Every value here has to be one the resolution in `PlaybackPreferences.kt` accepts unchanged —
 * a preset mpv would reject is a control that appears to work and does nothing, which is the
 * standing failure mode for every one of these options.
 */

/** A stored value and the word for it. */
internal data class StyleOption(val value: String, val label: String)

/**
 * How the text is separated from the picture, in the order the options add weight: nothing
 * behind it, a box hugging each line, a panel across the block.
 */
internal val SUBTITLE_BORDER_STYLES: List<StyleOption> = listOf(
    StyleOption("outline-and-shadow", "Outline"),
    StyleOption("background-box", "Box per line"),
    StyleOption("opaque-box", "Panel"),
)

internal fun subtitleBorderStyleLabel(style: String): String =
    SUBTITLE_BORDER_STYLES.firstOrNull { it.value == style }?.label
        // Shown as itself rather than guessed at, so a style synced from a newer build reads as
        // understood-but-unfamiliar instead of as a control that lost its value.
        ?: style.ifBlank { "Outline" }

/**
 * White, the two broadcast-caption colours, and a softer grey for anyone who finds pure white
 * too hot on an OLED at night. Deliberately short: subtitle colour is a legibility choice with
 * about four answers, not a design one with sixteen million.
 */
internal val SUBTITLE_TEXT_COLORS: List<StyleOption> = listOf(
    StyleOption("#FFFFFFFF", "White"),
    StyleOption("#FFFFF200", "Yellow"),
    StyleOption("#FF00FFFF", "Cyan"),
    StyleOption("#FFC8C8C8", "Soft grey"),
)

internal fun subtitleColorLabel(color: String): String =
    SUBTITLE_TEXT_COLORS.firstOrNull { it.value.equals(color, ignoreCase = true) }?.label
        ?: color.ifBlank { "White" }

/** For the panel and the drop shadow, which mpv draws from one value. */
internal val SUBTITLE_PANEL_COLORS: List<StyleOption> = listOf(
    StyleOption("#AF000000", "Black"),
    StyleOption("#AF1A1A1A", "Charcoal"),
    StyleOption("#AF2B1B4A", "Deep violet"),
    StyleOption("#AFFFFFFF", "White"),
)

internal val SUBTITLE_OUTLINE_COLORS: List<StyleOption> = listOf(
    StyleOption("#FF000000", "Black"),
    StyleOption("#FF404040", "Grey"),
    StyleOption("#FF1A0A2E", "Deep violet"),
    StyleOption("#FFFFFFFF", "White"),
)

/**
 * The bounds the settings sliders already use, shared so the player's steppers cannot walk a
 * value somewhere the settings screen could never produce — and so the two agree by
 * construction rather than by both being edited.
 */
internal const val SUBTITLE_SIZE_MIN = 50.0
internal const val SUBTITLE_SIZE_MAX = 200.0
internal const val SUBTITLE_SIZE_STEP = 10.0

/** Distance up from the bottom of the frame, in percent. Inverted for mpv on the way out. */
internal const val SUBTITLE_POSITION_MIN = 0.0
internal const val SUBTITLE_POSITION_MAX = 40.0
internal const val SUBTITLE_POSITION_STEP = 2.0

/**
 * What a reset goes back to, and the same values `AppSettings` declares as its defaults.
 *
 * Named here so the player's "back to default" and the stored default cannot drift apart —
 * a reset that landed somewhere the profile had never been would be its own small bug.
 */
internal const val DEFAULT_SUBTITLE_SIZE = 100.0
internal const val DEFAULT_SUBTITLE_POSITION = 8.0
