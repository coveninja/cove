package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.model.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackPreferencesTest {

    // Mutation applied to verify: resolved Original to the literal string
    // "original" → test failed, the player would have been given a language code
    // that no file declares.
    @Test
    fun `original resolves to the title's own language`() {
        val settings = AppSettings(defaultAudioLang = AUDIO_LANGUAGE_ORIGINAL)

        val preferences = settings.playbackPreferences(originalLanguage = "ja")

        assertEquals(listOf("ja"), preferences.audioLanguages)
    }

    // A title with no declared language leaves the preference empty rather than
    // inventing one, so the player falls back to the file's own default.
    // Mutation applied to verify: substituted "en" when unknown → test failed.
    @Test
    fun `original with no known language expresses no preference`() {
        val settings = AppSettings(defaultAudioLang = AUDIO_LANGUAGE_ORIGINAL)

        assertTrue(settings.playbackPreferences(originalLanguage = null).audioLanguages.isEmpty())
        assertTrue(settings.playbackPreferences(originalLanguage = "").audioLanguages.isEmpty())
    }

    // Mutation applied to verify: passed the percentage through unscaled → test
    // failed with 100.0, which would have rendered subtitles a hundred times
    // their normal size.
    @Test
    fun `subtitle size becomes a multiplier`() {
        assertEquals(1.0, AppSettings(subtitleSize = 100.0).playbackPreferences(null).subtitleScale)
        assertEquals(1.5, AppSettings(subtitleSize = 150.0).playbackPreferences(null).subtitleScale)
    }

    // Mutation applied to verify: removed the clamp → test failed, a stored zero
    // produced a scale of 0 and subtitles that could not be seen at all.
    @Test
    fun `an extreme stored size is clamped to something usable`() {
        assertEquals(0.25, AppSettings(subtitleSize = 0.0).playbackPreferences(null).subtitleScale)
        assertEquals(4.0, AppSettings(subtitleSize = 5000.0).playbackPreferences(null).subtitleScale)
    }

    // The setting measures up from the bottom; mpv's sub-pos measures down from
    // the top. Getting this backwards puts subtitles at the top of the picture.
    // Mutation applied to verify: passed the value straight through → test failed
    // with 8 instead of 92.
    @Test
    fun `subtitle position is inverted for the player`() {
        assertEquals(92, AppSettings(subtitlePosition = 8.0).playbackPreferences(null).subtitlePosition)
        assertEquals(100, AppSettings(subtitlePosition = 0.0).playbackPreferences(null).subtitlePosition)
    }

    // Mutation applied to verify: read defaultAudioLang for both → test failed,
    // the subtitle language followed the audio setting.
    @Test
    fun `audio and subtitle languages come from their own settings`() {
        val settings = AppSettings(defaultAudioLang = "ja", defaultSubtitleLang = "en")

        val preferences = settings.playbackPreferences(originalLanguage = "ja")

        assertEquals(listOf("ja"), preferences.audioLanguages)
        assertEquals(listOf("en"), preferences.subtitleLanguages)
    }
}
