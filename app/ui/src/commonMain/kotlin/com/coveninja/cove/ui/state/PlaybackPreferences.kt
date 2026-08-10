package com.coveninja.cove.ui.state

import com.coveninja.cove.shared.model.AppSettings

/**
 * The viewer's playback preferences, resolved for one title.
 *
 * Built here rather than read from AppSettings at the point of use so the
 * awkward parts — Original resolving against the title's own language, and the
 * unit differences between what the settings store and what a player wants — are
 * decided once and can be tested.
 */
data class PlaybackPreferences(
    /** Language codes in preference order; empty means let the file decide. */
    val audioLanguages: List<String>,
    val subtitleLanguages: List<String>,
    val subtitlesEnabled: Boolean,
    val startMuted: Boolean,
    /** 1.0 is the player's own default size. */
    val subtitleScale: Double,
    /** 0 is the bottom of the frame, 100 the top — mpv's convention. */
    val subtitlePosition: Int,
    val subtitleBackground: Boolean,
)

/**
 * @param originalLanguage the title's own language, used to resolve Original.
 */
fun AppSettings.playbackPreferences(originalLanguage: String?): PlaybackPreferences {
    fun resolve(preference: String): List<String> = when {
        preference.isBlank() -> emptyList()
        preference == AUDIO_LANGUAGE_ORIGINAL ->
            listOfNotNull(originalLanguage?.lowercase()?.takeIf { it.isNotBlank() })

        else -> listOf(preference.lowercase())
    }

    return PlaybackPreferences(
        audioLanguages = resolve(defaultAudioLang),
        subtitleLanguages = resolve(defaultSubtitleLang),
        subtitlesEnabled = subtitlesEnabled,
        startMuted = openOnMute,
        // The setting is a percentage where 100 means "normal"; players take a
        // multiplier. Clamped so a stored extreme cannot make subtitles unusable.
        subtitleScale = (subtitleSize / 100.0).coerceIn(0.25, 4.0),
        // The setting measures distance up from the bottom of the frame; mpv's
        // sub-pos measures down from the top, so the two are inverses.
        subtitlePosition = (100 - subtitlePosition.toInt()).coerceIn(0, 100),
        subtitleBackground = subtitleBackground,
    )
}
