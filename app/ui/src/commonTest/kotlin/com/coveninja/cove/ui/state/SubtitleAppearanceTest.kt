package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.model.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The value the player's subtitle menu and the settings screen both edit.
 *
 * These are now three controls writing one set of fields — the settings screen, the pointer
 * menu and the television's panel — so the rules about *how* they are written live here rather
 * than at each of them. A rule enforced in three places is a rule that holds in two.
 */
class SubtitleAppearanceTest {

    // Reading and writing back unchanged must be a no-op. The player writes the whole value on
    // every press, so anything this round trip alters would be altered by a viewer opening the
    // menu and nudging something unrelated.
    @Test
    fun `a round trip changes nothing`() {
        val settings = AppSettings(
            subtitleSize = 130.0,
            subtitlePosition = 12.0,
            subtitleBorderStyle = "background-box",
            subtitleBackground = true,
            subtitleTextColor = "#FFFFF200",
        )

        val returned = settings.withSubtitleAppearance(settings.subtitleAppearance())

        assertEquals(settings.subtitleSize, returned.subtitleSize)
        assertEquals(settings.subtitlePosition, returned.subtitlePosition)
        assertEquals(settings.subtitleBorderStyle, returned.subtitleBorderStyle)
        assertEquals(settings.subtitleBackground, returned.subtitleBackground)
        assertEquals(settings.subtitleTextColor, returned.subtitleTextColor)
    }

    // A stepper can be held down. Without a clamp it would walk the value somewhere the
    // settings sliders cannot represent and therefore cannot bring back.
    @Test
    fun `a stepper cannot walk a value past what settings can show`() {
        val big = AppSettings().withSubtitleAppearance(
            AppSettings().subtitleAppearance().copy(sizePercent = 5000.0),
        )
        assertEquals(SUBTITLE_SIZE_MAX, big.subtitleSize)

        val small = AppSettings().withSubtitleAppearance(
            AppSettings().subtitleAppearance().copy(sizePercent = -40.0),
        )
        assertEquals(SUBTITLE_SIZE_MIN, small.subtitleSize)

        val high = AppSettings().withSubtitleAppearance(
            AppSettings().subtitleAppearance().copy(position = 900.0),
        )
        assertEquals(SUBTITLE_POSITION_MAX, high.subtitlePosition)

        val low = AppSettings().withSubtitleAppearance(
            AppSettings().subtitleAppearance().copy(position = -900.0),
        )
        assertEquals(SUBTITLE_POSITION_MIN, low.subtitlePosition)
    }

    // A value already stored outside the range — from a hand-edited file, or a build with
    // different bounds — is brought back in on the way out, so the control shows something it
    // can actually act on rather than a number its own arrows cannot move.
    @Test
    fun `reading clamps a stored value that is out of range`() {
        val appearance = AppSettings(subtitleSize = 9000.0, subtitlePosition = -5.0)
            .subtitleAppearance()

        assertEquals(SUBTITLE_SIZE_MAX, appearance.sizePercent)
        assertEquals(SUBTITLE_POSITION_MIN, appearance.position)
    }

    // The legacy flag is the whole of what older builds and older profiles understand about
    // the backdrop, and it syncs. A writer that set only the three-way would leave this device
    // drawing a panel and the phone in the next room drawing none.
    @Test
    fun `the legacy background flag is kept in step, both ways`() {
        val panel = AppSettings().withSubtitleAppearance(
            AppSettings().subtitleAppearance().copy(borderStyle = "opaque-box"),
        )
        assertEquals("opaque-box", panel.subtitleBorderStyle)
        assertTrue(panel.subtitleBackground)

        val boxed = AppSettings().withSubtitleAppearance(
            AppSettings().subtitleAppearance().copy(borderStyle = "background-box"),
        )
        assertTrue(boxed.subtitleBackground, "a box per line is still a backdrop")

        // The one that turns it off, which is the direction a one-way sync would get wrong.
        val outline = AppSettings(subtitleBackground = true).withSubtitleAppearance(
            AppSettings().subtitleAppearance().copy(borderStyle = "outline-and-shadow"),
        )
        assertEquals("outline-and-shadow", outline.subtitleBorderStyle)
        assertFalse(outline.subtitleBackground)
    }

    // Every profile written before subtitleBorderStyle existed has it empty, and the boolean
    // beside it is the only thing that says what they chose.
    @Test
    fun `an unset border style still reads through the legacy flag`() {
        assertEquals(
            "opaque-box",
            AppSettings(subtitleBorderStyle = "", subtitleBackground = true)
                .subtitleAppearance().borderStyle,
        )
        assertEquals(
            "outline-and-shadow",
            AppSettings(subtitleBorderStyle = "", subtitleBackground = false)
                .subtitleAppearance().borderStyle,
        )
    }

