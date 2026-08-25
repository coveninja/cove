package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.model.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackPreferencesTest {

    @Test
    fun `original resolves to the title's own language`() {
        val settings = AppSettings(defaultAudioLang = AUDIO_LANGUAGE_ORIGINAL)

        val preferences = settings.playbackPreferences(originalLanguage = "ja")

        assertEquals("ja", preferences.audioLanguages.first())
    }

    // The bug this exists for: TMDB says "ja", the file says "jpn", and "jpn" does
    // not start with "ja" — so asking for the original audio of a Japanese title
    // matched nothing and left the English dub selected.
    @Test
    fun `a two-letter language also asks for its three-letter forms`() {
        val settings = AppSettings(defaultAudioLang = AUDIO_LANGUAGE_ORIGINAL)

        val languages = settings.playbackPreferences(originalLanguage = "ja").audioLanguages

        assertTrue("jpn" in languages, "was: $languages")
    }

    // Both ISO 639-2 forms, because releases use both: German tracks are tagged
    // "ger" about as often as "deu", and only one of them is a prefix match for "de".
    @Test
    fun `languages with two three-letter codes ask for both`() {
        assertTrue(languageAliases("de").containsAll(listOf("de", "deu", "ger")))
        assertTrue(languageAliases("fr").containsAll(listOf("fr", "fra", "fre")))
    }

    // The preferred code has to stay first, or the alias outranks the thing it is
    // an alias for and a file carrying both tracks picks the wrong one.
    @Test
    fun `the requested code outranks its aliases`() {
        assertEquals("ja", languageAliases("ja").first())
    }

    // A code that is already ISO 639-2 is not a key in the table, so it comes back
    // as itself. What this pins is that an unlisted code is passed through rather
    // than dropped — every language not in the table depends on that.
    //
    // No mutation kills the distinct() in languageAliases, and that is the honest
    // finding: no entry lists its own key, so a duplicate cannot arise today. It is
    // kept because the failure it prevents is a table-authoring slip rather than a
    // logic error, and this test would not catch that either.
    @Test
    fun `a code with no table entry passes through unchanged`() {
        assertEquals(listOf("eng"), languageAliases("eng"))
        assertEquals(listOf("mi"), languageAliases("mi"))
    }

    @Test
    fun `a blank language asks for nothing`() {
        assertTrue(languageAliases("").isEmpty())
        assertTrue(languageAliases("   ").isEmpty())
    }

    // Settings and file tags disagree about case often enough to matter.
    @Test
    fun `language codes are normalised before use`() {
        assertEquals("ja", languageAliases(" JA ").first())
    }

    // An explicit choice gets the same treatment as Original; there is nothing
    // special about the language happening to be the title's own.
    @Test
    fun `an explicitly chosen language is expanded too`() {
        val settings = AppSettings(defaultAudioLang = "de")

        assertTrue("deu" in settings.playbackPreferences(originalLanguage = "ja").audioLanguages)
    }

    // A title with no declared language leaves the preference empty rather than
    // inventing one, so the player falls back to the file's own default.
    @Test
    fun `original with no known language expresses no preference`() {
        val settings = AppSettings(defaultAudioLang = AUDIO_LANGUAGE_ORIGINAL)

        assertTrue(settings.playbackPreferences(originalLanguage = null).audioLanguages.isEmpty())
        assertTrue(settings.playbackPreferences(originalLanguage = "").audioLanguages.isEmpty())
    }

    @Test
    fun `subtitle size becomes a multiplier`() {
        assertEquals(1.0, AppSettings(subtitleSize = 100.0).playbackPreferences(null).subtitleScale)
        assertEquals(1.5, AppSettings(subtitleSize = 150.0).playbackPreferences(null).subtitleScale)
    }

    @Test
    fun `an extreme stored size is clamped to something usable`() {
        assertEquals(0.25, AppSettings(subtitleSize = 0.0).playbackPreferences(null).subtitleScale)
        assertEquals(4.0, AppSettings(subtitleSize = 5000.0).playbackPreferences(null).subtitleScale)
    }

    // The setting measures up from the bottom; mpv's sub-pos measures down from
    // the top. Getting this backwards puts subtitles at the top of the picture.
    @Test
    fun `subtitle position is inverted for the player`() {
        assertEquals(92, AppSettings(subtitlePosition = 8.0).playbackPreferences(null).subtitlePosition)
        assertEquals(100, AppSettings(subtitlePosition = 0.0).playbackPreferences(null).subtitlePosition)
    }

    @Test
    fun `audio and subtitle languages come from their own settings`() {
        val settings = AppSettings(defaultAudioLang = "ja", defaultSubtitleLang = "en")

        val preferences = settings.playbackPreferences(originalLanguage = "ja")

        // First, not the whole list: each preference is now sent with its
        // three-letter equivalents behind it. What this pins is that the two read
        // their own setting rather than sharing one.
        assertEquals("ja", preferences.audioLanguages.first())
        assertEquals("en", preferences.subtitleLanguages.first())
    }
}
