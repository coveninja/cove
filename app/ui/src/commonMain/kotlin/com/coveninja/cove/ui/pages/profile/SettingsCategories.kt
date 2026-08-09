package com.coveninja.cove.ui.pages.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.ui.state.SettingsEditor

/**
 * The settings are grouped rather than stacked: a single scroll of thirty
 * controls gives no sense of where anything lives, and most people come here to
 * change one thing.
 */
enum class SettingsCategory(
    val label: String,
    val icon: String,
    val headline: String,
    val blurb: String,
) {
    Addons(
        label = "Addons",
        icon = "lucide:blocks",
        headline = "Addons",
        blurb = "Where streams come from. Cove ships with none, so this is the " +
            "first thing to set up.",
    ),
    Playback(
        label = "Playback",
        icon = "lucide:play-circle",
        headline = "Playback",
        blurb = "How titles start and how they carry on.",
    ),
    Sources(
        label = "Sources",
        icon = "lucide:list-video",
        headline = "Sources",
        blurb = "Which stream gets picked, and how much is done ahead of time.",
    ),
    Subtitles(
        label = "Subtitles",
        icon = "lucide:captions",
        headline = "Subtitles and audio",
        blurb = "Languages and how subtitles are drawn.",
    ),
    Skipping(
        label = "Skipping",
        icon = "lucide:skip-forward",
        headline = "Skipping",
        blurb = "Segments to jump past automatically.",
    ),
    Content(
        label = "Content",
        icon = "lucide:shield-check",
        headline = "Content",
        blurb = "What is shown, in which language, and how it is recommended.",
    ),
    Network(
        label = "Network",
        icon = "lucide:upload",
        headline = "Sharing and network",
        blurb = "Upload behaviour and access from other devices.",
    ),
    Trakt(
        label = "Trakt",
        icon = "iconamoon:history",
        headline = "Trakt",
        blurb = "Scrobbling and library sync.",
    ),
}

private val LANGUAGES = listOf(
    "en" to "English",
    "es" to "Español",
    "fr" to "Français",
    "de" to "Deutsch",
    "tr" to "Türkçe",
    "ja" to "日本語",
)

/**
 * Renders one category.
 *
 * Everything writes through [SettingsEditor], which rebuilds the whole
 * AppSettings object — PUT /api/settings is a replace with no merge, so a partial
 * body silently resets every field it omits.
 *
 * Deliberately absent everywhere: defaultProvider, sourcePreference,
 * measuredBandwidthMbps, onboardingDone, remoteAccessToken and updatedAt. Those
 * are machine-managed, and a control that wrote them would do harm.
 */
