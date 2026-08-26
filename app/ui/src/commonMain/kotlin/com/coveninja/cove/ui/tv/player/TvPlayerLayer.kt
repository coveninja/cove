package com.coveninja.cove.ui.tv.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.components.player.PlayerStatsOverlay
import com.coveninja.cove.ui.components.player.SeekBurst
import com.coveninja.cove.ui.components.player.SeekFeedback
import com.coveninja.cove.ui.components.player.accumulateSeekFeedback
import com.coveninja.cove.ui.components.player.SEEK_FEEDBACK_WINDOW_MILLIS
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.rememberSettingsEditor
import com.coveninja.cove.ui.state.subtitleAppearance
import com.coveninja.cove.ui.state.withSubtitleAppearance
import com.coveninja.cove.ui.state.LocalVideoPlayerHost
import com.coveninja.cove.ui.state.MediaTrack
import com.coveninja.cove.ui.state.PlaybackPhase
import com.coveninja.cove.ui.state.PlaybackSession
import com.coveninja.cove.ui.state.PlaybackStatus
import com.coveninja.cove.ui.state.SleepTimer
import com.coveninja.cove.ui.state.SleepTimerChoice
import com.coveninja.cove.ui.state.VideoScaling
import com.coveninja.cove.ui.state.armSleepTimer
import com.coveninja.cove.ui.state.autoAdvanceAllowed
import com.coveninja.cove.ui.state.previousEpisodeBefore
import com.coveninja.cove.ui.state.sleepTimerElapsed
import com.coveninja.cove.ui.state.tickSleepTimer
import com.coveninja.cove.ui.state.playbackSegments
import com.coveninja.cove.ui.state.segmentAt
import com.coveninja.cove.ui.state.skipTarget
import com.coveninja.cove.ui.state.skipsAutomatically
import com.coveninja.cove.ui.state.identity
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import com.coveninja.cove.shared.model.SegmentKind
import com.coveninja.cove.ui.components.player.PlayerBackdrop
import com.coveninja.cove.ui.components.player.rememberPlaybackStart
import com.coveninja.cove.ui.components.player.PulsingLogo
import com.coveninja.cove.ui.state.nextEpisodeAfter
import com.coveninja.cove.ui.state.showUpNext
import com.coveninja.cove.ui.state.skipLabel
import com.coveninja.cove.ui.tv.TvTheme
import com.coveninja.cove.ui.tv.components.TvButton
import com.coveninja.cove.ui.tv.focus.FocusOnAppear
import com.coveninja.cove.ui.tv.focus.TvDirection
import com.coveninja.cove.ui.tv.focus.TvKeyAction
import com.coveninja.cove.ui.tv.focus.tvFocusGroup
import com.coveninja.cove.ui.tv.focus.tvKeyAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

/**
 * Playback, driven by a remote.
 *
 * Everything that decides *what* plays is [PlaybackSession]'s, shared with the phone and the
 * desktop — resolving sources, the one automatic reconnect, the resume point, segment
 * timestamps. What this owns is the part a remote changes completely: a control bar that is
 * absent by default and is summoned, rather than one that follows a pointer around.
 *
 * The arrows are the whole design problem, and [tvPlayerArrowOutcome] is where it is settled.
 *
 * Note what is *not* checked here: the session's presentation. The pointer shells have a slot
 * on the details page to embed a video in, and open trailers into it — `openExtra` and
 * `failExtra` both set `Inline` for their benefit. This shell has no such slot, so anything the
 * session opens belongs on the whole screen; honouring `Inline` here meant a trailer, and the
 * notice explaining why one would not play, drew nowhere at all and the press read as ignored.
 */