    // The control never has to deal with the empty case, unlike the stored field.
    @Test
    fun `the border style handed to a control is never empty`() {
        assertTrue(AppSettings(subtitleBorderStyle = "").subtitleAppearance().borderStyle.isNotEmpty())
        assertTrue(
            SUBTITLE_BORDER_STYLES.any {
                it.value == AppSettings(subtitleBorderStyle = "").subtitleAppearance().borderStyle
            },
            "it must also be one of the styles a control offers",
        )
    }

    // mpv ignores a colour it cannot parse, so a control that wrote one would appear to work
    // and do nothing. Keeping what was already stored is the recoverable answer.
    @Test
    fun `a malformed colour is refused rather than stored`() {
        val settings = AppSettings(subtitleTextColor = "#FF00FF00")

        val written = settings.withSubtitleAppearance(
            settings.subtitleAppearance().copy(textColor = "not a colour"),
        )

        assertEquals("#FF00FF00", written.subtitleTextColor)
    }

    // Only the four. The menu writes the whole value on every press, so a field outside this
    // set that it touched would be quietly reset by an unrelated nudge — the font someone set
    // in settings disappearing because they made the text bigger while watching.
    @Test
    fun `nothing outside the four is touched`() {
        val settings = AppSettings(
            subtitleFont = "Atkinson Hyperlegible",
            subtitleOutlineColor = "#FF404040",
            subtitleOutlineSize = 3.0,
            subtitleShadowOffset = 1.5,
            subtitleBackColor = "#AF1A1A1A",
            subtitleBold = true,
            subtitleItalic = true,
            subtitleBlur = 2.5,
            subtitleAssOverride = "force",
            subtitleAlign = "left",
            subtitlesEnabled = true,
            defaultSubtitleLang = "fr",
        )

        val written = settings.withSubtitleAppearance(
            settings.subtitleAppearance().copy(sizePercent = 150.0),
        )

        assertEquals("Atkinson Hyperlegible", written.subtitleFont)
        assertEquals("#FF404040", written.subtitleOutlineColor)
        assertEquals(3.0, written.subtitleOutlineSize)
        assertEquals(1.5, written.subtitleShadowOffset)
        assertEquals("#AF1A1A1A", written.subtitleBackColor)
        assertTrue(written.subtitleBold)
        assertTrue(written.subtitleItalic)
        assertEquals(2.5, written.subtitleBlur)
        assertEquals("force", written.subtitleAssOverride)
        assertEquals("left", written.subtitleAlign)
        assertTrue(written.subtitlesEnabled)
        assertEquals("fr", written.defaultSubtitleLang)
        // And the one thing it was asked to change did change.
        assertEquals(150.0, written.subtitleSize)
    }

    // What the player edits and what the player draws with have to be the same value. This is
    // the join between the two: a size set in the menu must reach mpv's sub-scale.
    @Test
    fun `a change reaches the style the player is given`() {
        val settings = AppSettings().withSubtitleAppearance(
            AppSettings().subtitleAppearance().copy(sizePercent = 150.0, position = 20.0),
        )

        val style = settings.subtitleStyle()

        assertEquals(1.5, style.scale)
        // sub-pos measures down from the top, so 20% up from the bottom is 80.
        assertEquals(80, style.position)
    }

    // The steps have to divide the range, or the arrows cannot reach the ends of it.
    @Test
    fun `the steps land on the bounds rather than beside them`() {
        assertEquals(0.0, (SUBTITLE_SIZE_MAX - SUBTITLE_SIZE_MIN) % SUBTITLE_SIZE_STEP)
        assertEquals(0.0, (SUBTITLE_POSITION_MAX - SUBTITLE_POSITION_MIN) % SUBTITLE_POSITION_STEP)
        // And a reset lands somewhere the arrows could also have reached.
        assertEquals(0.0, (DEFAULT_SUBTITLE_SIZE - SUBTITLE_SIZE_MIN) % SUBTITLE_SIZE_STEP)
        assertEquals(0.0, (DEFAULT_SUBTITLE_POSITION - SUBTITLE_POSITION_MIN) % SUBTITLE_POSITION_STEP)
    }

    // A reset that landed somewhere the stored default is not would leave the control claiming
    // to be at the default while the profile disagreed.
    @Test
    fun `reset targets are the stored defaults`() {
        assertEquals(AppSettings().subtitleSize, DEFAULT_SUBTITLE_SIZE)
        assertEquals(AppSettings().subtitlePosition, DEFAULT_SUBTITLE_POSITION)
    }
}
