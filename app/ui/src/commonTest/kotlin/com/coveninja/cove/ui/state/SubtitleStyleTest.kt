package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.model.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Turning stored settings into the options mpv actually takes.
 *
 * Everything here fails silently in the app if it is wrong: mpv answers a property it cannot
 * parse by ignoring it, so a malformed colour or an unrecognised border style is not an error
 * anywhere — it is subtitles that stay the way they were while the control appears to work.
 * That is the whole reason this is resolved in one testable place instead of at the two hosts.
 */
class SubtitleStyleTest {

    // The defaults are mpv's own, so a profile that never opens the new controls has to render
    // exactly as it did before they existed. Any drift here restyles every existing viewer's
    // subtitles on upgrade.
    @Test
    fun `an untouched profile resolves to mpv's own defaults`() {
        val style = AppSettings().subtitleStyle()

        assertEquals("#FFFFFFFF", style.textColor)
        assertEquals("#FF000000", style.outlineColor)
        assertEquals(1.65, style.outlineSize)
        assertEquals("#AF000000", style.backColor)
        assertEquals(0.0, style.shadowOffset)
        assertEquals(0.0, style.blur)
        assertEquals("scale", style.assOverride)
        assertEquals("center", style.align)
        assertEquals("", style.font)
    }

    // `subtitleBackground` is the only thing older profiles and older builds have ever said
    // about this, so it decides whenever the newer three-valued field has said nothing.
    @Test
    fun `an unset border style defers to the legacy background flag`() {
        assertEquals(
            "opaque-box",
            resolveBorderStyle(borderStyle = "", background = true),
        )
        assertEquals(
            "outline-and-shadow",
            resolveBorderStyle(borderStyle = "", background = false),
        )
    }

    @Test
    fun `an explicit border style wins over the legacy flag`() {
        assertEquals(
            "background-box",
            resolveBorderStyle(borderStyle = "background-box", background = false),
        )
        assertEquals(
            "outline-and-shadow",
            resolveBorderStyle(borderStyle = "outline-and-shadow", background = true),
        )
    }

    // A style from a newer build reaches this one through sync. Falling back to the boolean
    // means the viewer still gets approximately what they chose, rather than mpv silently
    // ignoring a value it does not know and leaving the last style in place.
    @Test
    fun `an unknown border style falls back rather than reaching mpv`() {
        assertEquals(
            "opaque-box",
            resolveBorderStyle(borderStyle = "shadow-box", background = true),
        )
        assertEquals(
            "outline-and-shadow",
            resolveBorderStyle(borderStyle = "shadow-box", background = false),
        )
    }

    // mpv takes #RRGGBB or #AARRGGBB and ignores the property entirely for anything else.
    @Test
    fun `a malformed colour falls back to one that works`() {
        assertEquals("#FF00FF00", resolveSubtitleColor("#FF00FF00", "#FFFFFFFF"))
        assertEquals("#00FF00", resolveSubtitleColor("#00FF00", "#FFFFFFFF"))
        assertEquals("#FFFFFFFF", resolveSubtitleColor("green", "#FFFFFFFF"))
        assertEquals("#FFFFFFFF", resolveSubtitleColor("00FF00", "#FFFFFFFF"))
        assertEquals("#FFFFFFFF", resolveSubtitleColor("#FF00F", "#FFFFFFFF"))
        assertEquals("#FFFFFFFF", resolveSubtitleColor("#GGGGGG", "#FFFFFFFF"))
        assertEquals("#FFFFFFFF", resolveSubtitleColor("", "#FFFFFFFF"))
    }

    // Each colour field falls back to its *own* mpv default, which is not the same value for
    // all three — white text, black outline, and a translucent black panel. Nothing else pins
    // that wiring: with valid settings the fallbacks are never reached, so a pair of them
    // swapped would give a viewer with one corrupt value a black-on-black subtitle and no
    // error anywhere to say why.
    @Test
    fun `each colour falls back to its own default, not a shared one`() {
        val corrupt = AppSettings(
            subtitleTextColor = "nonsense",
            subtitleOutlineColor = "nonsense",
            subtitleBackColor = "nonsense",
        ).subtitleStyle()

        assertEquals("#FFFFFFFF", corrupt.textColor)
        assertEquals("#FF000000", corrupt.outlineColor)
        assertEquals("#AF000000", corrupt.backColor)
    }

