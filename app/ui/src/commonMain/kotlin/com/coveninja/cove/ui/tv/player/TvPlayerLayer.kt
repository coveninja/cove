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
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.shared.model.labelled
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.components.player.SeekBurst
import com.coveninja.cove.ui.components.player.SeekFeedback
import com.coveninja.cove.ui.components.player.accumulateSeekFeedback
import com.coveninja.cove.ui.components.player.SEEK_FEEDBACK_WINDOW_MILLIS
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.LocalVideoPlayerHost
import com.coveninja.cove.ui.state.MediaTrack
import com.coveninja.cove.ui.state.PlaybackPhase
import com.coveninja.cove.ui.state.PlaybackPresentation
import com.coveninja.cove.ui.state.PlaybackSession
import com.coveninja.cove.ui.state.PlaybackStatus
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
 */
@Composable
internal fun TvPlayerLayer(
    session: PlaybackSession,
    modifier: Modifier = Modifier,
) {
    val request = session.request ?: return
    val phase = session.phase ?: return
    if (session.presentation != PlaybackPresentation.Fullscreen) return

    val host = LocalVideoPlayerHost.current
    // Resolved to a flow first so collectAsState is called unconditionally — a composable
    // behind a null check is only safe while the host never changes, which is a property of
    // the composition local rather than of this code.
    val statusFlow = remember(host) { host?.status ?: MutableStateFlow(PlaybackStatus()) }
    val status by statusFlow.collectAsState()
    val settings = (LocalAppGraph.current.settings.settings.value as? SettingsState.Ready)?.settings
    val seekStep = settings?.seekStepSeconds?.takeIf { it > 0.0 } ?: TV_SEEK_STEP_SECONDS

    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    var controlsVisible by remember { mutableStateOf(true) }
    var barHasFocus by remember { mutableStateOf(false) }
    var activityPulse by remember { mutableStateOf(0) }
    var seekFeedback by remember { mutableStateOf<SeekFeedback?>(null) }
    var lastSeekAt by remember { mutableStateOf<TimeMark?>(null) }

    // The phase turns Playing when the URL is handed over, which on a torrent is a long way
    // before the first frame. Both the chrome and the remote wait for this.
    val start = rememberPlaybackStart(
        status,
        request.media.id,
        request.season,
        request.episode,
    )

    // Re-requested on every phase change rather than once: the layer composes while sources
    // are still resolving, so an early request can be taken back by whatever had focus on the
    // page underneath.
    //
    // The frame wait is not optional. `requestFocus` throws if the node is not attached yet,
    // which is exactly the state this is in on the pass that mounts the player — and because
    // the failure is swallowed, the result was a player that held no focus at all and a remote
    // whose every press went nowhere.
    LaunchedEffect(phase) {
        withFrameNanos { }
        runCatching { rootFocus.requestFocus() }.let { }
    }

    LaunchedEffect(activityPulse, phase, barHasFocus) {
        controlsVisible = true
        if (phase is PlaybackPhase.Playing && !barHasFocus) {
            delay(TV_CONTROLS_HIDE_DELAY_MILLIS.milliseconds)
            controlsVisible = false
        }
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

    // Auto-skip is the session's own settings applied to the session's own timestamps; the
    // only reason it lives in a view file at all is that it needs the live position.
    val segments = session.timestamps.labelled()
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

    // A segment the viewer can skip by hand: one that exists, that Cove is not already skipping
    // for them, and that has somewhere to land. Null the rest of the time, which is what keeps
    // the hint off screen and centre free to summon the controls.
    val manualSkipTarget = if (settings?.skipsAutomatically(currentSegment?.kind ?: SegmentKind.Intro) == true) {
        null
    } else {
        currentSegment?.let { skipTarget(it, status.positionSeconds, status.durationSeconds) }
    }
    val skipLabel = currentSegment?.kind?.skipLabel()?.takeIf { manualSkipTarget != null }

    // What follows this episode, once the credits make it the more useful thing to offer.
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
                // Nothing below can be done to a file that is still opening: there is no
                // position to seek, nothing to pause, and a bar summoned over the opening
                // stage would take focus and answer to nothing. What is on screen then is
                // that stage's own Cancel button, so centre and Back are handed down to it
                // — while the arrows stay swallowed rather than going to the focus engine,
                // which would walk focus onto the page hidden behind the overlay. Only the
                // opening window: the earlier phases put a list on screen and are the focus
                // engine's to arbitrate.
                if (phase is PlaybackPhase.Playing && !start.started) {
                    return@onPreviewKeyEvent when (action) {
                        null, TvKeyAction.Select, TvKeyAction.Back -> false
                        else -> true
                    }
                }
                when (action) {
                    is TvKeyAction.Move -> when (
                        tvPlayerArrowOutcome(action.direction, controlsVisible, barHasFocus)
                    ) {
                        TvPlayerArrowOutcome.Seek -> {
                            val forward = action.direction == TvDirection.Right
                            jump(if (forward) seekStep else -seekStep)
                            true
                        }
                        // Consumed even though it only reveals: letting it through would move
                        // focus in the same press that summoned the bar, so the viewer would
                        // never see where focus started.
                        TvPlayerArrowOutcome.RevealControls -> {
                            controlsVisible = true
                            runCatching { playFocus.requestFocus() }.let { }
                            true
                        }

                        TvPlayerArrowOutcome.Navigate -> false
                    }

                    TvKeyAction.Select -> if (
                        tvSelectSkipsSegment(controlsVisible, manualSkipTarget != null)
                    ) {
                        manualSkipTarget?.let { host?.seek(it) }
                        true
                    } else if (!controlsVisible || !barHasFocus) {
                        controlsVisible = true
                        runCatching { playFocus.requestFocus() }.let { }
                        true
                    } else {
                        // The focused control is the one that should act on this.
                        false
                    }

                    TvKeyAction.PlayPause -> { host?.togglePause(); true }
                    TvKeyAction.FastForward -> { jump(seekStep * 3); true }
                    TvKeyAction.Rewind -> { jump(-seekStep * 3); true }
                    // Back is layered by the handler the shell installs; nothing to do here
                    // beyond hiding a bar that is up.
                    TvKeyAction.Back -> if (controlsVisible) {
                        controlsVisible = false
                        runCatching { rootFocus.requestFocus() }.let { }
                        true
                    } else {
                        false
                    }

                    TvKeyAction.Next, TvKeyAction.Previous, null -> false
                }
            },
    ) {
        // Mounted only once there is a file to draw. The player surface is an opaque native
        // view, not a Compose layer, so anything drawn underneath it is simply covered — mount
        // it while sources are still resolving and every pre-playback state becomes the black
        // rectangle it was hiding behind.
        if (host != null && phase is PlaybackPhase.Playing) {
            host.Surface(Modifier.fillMaxSize())
        }

        // Before there is a picture, the title's own artwork stands in for one — and "before
        // there is a picture" is not the same as "before playback started". The phase turns
        // Playing the moment the URL is handed over, while the first frame can be many seconds
        // later on a torrent, so this waits for media rather than for the phase.
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
                // The latch, not the live status alone: mpv drops hasMedia again at the end of
                // a file and briefly whenever it reloads, and without it the opening stage
                // slides back over a session that is playing perfectly well.
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

        // Buffering says so quietly and only while it is true. A spinner that outstays the
        // stall is what makes a working player feel broken.
        AnimatedVisibility(
            visible = phase is PlaybackPhase.Playing && status.hasMedia &&
                status.waitingForData && !session.reconnecting,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(180)),
        ) {
            TvBufferingIndicator(percent = status.bufferingPercent)
        }

        // The skip affordance. Deliberately not focusable: it appears mid-film, and something
        // that stole focus from the picture — or that had to be navigated to before it could be
        // used — would be worse than no affordance at all. While it is up, centre skips.
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
                autoAdvance = settings?.autoPlay == true,
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
            // Absent rather than disabled while the file opens. A bar on a television is
            // reached by moving focus onto it, and focus parked on a dead button is a remote
            // that has stopped working as far as the room can tell.
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
                onFocusChanged = { barHasFocus = it },
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
