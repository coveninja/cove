package com.coveninja.cove.ui.tv.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.components.player.SPEED_STEPS
import com.coveninja.cove.ui.components.player.badges
import com.coveninja.cove.ui.components.player.detailLabel
import com.coveninja.cove.ui.components.player.groupTracksByLanguage
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.model.MediaEpisode
import com.coveninja.cove.ui.state.LocalMotionPolicy
import com.coveninja.cove.ui.state.MediaTrack
import com.coveninja.cove.ui.state.PlaybackRequest
import com.coveninja.cove.ui.state.PlaybackStatus
import com.coveninja.cove.ui.state.DEFAULT_SUBTITLE_POSITION
import com.coveninja.cove.ui.state.DEFAULT_SUBTITLE_SIZE
import com.coveninja.cove.ui.state.SUBTITLE_BORDER_STYLES
import com.coveninja.cove.ui.state.SUBTITLE_POSITION_STEP
import com.coveninja.cove.ui.state.SUBTITLE_SIZE_STEP
import com.coveninja.cove.ui.state.SUBTITLE_TEXT_COLORS
import com.coveninja.cove.ui.state.SubtitleAppearance
import com.coveninja.cove.ui.state.subtitleBorderStyleLabel
import com.coveninja.cove.ui.state.subtitleColorLabel
import com.coveninja.cove.ui.state.SLEEP_TIMER_MINUTES
import com.coveninja.cove.ui.state.SleepTimer
import com.coveninja.cove.ui.state.SleepTimerChoice
import com.coveninja.cove.ui.state.VideoScaling
import com.coveninja.cove.ui.tv.TvTheme
import com.coveninja.cove.ui.tv.components.TvButton
import com.coveninja.cove.ui.tv.components.TvSettingRow
import com.coveninja.cove.ui.tv.focus.FocusOnAppear
import com.coveninja.cove.ui.tv.focus.TvFocusDefaults
import com.coveninja.cove.ui.tv.focus.tvFocusGroup
import com.coveninja.cove.ui.tv.focus.tvFocusTarget
import com.coveninja.cove.ui.tv.pages.cycleOption
import kotlin.math.roundToLong

/**
 * Which list the panel is showing.
 *
 * A stack of one rather than a nested menu. Everything a remote does here is "walk a list and
 * press something", and a submenu drawn over a menu turns that into "walk a list, press
 * something, and now work out which of the two lists you are in" — which is the shape the
 * previous TV shell's dropdowns had and the reason they fought the focus engine hardest.
 * Choosing a category replaces the list; Back puts the previous one straight back.
 */
internal enum class TvPanelPage {
    Root,
    Subtitles,
    Audio,
    Chapters,
    Episodes,
    Sleep,
}

/**
 * Everything about playback that is not seeking or pausing.
 *
 * Built because the alternative on this shell was a cycle button: subtitles advanced one track
 * per press, so a release carrying twenty of them cost nineteen presses to reach the last, each
 * one reloading the track over a running picture. The other shells grew delay steppers, a
 * chapter list, a speed menu, framing, a sleep timer and a stats readout while this one had
 * none of it, though `VideoPlayerHost` has exposed every one of those calls the whole time.
 *
 * The panel takes the right-hand third and leaves the film playing beside it. That is not
 * decoration: subtitle delay and framing are adjusted *by looking at the result*, and a full
 * screen surface would hide the only thing that says whether the adjustment worked.
 */
