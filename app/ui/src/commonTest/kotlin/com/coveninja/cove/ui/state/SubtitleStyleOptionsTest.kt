package com.coveninja.cove.ui.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The presets three screens offer.
 *
 * The property worth pinning is not what the values are but that the player will accept them.
 * Everything in this area fails by being ignored — mpv drops a colour it cannot parse and a
 * border style it does not know, with no error anywhere — so a preset the resolution would
 * reject is a swatch that appears to work and does nothing at all.
 */
class SubtitleStyleOptionsTest {

    @Test
    fun `every offered colour survives the resolution unchanged`() {
        val lists = mapOf(
            "text" to SUBTITLE_TEXT_COLORS,
            "panel" to SUBTITLE_PANEL_COLORS,
            "outline" to SUBTITLE_OUTLINE_COLORS,
        )
        lists.forEach { (name, colors) ->
            colors.forEach { option ->
                assertEquals(
                    option.value,
                    // A fallback that could never be mistaken for a real preset, so a value
                    // that failed would show up as itself rather than as a plausible colour.
                    resolveSubtitleColor(option.value, "#FF123456"),
                    "$name preset ${option.value} would not reach the player intact",
                )
            }
        }
    }

    // Anything a control offers must be a style the resolution recognises, or choosing it would
    // silently fall back to whatever the legacy flag said.
    @Test
    fun `every offered border style is one the resolution keeps`() {
        SUBTITLE_BORDER_STYLES.forEach { option ->
            assertEquals(
                option.value,
                resolveBorderStyle(option.value, background = true),
                "${option.value} is not a style the player understands",
            )
            // Checked against both, because the fallback differs by it: a style that was
            // actually being rejected would still look right in the first assertion alone.
            assertEquals(
                option.value,
                resolveBorderStyle(option.value, background = false),
            )
        }
    }

    @Test
    fun `every option is named`() {
        (SUBTITLE_TEXT_COLORS + SUBTITLE_PANEL_COLORS + SUBTITLE_OUTLINE_COLORS +
            SUBTITLE_BORDER_STYLES).forEach { option ->
            assertTrue(option.label.isNotBlank(), "${option.value} has no label")
        }
    }

    // The label lookups are what the television and the player menu display. A value present in
    // the list must resolve to its own name rather than to the not-recognised fallback.
    @Test
    fun `a listed value is labelled from the list rather than by fallback`() {
        assertEquals("Panel", subtitleBorderStyleLabel("opaque-box"))
        assertEquals("Box per line", subtitleBorderStyleLabel("background-box"))
        assertEquals("Outline", subtitleBorderStyleLabel("outline-and-shadow"))
        assertEquals("White", subtitleColorLabel("#FFFFFFFF"))
        assertEquals("Yellow", subtitleColorLabel("#FFFFF200"))
    }

    // Colours are stored upper case but arrive from a hand-edited file in either.
    @Test
    fun `a colour is labelled regardless of case`() {
        assertEquals("Yellow", subtitleColorLabel("#fffff200"))
    }

    // A value from a newer build reaches this one through sync. Shown as itself, so the control
    // reads as understood-but-unfamiliar instead of as one that lost its value.
    @Test
    fun `an unlisted value is shown as itself`() {
        assertEquals("shadow-box", subtitleBorderStyleLabel("shadow-box"))
        assertEquals("#FF00FF00", subtitleColorLabel("#FF00FF00"))
    }

    // No duplicates: a repeated preset would draw two identical swatches, both of which would
    // read as selected at once because selection is compared by value.
    @Test
    fun `no list offers the same value twice`() {
        listOf(
            SUBTITLE_TEXT_COLORS,
            SUBTITLE_PANEL_COLORS,
            SUBTITLE_OUTLINE_COLORS,
            SUBTITLE_BORDER_STYLES,
        ).forEach { options ->
            val values = options.map { it.value.uppercase() }
            assertEquals(values.size, values.distinct().size, "duplicate preset in $values")
        }
    }

    // Both defaults have to be offered, or opening a control on a fresh profile would show
    // nothing selected and the viewer could not get back to where they started.
    @Test
    fun `the stored defaults are among the presets`() {
        assertTrue(SUBTITLE_TEXT_COLORS.any { it.value == "#FFFFFFFF" })
        assertTrue(SUBTITLE_PANEL_COLORS.any { it.value == "#AF000000" })
        assertTrue(SUBTITLE_OUTLINE_COLORS.any { it.value == "#FF000000" })
    }
}