@Composable
fun SettingsCategoryContent(
    category: SettingsCategory,
    settings: AppSettings,
    editor: SettingsEditor,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (category) {
            SettingsCategory.Addons -> AddonSettings()

            SettingsCategory.Playback -> {
                SettingsCard {
                    SettingRows(
                        {
                            SettingToggle(
                                title = "Autoplay next episode",
                                description = "Continue series without stopping between episodes.",
                                checked = settings.autoPlay,
                                onCheckedChange = { editor.edit { copy(autoPlay = it) } },
                            )
                        },
                        {
                            SettingToggle(
                                title = "Remember position",
                                description = "Resume where you left off instead of restarting.",
                                checked = settings.rememberPosition,
                                onCheckedChange = { editor.edit { copy(rememberPosition = it) } },
                            )
                        },
                        {
                            SettingToggle(
                                title = "Start muted",
                                description = "Open every title with the sound off.",
                                checked = settings.openOnMute,
                                onCheckedChange = { editor.edit { copy(openOnMute = it) } },
                            )
                        },
                        {
                            SettingSlider(
                                title = "Default volume",
                                description = "Applied each time playback starts.",
                                value = settings.defaultVolume.toFloat(),
                                range = 0f..1f,
                                format = { "${(it * 100).toInt()}%" },
                                onCommit = { editor.edit { copy(defaultVolume = it.toDouble()) } },
                            )
                        },
                    )
                }
            }

            SettingsCategory.Sources -> {
                SettingsCard(
                    title = "Choosing a stream",
                    iconName = "lucide:list-video",
                ) {
                    SettingRows(
                        {
                            SettingToggle(
                                title = "Pick a source automatically",
                                description = "Off means Cove asks which source to use " +
                                    "whenever more than one is found.",
                                checked = settings.autoSelectStream,
                                onCheckedChange = { editor.edit { copy(autoSelectStream = it) } },
                            )
                        },
                        {
                            SettingChoice(
                                title = "Selection preference",
                                description = "How Cove ranks sources when it picks for you.",
                                options = listOf(
                                    "balanced" to "Balanced",
                                    "quality" to "Quality first",
                                    "seeders" to "Most seeded",
                                ),
                                selected = settings.streamSelectionMode,
                                onSelect = { editor.edit { copy(streamSelectionMode = it) } },
                            )
                        },
                        {
                            SettingToggle(
                                title = "Show source details",
                                description = "Display quality, size and provider on each candidate.",
                                checked = settings.showStreamDetails,
                                onCheckedChange = { editor.edit { copy(showStreamDetails = it) } },
                            )
                        },
                    )
                }

                SettingsCard(
                    title = "Ahead of time",
                    iconName = "lucide:clock",
                    description = "Work done before you press play. Costs bandwidth, saves waiting.",
                ) {
                    SettingRows(
                        {
                            SettingToggle(
                                title = "Check sources are alive",
                                description = "Probe each candidate before offering it. " +
                                    "Slower to start, fewer dead links.",
                                checked = settings.probeStreams,
                                onCheckedChange = { editor.edit { copy(probeStreams = it) } },
                            )
                        },
                        {
                            SettingToggle(
                                title = "Prefetch sources",
                                description = "Look sources up in the background so playback " +
                                    "starts sooner.",
                                checked = settings.prefetchStreams,
                                onCheckedChange = { editor.edit { copy(prefetchStreams = it) } },
                            )
                        },
                        {
                            SettingToggle(
                                title = "Prefetch the next episode",
                                description = "Begin fetching the following episode while you watch.",
                                checked = settings.prefetchNextEpisode,
                                onCheckedChange = { editor.edit { copy(prefetchNextEpisode = it) } },
                            )
                        },
                    )
                }

                SettingsCard(title = "Local network", iconName = "lucide:globe-2") {
                    SettingToggle(
                        title = "Allow sources on your local network",
                        description = "Permits private and LAN addresses. Leave off unless " +
                            "you run a source on this network.",
                        checked = settings.allowLanStreamSources,
                        onCheckedChange = { editor.edit { copy(allowLanStreamSources = it) } },
                    )
                }
            }

            SettingsCategory.Subtitles -> {
                SettingsCard(title = "Languages", iconName = "lucide:languages") {
                    SettingRows(
                        {
                            SettingToggle(
                                title = "Subtitles on by default",
                                description = "Turn subtitles on whenever a track is available.",
                                checked = settings.subtitlesEnabled,
                                onCheckedChange = { editor.edit { copy(subtitlesEnabled = it) } },
                            )
                        },
                        {
                            SettingChoice(
                                title = "Subtitle language",
                                options = LANGUAGES,
                                selected = settings.defaultSubtitleLang,
                                onSelect = { editor.edit { copy(defaultSubtitleLang = it) } },
                            )
                        },
                        {
                            SettingChoice(
                                title = "Audio language",
                                options = LANGUAGES,
                                selected = settings.defaultAudioLang,
                                onSelect = { editor.edit { copy(defaultAudioLang = it) } },
                            )
                        },
                    )
                }

                SettingsCard(title = "Appearance", iconName = "lucide:captions") {
                    SettingRows(
                        {
                            SettingSlider(
                                title = "Subtitle size",
                                value = settings.subtitleSize.toFloat(),
                                range = 50f..200f,
                                format = { "${it.toInt()}%" },
                                onCommit = { editor.edit { copy(subtitleSize = it.toDouble()) } },
                            )
                        },
                        {
                            SettingSlider(
                                title = "Subtitle position",
                                description = "Distance from the bottom of the picture.",
                                value = settings.subtitlePosition.toFloat(),
                                range = 0f..40f,
                                format = { it.toInt().toString() },
                                onCommit = { editor.edit { copy(subtitlePosition = it.toDouble()) } },
                            )
                        },
                        {
                            SettingToggle(
                                title = "Subtitle background",
                                description = "Draw a shaded box behind subtitles for readability.",
                                checked = settings.subtitleBackground,
                                onCheckedChange = { editor.edit { copy(subtitleBackground = it) } },
                            )
                        },
                    )
                }
            }

            SettingsCategory.Skipping -> {
                SettingsCard(
                    description = "Cove skips a segment only when a timestamp for it exists.",
                    title = "Segments",
                    iconName = "lucide:skip-forward",
                ) {
                    SettingRows(
                        {
                            SettingToggle(
                                title = "Skip intros",
                                description = "Jump past opening titles automatically.",
                                checked = settings.autoSkipIntro,
                                onCheckedChange = { editor.edit { copy(autoSkipIntro = it) } },
                            )
                        },
                        {
                            SettingToggle(
                                title = "Skip recaps",
                                description = "Jump past \"previously on\" segments.",
                                checked = settings.autoSkipRecap,
                                onCheckedChange = { editor.edit { copy(autoSkipRecap = it) } },
                            )
                        },
                        {
                            SettingToggle(
                                title = "Skip credits",
                                description = "Jump past closing credits.",
                                checked = settings.autoSkipCredits,
                                onCheckedChange = { editor.edit { copy(autoSkipCredits = it) } },
                            )
                        },
                        {
                            SettingToggle(
                                title = "Skip next-episode previews",
                                description = "Jump past trailers for the following episode.",
                                checked = settings.autoSkipPreview,
                                onCheckedChange = { editor.edit { copy(autoSkipPreview = it) } },
                            )
                        },
                    )
                }
            }

            SettingsCategory.Content -> {
                SettingsCard {
                    SettingRows(
                        {
                            SettingToggle(
                                title = "Hide spoilers",
                                description = "Keep episode titles and descriptions discreet " +
                                    "until watched.",
                                checked = settings.hideSpoilers,
                                onCheckedChange = { editor.edit { copy(hideSpoilers = it) } },
                            )
                        },
                        {
                            SettingChoice(
                                title = "Interface language",
                                description = "System follows your desktop's language.",
                                options = listOf("" to "System") + LANGUAGES,
                                selected = settings.uiLanguage,
                                onSelect = { editor.edit { copy(uiLanguage = it) } },
                            )
                        },
                        {
                            SettingChoice(
                                title = "Recommendations",
                                description = "How the home and explore feeds are built.",
                                options = listOf(
                                    "smart" to "Smart",
                                    "trending" to "Trending",
                                    "similar" to "More like what I watch",
                                ),
                                selected = settings.discoveryAlgorithm,
                                onSelect = { editor.edit { copy(discoveryAlgorithm = it) } },
                            )
                        },
                    )
                }
            }

            SettingsCategory.Network -> {
                SettingsCard(title = "Sharing", iconName = "lucide:upload") {
                    SettingToggle(
                        title = "Share back while streaming",
                        description = "Uploads pieces you already have to other people " +
                            "streaming the same file. Turn off to use no upload bandwidth.",
                        checked = settings.allowUploading,
                        onCheckedChange = { editor.edit { copy(allowUploading = it) } },
                    )
                }

                SettingsCard(title = "Remote access", iconName = "lucide:globe-2") {
                    SettingToggle(
                        title = "Reachable from other devices",
                        description = "Lets paired devices on your network reach this " +
                            "instance. Your existing pairing token is kept.",
                        checked = settings.remoteAccessEnabled,
                        onCheckedChange = { editor.edit { copy(remoteAccessEnabled = it) } },
                    )
                }
            }

            SettingsCategory.Trakt -> {
                SettingsCard {
                    SettingRows(
                        {
                            SettingToggle(
                                title = "Scrobble to Trakt",
                                description = "Report what you are watching as you watch it.",
                                checked = settings.traktScrobbleEnabled,
                                onCheckedChange = { editor.edit { copy(traktScrobbleEnabled = it) } },
                            )
                        },
                        {
                            SettingToggle(
                                title = "Sync your Trakt library",
                                description = "Keep your list and watch history in step with Trakt.",
                                checked = settings.traktSyncEnabled,
                                onCheckedChange = { editor.edit { copy(traktSyncEnabled = it) } },
                            )
                        },
                    )
                }
            }
        }
    }
}