@Composable
internal fun TvPlayerPanel(
    page: TvPanelPage,
    request: PlaybackRequest,
    status: PlaybackStatus,
    scaling: VideoScaling,
    sleepTimer: SleepTimer,
    statsVisible: Boolean,
    browsingSeason: Int?,
    browsingEpisodes: List<MediaEpisode>,
    onOpenPage: (TvPanelPage) -> Unit,
    onSelectSubtitle: (Int?) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onSetSubtitleDelay: (Double) -> Unit,
    /**
     * How subtitles are drawn, and where to send a change. Null until settings have loaded —
     * the write is a whole-object replace, so a control offered before anything had been read
     * would replace the profile's settings with whatever it was holding.
     */
    subtitleAppearance: SubtitleAppearance? = null,
    onSubtitleAppearanceChange: ((SubtitleAppearance) -> Unit)? = null,
    onSetAudioDelay: (Double) -> Unit,
    onSelectSpeed: (Double) -> Unit,
    onSetScaling: (VideoScaling) -> Unit,
    onSeek: (Double) -> Unit,
    onBrowseSeason: (Int) -> Unit,
    onPlayEpisode: (Int, MediaEpisode) -> Unit,
    onSetSleepTimer: (SleepTimerChoice) -> Unit,
    onToggleStats: () -> Unit,
    onChooseDifferentSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = TvTheme.dimens
    // A scroll position per page: they are different lists, and carrying one page's offset
    // into the next opens it halfway down.
    val listState = remember(page) { LazyListState() }
    val firstRowFocus = remember(page) { FocusRequester() }
    // Re-armed per page, because replacing the list removes the node that held focus and a
    // panel with nothing focused is a panel with no way out but Back.
    FocusOnAppear(firstRowFocus)

    Box(modifier = modifier.fillMaxSize()) {
        // Darkens the picture towards the panel rather than everywhere: the film has to stay
        // watchable beside it, since half of what this panel changes is only judged by eye.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.45f to CoveColors.Scrim.copy(alpha = 0.35f),
                        1f to CoveColors.Scrim.copy(alpha = 0.9f),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(dimens.width * PANEL_WIDTH_FRACTION)
                .background(CoveColors.Neutral.Background.copy(alpha = 0.94f))
                .padding(
                    start = 30.dp,
                    end = dimens.overscanHorizontal,
                    top = dimens.overscanVertical + 18.dp,
                    bottom = dimens.overscanVertical,
                ),
        ) {
            TvPanelHeader(page = page, request = request, status = status)

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp)
                    .tvFocusGroup(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (page) {
                    TvPanelPage.Root -> rootRows(
                        request = request,
                        status = status,
                        scaling = scaling,
                        sleepTimer = sleepTimer,
                        statsVisible = statsVisible,
                        firstRowFocus = firstRowFocus,
                        onOpenPage = onOpenPage,
                        onSelectSpeed = onSelectSpeed,
                        onSetScaling = onSetScaling,
                        onToggleStats = onToggleStats,
                        onChooseDifferentSource = onChooseDifferentSource,
                    )

                    TvPanelPage.Subtitles -> trackRows(
                        tracks = status.subtitleTracks,
                        selectedId = status.selectedSubtitleId,
                        delaySeconds = status.subtitleDelaySeconds,
                        delayLabel = "Subtitle delay",
                        // Subtitles alone can be switched off: a film with no audio track
                        // selected is a fault rather than a choice anyone makes.
                        offEntry = true,
                        firstRowFocus = firstRowFocus,
                        onSelect = onSelectSubtitle,
                        onSetDelay = onSetSubtitleDelay,
                        appearance = subtitleAppearance,
                        onAppearanceChange = onSubtitleAppearanceChange,
                    )

                    TvPanelPage.Audio -> trackRows(
                        tracks = status.audioTracks,
                        selectedId = status.selectedAudioId,
                        delaySeconds = status.audioDelaySeconds,
                        delayLabel = "Audio delay",
                        offEntry = false,
                        firstRowFocus = firstRowFocus,
                        onSelect = { id -> id?.let(onSelectAudio) },
                        onSetDelay = onSetAudioDelay,
                    )

                    TvPanelPage.Chapters -> chapterRows(
                        status = status,
                        firstRowFocus = firstRowFocus,
                        onSeek = onSeek,
                    )

                    TvPanelPage.Episodes -> episodeRows(
                        request = request,
                        browsingSeason = browsingSeason,
                        episodes = browsingEpisodes,
                        firstRowFocus = firstRowFocus,
                        onBrowseSeason = onBrowseSeason,
                        onPlayEpisode = onPlayEpisode,
                    )

                    TvPanelPage.Sleep -> sleepRows(
                        request = request,
                        timer = sleepTimer,
                        firstRowFocus = firstRowFocus,
                        onSetSleepTimer = onSetSleepTimer,
                    )
                }
            }
        }
    }
}

