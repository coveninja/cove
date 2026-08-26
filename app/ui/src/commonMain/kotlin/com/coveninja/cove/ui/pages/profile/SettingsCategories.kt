package com.coveninja.cove.ui.pages.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.ui.state.LocalVideoPlayerHost
import com.coveninja.cove.ui.state.SettingsEditor
import com.coveninja.cove.ui.state.orderedAudioLanguages
import com.coveninja.cove.ui.state.orderedSubtitleLanguages
import com.coveninja.cove.ui.state.resolveBorderStyle
import com.coveninja.cove.ui.state.withAudioLanguages
import com.coveninja.cove.ui.state.withSubtitleLanguages

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
    Account(
        label = "Account",
        icon = "iconamoon:profile-circle",
        headline = "Account and sync",
        blurb = "Signing in, keeping devices in step, and who is watching.",
    ),
    Addons(
        label = "Addons",
        icon = "lucide:blocks",
        headline = "Addons",
        blurb = "Where streams come from and how they are configured",
    ),
    Plugins(
        label = "Plugins",
        icon = "lucide:blocks",
        headline = "Desktop plugins",
        blurb = "Optional integrations that run outside Cove's main process.",
    ),
    Interface(
        label = "Interface",
        icon = "lucide:layout-dashboard",
        headline = "Interface",
        blurb = "How Cove looks, what it shows you, and in which language.",
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
        blurb = "How Cove decides what to put in front of you.",
    ),
    Network(
        label = "Network",
        icon = "lucide:upload",
        headline = "Sharing and network",
        blurb = "Upload behaviour and access from other devices.",
    ),
    Storage(
        label = "Storage",
        icon = "lucide:hard-drive",
        headline = "Storage",
        blurb = "What Cove keeps on this device, and when it lets go of it.",
    ),
    Tracking(
        label = "Tracking",
        icon = "iconamoon:history",
        headline = "Tracking",
        blurb = "Scrobbling and library sync with Trakt and Simkl.",
    ),
    Advanced(
        label = "Advanced",
        icon = "lucide:settings",
        headline = "Advanced",
        blurb = "The player's own configuration, and which build this is.",
    ),
}

// The nine-language list that used to live here is gone: languages now come from the one
// table in `ui/state/Languages.kt`, which the track menu and the television read too, and
// SettingLanguageOrder offers all of them rather than the handful that fit in a pill row.

/** mpv's sub-align-x. */
private val SUBTITLE_ALIGNMENTS = listOf(
    "center" to "Centre",
    "left" to "Left",
    "right" to "Right",
)

/**
 * The colours worth offering for subtitle text — white, the two broadcast-caption colours,
 * and a softer grey for anyone who finds pure white too hot on an OLED at night.
 */
private val SUBTITLE_TEXT_COLORS = listOf(
    "#FFFFFFFF",
    "#FFFFF200",
    "#FF00FFFF",
    "#FFC8C8C8",
)

/** For the panel and the shadow, which mpv draws from one value. */
private val SUBTITLE_PANEL_COLORS = listOf(
    "#AF000000",
    "#AF1A1A1A",
    "#AF2B1B4A",
    "#AFFFFFFF",
)

private val SUBTITLE_OUTLINE_COLORS = listOf(
    "#FF000000",
    "#FF404040",
    "#FF1A0A2E",
    "#FFFFFFFF",
)

/** mpv's sub-border-style, in the order they add weight. */
private val SUBTITLE_BORDER_STYLES = listOf(
    "outline-and-shadow" to "Outline",
    "background-box" to "Box per line",
    "opaque-box" to "Panel",
)

/** mpv's sub-ass-override, named by what it does rather than by its value. */
private val SUBTITLE_ASS_OVERRIDES = listOf(
    "scale" to "Keep, scaled",
    "no" to "Keep all",
    "yes" to "Prefer mine",
    "force" to "Force mine",
    "strip" to "Strip",
)

/** mpv's audio-channels. Empty is auto-safe: whatever the track already is. */
private val AUDIO_DOWNMIXES = listOf(
    "" to "As recorded",
    "stereo" to "Stereo",
    "mono" to "Mono",
)

