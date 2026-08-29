package com.coveninja.cove.ui.tv.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.coveninja.cove.shared.data.AppUpdateState
import com.coveninja.cove.shared.data.AddonsState
import com.coveninja.cove.shared.data.CacheKind
import com.coveninja.cove.shared.data.StorageUsageState
import com.coveninja.cove.shared.data.ProfilesState
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.model.Addon
import com.coveninja.cove.shared.model.NuvioRepoSummary
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.components.common.formatUpdateBytes
import com.coveninja.cove.ui.state.CacheAgeChoices
import com.coveninja.cove.ui.state.CacheLimitChoices
import com.coveninja.cove.ui.state.DownloadAheadChoices
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.LocalVideoPlayerHost
import com.coveninja.cove.ui.state.cacheAgeLabel
import com.coveninja.cove.ui.state.cacheLimitLabel
import com.coveninja.cove.ui.state.downloadAheadLabel
import com.coveninja.cove.ui.state.languageName
import com.coveninja.cove.ui.state.orderedAudioLanguages
import com.coveninja.cove.ui.state.orderedSubtitleLanguages
import com.coveninja.cove.ui.state.resolveBorderStyle
import com.coveninja.cove.ui.state.subtitleBorderStyleLabel
import com.coveninja.cove.ui.state.subtitleColorLabel
import com.coveninja.cove.ui.state.withAudioLanguages
import com.coveninja.cove.ui.state.withCurrent
import com.coveninja.cove.ui.state.withSubtitleLanguages
import com.coveninja.cove.ui.state.SettingsEditor
import com.coveninja.cove.ui.state.rememberSettingsEditor
import com.coveninja.cove.ui.tv.TvTheme
import com.coveninja.cove.ui.tv.components.TvSettingRow
import com.coveninja.cove.ui.tv.components.TvSettingsHeading
import com.coveninja.cove.ui.tv.focus.TvSectionScroll
import com.coveninja.cove.ui.tv.focus.tvFocusGroup
import kotlinx.coroutines.launch

/** Scroll position worth keeping while another destination is on screen. */
@Stable
class TvSettingsPageState internal constructor(
    internal val listState: LazyListState,
)

@Composable
fun rememberTvSettingsPageState(): TvSettingsPageState {
    val listState = rememberLazyListState()
    return remember(listState) { TvSettingsPageState(listState) }
}

/**
 * Settings, reduced to what a remote can usefully change.
 *
 * The phone's settings are six categories of switches, sliders, steppers, text fields and
 * dropdowns. Most of that has no business on a television: a slider cannot be dragged, a text
 * field raises a keyboard over the whole panel, and every dropdown is a surface to get trapped
 * in. What survives is what someone actually changes from the sofa — how playback behaves, what
 * gets skipped, whether subtitles are on, and which providers are live.
 *
 * Everything else stays a desktop job, which is honest rather than a limitation: adding a
 * provider means pasting a manifest URL, and that has never been a thing to do with a D-pad.
 * Signing in carries the result across — see [TvAccountPage].
 */