    @Test
    fun `colours are normalised to upper case hex`() {
        assertEquals("#FF00FF00", resolveSubtitleColor("#ff00ff00", "#FFFFFFFF"))
        assertEquals("#FF00FF00", resolveSubtitleColor("  #Ff00fF00  ", "#FFFFFFFF"))
    }

    // The opacity control reads and writes the alpha half of the same value the swatches set.
    @Test
    fun `opacity is read from and written to the colour`() {
        assertEquals(0xAF, subtitleColorAlpha("#AF000000"))
        assertEquals(255, subtitleColorAlpha("#FFFFFFFF"))
        // No alpha at all is fully opaque, which is what mpv makes of a six-digit colour.
        assertEquals(255, subtitleColorAlpha("#00FF00"))
        assertEquals("#80000000", withSubtitleColorAlpha("#AF000000", 0x80))
        // The colour survives the change; only the alpha moves.
        assertEquals("#0000FF00", withSubtitleColorAlpha("#FF00FF00", 0))
    }

    @Test
    fun `an out-of-range opacity is clamped rather than wrapping`() {
        assertEquals("#FF000000", withSubtitleColorAlpha("#00000000", 999))
        assertEquals("#00000000", withSubtitleColorAlpha("#FF000000", -5))
    }

    // A stored extreme must not be able to make subtitles unusable or unreadable.
    @Test
    fun `extreme stored appearance values are clamped`() {
        val huge = AppSettings(
            subtitleOutlineSize = 500.0,
            subtitleShadowOffset = 500.0,
            subtitleBlur = 500.0,
        ).subtitleStyle()

        assertEquals(10.0, huge.outlineSize)
        assertEquals(10.0, huge.shadowOffset)
        assertEquals(20.0, huge.blur)

        val negative = AppSettings(
            subtitleOutlineSize = -3.0,
            subtitleShadowOffset = -3.0,
            subtitleBlur = -3.0,
        ).subtitleStyle()

        assertEquals(0.0, negative.outlineSize)
        assertEquals(0.0, negative.shadowOffset)
        assertEquals(0.0, negative.blur)
    }

    // Same reasoning as the border style: a value mpv does not know is a property it drops.
    @Test
    fun `an unknown ass-override or alignment falls back to mpv's default`() {
        assertEquals("scale", AppSettings(subtitleAssOverride = "nonsense").subtitleStyle().assOverride)
        assertEquals("force", AppSettings(subtitleAssOverride = " FORCE ").subtitleStyle().assOverride)
        assertEquals("center", AppSettings(subtitleAlign = "middle").subtitleStyle().align)
        assertEquals("right", AppSettings(subtitleAlign = "Right").subtitleStyle().align)
    }

    // The filter string is the destructive one. mpv answers a filter it cannot build by
    // ending the file, so a mode this build does not recognise must resolve to Off — never
    // to a filter name passed through on the assumption that the player will cope.
    @Test
    fun `an unrecognised normalisation mode is off rather than passed through`() {
        assertEquals(AudioNormalization.Off, AudioNormalization.from("loudnorm"))
        assertEquals(AudioNormalization.Off, AudioNormalization.from(null))
        assertEquals(AudioNormalization.Off, AudioNormalization.from(""))
        assertEquals("", AudioNormalization.from("loudnorm").filter)
    }

    @Test
    fun `each normalisation mode carries the filter it needs`() {
        assertEquals(AudioNormalization.Normalize, AudioNormalization.from("normalize"))
        assertEquals(AudioNormalization.Night, AudioNormalization.from(" Night "))
        assertTrue(AudioNormalization.Normalize.filter.startsWith("lavfi=["))
        // Night is the one that also pulls loud scenes down, which is the compressor.
        assertTrue(AudioNormalization.Night.filter.contains("acompressor"))
        // Off clears the chain rather than leaving the previous filter running.
        assertEquals("", AudioNormalization.Off.filter)
    }

    // A downmix mpv cannot parse would leave the previous layout in place, so only the values
    // Cove offers reach it. Empty means auto-safe: whatever the track already is.
    @Test
    fun `only offered downmix values survive`() {
        assertEquals("stereo", AppSettings(audioDownmix = "stereo").playbackPreferences(null).audioDownmix)
        assertEquals("mono", AppSettings(audioDownmix = " MONO ").playbackPreferences(null).audioDownmix)
        assertEquals("", AppSettings(audioDownmix = "7.1").playbackPreferences(null).audioDownmix)
        assertEquals("", AppSettings(audioDownmix = "").playbackPreferences(null).audioDownmix)
    }
}