private val AUDIO_NORMALIZATIONS = listOf(
    "off" to "Off",
    "normalize" to "Even out",
    "night" to "Night mode",
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
            SettingsCategory.Account -> {
                AccountSettings()
                ProfilesSettings()
            }

            SettingsCategory.Addons -> AddonSettings()

            SettingsCategory.Plugins -> PluginSettings()

            SettingsCategory.Advanced -> AdvancedSettings()

            SettingsCategory.Storage -> StorageSettings()

            SettingsCategory.Interface -> InterfaceSettings(settings, editor)

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
                        {
                            SettingToggle(
                                title = "Remember the volume",
                                description = "Carry the volume you set into the next " +
                                    "thing you play.",
                                checked = settings.rememberVolume,
                                onCheckedChange = { editor.edit { copy(rememberVolume = it) } },
                            )
                        },
                        {
                            SettingSlider(
                                title = "Skip step",
                                description = "How far the skip buttons and the arrow " +
                                    "keys jump.",
                                value = settings.seekStepSeconds.toFloat(),
                                range = 5f..60f,
                                format = { "${it.toInt()}s" },
                                onCommit = {
                                    editor.edit { copy(seekStepSeconds = it.toDouble()) }
                                },
                            )
                        },
                        {
                            SettingToggle(
                                title = "Hardware decoding",
                                description = "Decode video on the graphics card. Leave " +
                                    "this on unless playback tears or shows the wrong " +
                                    "colours, which means the driver mishandles it.",
                                checked = settings.hardwareDecoding,
                                onCheckedChange = { editor.edit { copy(hardwareDecoding = it) } },
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
                                title = "Keep yt-dlp up to date",
                                description = "Trailers and other extras are YouTube " +
                                    "pages, and the player needs yt-dlp to turn one into " +
                                    "a stream. Cove fetches a copy the first time you " +
                                    "play one, about 40 MB, and refreshes it weekly. Your " +
                                    "own yt-dlp is used instead where you have one.",
                                checked = settings.manageYtDlp,
                                onCheckedChange = { editor.edit { copy(manageYtDlp = it) } },
                            )
                        },
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
                SettingsCard(
                    title = "Languages",
                    iconName = "lucide:languages",
                    description = "Tried in order. The first one a release carries is the one used.",
                ) {
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
                            SettingLanguageOrder(
                                title = "Subtitle languages",
                                description = null,
                                languages = settings.orderedSubtitleLanguages(),
                                // "Original" would mean the language the film is already in,
                                // which is the one case subtitles are not wanted for.
                                allowOriginal = false,
                                onChange = { editor.edit { withSubtitleLanguages(it) } },
                            )
                        },
                        {
                            SettingLanguageOrder(
                                title = "Audio languages",
                                description = null,
                                languages = settings.orderedAudioLanguages(),
                                allowOriginal = true,
                                onChange = { editor.edit { withAudioLanguages(it) } },
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
                            SettingChoice(
                                title = "Alignment",
                                description = "Where a line sits when it does not fill the width.",
                                options = SUBTITLE_ALIGNMENTS,
                                selected = settings.subtitleAlign,
                                onSelect = { editor.edit { copy(subtitleAlign = it) } },
                            )
                        },
                        {
                            SettingColor(
                                title = "Text colour",
                                value = settings.subtitleTextColor,
                                presets = SUBTITLE_TEXT_COLORS,
                                onSelect = { editor.edit { copy(subtitleTextColor = it) } },
                            )
                        },
                        {
                            SettingToggle(
                                title = "Bold",
                                description = "Thicker strokes. Carries further across a room.",
                                checked = settings.subtitleBold,
                                onCheckedChange = { editor.edit { copy(subtitleBold = it) } },
                            )
                        },
                        {
                            SettingToggle(
                                title = "Italic",
                                checked = settings.subtitleItalic,
                                onCheckedChange = { editor.edit { copy(subtitleItalic = it) } },
                            )
                        },
                    )
                }

                SettingsCard(
                    title = "Legibility",
                    iconName = "lucide:captions",
                    description = "What keeps the text readable over a bright or busy picture.",
                ) {
                    SettingRows(
                        {
                            SettingChoice(
                                title = "Behind the text",
                                description = "A panel across the block, a box per line, " +
                                    "or nothing but the outline.",
                                options = SUBTITLE_BORDER_STYLES,
                                selected = resolveBorderStyle(
                                    settings.subtitleBorderStyle,
                                    settings.subtitleBackground,
                                ),
                                onSelect = { style ->
                                    editor.edit {
                                        // The older boolean is kept in step, so a device that
                                        // predates the three-way still draws what was chosen here.
                                        copy(
                                            subtitleBorderStyle = style,
                                            subtitleBackground = style != "outline-and-shadow",
                                        )
                                    }
                                },
                            )
                        },
                        {
                            SettingColor(
                                title = "Panel colour",
                                description = "Also the colour of the drop shadow — mpv draws " +
                                    "both from one value.",
                                value = settings.subtitleBackColor,
                                presets = SUBTITLE_PANEL_COLORS,
                                showOpacity = true,
                                onSelect = { editor.edit { copy(subtitleBackColor = it) } },
                            )
                        },
                        {
                            SettingColor(
                                title = "Outline colour",
                                value = settings.subtitleOutlineColor,
                                presets = SUBTITLE_OUTLINE_COLORS,
                                onSelect = { editor.edit { copy(subtitleOutlineColor = it) } },
                            )
                        },
                        {
                            SettingSlider(
                                title = "Outline weight",
                                value = settings.subtitleOutlineSize.toFloat(),
                                range = 0f..8f,
                                format = { "${(it * 10).toInt() / 10.0}" },
                                onCommit = {
                                    editor.edit { copy(subtitleOutlineSize = it.toDouble()) }
                                },
                            )
                        },
                        {
                            SettingSlider(
                                title = "Shadow",
                                description = "Offset behind the text. Zero draws none.",
                                value = settings.subtitleShadowOffset.toFloat(),
                                range = 0f..6f,
                                format = { "${(it * 10).toInt() / 10.0}" },
                                onCommit = {
                                    editor.edit { copy(subtitleShadowOffset = it.toDouble()) }
                                },
                            )
                        },
                        {
                            SettingSlider(
                                title = "Blur",
                                description = "Softens the edges. A little helps on a sharp panel.",
                                value = settings.subtitleBlur.toFloat(),
                                range = 0f..8f,
                                format = { "${(it * 10).toInt() / 10.0}" },
                                onCommit = { editor.edit { copy(subtitleBlur = it.toDouble()) } },
                            )
                        },
                        {
                            SettingChoice(
                                title = "Styled subtitles",
                                description = "ASS and SSA tracks carry their own fonts and " +
                                    "positioning — the signs and karaoke of a fansub. This is " +
                                    "how much of that to keep.",
                                options = SUBTITLE_ASS_OVERRIDES,
                                selected = settings.subtitleAssOverride,
                                onSelect = { editor.edit { copy(subtitleAssOverride = it) } },
                            )
                        },
                    )
                }

                SettingsCard(title = "Audio", iconName = "lucide:audio-lines") {
                    // Absent rather than present and inert where the player cannot run audio
                    // filters, which is every Android build: its libmpv links a libavfilter
                    // with no audio filters in it, and mpv answers one it cannot build by
                    // ending the file rather than playing on without it.
                    //
                    // Decided out here rather than inside the last row, because SettingRows
                    // draws a divider before every row past the first: a row that renders
                    // nothing still gets its divider, and the card would end on a rule with
                    // nothing under it.
                    val canFilterAudio = LocalVideoPlayerHost.current?.supportsAudioFilters == true
                    SettingRows(
                        *buildList<@Composable () -> Unit> {
                            add {
                                SettingChoice(
                                    title = "Channels",
                                    description = "Fold a surround track down so dialogue is " +
                                        "not left on a centre speaker you do not have.",
                                    options = AUDIO_DOWNMIXES,
                                    selected = settings.audioDownmix,
                                    onSelect = { editor.edit { copy(audioDownmix = it) } },
                                )
                            }
                            add {
                                SettingToggle(
                                    title = "Rescale the downmix",
                                    description = "Stops a folded-down track clipping. " +
                                        "Only applies while downmixing.",
                                    checked = settings.audioNormalizeDownmix,
                                    onCheckedChange = {
                                        editor.edit { copy(audioNormalizeDownmix = it) }
                                    },
                                )
                            }
                            if (canFilterAudio) {
                                add {
                                    SettingChoice(
                                        title = "Even out the volume",
                                        description = "Brings quiet dialogue up. Night mode " +
                                            "also pulls loud scenes down.",
                                        options = AUDIO_NORMALIZATIONS,
                                        selected = settings.audioNormalization,
                                        onSelect = {
                                            editor.edit { copy(audioNormalization = it) }
                                        },
                                    )
                                }
                            }
                        }.toTypedArray(),
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
                            SettingChoice(
                                title = "Recommendations",
                                description = "How the home and explore feeds are built.",
                                options = listOf(
                                    "smart" to "Smart",
                                    "trending" to "Trending",
                                    "similar" to "More like what I watch",
                                    "custom" to "Custom",
                                ),
                                selected = settings.discoveryAlgorithm,
                                onSelect = { editor.edit { copy(discoveryAlgorithm = it) } },
                            )
                        },
                    )
                }

                // Only meaningful under the custom algorithm, and the backend
                // ignores it otherwise — so it appears with the option that uses it.
                if (settings.discoveryAlgorithm == "custom") {
                    SettingsCard(title = "Custom algorithm", iconName = "lucide:sparkles") {
                        SettingTextRow(
                            title = "Scoring endpoint",
                            description = "Cove posts your taste profile and the candidate " +
                                "titles here, and orders the feed by the scores it returns.",
                            value = settings.customAlgorithmUrl,
                            placeholder = "https://example.com/score",
                            onCommit = { editor.edit { copy(customAlgorithmUrl = it) } },
                        )
                    }
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
                    SettingRows(
                        {
                            SettingToggle(
                                title = "Reachable from other devices",
                                description = "Lets paired devices on your network reach this " +
                                    "instance. Your existing pairing token is kept.",
                                checked = settings.remoteAccessEnabled,
                                onCheckedChange = { editor.edit { copy(remoteAccessEnabled = it) } },
                            )
                        },
                        {
                            // Read-only on purpose: the token is what already-paired
                            // devices authenticate with, so regenerating it here
                            // would silently lock every one of them out.
                            SecretRow(
                                title = "Pairing token",
                                description = "Another device needs this to connect. " +
                                    "Treat it like a password.",
                                secret = settings.remoteAccessToken,
                                emptyLabel = "Generated when remote access is first enabled.",
                            )
                        },
                    )
                }
            }

            SettingsCategory.Tracking -> TrackingSettings(settings, editor)
        }
    }
}
