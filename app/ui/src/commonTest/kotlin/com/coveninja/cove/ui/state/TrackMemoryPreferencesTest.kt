package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.data.TrackMemory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A remembered choice laid over the settings.
 *
 * The point of the feature: picking the Japanese track on episode one should not have to be
 * repeated eleven more times. The point of the *tests*: it must not overrule a setting the
 * viewer never contradicted.
 */
class TrackMemoryPreferencesTest {

    private val defaults = PlaybackPreferences(
        audioLanguages = listOf("en", "eng"),
        subtitleLanguages = listOf("en", "eng"),
        subtitlesEnabled = false,
        startMuted = false,
        subtitleScale = 1.0,
        subtitlePosition = 92,
        subtitleBackground = true,
        hardwareDecoding = true,
    )

    @Test
    fun `an empty memory changes nothing`() {
        assertEquals(defaults, defaults.withMemory(TrackMemory.None))
    }

    /**
     * The alias expansion matters as much as the override: a file tags Japanese audio "jpn",
     * which does not start with "ja", so handing the player the two-letter code alone selects
     * the English dub — exactly the case someone turns this on to avoid.
     */
    @Test
    fun `a remembered audio language wins and is expanded to its aliases`() {
        val resolved = defaults.withMemory(TrackMemory(audioLanguage = "ja"))
        assertTrue("jpn" in resolved.audioLanguages, "the three-letter form must be offered")
        assertEquals("ja", resolved.audioLanguages.first())
    }

    // Mutation check: overriding unconditionally wipes the settings' own subtitle language.
    @Test
    fun `a memory that only names audio leaves the subtitles to the settings`() {
        val resolved = defaults.withMemory(TrackMemory(audioLanguage = "de"))
        assertEquals(defaults.subtitleLanguages, resolved.subtitleLanguages)
        assertEquals(defaults.subtitlesEnabled, resolved.subtitlesEnabled)
    }

    /** Choosing a subtitle track is also choosing to have subtitles on. */
    @Test
    fun `a remembered subtitle language turns subtitles on`() {
        val resolved = defaults.withMemory(TrackMemory(subtitleLanguage = "fr"))
        assertTrue(resolved.subtitlesEnabled)
        assertTrue("fra" in resolved.subtitleLanguages)
    }

    /**
     * "Off" and "never chose" are different facts, which is why the memory carries a flag for
     * it rather than an empty language — without the flag, switching subtitles off would be
     * indistinguishable from never having touched them and would not survive the episode.
     */
    @Test
    fun `subtitles switched off stay off against a setting that wants them on`() {
        val wantsSubtitles = defaults.copy(subtitlesEnabled = true)
        val resolved = wantsSubtitles.withMemory(TrackMemory(subtitlesOff = true))
        assertFalse(resolved.subtitlesEnabled)
    }

    // Mutation check: letting a stale language survive the off flag re-selects a track.
    @Test
    fun `switching off clears any remembered language`() {
        val resolved = defaults.withMemory(
            TrackMemory(subtitleLanguage = "fr", subtitlesOff = true),
        )
        assertFalse(resolved.subtitlesEnabled)
    }

    /** Everything else about the preferences is the settings' business and stays untouched. */
    @Test
    fun `the memory has no opinion on scale, position or decoding`() {
        val resolved = defaults.withMemory(
            TrackMemory(audioLanguage = "ja", subtitleLanguage = "en", speed = 1.5),
        )
        assertEquals(defaults.subtitleScale, resolved.subtitleScale)
        assertEquals(defaults.subtitlePosition, resolved.subtitlePosition)
        assertEquals(defaults.subtitleBackground, resolved.subtitleBackground)
        assertEquals(defaults.hardwareDecoding, resolved.hardwareDecoding)
        assertEquals(defaults.startMuted, resolved.startMuted)
    }
}

/**
 * Whether a memory is worth storing at all.
 *
 * A row full of defaults reads back as "chose the defaults" and would override the settings
 * for ever, so clearing a choice has to erase the row rather than write a blank one.
 */
class TrackMemoryEmptinessTest {

    @Test
    fun `a memory with no choices in it is empty`() {
        assertTrue(TrackMemory.None.isEmpty)
    }

    // Mutation check: omitting any one term below reports one of these as empty.
    @Test
    fun `any single choice makes it worth keeping`() {
        assertFalse(TrackMemory(audioLanguage = "ja").isEmpty)
        assertFalse(TrackMemory(subtitleLanguage = "en").isEmpty)
        assertFalse(TrackMemory(subtitlesOff = true).isEmpty)
        assertFalse(TrackMemory(speed = 1.25).isEmpty)
    }

    /** Normal speed is not a choice; it is the absence of one. */
    @Test
    fun `speed back at normal does not by itself count`() {
        assertTrue(TrackMemory(speed = 1.0).isEmpty)
    }
}