@Composable
internal fun TvSettingsPage(
    pageState: TvSettingsPageState,
    modifier: Modifier = Modifier,
) {
    val graph = LocalAppGraph.current
    val dimens = TvTheme.dimens
    val settingsState by graph.settings.settings.collectAsState()
    val addonsState by graph.addons.state.collectAsState()
    var focusedSection by remember { mutableStateOf<Int?>(null) }

    val settings = (settingsState as? SettingsState.Ready)?.settings
    if (settings == null) {
        Text(
            text = "Loading settings…",
            style = MaterialTheme.typography.titleLarge,
            color = CoveColors.Neutral.Muted,
            modifier = modifier.padding(
                start = dimens.overscanHorizontal,
                top = dimens.overscanVertical + 24.dp,
            ),
        )
        return
    }

    val editor = rememberSettingsEditor(settings)
    val addons = (addonsState as? AddonsState.Ready)?.addons.orEmpty()
    val nuvioRepos = (addonsState as? AddonsState.Ready)?.nuvioRepos.orEmpty()
    // The scrapers block is dropped rather than shown empty on a host with no Nuvio sandbox:
    // an always-empty section is a focus stop that never has anything in it.
    val sections = remember(
        nuvioRepos,
        graph.addons.supportsNuvio,
        graph.storage.available,
        graph.trackers,
        graph.device.available,
        graph.updates.available,
    ) {
        TvSettingsSection.entries.filter { section ->
            when (section) {
                TvSettingsSection.Scrapers -> graph.addons.supportsNuvio
                // A host with no trackers wired would contribute a heading and nothing else.
                TvSettingsSection.Trackers -> graph.trackers.isNotEmpty()
                // Same reasoning: a host with no caches of its own would contribute a heading,
                // a focus stop and nothing to do once you reached it.
                TvSettingsSection.Storage -> graph.storage.available
                // Nothing in Advanced exists on a host with neither device settings nor an
                // updater, and a heading over an empty block is a focus stop that never pays.
                TvSettingsSection.Advanced -> graph.device.available || graph.updates.available
                else -> true
            }
        }
    }

    TvSectionScroll(
        state = pageState.listState,
        focusedIndex = focusedSection,
        margin = dimens.focusScrollMargin,
    )

    LazyColumn(
        state = pageState.listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = dimens.overscanHorizontal,
            end = dimens.overscanHorizontal,
            top = dimens.overscanVertical + 20.dp,
            bottom = dimens.overscanVertical + 32.dp,
        ),
    ) {
            item(key = "account") {
            TvAccountSection(modifier = Modifier.widthIn(max = 980.dp))
        }

        itemsIndexed(items = sections, key = { _, section -> section.name }) { position, section ->
            Column(
                modifier = Modifier
                    .widthIn(max = 980.dp)
                    .padding(top = dimens.sectionSpacing)
                    // Offset by one: the account block is item zero.
                    .onFocusChanged { if (it.hasFocus) focusedSection = position + 1 },
            ) {
                TvSettingsHeading(
                    title = section.title,
                    detail = section.detail,
                    icon = section.icon,
                )
                Column(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .tvFocusGroup(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (section) {
                        TvSettingsSection.Profiles -> ProfileRows(settings, editor)
                        TvSettingsSection.Playback -> PlaybackRows(settings, editor)
                        TvSettingsSection.Skipping -> SkippingRows(settings, editor)
                        TvSettingsSection.Subtitles -> SubtitleRows(settings, editor)
                        TvSettingsSection.Providers -> ProviderRows(addons)
                        TvSettingsSection.Trackers ->
                            TvTrackerRows(graph.trackers, settings, editor)
                        TvSettingsSection.Scrapers -> ScraperRows(nuvioRepos)
                        TvSettingsSection.Sources -> SourceRows(settings, editor)
                        TvSettingsSection.Storage -> StorageRows()
                        TvSettingsSection.Advanced -> AdvancedRows()
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackRows(settings: AppSettings, editor: SettingsEditor) {
    TvSettingRow(
        label = "Play the next episode",
        detail = "Roll straight on when an episode ends.",
        value = onOff(settings.autoPlay),
        highlighted = settings.autoPlay,
        onActivate = { editor.edit { copy(autoPlay = !autoPlay) } },
    )
    TvSettingRow(
        label = "Watch reminder",
        detail = "A quiet note after a long stretch, pointing at the sleep timer.",
        // One row rather than a switch and an interval: on a remote, "off" is simply the
        // step before one hour.
        value = watchReminderLabel(settings.watchReminderEnabled, settings.watchReminderHours),
        highlighted = settings.watchReminderEnabled,
        onActivate = {
            editor.edit {
                val next = cycleOption(
                    WatchReminderSteps,
                    if (watchReminderEnabled) watchReminderHours else 0,
                )
                copy(
                    watchReminderEnabled = next > 0,
                    // Off keeps the hours it was set to, so cycling past it comes back
                    // to the same interval rather than resetting the viewer's choice.
                    watchReminderHours = if (next > 0) next else watchReminderHours,
                )
            }
        },
    )
    TvSettingRow(
        label = "Remember where you stopped",
        detail = "Resume part-watched titles instead of starting again.",
        value = onOff(settings.rememberPosition),
        highlighted = settings.rememberPosition,
        onActivate = { editor.edit { copy(rememberPosition = !rememberPosition) } },
    )
    TvSettingRow(
        label = "Hardware decoding",
        detail = "Leave on unless playback stutters or a format refuses to play.",
        value = onOff(settings.hardwareDecoding),
        highlighted = settings.hardwareDecoding,
        onActivate = { editor.edit { copy(hardwareDecoding = !hardwareDecoding) } },
    )
    TvSettingRow(
        label = "Pick a source automatically",
        detail = "Off means Cove asks whenever it finds more than one.",
        value = onOff(settings.autoSelectStream),
        highlighted = settings.autoSelectStream,
        onActivate = { editor.edit { copy(autoSelectStream = !autoSelectStream) } },
    )
    TvSettingRow(
        label = "Selection preference",
        detail = "How sources are ranked when Cove picks for you.",
        value = streamSelectionLabel(settings.streamSelectionMode),
        onActivate = {
            editor.edit {
                copy(
                    streamSelectionMode = cycleOption(
                        StreamSelectionModes,
                        settings.streamSelectionMode,
                    ),
                )
            }
        },
    )
    TvSettingRow(
        label = "Skip step",
        detail = "How far left and right move while something is playing.",
        value = seekStepLabel(settings.seekStepSeconds),
        onActivate = {
            editor.edit {
                copy(seekStepSeconds = cycleOption(SeekStepChoices, settings.seekStepSeconds))
            }
        },
    )
}

@Composable
private fun SkippingRows(settings: AppSettings, editor: SettingsEditor) {
    TvSettingRow(
        label = "Skip intros",
        detail = null,
        value = onOff(settings.autoSkipIntro),
        highlighted = settings.autoSkipIntro,
        onActivate = { editor.edit { copy(autoSkipIntro = !autoSkipIntro) } },
    )
    TvSettingRow(
        label = "Skip recaps",
        detail = null,
        value = onOff(settings.autoSkipRecap),
        highlighted = settings.autoSkipRecap,
        onActivate = { editor.edit { copy(autoSkipRecap = !autoSkipRecap) } },
    )
    TvSettingRow(
        label = "Skip credits",
        detail = null,
        value = onOff(settings.autoSkipCredits),
        highlighted = settings.autoSkipCredits,
        onActivate = { editor.edit { copy(autoSkipCredits = !autoSkipCredits) } },
    )
    TvSettingRow(
        label = "Skip next-episode previews",
        detail = null,
        value = onOff(settings.autoSkipPreview),
        highlighted = settings.autoSkipPreview,
        onActivate = { editor.edit { copy(autoSkipPreview = !autoSkipPreview) } },
    )
}

@Composable
private fun SubtitleRows(settings: AppSettings, editor: SettingsEditor) {
    TvSettingRow(
        label = "Subtitles on by default",
        detail = "Turn them on for every title without asking each time.",
        value = onOff(settings.subtitlesEnabled),
        highlighted = settings.subtitlesEnabled,
        onActivate = { editor.edit { copy(subtitlesEnabled = !subtitlesEnabled) } },
    )
    TvSettingRow(
        label = "Subtitle size",
        detail = "Larger than a monitor wants — this is read from across a room.",
        value = subtitleSizeLabel(settings.subtitleSize),
        onActivate = {
            editor.edit {
                copy(subtitleSize = cycleOption(SubtitleSizeChoices, settings.subtitleSize))
            }
        },
    )
    TvSettingRow(
        label = "Subtitle position",
        detail = "How far up from the bottom edge. Televisions overscan; this is the fix.",
        value = subtitlePositionLabel(settings.subtitlePosition),
        onActivate = {
            editor.edit {
                copy(
                    subtitlePosition = cycleOption(
                        SubtitlePositionChoices,
                        settings.subtitlePosition,
                    ),
                )
            }
        },
    )
    TvSettingRow(
        label = "Behind the text",
        detail = "A panel, a box per line, or nothing but an outline.",
        value = subtitleBorderStyleLabel(
            resolveBorderStyle(settings.subtitleBorderStyle, settings.subtitleBackground),
        ),
        onActivate = {
            editor.edit {
                val next = cycleOption(
                    SubtitleBorderStyleChoices,
                    resolveBorderStyle(subtitleBorderStyle, subtitleBackground),
                )
                // The older boolean is kept in step so a device that predates the
                // three-way still draws the box this row just asked for.
                copy(
                    subtitleBorderStyle = next,
                    subtitleBackground = next != "outline-and-shadow",
                )
            }
        },
    )
    TvSettingRow(
        label = "Text colour",
        detail = "White unless the picture keeps swallowing it.",
        value = subtitleColorLabel(settings.subtitleTextColor),
        onActivate = {
            editor.edit {
                copy(
                    subtitleTextColor = cycleOption(SubtitleColorChoices, subtitleTextColor),
                )
            }
        },
    )
    TvSettingRow(
        label = "Outline weight",
        detail = "What keeps the text legible over a bright scene.",
        value = subtitleOutlineSizeLabel(settings.subtitleOutlineSize),
        onActivate = {
            editor.edit {
                copy(
                    subtitleOutlineSize = cycleOption(
                        SubtitleOutlineSizeChoices,
                        subtitleOutlineSize,
                    ),
                )
            }
        },
    )
    TvSettingRow(
        label = "Bold",
        detail = "Thicker strokes, which carry further across a room.",
        value = onOff(settings.subtitleBold),
        highlighted = settings.subtitleBold,
        onActivate = { editor.edit { copy(subtitleBold = !subtitleBold) } },
    )
    TvSettingRow(
        label = "Alignment",
        detail = "Where a line sits when it does not fill the width.",
        value = subtitleAlignLabel(settings.subtitleAlign),
        onActivate = {
            editor.edit { copy(subtitleAlign = cycleOption(SubtitleAlignChoices, subtitleAlign)) }
        },
    )
    TvSettingRow(
        label = "Styled subtitles",
        detail = "How much of a release's own fonts and positioning to keep.",
        value = subtitleAssOverrideLabel(settings.subtitleAssOverride),
        onActivate = {
            editor.edit {
                copy(
                    subtitleAssOverride = cycleOption(
                        SubtitleAssOverrideChoices,
                        subtitleAssOverride,
                    ),
                )
            }
        },
    )
    TvSettingRow(
        label = "Preferred subtitle language",
        detail = "Chosen automatically when a release carries it.",
        value = settings.orderedSubtitleLanguages().firstOrNull()
            ?.let(::languageName) ?: "No preference",
        onActivate = {
            editor.edit {
                // Only the most-wanted language is cycled; anything further down an order set
                // on another device is kept behind it. A full reorder editor is a pointer
                // control, and rebuilding the list from this row would silently discard it.
                val current = orderedSubtitleLanguages()
                // An empty order matches nothing in the list, and cycleOption answers that
                // with the first entry — which is exactly where a viewer with no preference
                // should land.
                val next = cycleOption(LanguageChoices, current.firstOrNull().orEmpty())
                withSubtitleLanguages(listOf(next) + current.drop(1))
            }
        },
    )
    TvSettingRow(
        label = "Preferred audio language",
        detail = "The track picked first where a release has several.",
        value = settings.orderedAudioLanguages().firstOrNull()
            ?.let(::languageName) ?: "No preference",
        onActivate = {
            editor.edit {
                val current = orderedAudioLanguages()
                val next = cycleOption(LanguageChoices, current.firstOrNull().orEmpty())
                withAudioLanguages(listOf(next) + current.drop(1))
            }
        },
    )
    TvSettingRow(
        label = "Downmix",
        detail = "Fold a surround track down so the dialogue is not left on a centre speaker.",
        value = audioDownmixLabel(settings.audioDownmix),
        onActivate = {
            editor.edit { copy(audioDownmix = cycleOption(AudioDownmixChoices, audioDownmix)) }
        },
    )
    if (settings.audioDownmix.isNotBlank()) {
        TvSettingRow(
            label = "Rescale the downmix",
            detail = "Stops a folded-down track clipping. Only applies while downmixing.",
            value = onOff(settings.audioNormalizeDownmix),
            highlighted = settings.audioNormalizeDownmix,
            onActivate = {
                editor.edit { copy(audioNormalizeDownmix = !audioNormalizeDownmix) }
            },
        )
    }
    // Absent rather than inert where the player cannot run audio filters — which is every
    // Android build, television included. See VideoPlayerHost.supportsAudioFilters.
    if (LocalVideoPlayerHost.current?.supportsAudioFilters == true) {
        TvSettingRow(
            label = "Even out the volume",
            detail = "Brings quiet dialogue up. Night mode also pulls loud scenes down.",
            value = audioNormalizationLabel(settings.audioNormalization),
            onActivate = {
                editor.edit {
                    copy(
                        audioNormalization = cycleOption(
                            AudioNormalizationChoices,
                            audioNormalization,
                        ),
                    )
                }
            },
        )
    }
}

/**
 * What Cove does before a press of Play, and how hard it works at it.
 *
 * All booleans, which is why they survive the trip: each is one press on a row that already
 * says what it currently is.
 */
@Composable
private fun SourceRows(settings: AppSettings, editor: SettingsEditor) {
    TvSettingRow(
        label = "Check sources are alive",
        detail = "Drops dead links before offering them. Slower, and fewer failed starts.",
        value = onOff(settings.probeStreams),
        highlighted = settings.probeStreams,
        onActivate = { editor.edit { copy(probeStreams = !probeStreams) } },
    )
    TvSettingRow(
        label = "Prefetch sources",
        detail = "Resolve streams while you are still browsing.",
        value = onOff(settings.prefetchStreams),
        highlighted = settings.prefetchStreams,
        onActivate = { editor.edit { copy(prefetchStreams = !prefetchStreams) } },
    )
    TvSettingRow(
        label = "Prefetch the next episode",
        detail = "Have the next one ready before this one ends.",
        value = onOff(settings.prefetchNextEpisode),
        highlighted = settings.prefetchNextEpisode,
        onActivate = { editor.edit { copy(prefetchNextEpisode = !prefetchNextEpisode) } },
    )
    TvSettingRow(
        label = "Show source details",
        detail = "Size, seeders and codec on each stream in the list.",
        value = onOff(settings.showStreamDetails),
        highlighted = settings.showStreamDetails,
        onActivate = { editor.edit { copy(showStreamDetails = !showStreamDetails) } },
    )
}

/**
 * The device, and the build.
 *
 * Low-performance mode matters more here than anywhere: Cove runs on television sticks with a
 * fraction of a desktop's budget, and this is the switch that turns off the animation those
 * devices cannot afford. It was reachable on every shell except the one most likely to need it.
 *
 * The update rows are the other half. `AppUpdateOverlay` is already mounted on this shell, so a
 * television could be *told* about an update but had no way to ask for one, or to see which
 * build it was running when reporting a fault.
 */
@Composable
private fun AdvancedRows() {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val performance by graph.device.performance.collectAsState()
    val automaticUpdates by graph.updates.automaticUpdatesEnabled.collectAsState()
    val updateState by graph.updates.state.collectAsState()

    if (graph.device.available) {
        TvSettingRow(
            label = "Low-performance mode",
            detail = "Drops animation and blur. Worth having on a television stick.",
            value = onOff(performance.lowPerformanceMode),
            highlighted = performance.lowPerformanceMode,
            onActivate = {
                scope.launch {
                    graph.device.setLowPerformanceMode(!performance.lowPerformanceMode)
                }
            },
        )
    }

    if (graph.updates.available) {
        TvSettingRow(
            label = "Automatic updates",
            detail = "Download new versions in the background.",
            value = onOff(automaticUpdates),
            highlighted = automaticUpdates,
            onActivate = {
                scope.launch { graph.updates.setAutomaticUpdatesEnabled(!automaticUpdates) }
            },
        )
        TvSettingRow(
            label = "Check for updates",
            detail = tvUpdateStatusDetail(updateState),
            value = if (updateState is AppUpdateState.Checking) "Checking…" else "Check now",
            enabled = updateState !is AppUpdateState.Checking,
            onActivate = { scope.launch { graph.updates.checkNow() } },
        )
    }

    val version = graph.updates.currentVersion.ifBlank { graph.device.appVersion }
    if (version.isNotBlank()) {
        TvSettingRow(
            label = "Version",
            // Not focusable-looking for the sake of it: this is the line a bug report needs,
            // and on a television it is the only place the build number appears at all.
            detail = "The build this television is running.",
            value = version,
            enabled = false,
            onActivate = {},
        )
    }
}

/**
 * Where an update got to, in the row that starts one.
 *
 * The overlay says all this too, but only while it is up; somebody who dismissed it and came
 * looking deserves an answer from the control they came to press.
 */
private fun tvUpdateStatusDetail(state: AppUpdateState): String = when (state) {
    is AppUpdateState.ManagedExternally -> state.message
    is AppUpdateState.Checking -> "Asking for the latest release."
    is AppUpdateState.UpToDate -> "You are on the latest release."
    is AppUpdateState.MeteredApprovalRequired ->
        "${state.release.version} is waiting for approval to download."
    is AppUpdateState.Downloading -> "Downloading ${state.release.version}."
    is AppUpdateState.Ready -> "${state.release.version} is ready to install."
    is AppUpdateState.PermissionRequired -> "${state.release.version} needs permission to install."
    is AppUpdateState.Installing -> "Installing ${state.release.version}."
    is AppUpdateState.Failed -> state.message
    AppUpdateState.Idle -> "Ask Cove to look for a new version."
}

/**
 * Storage, reduced to what a remote can change.
 *
 * A television is where this matters most and where it is hardest to reach: set-top boxes ship
 * with a few gigabytes, nothing on Android reclaims the app's own files, and there is no file
 * manager to go and look. So the usage line comes first, as a row that reports rather than
 * responds, and the clear arms on one press and commits on the second — the D-pad reading of the
 * confirm step the pointer shells get.
 */
@Composable
private fun StorageRows() {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val storage = graph.storage
    val policy by storage.policy.collectAsState()
    val usage by storage.usage.collectAsState()
    var armed by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(storage) { storage.refresh() }

    val downloads = (usage as? StorageUsageState.Ready)
        ?.usage
        ?.entries
        ?.firstOrNull { it.kind == CacheKind.TorrentDownloads }

    TvSettingRow(
        label = "Downloads on this device",
        detail = result ?: (usage as? StorageUsageState.Failed)?.message,
        value = when {
            downloads != null -> formatUpdateBytes(downloads.bytes)
            usage is StorageUsageState.Failed -> "Unknown"
            usage is StorageUsageState.Ready -> formatUpdateBytes(0)
            else -> "Measuring…"
        },
        // Not a focus stop: there is nothing to press, and a remote that stopped here would be
        // asking the viewer to work out why nothing happened.
        enabled = false,
        onActivate = {},
    )
    TvSettingRow(
        label = "Keep at most",
        detail = "Past this, the downloads you watched longest ago go first.",
        value = cacheLimitLabel(policy.limitBytes),
        onActivate = {
            scope.launch {
                storage.setPolicy(
                    policy.copy(
                        limitBytes = cycleOption(
                            withCurrent(CacheLimitChoices, policy.limitBytes, 0),
                            policy.limitBytes,
                        ),
                    ),
                )
            }
        },
    )
    TvSettingRow(
        label = "Download ahead",
        detail = "How far past what you are watching a torrent keeps fetching.",
        value = downloadAheadLabel(policy.downloadAheadBytes),
        onActivate = {
            scope.launch {
                storage.setPolicy(
                    policy.copy(
                        downloadAheadBytes = cycleOption(
                            withCurrent(DownloadAheadChoices, policy.downloadAheadBytes, 0),
                            policy.downloadAheadBytes,
                        ),
                    ),
                )
            }
        },
    )
    TvSettingRow(
        label = "Keep downloads for",
        detail = "Anything unplayed for longer is removed.",
        value = cacheAgeLabel(policy.maxAgeDays),
        onActivate = {
            scope.launch {
                storage.setPolicy(
                    policy.copy(
                        maxAgeDays = cycleOption(
                            withCurrent(CacheAgeChoices, policy.maxAgeDays, 0),
                            policy.maxAgeDays,
                        ),
                    ),
                )
            }
        },
    )
    TvSettingRow(
        label = "Delete after watching",
        detail = "Removes each download a few minutes after you stop.",
        value = onOff(policy.deleteAfterWatching),
        highlighted = policy.deleteAfterWatching,
        onActivate = {
            scope.launch {
                storage.setPolicy(policy.copy(deleteAfterWatching = !policy.deleteAfterWatching))
            }
        },
    )
    TvSettingRow(
        label = if (armed) "Press again to delete every download" else "Clear downloads now",
        detail = "Anything playing right now is left alone.",
        value = if (armed) "Confirm" else "Clear",
        highlighted = armed,
        onActivate = {
            if (!armed) {
                armed = true
                return@TvSettingRow
            }
            armed = false
            scope.launch {
                val cleared = storage.clear(CacheKind.TorrentDownloads)
                result = buildString {
                    append("Freed ")
                    append(formatUpdateBytes(cleared.freedBytes))
                    if (cleared.keptInUse > 0) append(" — one kept, still playing")
                }
            }
        },
    )
}

@Composable
private fun ProviderRows(addons: List<Addon>) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val lastError by graph.addons.lastError.collectAsState()

    if (addons.isEmpty()) {
        Text(
            text = "No providers yet. Add them on a desktop and sign in here — they arrive " +
                "with everything else on your account.",
            style = MaterialTheme.typography.bodyLarge,
            color = CoveColors.Neutral.MutedDim,
        )
        return
    }

    addons.forEach { addon ->
        val description = addon.manifest.description.takeIf { it.isNotBlank() } ?: addon.url
        TvSettingRow(
            label = addon.displayName,
            // Says why the row will not answer before it is reached. A remote has
            // no hover and no error toast worth reading from a sofa, so a row that
            // simply did nothing would read as the television having missed a press.
            detail = if (addon.managed) "Shared by the primary profile · $description" else description,
            value = onOff(addon.enabled),
            highlighted = addon.enabled,
            // Skipped by the D-pad rather than merely inert: this profile streams
            // through the addon but the switch belongs to the primary.
            enabled = !addon.managed,
            onActivate = {
                scope.launch { graph.addons.setAddonEnabled(addon.id, !addon.enabled) }
            },
        )
    }

    lastError?.let { error ->
        Text(
            text = error,
            style = MaterialTheme.typography.bodyMedium,
            color = CoveColors.Status.Warning,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}


/**
 * Who is watching.
 *
 * The one setting on this page that genuinely belongs on a television rather than merely
 * surviving the trip: a phone has one owner, a television in a living room does not, and
 * switching profile is what keeps one person's half-watched episode out of another's rows.
 *
 * Creating and renaming stay elsewhere — both need a keyboard, and neither is something anyone
 * does from the sofa. Switching needs nothing but the D-pad.
 */
@Composable
private fun ProfileRows(settings: AppSettings, editor: SettingsEditor) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val profilesState by graph.profiles.profiles.collectAsState()
    val addonsState by graph.addons.state.collectAsState()

    when (val state = profilesState) {
        ProfilesState.Loading -> Text(
            text = "Loading profiles…",
            style = MaterialTheme.typography.bodyLarge,
            color = CoveColors.Neutral.MutedDim,
        )

        is ProfilesState.Failed -> Text(
            text = state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = CoveColors.Neutral.MutedDim,
        )

        is ProfilesState.Ready -> {
            state.profiles.forEach { profile ->
                val active = profile.id == state.activeProfileId
                TvSettingRow(
                    label = profile.name,
                    detail = if (profile.isPrimary) "Primary profile" else null,
                    value = if (active) "Watching" else "Switch",
                    highlighted = active,
                    // Switching to the profile already active would tear the whole graph down and
                    // rebuild it to arrive exactly where it started.
                    enabled = !active,
                    onActivate = { scope.launch { graph.profiles.activate(profile.id) } },
                )
            }

            // The flag is a field on the primary's own settings row, so only the primary
            // is offered it — a secondary flipping its copy would change nothing. What a
            // secondary gets instead is the already-resolved answer, read back off the
            // addon state, because it cannot read the primary's settings at all.
            val activeIsPrimary = state.profiles
                .firstOrNull { it.id == state.activeProfileId }?.isPrimary == true
            val sharing = (addonsState as? AddonsState.Ready)?.sharing
            when {
                activeIsPrimary -> TvSettingRow(
                    label = "Primary profile drives addons",
                    detail = "Every other profile gets this profile's addons and cannot change them.",
                    value = onOff(settings.addonsFollowPrimary),
                    highlighted = settings.addonsFollowPrimary,
                    onActivate = {
                        editor.edit { copy(addonsFollowPrimary = !addonsFollowPrimary) }
                    },
                )

                sharing?.enabled == true -> TvSettingRow(
                    label = "Addons shared by ${sharing.primaryName.ifBlank { "the primary profile" }}",
                    detail = "You can still add your own on a desktop.",
                    value = "On",
                    enabled = false,
                    onActivate = {},
                )
            }
        }
    }
}

/**
 * Nuvio scrapers, one row each under the repository that supplies them.
 *
 * Listed flat rather than nested because a nested list needs a way in and a way out, and the
 * whole point of the settings rows is that everything is one press deep. A repository that
 * failed to fetch says so in place instead of silently offering nothing.
 */
@Composable
private fun ScraperRows(repos: List<NuvioRepoSummary>) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()

    if (repos.isEmpty()) {
        Text(
            text = "No scraper repositories. These run third-party code and are added on a " +
                "desktop, deliberately.",
            style = MaterialTheme.typography.bodyLarge,
            color = CoveColors.Neutral.MutedDim,
        )
        return
    }

    repos.forEach { repo ->
        TvSettingRow(
            label = repo.displayName,
            detail = repo.fetchError.takeIf { it.isNotBlank() }
                ?: "${repo.scrapers.size} scrapers",
            value = onOff(repo.enabled),
            highlighted = repo.enabled,
            onActivate = {
                scope.launch { graph.addons.setNuvioRepoEnabled(repo.id, !repo.enabled) }
            },
        )
        if (!repo.enabled) return@forEach
        repo.scrapers.forEach { scraper ->
            TvSettingRow(
                label = "    " + scraper.name.ifBlank { scraper.id },
                detail = scraper.codeError.takeIf { it.isNotBlank() }
                    ?: scraper.description.takeIf { it.isNotBlank() },
                value = onOff(scraper.enabled),
                highlighted = scraper.enabled,
                onActivate = {
                    scope.launch {
                        graph.addons.setNuvioScraperEnabled(
                            repo.id,
                            scraper.id,
                            !scraper.enabled,
                        )
                    }
                },
            )
        }
    }
}

/** The blocks the page is made of, in the order they appear. */
private enum class TvSettingsSection(
    val title: String,
    val detail: String?,
    val icon: String,
) {
    Profiles("Who is watching", "Each profile keeps its own list, progress and settings.", "lucide:users"),
    Playback("Playback", "How a title starts and how it is steered.", "lucide:play-circle"),
    Skipping("Skipping", "What Cove jumps past on its own.", "lucide:skip-forward"),
    Subtitles("Subtitles", null, "lucide:captions"),
    Sources("Sources", "How much Cove does before you press play.", "lucide:list-video"),
    Providers("Providers", "Where streams are found. Managed on a desktop.", "lucide:blocks"),
    Trackers("Tracking", "Scrobbling and list sync with Trakt and Simkl.", "iconamoon:history"),
    Scrapers("Community scrapers", "Third-party code, off unless you turn it on.", "lucide:blocks"),
    Storage("Storage", "What streaming has left on this device.", "lucide:hard-drive"),
    Advanced("Advanced", "How this device behaves, and which build it is running.", "lucide:settings"),
}
