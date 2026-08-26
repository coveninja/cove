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

    private fun style(settings: AppSettings) = settings.playbackPreferences(null).subtitleStyle

    @Test
    fun `subtitle size becomes a multiplier`() {
        assertEquals(1.0, style(AppSettings(subtitleSize = 100.0)).scale)
        assertEquals(1.5, style(AppSettings(subtitleSize = 150.0)).scale)
    }

    @Test
    fun `an extreme stored size is clamped to something usable`() {
        assertEquals(0.25, style(AppSettings(subtitleSize = 0.0)).scale)
        assertEquals(4.0, style(AppSettings(subtitleSize = 5000.0)).scale)
    }

    // The setting measures up from the bottom; mpv's sub-pos measures down from
    // the top. Getting this backwards puts subtitles at the top of the picture.
    @Test
    fun `subtitle position is inverted for the player`() {
        assertEquals(92, style(AppSettings(subtitlePosition = 8.0)).position)
        assertEquals(100, style(AppSettings(subtitlePosition = 0.0)).position)
    }

    // The ordered list is what the viewer arranged; the single-language field is what every
    // build before it wrote. Reading the list where there is one is the whole feature.
    @Test
    fun `an ordered preference is used in order, each with its aliases behind it`() {
        val settings = AppSettings(
            defaultAudioLang = "en",
            audioLanguages = listOf("ja", "en"),
        )

        val languages = settings.playbackPreferences(originalLanguage = null).audioLanguages

        assertEquals("ja", languages.first())
        // Japanese and its three-letter forms all rank ahead of English, or "en" would be
        // matched by a file tagged "eng" before the Japanese track was ever considered.
        assertTrue(languages.indexOf("jpn") < languages.indexOf("en"), "was: $languages")
        assertTrue("eng" in languages, "was: $languages")
    }

    // Every profile written before the list existed has an empty one, and must keep working
    // without a migration step. This is what makes that true.
    @Test
    fun `an empty order falls back to the single-language setting`() {
        val settings = AppSettings(defaultAudioLang = "de", audioLanguages = emptyList())

        assertEquals(listOf("de"), settings.orderedAudioLanguages())
        assertTrue("deu" in settings.playbackPreferences(null).audioLanguages)
    }

    // The accessor is tested directly, not only through playbackPreferences. A mutation that
    // made it ignore the list entirely went unnoticed while the two of them each implemented
    // the fallback: the resolution read the raw field and agreed by coincidence. They share
    // one implementation now, and this is what holds the accessor itself to it.
    @Test
    fun `the accessor reads the order rather than the single-language field`() {
        val settings = AppSettings(
            defaultAudioLang = "de",
            audioLanguages = listOf("ja", "en"),
            defaultSubtitleLang = "de",
            subtitleLanguages = listOf("es"),
        )

        assertEquals(listOf("ja", "en"), settings.orderedAudioLanguages())
        assertEquals(listOf("es"), settings.orderedSubtitleLanguages())
    }

    // Blank entries can arrive from a hand-edited file or an older build. A blank reaching
    // mpv's alang would ask for a language called "", and reaching the settings row would
    // draw a nameless entry the viewer cannot identify or remove with confidence.
    @Test
    fun `blank entries are dropped from an order as it is read`() {
        val settings = AppSettings(audioLanguages = listOf("", "  ", "ja"))

        assertEquals(listOf("ja"), settings.orderedAudioLanguages())
    }

    // Both blank is a real state and means what it says: no preference, so the file decides.
    // Callers have to cope with an empty list rather than assuming a first element.
    @Test
    fun `no preference at all resolves to an empty order`() {
        val settings = AppSettings(defaultAudioLang = "", audioLanguages = emptyList())

        assertTrue(settings.orderedAudioLanguages().isEmpty())
        assertTrue(settings.playbackPreferences(null).audioLanguages.isEmpty())
    }

    // The invariant the sync contract rests on: a build that has never heard of the list reads
    // the scalar, so the scalar must always name the language the list leads with. Left to
    // drift, that build would show — and push back — a different language entirely.
    @Test
    fun `writing an order keeps the single-language field on its head`() {
        val settings = AppSettings().withAudioLanguages(listOf("ja", "en"))

        assertEquals("ja", settings.defaultAudioLang)
        assertEquals(listOf("ja", "en"), settings.audioLanguages)

        // And the subtitle setter writes its own field rather than sharing one.
        val both = settings.withSubtitleLanguages(listOf("es"))
        assertEquals("es", both.defaultSubtitleLang)
        assertEquals("ja", both.defaultAudioLang)
    }

    // Clearing the order is "I have expressed no order", not "I have no language". Blanking
    // the scalar too would leave the profile with nothing at all to fall back on.
    @Test
    fun `clearing an order leaves the single-language field alone`() {
        val settings = AppSettings(defaultAudioLang = "de").withAudioLanguages(emptyList())

        assertTrue(settings.audioLanguages.isEmpty())
        assertEquals("de", settings.defaultAudioLang)
    }

    // Blank and duplicate entries can arrive from a hand-edited file or a sync from a build
    // that allowed them. A duplicate would ask mpv for the same language twice.
    @Test
    fun `an order is cleaned as it is written`() {
        val settings = AppSettings().withAudioLanguages(listOf("ja", "", "  ", "ja", "en"))

        assertEquals(listOf("ja", "en"), settings.audioLanguages)
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