/** What this panel is about, and — on the root — what is playing under it. */
@Composable
private fun TvPanelHeader(
    page: TvPanelPage,
    request: PlaybackRequest,
    status: PlaybackStatus,
) {
    val title = when (page) {
        TvPanelPage.Root -> request.heading
        TvPanelPage.Subtitles -> "Subtitles"
        TvPanelPage.Audio -> "Audio"
        TvPanelPage.Chapters -> "Chapters"
        TvPanelPage.Episodes -> "Episodes"
        TvPanelPage.Sleep -> "Sleep timer"
    }
    val detail = when (page) {
        TvPanelPage.Root -> listOfNotNull(
            request.episodeSubtitle,
            formatClock(status.positionSeconds) + " of " + formatClock(status.durationSeconds),
        ).joinToString("  ·  ")

        else -> "Back returns to the player"
    }

    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = CoveColors.Neutral.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelMedium,
            color = CoveColors.Neutral.MutedDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/**
 * The panel's front page.
 *
 * Ordered by how often a hand reaches for it, not by category: subtitles and audio are most of
 * why anyone opens this, and the sleep timer and the stats readout are the two nobody should
 * have to walk past to reach anything else.
 *
 * Rows that lead somewhere and rows that change in place look the same deliberately — on a
 * remote they are the same gesture, and the value on the right already says which happened.
 */
private fun LazyListScope.rootRows(
    request: PlaybackRequest,
    status: PlaybackStatus,
    scaling: VideoScaling,
    sleepTimer: SleepTimer,
    statsVisible: Boolean,
    firstRowFocus: FocusRequester,
    onOpenPage: (TvPanelPage) -> Unit,
    onSelectSpeed: (Double) -> Unit,
    onSetScaling: (VideoScaling) -> Unit,
    onToggleStats: () -> Unit,
    onChooseDifferentSource: () -> Unit,
) {
    val entries = buildList {
        if (status.subtitleTracks.isNotEmpty()) {
            add(
                PanelEntry(
                    key = "subtitles",
                    label = "Subtitles",
                    detail = "${status.subtitleTracks.size} available",
                    value = status.subtitleTracks
                        .firstOrNull { it.id == status.selectedSubtitleId }
                        ?.label
                        ?: "Off",
                    highlighted = status.selectedSubtitleId != null,
                    onActivate = { onOpenPage(TvPanelPage.Subtitles) },
                ),
            )
        }
        if (status.audioTracks.isNotEmpty()) {
            add(
                PanelEntry(
                    key = "audio",
                    label = "Audio",
                    detail = "${status.audioTracks.size} available",
                    value = status.audioTracks
                        .firstOrNull { it.id == status.selectedAudioId }
                        ?.label
                        ?: "Default",
                    onActivate = { onOpenPage(TvPanelPage.Audio) },
                ),
            )
        }
        add(
            PanelEntry(
                key = "speed",
                label = "Speed",
                detail = "How fast it plays.",
                value = speedLabel(status.speed),
                highlighted = status.speed != 1.0,
                onActivate = { onSelectSpeed(cycleOption(SPEED_STEPS, status.speed)) },
            ),
        )
        add(
            PanelEntry(
                key = "framing",
                label = "Framing",
                detail = scaling.description,
                value = scaling.label,
                highlighted = scaling != VideoScaling.Fit,
                onActivate = { onSetScaling(cycleOption(VideoScaling.entries, scaling)) },
            ),
        )
        if (status.chapters.isNotEmpty()) {
            add(
                PanelEntry(
                    key = "chapters",
                    label = "Chapters",
                    detail = "Jump to a marked point.",
                    value = "${status.chapters.size}",
                    onActivate = { onOpenPage(TvPanelPage.Chapters) },
                ),
            )
        }
        // An extra is a trailer: it has no season behind it and no source list to go back to.
        if (request.extra == null && request.season != null) {
            add(
                PanelEntry(
                    key = "episodes",
                    label = "Episodes",
                    detail = "Pick another one without leaving the player.",
                    value = request.episodeSubtitle.orEmpty(),
                    onActivate = { onOpenPage(TvPanelPage.Episodes) },
                ),
            )
        }
        add(
            PanelEntry(
                key = "sleep",
                label = "Sleep timer",
                detail = "Stop on its own.",
                value = sleepTimer.label ?: "Off",
                highlighted = sleepTimer.armed,
                onActivate = { onOpenPage(TvPanelPage.Sleep) },
            ),
        )
        add(
            PanelEntry(
                key = "stats",
                label = "Playback stats",
                detail = "Decoder, frame rate and dropped frames.",
                value = if (statsVisible) "On" else "Off",
                highlighted = statsVisible,
                onActivate = onToggleStats,
            ),
        )
        if (request.extra == null) {
            add(
                PanelEntry(
                    key = "source",
                    label = "Choose a different source",
                    detail = "Back to the list of streams for this title.",
                    value = "",
                    onActivate = onChooseDifferentSource,
                ),
            )
        }
    }

    itemsIndexed(items = entries, key = { _, entry -> entry.key }) { index, entry ->
        TvSettingRow(
            label = entry.label,
            detail = entry.detail,
            value = entry.value,
            highlighted = entry.highlighted,
            onActivate = entry.onActivate,
            modifier = if (index == 0) Modifier.focusRequester(firstRowFocus) else Modifier,
        )
    }
}

/** One root row, so the list's order and its keys cannot drift apart. */
private data class PanelEntry(
    val key: String,
    val label: String,
    val detail: String?,
    val value: String,
    val onActivate: () -> Unit,
    val highlighted: Boolean = false,
)

/**
 * A track list, grouped by language, with the delay control above it.
 *
 * Grouping is [groupTracksByLanguage], the same function the pointer shells use, so a release
 * whose Spanish subtitles arrive as `es-419` and `es-ES` reads the same way on every shell.
 *
 * The delay sits at the top rather than in a settings screen because the only way to judge it
 * is to watch the picture while it changes, which is exactly where the viewer already is.
 */
private fun LazyListScope.trackRows(
    tracks: List<MediaTrack>,
    selectedId: Int?,
    delaySeconds: Double,
    delayLabel: String,
    offEntry: Boolean,
    firstRowFocus: FocusRequester,
    onSelect: (Int?) -> Unit,
    onSetDelay: (Double) -> Unit,
    /** Subtitles only; the audio page passes neither. */
    appearance: SubtitleAppearance? = null,
    onAppearanceChange: ((SubtitleAppearance) -> Unit)? = null,
) {
    item(key = "delay") {
        TvPanelStepper(
            label = delayLabel,
            value = formatDelay(delaySeconds),
            onDecrease = { onSetDelay(delaySeconds - DELAY_STEP_SECONDS) },
            onIncrease = { onSetDelay(delaySeconds + DELAY_STEP_SECONDS) },
            onReset = if (delaySeconds != 0.0) ({ onSetDelay(0.0) }) else null,
        )
    }

    // Above the tracks rather than below them, unlike the pointer menu. A remote walks the
    // list top to bottom and a release can carry twenty subtitle tracks; putting these last
    // would mean twenty presses to reach the control for text that is too small to read.
    if (appearance != null && onAppearanceChange != null) {
        item(key = "size") {
            TvPanelStepper(
                label = "Text size",
                value = "${appearance.sizePercent.roundToLong()}%",
                onDecrease = {
                    onAppearanceChange(
                        appearance.copy(sizePercent = appearance.sizePercent - SUBTITLE_SIZE_STEP),
                    )
                },
                onIncrease = {
                    onAppearanceChange(
                        appearance.copy(sizePercent = appearance.sizePercent + SUBTITLE_SIZE_STEP),
                    )
                },
                onReset = if (appearance.sizePercent != DEFAULT_SUBTITLE_SIZE) {
                    { onAppearanceChange(appearance.copy(sizePercent = DEFAULT_SUBTITLE_SIZE)) }
                } else {
                    null
                },
            )
        }
        item(key = "position") {
            TvPanelStepper(
                // A television overscans: on plenty of sets the bottom of the picture is
                // behind the bezel, and this is the control that gets the text out of it.
                label = "Height from the bottom",
                value = "${appearance.position.roundToLong()}%",
                onDecrease = {
                    onAppearanceChange(
                        appearance.copy(position = appearance.position - SUBTITLE_POSITION_STEP),
                    )
                },
                onIncrease = {
                    onAppearanceChange(
                        appearance.copy(position = appearance.position + SUBTITLE_POSITION_STEP),
                    )
                },
                onReset = if (appearance.position != DEFAULT_SUBTITLE_POSITION) {
                    { onAppearanceChange(appearance.copy(position = DEFAULT_SUBTITLE_POSITION)) }
                } else {
                    null
                },
            )
        }
        item(key = "backdrop") {
            TvSettingRow(
                label = "Behind the text",
                detail = "A panel, a box per line, or nothing but an outline.",
                value = subtitleBorderStyleLabel(appearance.borderStyle),
                onActivate = {
                    onAppearanceChange(
                        appearance.copy(
                            borderStyle = cycleOption(
                                SUBTITLE_BORDER_STYLES.map { it.value },
                                appearance.borderStyle,
                            ),
                        ),
                    )
                },
            )
        }
        item(key = "colour") {
            TvSettingRow(
                label = "Text colour",
                detail = "White unless the picture keeps swallowing it.",
                value = subtitleColorLabel(appearance.textColor),
                onActivate = {
                    onAppearanceChange(
                        appearance.copy(
                            textColor = cycleOption(
                                SUBTITLE_TEXT_COLORS.map { it.value },
                                appearance.textColor,
                            ),
                        ),
                    )
                },
            )
        }
    }

    if (offEntry) {
        item(key = "off") {
            TvSettingRow(
                label = "Off",
                detail = null,
                value = if (selectedId == null) "Selected" else "",
                highlighted = selectedId == null,
                onActivate = { onSelect(null) },
                modifier = Modifier.focusRequester(firstRowFocus),
            )
        }
    }

    groupTracksByLanguage(tracks).forEach { group ->
        item(key = "group-${group.languageLabel}") {
            Text(
                text = group.languageLabel,
                style = MaterialTheme.typography.labelMedium,
                color = CoveColors.Brand.Accent,
                modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
            )
        }
        items(items = group.tracks, key = { track -> "track-${track.id}" }) { track ->
            val selected = track.id == selectedId
            TvSettingRow(
                label = track.detailLabel(),
                // The television already has a second line per row, so the badges go there
                // rather than needing the pill treatment the pointer menu uses.
                detail = track.badges().takeIf { it.isNotEmpty() }?.joinToString(" · "),
                value = if (selected) "Selected" else "",
                highlighted = selected,
                onActivate = { onSelect(track.id) },
                // Where nothing can be switched off, the first track is the page's first stop.
                modifier = if (!offEntry && track === tracks.firstOrNull()) {
                    Modifier.focusRequester(firstRowFocus)
                } else {
                    Modifier
                },
            )
        }
    }
}

/** Marked points in the file, as somewhere to jump to. */
private fun LazyListScope.chapterRows(
    status: PlaybackStatus,
    firstRowFocus: FocusRequester,
    onSeek: (Double) -> Unit,
) {
    itemsIndexed(
        items = status.chapters,
        key = { _, chapter -> "chapter-${chapter.index}" },
    ) { index, chapter ->
        // The chapter the viewer is inside, so a long list opens saying where they are rather
        // than making them work it out from the timings.
        val next = status.chapters.getOrNull(index + 1)?.startSeconds ?: status.durationSeconds
        val current = status.positionSeconds >= chapter.startSeconds &&
            status.positionSeconds < next
        TvSettingRow(
            label = chapter.label,
            detail = null,
            value = if (current) "Playing" else formatClock(chapter.startSeconds),
            highlighted = current,
            onActivate = { onSeek(chapter.startSeconds) },
            modifier = if (index == 0) Modifier.focusRequester(firstRowFocus) else Modifier,
        )
    }
}

/**
 * The season strip and its episodes, without leaving the film.
 *
 * Episodes are fetched per season on demand, exactly as the details screen does it: a remote
 * walks along the strip and firing a request for every season it passed through would spend a
 * fan-out on seasons nobody stopped at.
 */
private fun LazyListScope.episodeRows(
    request: PlaybackRequest,
    browsingSeason: Int?,
    episodes: List<MediaEpisode>,
    firstRowFocus: FocusRequester,
    onBrowseSeason: (Int) -> Unit,
    onPlayEpisode: (Int, MediaEpisode) -> Unit,
) {
    val seasons = request.media.seasons
    if (seasons.isNotEmpty()) {
        item(key = "seasons") {
            Row(
                modifier = Modifier.tvFocusGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                seasons.forEach { season ->
                    TvButton(
                        label = season.title,
                        onClick = { onBrowseSeason(season.number) },
                        selected = season.number == browsingSeason,
                        modifier = if (season.number == browsingSeason) {
                            Modifier.focusRequester(firstRowFocus)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
    }

    if (episodes.isEmpty()) {
        item(key = "empty") {
            Text(
                text = "Loading episodes…",
                style = MaterialTheme.typography.bodyMedium,
                color = CoveColors.Neutral.MutedDim,
                modifier = Modifier.padding(top = 16.dp, start = 4.dp),
            )
        }
        return
    }

    items(items = episodes, key = { episode -> "episode-${episode.id}" }) { episode ->
        val playing = browsingSeason == request.season && episode.number == request.episode
        TvSettingRow(
            label = "${episode.number}. ${episode.title}",
            detail = episode.airDate?.takeIf { it.isNotBlank() },
            value = when {
                playing -> "Playing"
                episode.watched -> "Watched"
                else -> ""
            },
            highlighted = playing,
            onActivate = {
                browsingSeason?.let { season -> onPlayEpisode(season, episode) }
            },
        )
    }
}

/**
 * When to stop on its own.
 *
 * More useful on a television than anywhere else, which is why it is here at all: a phone ends
 * up face down on a chest, and a television carries on playing to a dark room all night.
 */
private fun LazyListScope.sleepRows(
    request: PlaybackRequest,
    timer: SleepTimer,
    firstRowFocus: FocusRequester,
    onSetSleepTimer: (SleepTimerChoice) -> Unit,
) {
    val choices = buildList {
        add(SleepTimerChoice.Off)
        // A film has nothing after it to decline, so the choice would mean the same as Off.
        if (request.season != null) add(SleepTimerChoice.AfterThisEpisode)
        SLEEP_TIMER_MINUTES.forEach { minutes -> add(SleepTimerChoice.After(minutes)) }
    }

    itemsIndexed(items = choices, key = { _, choice -> sleepChoiceLabel(choice) }) { index, choice ->
        val selected = choice == timer.choice
        TvSettingRow(
            label = sleepChoiceLabel(choice),
            detail = null,
            // The armed timer shows what is left rather than what was asked for, since the
            // number that matters after the first minute is the one counting down.
            value = when {
                selected && choice is SleepTimerChoice.After -> timer.label.orEmpty()
                selected -> "On"
                else -> ""
            },
            highlighted = selected,
            onActivate = { onSetSleepTimer(choice) },
            modifier = if (index == 0) Modifier.focusRequester(firstRowFocus) else Modifier,
        )
    }
}

/**
 * A value nudged by two buttons rather than dragged along a slider.
 *
 * A slider needs a continuous pointer or a key-repeat handler that owns Left and Right, and
 * owning arrows inside a list a D-pad is walking down is how focus gets trapped. Two targets
 * and a number between them cost one extra press per step and nothing else.
 */
@Composable
private fun TvPanelStepper(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    /** Absent at zero, where it would do nothing. */
    onReset: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CoveColors.Neutral.Surface, RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .tvFocusGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = CoveColors.Neutral.Text,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = CoveColors.Neutral.MutedDim,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        onReset?.let {
            TvStepperButton(icon = "lucide:rotate-ccw", onClick = it)
            Spacer(modifier = Modifier.width(10.dp))
        }
        TvStepperButton(icon = "lucide:minus", onClick = onDecrease)
        Spacer(modifier = Modifier.width(10.dp))
        TvStepperButton(icon = "lucide:plus", onClick = onIncrease)
    }
}

@Composable
private fun TvStepperButton(icon: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    val background by animateColorAsState(
        targetValue = if (focused) {
            CoveColors.Neutral.Text
        } else {
            CoveColors.Neutral.SurfaceRaised
        },
        animationSpec = if (reducedMotion) snap() else tween(140),
        label = "TvStepperBackground",
    )
    val content by animateColorAsState(
        targetValue = if (focused) CoveColors.Neutral.Background else CoveColors.Neutral.Text,
        animationSpec = if (reducedMotion) snap() else tween(140),
        label = "TvStepperContent",
    )

    Box(
        modifier = Modifier
            .size(42.dp)
            .tvFocusTarget(
                shape = CircleShape,
                onClick = onClick,
                scale = TvFocusDefaults.ControlScale,
                ringColor = Color.Transparent,
                interactionSource = interactionSource,
            )
            .background(background, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        IconifyIcon(icon = icon, tint = content, modifier = Modifier.size(18.dp))
    }
}

private fun sleepChoiceLabel(choice: SleepTimerChoice): String = when (choice) {
    SleepTimerChoice.Off -> "Off"
    SleepTimerChoice.AfterThisEpisode -> "After this episode"
    is SleepTimerChoice.After -> "In ${choice.minutes} minutes"
}

/** `1.5×`, and a bare `Normal` at one, which reads better than `1×` in a column of values. */
internal fun speedLabel(speed: Double): String {
    if (speed == 1.0) return "Normal"
    val rounded = (speed * 100).toLong()
    val text = if (rounded % 100L == 0L) {
        (rounded / 100).toString()
    } else {
        val whole = rounded / 100
        val fraction = (rounded % 100).toString().padStart(2, '0').trimEnd('0')
        "$whole.$fraction"
    }
    return text + "×"
}

/**
 * A delay, signed, in tenths.
 *
 * The sign is the whole point and has to be explicit: "0.4" says nothing about whether the
 * subtitles were pushed later or pulled earlier, and getting it backwards means stepping four
 * times in the wrong direction before the picture says so.
 */
internal fun formatDelay(seconds: Double): String {
    val tenths = (seconds * 10).toLong()
    if (tenths == 0L) return "None"
    val sign = if (tenths > 0) "+" else "-"
    val magnitude = if (tenths < 0) -tenths else tenths
    return sign + (magnitude / 10) + "." + (magnitude % 10) + "s"
}

/** One press of a delay button. Fine enough to land on, coarse enough to get there. */
private const val DELAY_STEP_SECONDS = 0.1

/** Wide enough for a track title, narrow enough to leave the film worth looking at. */
private const val PANEL_WIDTH_FRACTION = 0.38f