@Composable
internal fun TvPlayerLayer(
    session: PlaybackSession,
    modifier: Modifier = Modifier,
) {
    val request = session.request ?: return
    val phase = session.phase ?: return

    val host = LocalVideoPlayerHost.current
    // Resolve a flow first so collectAsState remains unconditional if the host changes.
    val statusFlow = remember(host) { host?.status ?: MutableStateFlow(PlaybackStatus()) }
    val status by statusFlow.collectAsState()
    // Collected rather than read off .value, for the reason spelled out in PlayerLayer: a
    // StateFlow's value is not a Compose state read, and the panel's appearance rows write
    // settings and have to see their own writes.
    val settingsState by LocalAppGraph.current.settings.settings.collectAsState()
    val settings = (settingsState as? SettingsState.Ready)?.settings
    // Rebuilt whenever settings change: the editor transforms the settings it was built with,
    // so a stale one would make repeated presses of a stepper all start from the same value.
    val settingsEditor = settings?.let { rememberSettingsEditor(it) }
    val seekStep = settings?.seekStepSeconds?.takeIf { it > 0.0 } ?: TV_SEEK_STEP_SECONDS

    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    var controlsVisible by remember { mutableStateOf(true) }
    var barHasFocus by remember { mutableStateOf(false) }
    var activityPulse by remember { mutableStateOf(0) }
    var seekFeedback by remember { mutableStateOf<SeekFeedback?>(null) }
    var lastSeekAt by remember { mutableStateOf<TimeMark?>(null) }
    // Null while the panel is closed, so one value carries both "is it open" and "which list".
    var panelPage by remember { mutableStateOf<TvPanelPage?>(null) }
    var scaling by remember { mutableStateOf(VideoScaling.Fit) }
    var statsVisible by remember { mutableStateOf(false) }
    // Not remembered across requests. A sleep timer is about this sitting, and carrying one
    // into the title the viewer deliberately started next would stop it for no stated reason.
    var sleepTimer by remember(request.media.id, request.season, request.episode) {
        mutableStateOf(SleepTimer.Off)
    }
    val panelOpen = panelPage != null

    // Playing can precede the first frame, especially for torrents.
    val start = rememberPlaybackStart(
        status,
        request.media.id,
        request.season,
        request.episode,
    )

    // Retry after attachment and phase changes; requestFocus throws on an unattached node.
    LaunchedEffect(phase) {
        withFrameNanos { }
        runCatching { rootFocus.requestFocus() }.let { }
    }

    LaunchedEffect(activityPulse, phase, barHasFocus, panelOpen) {
        // The panel and the control bar are two answers to the same press and must never be
        // on screen together — focus would have somewhere to go that the viewer cannot see.
        if (panelOpen) {
            controlsVisible = false
            return@LaunchedEffect
        }
        controlsVisible = true
        if (phase is PlaybackPhase.Playing && !barHasFocus) {
            delay(TV_CONTROLS_HIDE_DELAY_MILLIS.milliseconds)
            controlsVisible = false
        }
    }

    // A duration timer counts in real seconds rather than playback ones: somebody who fell
    // asleep during a buffering stall still meant "stop in thirty minutes".
    LaunchedEffect(sleepTimer.choice) {
        if (sleepTimer.choice !is SleepTimerChoice.After) return@LaunchedEffect
        while (!sleepTimerElapsed(sleepTimer)) {
            delay(1_000.milliseconds)
            sleepTimer = tickSleepTimer(sleepTimer, elapsedSeconds = 1)
        }
        session.close()
    }
    LaunchedEffect(seekFeedback?.id) {
        if (seekFeedback != null) {
            delay(SEEK_FEEDBACK_WINDOW_MILLIS.milliseconds)
            seekFeedback = null
        }
    }

    val jump: (Double) -> Unit = { delta ->
        seekFeedback = accumulateSeekFeedback(
            current = seekFeedback,
            deltaSeconds = delta,
            withinWindow = lastSeekAt?.elapsedNow()
                ?.let { it < SEEK_FEEDBACK_WINDOW_MILLIS.milliseconds } == true,
        )
        lastSeekAt = TimeSource.Monotonic.markNow()
        host?.seekRelative(delta)
    }

    // The remote's skip-track buttons. They have been in the key vocabulary since the shell was
    // written and dropped on the floor by this handler ever since, which from the sofa is
    // indistinguishable from two broken buttons.
    val stepEpisode: (Int) -> Boolean = step@{ delta ->
        val season = request.season ?: return@step false
        val number = request.episode ?: return@step false
        val target = if (delta > 0) {
            nextEpisodeAfter(request.media.seasons, season, number)
        } else {
            previousEpisodeBefore(request.media.seasons, season, number)
        } ?: return@step false
        session.open(
            media = request.media,
            season = target.first,
            episode = target.second,
        )
        true
    }

    // Point the episode picker at what is playing the first time that page is opened, so it
    // shows this season rather than an empty list waiting for a season to be chosen. Deferred
    // until the page is asked for rather than run at startup: it costs an episode fetch, and
    // most playbacks never open the panel at all.
    LaunchedEffect(panelPage, request.media.id, request.season) {
        if (panelPage != TvPanelPage.Episodes) return@LaunchedEffect
        if (session.browsingSeason != null) return@LaunchedEffect
        request.season?.let(session::browseSeason)
    }

    // Segment skipping depends on the live playback position.
    val segments = remember(session.timestamps, status.chapters, status.durationSeconds) {
        playbackSegments(session.timestamps, status.chapters, status.durationSeconds)
    }
    val currentSegment = segmentAt(status.positionSeconds, segments)
    val skipped = remember(request.season, request.episode, request.media.id) {
        mutableSetOf<String>()
    }
    LaunchedEffect(currentSegment, status.positionSeconds, settings, session.reconnecting) {
        if (status.interrupted || session.reconnecting || session.recoveryFailed) {
            return@LaunchedEffect
        }
        val segment = currentSegment ?: return@LaunchedEffect
        val preferences = settings ?: return@LaunchedEffect
        if (!preferences.skipsAutomatically(segment.kind)) return@LaunchedEffect
        if (!skipped.add(segment.identity())) return@LaunchedEffect
        skipTarget(segment, status.positionSeconds, status.durationSeconds)?.let { host?.seek(it) }
    }

    val dimens = TvTheme.dimens

    // Offer manual skip only for actionable segments not already auto-skipped.
    val manualSkipTarget = if (settings?.skipsAutomatically(currentSegment?.kind ?: SegmentKind.Intro) == true) {
        null
    } else {
        currentSegment?.let { skipTarget(it, status.positionSeconds, status.durationSeconds) }
    }
    val skipLabel = currentSegment?.kind?.skipLabel()?.takeIf { manualSkipTarget != null }

    val atEnd = phase is PlaybackPhase.Playing && !status.interrupted &&
        !session.reconnecting && !session.recoveryFailed &&
        showUpNext(
            positionSeconds = status.positionSeconds,
            durationSeconds = status.durationSeconds,
            segments = segments,
            endReached = status.endReached,
        )
    val upNext = remember(atEnd, request.season, request.episode, request.media.id) {
        if (!atEnd) {
            null
        } else {
            request.season?.let { season ->
                request.episode?.let { number ->
                    nextEpisodeAfter(request.media.seasons, season, number)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                activityPulse++
                val action = tvKeyAction(event.key)
                // While media opens, leave centre and Back to Cancel but swallow arrows so
                // focus cannot escape to the page behind the player.
                if (phase is PlaybackPhase.Playing && !start.started) {
                    return@onPreviewKeyEvent when (action) {
                        null, TvKeyAction.Select, TvKeyAction.Back -> false
                        else -> true
                    }
                }
                when (action) {
                    is TvKeyAction.Move -> when (
                        tvPlayerArrowOutcome(
                            direction = action.direction,
                            controlsVisible = controlsVisible,
                            barHasFocus = barHasFocus,
                            panelOpen = panelOpen,
                        )
                    ) {
                        TvPlayerArrowOutcome.Seek -> {
                            val forward = action.direction == TvDirection.Right
                            jump(if (forward) seekStep else -seekStep)
                            true
                        }
                        // Consume the reveal press so it does not also move focus.
                        TvPlayerArrowOutcome.RevealControls -> {
                            controlsVisible = true
                            runCatching { playFocus.requestFocus() }.let { }
                            true
                        }

                        TvPlayerArrowOutcome.Navigate -> false
                    }

                    TvKeyAction.Select -> if (
                        tvSelectSkipsSegment(
                            controlsVisible = controlsVisible,
                            skipAvailable = manualSkipTarget != null,
                            panelOpen = panelOpen,
                        )
                    ) {
                        manualSkipTarget?.let { host?.seek(it) }
                        true
                    } else if (panelOpen) {
                        // Every row in the panel is activated with centre; it belongs to
                        // whatever holds focus in there and nothing out here may take it.
                        false
                    } else if (!controlsVisible || !barHasFocus) {
                        controlsVisible = true
                        runCatching { playFocus.requestFocus() }.let { }
                        true
                    } else {
                        false
                    }

                    TvKeyAction.PlayPause -> { host?.togglePause(); true }
                    TvKeyAction.FastForward -> { jump(seekStep * 3); true }
                    TvKeyAction.Rewind -> { jump(-seekStep * 3); true }
                    // Outward one layer per press, matching `resolveTvBackAction`: a panel
                    // page returns to the panel's root, the root closes the panel, and only
                    // then does Back mean the player itself.
                    TvKeyAction.Back -> when {
                        panelPage != null && panelPage != TvPanelPage.Root -> {
                            panelPage = TvPanelPage.Root
                            true
                        }

                        panelPage != null -> {
                            panelPage = null
                            runCatching { rootFocus.requestFocus() }.let { }
                            true
                        }

                        controlsVisible -> {
                            controlsVisible = false
                            runCatching { rootFocus.requestFocus() }.let { }
                            true
                        }

                        else -> false
                    }

                    // Only over a running picture. While a source is being resolved or chosen
                    // there is nothing for the panel to be about, and it would cover the very
                    // list the viewer is being asked to pick from.
                    TvKeyAction.Menu -> when {
                        panelOpen -> {
                            panelPage = null
                            runCatching { rootFocus.requestFocus() }.let { }
                            true
                        }

                        phase is PlaybackPhase.Playing && start.started -> {
                            panelPage = TvPanelPage.Root
                            true
                        }

                        else -> false
                    }

                    TvKeyAction.Next -> stepEpisode(1)
                    TvKeyAction.Previous -> stepEpisode(-1)
                    null -> false
                }
            },
    ) {
        // Mount the opaque native surface only once there is media to draw.
        if (host != null && phase is PlaybackPhase.Playing) {
            host.Surface(Modifier.fillMaxSize())
        }

        // Keep artwork visible until the first frame rather than only until phase Playing.
        if (phase !is PlaybackPhase.Playing || !status.hasMedia) {
            PlayerBackdrop(
                backdropUrl = request.media.backdropUrl ?: request.media.posterUrl,
                modifier = Modifier.fillMaxSize(),
            )
        }

        when (phase) {
            PlaybackPhase.Resolving -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PulsingLogo(
                        logoUrl = request.media.logoUrl,
                        title = request.media.title ?: request.media.name.orEmpty(),
                    )
                    Text(
                        text = "Finding a source…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = CoveColors.Neutral.Muted,
                        modifier = Modifier.padding(top = 22.dp),
                    )
                }
            }

            is PlaybackPhase.Choosing -> TvSourcePicker(
                sources = phase.sources,
                onChoose = session::choose,
                onCancel = session::close,
            )

            is PlaybackPhase.Failed -> TvPlayerNotice(
                title = "Could not play this",
                detail = phase.message,
                action = "Try again" to session::retry,
            )

            is PlaybackPhase.Playing -> {
                // Latch first media because mpv briefly clears hasMedia during reload and EOF.
                if (!status.hasMedia && !start.opened) {
                    TvStartingStage(
                        media = request.media,
                        source = phase.source,
                        status = status,
                        torrent = rememberTvTorrentProgress(session, phase.source),
                        onCancel = session::close,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        seekFeedback?.let { feedback -> SeekBurst(feedback = feedback) }

        AnimatedVisibility(
            visible = phase is PlaybackPhase.Playing && status.hasMedia &&
                status.waitingForData && !session.reconnecting,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(180)),
        ) {
            TvBufferingIndicator(percent = status.bufferingPercent)
        }

        // Keep the transient skip affordance unfocusable; centre activates it while visible.
        AnimatedVisibility(
            visible = skipLabel != null && !controlsVisible,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = dimens.overscanHorizontal, bottom = dimens.overscanVertical + 20.dp),
            enter = fadeIn(tween(200)) + slideInVertically { it / 2 },
            exit = fadeOut(tween(160)),
        ) {
            TvSkipHint(label = skipLabel.orEmpty())
        }

        upNext?.let { (nextSeason, nextEpisode) ->
            TvUpNextCard(
                season = nextSeason,
                episode = nextEpisode,
                // "Stop after this episode" is the more specific of the two opinions about
                // what happens next, and is decided here so the card cannot start the episode
                // while the timer is still working out that it should not.
                autoAdvance = autoAdvanceAllowed(settings?.autoPlay == true, sleepTimer),
                onPlay = {
                    session.open(
                        media = request.media,
                        season = nextSeason,
                        episode = nextEpisode,
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = dimens.overscanHorizontal,
                        bottom = dimens.overscanVertical + 20.dp,
                    ),
            )
        }

        AnimatedVisibility(
            // Keep focus away from disabled transport controls while media opens.
            visible = controlsVisible && phase is PlaybackPhase.Playing && start.started,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(160)) + slideOutVertically { it / 4 },
        ) {
            TvPlayerChrome(
                request = request,
                status = status,
                segments = segments,
                playFocusRequester = playFocus,
                seekStep = seekStep,
                onTogglePause = { host?.togglePause() },
                onSeek = jump,
                onCycleSubtitles = {
                    host?.selectSubtitleTrack(
                        nextTrack(status.subtitleTracks, status.selectedSubtitleId),
                    )
                },
                onCycleAudio = {
                    nextTrack(status.audioTracks, status.selectedAudioId)
                        ?.let { host?.selectAudioTrack(it) }
                },
                onClose = session::close,
                onOpenPanel = { panelPage = TvPanelPage.Root },
                onFocusChanged = { barHasFocus = it },
            )
        }

        // Outside the panel on purpose: a readout that vanished with the surface used to
        // summon it could only ever be read while it was covering the thing it describes.
        if (statsVisible) {
            PlayerStatsOverlay(
                status = status,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        start = dimens.overscanHorizontal,
                        top = dimens.overscanVertical,
                    )
                    .zIndex(9f),
            )
        }

        panelPage?.let { page ->
            TvPlayerPanel(
                page = page,
                request = request,
                status = status,
                scaling = scaling,
                sleepTimer = sleepTimer,
                statsVisible = statsVisible,
                browsingSeason = session.browsingSeason,
                browsingEpisodes = session.browsingEpisodes,
                onOpenPage = { panelPage = it },
                onSelectSubtitle = { id ->
                    host?.selectSubtitleTrack(id)
                    // Recorded only from an explicit choice, never from the selection made on
                    // load — which is the rule `PlaybackSession.updateMemory` documents, and
                    // the reason a television could previously read a remembered track but
                    // never write one back.
                    session.rememberSubtitleChoice(
                        language = status.subtitleTracks.firstOrNull { it.id == id }?.language,
                        off = id == null,
                    )
                },
                onSelectAudio = { id ->
                    host?.selectAudioTrack(id)
                    session.rememberAudioLanguage(
                        status.audioTracks.firstOrNull { it.id == id }?.language,
                    )
                },
                subtitleAppearance = settings?.subtitleAppearance(),
                onSubtitleAppearanceChange = settingsEditor?.let { editor ->
                    { appearance -> editor.edit { withSubtitleAppearance(appearance) } }
                },
                onSetSubtitleDelay = { host?.setSubtitleDelay(it) },
                onSetAudioDelay = { host?.setAudioDelay(it) },
                onSelectSpeed = { speed ->
                    host?.setSpeed(speed)
                    session.rememberSpeed(speed)
                },
                onSetScaling = { value ->
                    scaling = value
                    host?.setScaling(value)
                },
                onSeek = { host?.seek(it) },
                onBrowseSeason = session::browseSeason,
                onPlayEpisode = { season, episode ->
                    panelPage = null
                    session.open(
                        media = request.media,
                        season = season,
                        episode = episode.number,
                        episodeTitle = episode.title,
                    )
                },
                onSetSleepTimer = { choice ->
                    sleepTimer = armSleepTimer(choice)
                    panelPage = TvPanelPage.Root
                },
                onToggleStats = { statsVisible = !statsVisible },
                onChooseDifferentSource = {
                    panelPage = null
                    session.reopenSources()
                },
                modifier = Modifier.zIndex(10f),
            )
        }
    }
}

/**
 * The next track after the selected one, wrapping, with null meaning "off".
 *
 * Subtitles cycle through off; audio does not, because a film with no audio track selected is
 * a fault rather than a choice anyone makes deliberately.
 */
private fun nextTrack(tracks: List<MediaTrack>, selectedId: Int?): Int? {
    if (tracks.isEmpty()) return null
    val index = tracks.indexOfFirst { it.id == selectedId }
    return when {
        index < 0 -> tracks.first().id
        index == tracks.lastIndex -> null
        else -> tracks[index + 1].id
    }
}

@Composable
private fun TvPlayerNotice(
    title: String,
    detail: String,
    action: Pair<String, () -> Unit>? = null,
) {
    val focusRequester = remember { FocusRequester() }
    FocusOnAppear(focusRequester, enabled = action != null)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = CoveColors.Neutral.Text,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyLarge,
                color = CoveColors.Neutral.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
            action?.let { (label, onClick) ->
                Box(modifier = Modifier.padding(top = 22.dp)) {
                    TvButton(
                        label = label,
                        onClick = onClick,
                        primary = true,
                        modifier = Modifier.focusRequester(focusRequester),
                    )
                }
            }
        }
    }
}

/**
 * `1:04:12` past an hour, `4:12` below it — a leading hour of zero reads as a stopped clock.
 *
 * Assembled rather than written out because `verifyIcons` scans source for quoted
 * `prefix:name` literals and a hard-coded zero time matches that shape, failing the build with
 * a missing icon named after the clock.
 */
internal fun formatClock(seconds: Double): String {
    val total = if (!seconds.isFinite() || seconds <= 0.0) 0L else seconds.toLong()
    val hours = total / 3_600
    val minutes = (total % 3_600) / 60
    val secs = total % 60
    val minuteText = minutes.toString().let { if (hours > 0) it.padStart(2, '0') else it }
    val body = minuteText + ':' + secs.toString().padStart(2, '0')
    return if (hours > 0) hours.toString() + ':' + body else body
}
