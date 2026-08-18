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
import com.coveninja.cove.shared.data.AddonsState
import com.coveninja.cove.shared.data.ProfilesState
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.model.Addon
import com.coveninja.cove.shared.model.NuvioRepoSummary
import com.coveninja.cove.shared.model.AppSettings
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.state.LocalAppGraph
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
    val sections = remember(nuvioRepos, graph.addons.supportsNuvio) {
        TvSettingsSection.entries.filter {
            it != TvSettingsSection.Scrapers || graph.addons.supportsNuvio
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
                        TvSettingsSection.Profiles -> ProfileRows()
                        TvSettingsSection.Playback -> PlaybackRows(settings, editor)
                        TvSettingsSection.Skipping -> SkippingRows(settings, editor)
                        TvSettingsSection.Subtitles -> SubtitleRows(settings, editor)
                        TvSettingsSection.Providers -> ProviderRows(addons)
                        TvSettingsSection.Scrapers -> ScraperRows(nuvioRepos)
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
        label = "Subtitle background",
        detail = "A panel behind the text, for bright scenes.",
        value = onOff(settings.subtitleBackground),
        highlighted = settings.subtitleBackground,
        onActivate = { editor.edit { copy(subtitleBackground = !subtitleBackground) } },
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
        TvSettingRow(
            label = addon.displayName,
            detail = addon.manifest.description.takeIf { it.isNotBlank() } ?: addon.url,
            value = onOff(addon.enabled),
            highlighted = addon.enabled,
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
private fun ProfileRows() {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val profilesState by graph.profiles.profiles.collectAsState()

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

        is ProfilesState.Ready -> state.profiles.forEach { profile ->
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
    Providers("Providers", "Where streams are found. Managed on a desktop.", "lucide:blocks"),
    Scrapers("Community scrapers", "Third-party code, off unless you turn it on.", "lucide:blocks"),
}
