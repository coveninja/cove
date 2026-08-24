package com.coveninja.cove.ui.components.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.shared.model.LabelledSegment
import com.coveninja.cove.shared.model.StreamSource
import com.coveninja.cove.shared.model.TorrentProgress
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.ui.platform.hideCursorWhen
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.LocalVideoPlayerHost
import com.coveninja.cove.ui.state.MediaTrack
import com.coveninja.cove.ui.state.identity
import com.coveninja.cove.ui.state.nextEpisodeAfter
import com.coveninja.cove.ui.state.playbackSegments
import com.coveninja.cove.ui.state.segmentAt
import com.coveninja.cove.ui.state.showUpNext
import com.coveninja.cove.ui.state.skipLabel
import com.coveninja.cove.ui.state.skipTarget
import com.coveninja.cove.ui.state.skipsAutomatically
import com.coveninja.cove.ui.state.LocalFullscreenController
import com.coveninja.cove.ui.state.PlaybackPhase
import com.coveninja.cove.ui.state.PlaybackPresentation
import com.coveninja.cove.ui.state.PlaybackRequest
import com.coveninja.cove.ui.state.PlaybackSession
import com.coveninja.cove.ui.state.PlaybackStatus
import com.coveninja.cove.ui.state.VideoScaling
import com.coveninja.cove.ui.platform.PlaybackBackHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Full-screen playback layer. Renders nothing until a session is open, so it can
 * sit unconditionally at the top of the app's z-stack.
 *
 * On desktop the video arrives through a SwingPanel, and Compose only draws over
 * an interop surface when `compose.interop.blending` is enabled — see the property
 * set in the desktop entry point. Without it the controls below are invisible even
 * though they are composed.
 */
@Composable
fun PlayerLayer(
    session: PlaybackSession,
    modifier: Modifier = Modifier,
) {
    val request = session.request ?: return
    val phase = session.phase ?: return
    // Inline sessions are drawn by the page that started them; this layer covers
    // the window and would bury the sheet the video is embedded in.
    if (session.presentation != PlaybackPresentation.Fullscreen) return
    val host = LocalVideoPlayerHost.current
    val fullscreen = LocalFullscreenController.current
    // Resolved to a flow first so collectAsState is called unconditionally: a
    // composable behind a null-check is only safe while the host never changes,
    // which is a property of the composition local, not of this code.
    val statusFlow = remember(host) { host?.status ?: MutableStateFlow(PlaybackStatus()) }
    val status by statusFlow.collectAsState()

    PlaybackBackHandler(enabled = true) {
        if (request.extra != null) session.collapseToInline() else session.close()
    }

    val focusRequester = remember { FocusRequester() }
    // Re-requested on every phase change, not once on mount. The layer composes while
    // sources are still being resolved, and the request can land before the node is
    // attached or be taken by whatever was focused on the page underneath — either
    // way the keys then go nowhere until something inside the player is clicked,
    // which is not a thing anyone should have to discover. Also re-requested on any
    // press below, so a click anywhere over the player hands the keyboard back.
    val takeFocus = { runCatching { focusRequester.requestFocus() }.let { } }
    LaunchedEffect(phase) { takeFocus() }

    // Controls fade out while the pointer is still, and any movement brings them
    // back. Tracked as a counter rather than a boolean so each movement restarts
    // the timer instead of extending a single pending one.
    // Reset to Fit for every session: the layer leaves the composition when
    // playback closes, so a previous title's crop never carries over.
    var scaling by remember { mutableStateOf(VideoScaling.Fit) }
    var activityPulse by remember { mutableStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }
    // Set while a picker is open or the pointer is on the bar. Pointer movement
    // inside a dropdown happens in a popup of its own and never reaches this
    // layer's handler, so without this the controls time out from under an open
    // menu while it is being read.
    var controlsHeld by remember { mutableStateOf(false) }

    // What a tap means depends on how the picture looked when the finger landed,
    // and on whether it was a finger at all — both are recorded at press time,
    // because the press itself wakes the controls long before the tap resolves.
    var pressWasTouch by remember { mutableStateOf(false) }
    var controlsShownAtPress by remember { mutableStateOf(true) }

    // Nothing here can drive a file that has not opened yet, and the phase turns Playing
    // the moment the URL is handed over — many seconds before the first frame on a
    // torrent. Everything that acts on the player is gated on this.
    val start = rememberPlaybackStart(
        status,
        request.media.id,
        request.season,
        request.episode,
    )

    val settings = (LocalAppGraph.current.settings.settings.value as? SettingsState.Ready)?.settings
    val segments = remember(session.timestamps, status.chapters, status.durationSeconds) {
        playbackSegments(session.timestamps, status.chapters, status.durationSeconds)
    }
    val currentSegment = segmentAt(status.positionSeconds, segments)
    val seekStep = settings?.seekStepSeconds?.takeIf { it > 0.0 } ?: SEEK_STEP_SECONDS

    // Transient on-screen replies. Each is a piece of state plus a timestamp, because
    // they all answer "something just happened" and have to expire on their own.
    var seekFeedback by remember { mutableStateOf<SeekFeedback?>(null) }
    // Monotonic: this measures a gap between two presses, and a wall clock that the
    // system adjusts underneath it would measure something else.
    var lastSeekAt by remember { mutableStateOf<TimeMark?>(null) }
    var volumePulse by remember { mutableStateOf(0) }
    var transportPulse by remember { mutableStateOf(0) }
    var statsVisible by remember { mutableStateOf(false) }
    var shortcutsVisible by remember { mutableStateOf(false) }

    // Every command the player takes passes through here, and none of them is a
    // question a file that is still opening can answer: there is no position to seek,
    // no track to choose, nothing to pause. Gating the commands rather than the keys
    // that carry them is what leaves the keys falling through as before — Tab and
    // Enter still reach the Cancel button on the opening stage. Returns true so a key
    // branch can be written as one call: the press was ours whether or not it did
    // anything, and must not also reach the page underneath.
    val onPlayer: (() -> Unit) -> Boolean = { action ->
        if (start.started) action()
        true
    }

    // One place every jump goes through, so the burst on screen and the seek sent to
    // the player can never disagree about what happened.
    val jump: (Double) -> Unit = { delta ->
        onPlayer {
            seekFeedback = accumulateSeekFeedback(
                current = seekFeedback,
                deltaSeconds = delta,
                withinWindow = lastSeekAt?.elapsedNow()
                    ?.let { it < SEEK_FEEDBACK_WINDOW_MILLIS.milliseconds } == true,
            )
            lastSeekAt = TimeSource.Monotonic.markNow()
            host?.seekRelative(delta)
        }
    }

    val changeVolume: (Double) -> Unit = { delta ->
        onPlayer {
            host?.setVolume((status.volume + delta).coerceIn(0.0, 100.0))
            volumePulse++
        }
    }

    val toggleTransport: () -> Unit = {
        onPlayer {
            host?.togglePause()
            transportPulse++
        }
    }

    // The burst clears itself; nothing else is watching to take it away.
    LaunchedEffect(seekFeedback?.id) {
        if (seekFeedback != null) {
            delay(SEEK_FEEDBACK_WINDOW_MILLIS.milliseconds)
            seekFeedback = null
        }
    }

    // Skipped segments are remembered for the episode so a viewer who seeks back
    // into an intro on purpose is not immediately thrown out of it again.
    val skipped = remember(request.season, request.episode, request.media.id) {
        mutableSetOf<String>()
    }
    LaunchedEffect(
        currentSegment,
        status.positionSeconds,
        status.interrupted,
        session.reconnecting,
        session.recoveryFailed,
        settings,
    ) {
        if (status.interrupted || session.reconnecting || session.recoveryFailed) return@LaunchedEffect
        val segment = currentSegment ?: return@LaunchedEffect
        val preferences = settings ?: return@LaunchedEffect
        if (!preferences.skipsAutomatically(segment.kind)) return@LaunchedEffect
        if (!skipped.add(segment.identity())) return@LaunchedEffect
        skipTarget(segment, status.positionSeconds, status.durationSeconds)
            ?.let { host?.seek(it) }
    }
    // A long open outlasts the auto-hide timer, so by the time there is something to
    // control the timer has usually run out on a bar that was never on screen. One pulse
    // puts it up at the moment it becomes worth showing, and restarts the timer with it.
    LaunchedEffect(start.started) {
        if (start.started) activityPulse++
    }
    LaunchedEffect(activityPulse, phase, controlsHeld) {
        controlsVisible = true
        if (phase is PlaybackPhase.Playing && !controlsHeld) {
            delay(CONTROLS_HIDE_DELAY_MILLIS.milliseconds)
            controlsVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Any key counts as activity, so the controls come back for the
                // keyboard the same way they do for the mouse.
                val handled = when (event.key) {
                    Key.Escape -> {
                        // Leaves fullscreen first if it is on, so one Escape does
                        // not both un-maximise and abandon what you were watching.
                        // An extra has one more step below that: it came from a
                        // slot on the details sheet, and going back there is not
                        // the same as giving up on it.
                        when {
                            shortcutsVisible -> shortcutsVisible = false
                            fullscreen?.isFullscreen?.value == true -> fullscreen.toggle()
                            request.extra != null ->
                                session.collapseToInline()

                            else -> session.close()
                        }
                        true
                    }
                    Key.Spacebar, Key.K -> { toggleTransport(); true }
                    Key.F -> { fullscreen?.toggle(); true }
                    Key.M -> onPlayer {
                        host?.setMuted(!status.muted); volumePulse++
                    }
                    // Relative, not position + step: the position here is whatever the
                    // last poll reported, so two presses inside one interval would both
                    // start from the same place and collapse into a single jump. The
                    // host tracks the seek it has not yet arrived at; this cannot.
                    Key.DirectionRight, Key.L -> {
                        jump(if (event.isShiftPressed) FINE_SEEK_SECONDS else seekStep); true
                    }
                    Key.DirectionLeft, Key.J -> {
                        jump(if (event.isShiftPressed) -FINE_SEEK_SECONDS else -seekStep); true
                    }
                    Key.DirectionUp -> { changeVolume(VOLUME_STEP); true }
                    Key.DirectionDown -> { changeVolume(-VOLUME_STEP); true }
                    // Frame stepping only makes sense against a still picture, and
                    // mpv pauses itself on the first step anyway.
                    Key.Comma -> onPlayer { host?.stepFrame(-1) }
                    Key.Period -> onPlayer { host?.stepFrame(1) }
                    Key.LeftBracket -> onPlayer { host?.setSpeed(stepSpeed(status.speed, -1)) }
                    Key.RightBracket -> onPlayer { host?.setSpeed(stepSpeed(status.speed, 1)) }
                    Key.Backspace -> onPlayer { host?.setSpeed(1.0) }
                    Key.PageUp -> onPlayer { host?.stepChapter(-1) }
                    Key.PageDown -> onPlayer { host?.stepChapter(1) }
                    Key.MoveHome -> onPlayer { host?.seek(0.0) }
                    // The clamp inside the host keeps this clear of the last frame,
                    // which is the whole reason End is safe to offer at all.
                    Key.MoveEnd -> onPlayer { host?.seek(status.durationSeconds) }
                    Key.C -> onPlayer { host?.selectSubtitleTrack(cycleTrack(status.subtitleTracks, status.selectedSubtitleId, allowOff = true)) }
                    Key.A -> onPlayer {
                        cycleTrack(status.audioTracks, status.selectedAudioId, allowOff = false)
                            ?.let { host?.selectAudioTrack(it) }
                    }
                    Key.S -> onPlayer { host?.takeScreenshot() }
                    Key.I -> { statsVisible = !statsVisible; true }
                    // Bound to the key rather than to the shifted character. `?` is
                    // Shift+/ on some layouts, AltGr+something on others, and its own
                    // key on a few; plain / does nothing else here, so accepting both
                    // is one fewer layout to be wrong about.
                    Key.Slash -> { shortcutsVisible = !shortcutsVisible; true }
                    else -> percentJumpFor(event.key)?.let { fraction ->
                        onPlayer {
                            if (status.durationSeconds > 0.0) {
                                host?.seek(status.durationSeconds * fraction)
                            }
                        }
                    } ?: false
                }
                if (handled) activityPulse++
                handled
            }

            // Blank while the controls are gone: an arrow parked over the picture is
            // the one thing on screen that cannot be part of the film. Applied to the
            // whole layer rather than to the video surface so it also covers the
            // margins, and so nothing drawn over the video reinstates the arrow.
            .hideCursorWhen(!controlsVisible && !controlsHeld)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var lastPosition: Offset? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        // Any press hands the keyboard back to the player, whatever
                        // had it before.
                        if (event.type == PointerEventType.Press) {
                            takeFocus()
                            pressWasTouch = event.changes.any {
                                it.type == PointerType.Touch || it.type == PointerType.Stylus
                            }
                            controlsShownAtPress = controlsVisible || controlsHeld
                            // A phone has no hover movement to wake hidden controls.
                            // Treat the touch itself as activity before the tap is
                            // interpreted as pause, seek, or another player action.
                            activityPulse++
                        }
                        when (event.type) {
                            PointerEventType.Move -> {
                                // Only real movement counts — see pointerMovedEnough.
                                val position = event.changes.lastOrNull()?.position
                                if (position != null &&
                                    pointerMovedEnough(lastPosition, position)
                                ) {
                                    lastPosition = position
                                    activityPulse++
                                }
                            }
                            // Wheel over the picture is volume, the convention
                            // every desktop player follows. Handled here rather
                            // than with onPointerEvent, which is desktop-only and
                            // would not compile for the Android target. Scroll
                            // deltas are inverted: up is negative.
                            PointerEventType.Scroll -> {
                                val scrolled = event.changes
                                    .firstOrNull()
                                    ?.scrollDelta
                                    ?.y
                                    ?: 0f
                                if (scrolled != 0f) {
                                    activityPulse++
                                    changeVolume(-scrolled * VOLUME_STEP)
                                }
                            }
                        }
                    }
                }
            },
    ) {
        // Mounted only once there is video to show. The mpv surface is an opaque
        // AWT interop panel, and Compose composites over interop only when
        // compose.interop.blending is on — so anything drawn beneath it during
        // Resolving would be an unexplained black rectangle rather than a
        // progress spinner. Every pre-playback state below is pure Compose and
        // therefore always visible and always cancellable.
        if (host != null && phase is PlaybackPhase.Playing) {
            host.Surface(
                Modifier
                    .fillMaxSize()
                    .pointerInput(seekStep) {
                        detectTapGestures(
                            // detectTapGestures resolves this itself: onTap only
                            // fires once the double-tap window has closed, so a
                            // double tap never pauses on its way past.
                            onTap = {
                                if (tapTogglesPause(pressWasTouch, controlsShownAtPress)) {
                                    toggleTransport()
                                }
                            },
                            onDoubleTap = { offset ->
                                when {
                                    offset.x < size.width * EDGE_TAP_FRACTION -> jump(-seekStep)
                                    offset.x > size.width * (1f - EDGE_TAP_FRACTION) -> jump(seekStep)
                                    // Framing the window is not a player command, so unlike
                                    // the two above it works while the file is still opening.
                                    else -> fullscreen?.toggle()
                                }
                            },
                        )
                    },
            )
        }

        // Everything before the first frame sits on the title's own artwork
        // rather than on flat black, which read as an error dialog.
        if (phase !is PlaybackPhase.Playing || !status.hasMedia) {
            PlayerBackdrop(backdropUrl = request.media.backdropUrl)
        }

        when (phase) {
            is PlaybackPhase.Resolving -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                StagePanel(
                    media = request.media,
                    title = request.label,
                    headline = "Finding sources",
                    detail = "Asking every enabled source provider.",
                    onCancel = session::close,
                )
            }

            is PlaybackPhase.Choosing -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PanelEntrance {
                        StreamSourcePicker(
                            sources = phase.sources,
                            onSelect = session::choose,
                            title = request.label,
                        )
                    }
                    Box(modifier = Modifier.padding(top = 16.dp)) {
                        TextAction(label = "Cancel", onClick = session::close)
                    }
                }
            }

            is PlaybackPhase.Failed -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                PanelEntrance {
                    Surface(
                        modifier = Modifier.widthIn(max = 460.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
                        shadowElevation = 18.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                IconifyIcon(
                                    icon = "lucide:triangle-alert",
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                            Text(
                                text = "Nothing to play",
                                modifier = Modifier.padding(top = 14.dp),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = phase.message,
                                modifier = Modifier.padding(top = 6.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                            )
                            Row(
                                modifier = Modifier.padding(top = 18.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                TextAction(label = "Try again", onClick = session::retry)
                                TextAction(label = "Close", onClick = session::close)
                            }
                        }
                    }
                }
            }

            is PlaybackPhase.Playing -> {
                // mpv reports no media until the stream opens, which for a torrent
                // means waiting on the first pieces. Without this the window is
                // simply black for as long as that takes. Anything that goes wrong
                // after the first frame is the error banner's to report, which is why
                // this asks the latch rather than the live status alone.
                if (!status.hasMedia && !start.opened) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        StartingStage(
                            media = request.media,
                            title = request.label,
                            source = phase.source,
                            status = status,
                            torrent = rememberTorrentProgress(session, phase.source),
                            // Reopening the source list is meaningless for an
                            // extra, which has exactly one address; reloading it
                            // is the only recovery there is.
                            onRetry = {
                                if (request.extra != null) {
                                    session.retry()
                                } else {
                                    session.reopenSources()
                                }
                            },
                            onCancel = session::close,
                        )
                    }
                }

                // Feedback for things that leave no other trace. Placed above the
                // controls in the stack so a burst is never half-hidden behind them.
                seekFeedback?.let { feedback ->
                    SeekBurst(
                        feedback = feedback,
                        modifier = Modifier.align(
                            if (feedback.forward) Alignment.CenterEnd else Alignment.CenterStart,
                        ),
                    )
                }

                // Keyed on the pulse rather than made visible: it plays once and
                // removes itself, and pressing again mid-fade restarts it cleanly.
                key(transportPulse) {
                    if (transportPulse > 0) {
                        TransportPulse(
                            paused = status.paused,
                            pulseId = transportPulse,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }

                var volumeShown by remember { mutableStateOf(false) }
                LaunchedEffect(volumePulse) {
                    if (volumePulse > 0) {
                        volumeShown = true
                        delay(VOLUME_OVERLAY_MILLIS.milliseconds)
                        volumeShown = false
                    }
                }
                AnimatedVisibility(
                    visible = volumeShown,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 96.dp),
                    enter = fadeIn(tween(110)) + scaleIn(tween(140), initialScale = 0.9f),
                    exit = fadeOut(tween(220)),
                ) {
                    VolumeOverlay(volume = status.volume, muted = status.muted)
                }

                // A stall after playback has started, which nothing reported before:
                // the starting stage covers the wait for the first frame and then
                // never comes back, so a torrent running dry mid-episode simply froze.
                AnimatedVisibility(
                    visible = status.waitingForData && status.hasMedia && !session.reconnecting,
                    modifier = Modifier.align(Alignment.Center),
                    enter = fadeIn(tween(220)),
                    exit = fadeOut(tween(160)),
                ) {
                    StallIndicator(bufferingPercent = status.bufferingPercent)
                }

                AnimatedVisibility(
                    visible = session.reconnecting,
                    modifier = Modifier.align(Alignment.Center),
                    enter = fadeIn(tween(160)),
                    exit = fadeOut(tween(160)),
                ) {
                    StallIndicator(bufferingPercent = 0, label = "Reconnecting…")
                }

                AnimatedVisibility(
                    visible = statsVisible,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 26.dp, top = 96.dp),
                    enter = fadeIn(tween(140)) + slideInHorizontally { -it / 4 },
                    exit = fadeOut(tween(160)),
                ) {
                    PlayerStatsOverlay(status = status)
                }

                AnimatedVisibility(
                    visible = shortcutsVisible,
                    modifier = Modifier.align(Alignment.Center),
                    enter = fadeIn(tween(140)) + scaleIn(tween(180), initialScale = 0.94f),
                    exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.96f),
                ) {
                    ShortcutSheet()
                }

                if ((status.interrupted || session.recoveryFailed) && !session.reconnecting) {
                    PlaybackInterruptionBanner(
                        message = "The stream stopped before the end.",
                        onRetry = session::retryCurrentSource,
                        onPickSource = (session::reopenSources).takeUnless { request.extra != null },
                        onClose = session::close,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 78.dp),
                    )
                }

                // Deliberately not gated on hasMedia: a source that fails to open never loads
                // any, and that is exactly when the viewer most needs the banner — it carries
                // the only route to the next source. Requiring media meant an unplayable link
                // left the player sitting on the backdrop with no error and no way forward.
                status.error?.takeIf {
                    !status.interrupted && !session.recoveryFailed
                }?.let { error ->
                    PlaybackErrorBanner(
                        message = error,
                        // Both actions collapse onto a reload for an extra, for
                        // the same reason: there is no second source to walk to.
                        onTryNextSource = {
                            if (request.extra != null) {
                                session.retry()
                                true
                            } else {
                                session.failoverToNextSource()
                            }
                        },
                        onPickSource = {
                            if (request.extra != null) {
                                session.retry()
                            } else {
                                session.reopenSources()
                            }
                        },
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 78.dp),
                    )
                }

                // Title block and window actions ride with the controls: they are
                // chrome over the picture, and should leave together.
                AnimatedVisibility(
                    visible = controlsVisible,
                    modifier = Modifier.align(Alignment.TopStart),
                    enter = fadeIn(tween(140)) + slideInVertically { -it / 3 },
                    exit = fadeOut(tween(200)) + slideOutVertically { -it / 3 },
                ) {
                    PlayerTitleBlock(
                        title = request.media.title ?: request.media.name ?: "Untitled",
                        episode = request.episodeSubtitle(),
                        onBack = session::close,
                    )
                }

                AnimatedVisibility(
                    visible = controlsVisible,
                    modifier = Modifier.align(Alignment.TopEnd).padding(22.dp),
                    enter = fadeIn(tween(140)) + slideInVertically { -it / 3 },
                    exit = fadeOut(tween(200)) + slideOutVertically { -it / 3 },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Only for an extra: it has a slot on the page waiting for
                        // it, which the film and its episodes do not.
                        if (request.extra != null) {
                            ControlButton(
                                icon = "lucide:picture-in-picture-2",
                                onClick = {
                                    session.collapseToInline()
                                },
                            )
                        }
                        fullscreen?.let { controller ->
                            val isFullscreen by controller.isFullscreen.collectAsState()
                            ControlButton(
                                icon = if (isFullscreen) {
                                    "lucide:minimize"
                                } else {
                                    "lucide:maximize"
                                },
                                onClick = controller::toggle,
                            )
                        }
                    }
                }

                session.resumedFrom?.let { resumed ->
                    ResumeNotice(
                        seconds = resumed,
                        onStartOver = {
                            session.acknowledgeResume()
                            host?.seek(0.0)
                        },
                        onDismiss = session::acknowledgeResume,
                        modifier = Modifier.align(Alignment.BottomStart)
                            .padding(start = 30.dp, bottom = 118.dp),
                    )
                }

                // At the end of an episode, what comes next. Shown whether or not
                // autoplay is on: with it off this is the way to continue, with
                // it on it is the way to stop it happening.
                val atEnd = !status.interrupted && !session.reconnecting &&
                    !session.recoveryFailed && showUpNext(
                    positionSeconds = status.positionSeconds,
                    durationSeconds = status.durationSeconds,
                    segments = segments,
                    endReached = status.endReached,
                )
                val upNext = remember(atEnd, request.season, request.episode, request.media.id) {
                    if (!atEnd) null
                    else request.season?.let { season ->
                        request.episode?.let { number ->
                            nextEpisodeAfter(request.media.seasons, season, number)
                        }
                    }
                }
                upNext?.let { (nextSeason, nextEpisode) ->
                    UpNextCard(
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
                        onDismiss = session::close,
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .padding(end = 30.dp, bottom = 118.dp),
                    )
                }

                // Offered only where auto-skip is off: with it on the segment is
                // already gone, and a button for it would never be reachable.
                val manualSkip = currentSegment?.takeIf { segment ->
                    settings?.skipsAutomatically(segment.kind) != true &&
                        skipTarget(segment, status.positionSeconds, status.durationSeconds) != null
                }
                AnimatedVisibility(
                    visible = manualSkip != null,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 30.dp, bottom = 118.dp),
                    enter = fadeIn(tween(160)) + slideInVertically { it / 2 },
                    exit = fadeOut(tween(160)) + slideOutVertically { it / 2 },
                ) {
                    manualSkip?.let { segment ->
                        SkipSegmentButton(
                            label = segment.kind.skipLabel(),
                            remainingFraction = segmentRemaining(segment, status.positionSeconds),
                            onClick = {
                                skipped.add(segment.identity())
                                skipTarget(
                                    segment,
                                    status.positionSeconds,
                                    status.durationSeconds,
                                )?.let { host?.seek(it) }
                            },
                        )
                    }
                }

                AnimatedVisibility(
                    // Nothing to show until there is something to control: a transport
                    // over a file that has not opened has no position to draw, no tracks
                    // to list and nothing to pause.
                    visible = controlsVisible && start.started,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn(tween(120)) + slideInVertically { it / 4 },
                    exit = fadeOut(tween(220)) + slideOutVertically { it / 4 },
                ) {
                    PlayerControls(
                        title = request.label,
                        status = status,
                        segments = segments,
                        onTogglePause = toggleTransport,
                        onSeek = { seconds -> onPlayer { host?.seek(seconds) } },
                        onShowShortcuts = { shortcutsVisible = !shortcutsVisible },
                        onSetVolume = { host?.setVolume(it) },
                        onSetMuted = { host?.setMuted(it) },
                        onSelectAudio = { host?.selectAudioTrack(it) },
                        onSelectSubtitle = { host?.selectSubtitleTrack(it) },
                        onSetSubtitleDelay = { host?.setSubtitleDelay(it) },
                        onSetAudioDelay = { host?.setAudioDelay(it) },
                        scaling = scaling,
                        onSelectScaling = {
                            scaling = it
                            host?.setScaling(it)
                        },
                        onSelectSpeed = { host?.setSpeed(it) },
                        // An extra came with its own URL; there is no list of
                        // alternatives behind it to change to.
                        canChangeSource = request.extra == null,
                        onChangeSource = session::reopenSources,
                        episodeBrowser = request.episodeBrowser(
                            session = session,
                            playingProgress = status.progressFraction,
                        ),
                        onInteractingChange = { controlsHeld = it },
                    )
                }
            }
        }
    }
}

/**
 * Scales a panel up as it appears. Panels arrive over a still backdrop, and a
 * hard cut makes them look like a dialog that was already there.
 */
@Composable
private fun PanelEntrance(content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "PanelEntrance",
    )

    Box(
        modifier = Modifier.graphicsLayer {
            alpha = progress.coerceIn(0f, 1f)
            scaleX = 0.94f + 0.06f * progress
            scaleY = 0.94f + 0.06f * progress
        },
    ) {
        content()
    }
}

/**
 * The shared pre-playback stage: no card, just the title's logo glowing on its
 * own backdrop with a line of status under it.
 *
 * A panel here would be a box drawn over artwork that is already the subject;
 * letting the logo carry it keeps the screen feeling like part of the title
 * rather than a dialog interrupting it.
 */
@Composable
private fun StagePanel(
    media: Media,
    title: String,
    headline: String,
    detail: String,
    onCancel: () -> Unit,
    progress: Float? = null,
    tags: List<String> = emptyList(),
    problem: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    PanelEntrance {
        Column(
            modifier = Modifier.widthIn(max = 560.dp).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PulsingLogo(
                logoUrl = media.logoUrl,
                title = media.title ?: media.name ?: "Untitled",
                modifier = Modifier.size(width = 340.dp, height = 108.dp),
            )

            Text(
                text = headline,
                modifier = Modifier.padding(top = 22.dp),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = title,
                modifier = Modifier.padding(top = 5.dp),
                color = Color.White.copy(alpha = 0.66f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (tags.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tags.forEach { StageTag(it) }
                }
            }

            // A determinate bar once the player reports real buffer progress; an
            // indeterminate one until then, so the two never contradict.
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .padding(top = 18.dp)
                        .fillMaxWidth(0.7f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = Color.White.copy(alpha = 0.16f),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .padding(top = 18.dp)
                        .fillMaxWidth(0.7f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = Color.White.copy(alpha = 0.16f),
                    gapSize = 0.dp,
                )
            }

            Text(
                text = detail,
                modifier = Modifier.padding(top = 12.dp),
                color = Color.White.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )

            problem?.let { message ->
                Row(
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .background(
                            MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
                            RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconifyIcon(
                        icon = "lucide:triangle-alert",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = message,
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Row(
                modifier = Modifier.padding(top = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                onRetry?.let { TextAction(label = "Pick another", onClick = it) }
                TextAction(label = "Cancel", onClick = onCancel)
            }
        }
    }
}

@Composable
private fun StageTag(text: String) {
    Box(
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(7.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.82f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
internal fun TextAction(label: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else if (hovered) 1.04f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "TextActionScale",
    )
    val container by animateColorAsState(
        targetValue = Color.White.copy(alpha = if (hovered) 0.2f else 0.11f),
        animationSpec = tween(140),
        label = "TextActionContainer",
    )

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(color = container, shape = RoundedCornerShape(10.dp))
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * Steps the playback rate through the same ladder the speed menu offers, so the
 * keyboard and the menu cannot end up disagreeing about what rates exist.
 */
private fun stepSpeed(current: Double, direction: Int): Double {
    val nearest = SPEED_STEPS.withIndex().minBy { (_, step) -> abs(step - current) }.index
    return SPEED_STEPS[(nearest + direction).coerceIn(0, SPEED_STEPS.lastIndex)]
}

/**
 * The next track to select, wrapping round the end of the list.
 *
 * Returns null to mean "off", which is only reachable when [allowOff] — cycling audio
 * into silence would leave no way back except the menu, whereas subtitles off is a
 * state people cycle to on purpose.
 */
private fun cycleTrack(
    tracks: List<MediaTrack>,
    selectedId: Int?,
    allowOff: Boolean,
): Int? {
    if (tracks.isEmpty()) return selectedId
    val ids: List<Int?> = if (allowOff) tracks.map { it.id } + null else tracks.map { it.id }
    val next = ids.indexOf(selectedId) + 1
    return ids[if (next in ids.indices) next else 0]
}

/** 1 jumps a tenth in, 9 nine tenths in, 0 back to the start — as every player does. */
private fun percentJumpFor(key: Key): Double? = when (key) {
    Key.Zero -> 0.0
    Key.One -> 0.1
    Key.Two -> 0.2
    Key.Three -> 0.3
    Key.Four -> 0.4
    Key.Five -> 0.5
    Key.Six -> 0.6
    Key.Seven -> 0.7
    Key.Eight -> 0.8
    Key.Nine -> 0.9
    else -> null
}

/** How much of a labelled stretch is still ahead of the playhead, as 0..1. */
private fun segmentRemaining(segment: LabelledSegment, positionSeconds: Double): Float {
    val length = segment.endSeconds - segment.startSeconds
    if (length <= 0.0) return 0f
    return ((segment.endSeconds - positionSeconds) / length).coerceIn(0.0, 1.0).toFloat()
}

private val SPEED_STEPS = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)

/**
 * Shown when the player runs out of data after playback has begun.
 *
 * Deliberately smaller and quieter than the opening stage: this is an interruption to
 * something already underway, not a fresh start, and the picture behind it is still
 * the last frame of the episode rather than a backdrop.
 */
@Composable
private fun StallIndicator(bufferingPercent: Int, label: String? = null) {
    val spin = rememberInfiniteTransition(label = "Stall")
    val sweep by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
        ),
        label = "StallSweep",
    )

    Column(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconifyIcon(
            icon = "lucide:loader-circle",
            modifier = Modifier.size(26.dp).graphicsLayer { rotationZ = sweep },
            tint = Color.White,
        )
        Text(
            text = label ?: if (bufferingPercent in 1..99) {
                "Buffering · $bufferingPercent%"
            } else {
                "Buffering"
            },
            modifier = Modifier.padding(top = 10.dp),
            color = Color.White.copy(alpha = 0.86f),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private const val CONTROLS_HIDE_DELAY_MILLIS = 3_000L
/** Long enough to read the number, short enough not to sit over the picture. */
private const val VOLUME_OVERLAY_MILLIS = 1_100L
/** How much of each edge counts as a "seek" double-tap rather than a fullscreen one. */
private const val EDGE_TAP_FRACTION = 0.3f
private const val SEEK_STEP_SECONDS = 10.0
/** Shift-arrow: for lining up a subtitle or finding the exact frame something happens. */
private const val FINE_SEEK_SECONDS = 1.0
private const val VOLUME_STEP = 5.0
// Long enough to read the card and stop it, short enough not to feel stuck.
private const val AUTOPLAY_COUNTDOWN_SECONDS = 8
/** When the countdown starts pulsing, because stopping it is about to stop being possible. */
private const val UP_NEXT_URGENT_SECONDS = 3
private const val TORRENT_POLL_MILLIS = 1500L
private const val RESUME_NOTICE_MILLIS = 7000L
// A remote mkv with many tracks routinely needs ten seconds of probing
// before mpv reports anything, so patience here is normal, not a fault.
private const val STALLED_SECONDS = 45

/**
 * The gap between choosing a source and the first frame.
 *
 * Previously this said "Starting playback" and nothing else, so a source that was
 * never going to work looked exactly like one that was two seconds away. It now
 * reports what the player is actually doing: how full its buffer is, whether it
 * is stalled waiting for data, how long it has been trying, and any error mpv has
 * raised — with a way out that does not mean abandoning the title.
 */
@Composable
private fun StartingStage(
    media: Media,
    title: String,
    source: StreamSource,
    status: PlaybackStatus,
    torrent: TorrentProgress?,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    var elapsed by remember(source) { mutableStateOf(0) }
    LaunchedEffect(source) {
        elapsed = 0
        while (true) {
            delay(1000)
            elapsed++
        }
    }

    val buffering = status.bufferingPercent
    val headline = when {
        status.error != null -> "This source is not responding"
        torrent != null && torrent.peers == 0 -> "Looking for peers"
        status.waitingForData -> "Waiting for data"
        buffering > 0 || torrent != null -> "Buffering"
        else -> "Opening the file"
    }

    // Only a real buffer reading drives the determinate bar. mpv reports no
    // buffering figure at all until its demuxer is up — the property is simply
    // unavailable — so anything else here would be an invented number.
    val progress = when {
        torrent != null && torrent.totalBytes > 0 ->
            (torrent.downloadedBytes.toDouble() / torrent.totalBytes).toFloat()

        buffering > 0 -> buffering / 100f
        else -> null
    }

    val detail = buildString {
        when {
            // A torrent knows far more about itself than mpv does at this point:
            // peers and rate say whether anything is coming at all, where mpv's
            // buffer figure is simply unavailable until its demuxer is up.
            torrent != null -> {
                append("${torrent.peers} peers · ${formatBytes(torrent.downloadRate.toLong())}/s")
            }

            buffering > 0 -> append("Buffered $buffering%")
            // mpv's own log is the only running commentary during an open.
            status.statusMessage.isNotBlank() -> append(status.statusMessage.take(90))
            else -> append("Reading the stream")
        }
        append(" · ")
        append(formatDuration(elapsed.toDouble()))
    }

    StagePanel(
        media = media,
        title = title,
        headline = headline,
        detail = detail,
        onCancel = onCancel,
        progress = progress,
        tags = buildList {
            source.qualityLabel()?.let(::add)
            if (source.sizeBytes > 0) add(formatBytes(source.sizeBytes))
            add(if (source.url.isNullOrBlank()) "Torrent" else "Direct")
            source.addonName?.takeIf { it.isNotBlank() }?.let(::add)
        },
        problem = status.error
            ?: "No peers have any of this file. It is unlikely to start."
                .takeIf { torrent != null && torrent.peers == 0 && elapsed >= STALLED_SECONDS }
            ?: "Still no data. A dead link can sit here forever."
                .takeIf { torrent == null && elapsed >= STALLED_SECONDS },
        onRetry = onRetry,
    )
}

/**
 * What is playing, in the corner the eye already goes to.
 *
 * Two lines rather than one string: the title stays legible at a glance and the
 * episode is available without competing with it.
 */
@Composable
private fun PlayerTitleBlock(title: String, episode: String?, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(start = 22.dp, end = 60.dp, top = 20.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A back arrow next to what you are watching, rather than a bare X over
        // the picture. It rides with the rest of the chrome, so it is only ever
        // present while the controls are.
        ControlButton(icon = "iconamoon:arrow-left-1", onClick = onBack)

        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            episode?.let {
                Text(
                    text = it,
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "S2E4 · Episode name", or null for a film. */
private fun PlaybackRequest.episodeSubtitle(): String? {
    // For an extra the title block reads "Dune" over "Official Trailer", which is
    // the same shape as a series reading "Breaking Bad" over "S1E1".
    extra?.let { return it.title }
    val season = season ?: return null
    val number = episode ?: return null
    return listOfNotNull(
        "S${season}E$number",
        episodeTitle?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
}

/**
 * Gathers what the episode picker needs, or null for a film.
 *
 * Seasons come from the details payload, which only enriched media carries; a
 * title opened without it still browses the season being played.
 */
private fun PlaybackRequest.episodeBrowser(
    session: PlaybackSession,
    playingProgress: Float,
): EpisodeBrowser? {
    val playingSeason = season ?: return null
    val playingEpisode = episode ?: return null
    val seasons = media.seasons.map { it.number }
        .ifEmpty { listOf(playingSeason) }
        .sorted()

    return EpisodeBrowser(
        seasons = seasons,
        playingSeason = playingSeason,
        playingEpisode = playingEpisode,
        browsingSeason = session.browsingSeason ?: playingSeason,
        episodes = session.browsingEpisodes,
        playingProgress = playingProgress,
        onBrowseSeason = session::browseSeason,
        onPlayEpisode = { pickedSeason, pickedEpisode ->
            session.open(
                media = media,
                season = pickedSeason,
                episode = pickedEpisode.number,
                episodeTitle = pickedEpisode.title,
            )
        },
    )
}

/**
 * The "skip intro" affordance, shown while inside a segment the viewer has not
 * asked to skip automatically. Sits above the control bar so it stays reachable
 * whether or not the controls are showing.
 */
@Composable
private fun SkipSegmentButton(
    label: String,
    /** 0..1 of the segment still to run; the offer expires when it reaches zero. */
    remainingFraction: Float,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else if (hovered) 1.04f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "SkipButtonScale",
    )

    Box {
        Row(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = if (hovered) 0.88f else 0.72f))
                .hoverable(interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 18.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            IconifyIcon(
                icon = "lucide:skip-forward",
                modifier = Modifier.size(15.dp),
                tint = Color.White,
            )
        }

        // A line under the button that drains as the segment plays out, so how long
        // the offer stands is visible rather than something you find out by watching
        // it vanish. Drawn over the bottom edge, inside the same rounded corners.
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .fillMaxWidth(remainingFraction.coerceIn(0f, 1f))
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(Color.White.copy(alpha = 0.55f)),
        )
    }
}

/**
 * What plays next, offered when an episode finishes.
 *
 * The countdown only runs when autoplay is on. With it off the card is a button
 * and nothing more — an episode that ends should not start another one because
 * a card happened to appear.
 */
@Composable
private fun UpNextCard(
    season: Int,
    episode: Int,
    autoAdvance: Boolean,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var remaining by remember(season, episode, autoAdvance) {
        mutableStateOf(if (autoAdvance) AUTOPLAY_COUNTDOWN_SECONDS else 0)
    }
    var cancelled by remember(season, episode) { mutableStateOf(false) }

    LaunchedEffect(season, episode, autoAdvance, cancelled) {
        if (!autoAdvance || cancelled) return@LaunchedEffect
        while (remaining > 0) {
            delay(1000)
            remaining--
        }
        onPlay()
    }

    Surface(
        modifier = modifier.widthIn(max = 320.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        shadowElevation = 16.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Up next",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "S${season}E$episode",
                modifier = Modifier.padding(top = 3.dp),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            // The countdown as a bar rather than only a number in the button label.
            // Something is about to happen without being asked, and a shrinking line
            // reads at a glance where a digit has to be found and then read.
            if (autoAdvance && !cancelled) {
                val sweep by animateFloatAsState(
                    targetValue = remaining.toFloat() / AUTOPLAY_COUNTDOWN_SECONDS,
                    animationSpec = tween(durationMillis = 980, easing = LinearEasing),
                    label = "UpNextCountdown",
                )
                // The last few seconds pulse, because that is when stopping it stops
                // being optional.
                val urgency = rememberInfiniteTransition(label = "UpNextUrgency")
                val flash by urgency.animateFloat(
                    initialValue = 0.55f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 520, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "UpNextFlash",
                )
                val pressing = remaining in 1..UP_NEXT_URGENT_SECONDS

                Box(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(sweep.coerceIn(0f, 1f))
                            .height(3.dp)
                            .graphicsLayer { alpha = if (pressing) flash else 1f }
                            .background(MaterialTheme.colorScheme.tertiary),
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextAction(
                    label = if (autoAdvance && !cancelled && remaining > 0) {
                        "Play now · $remaining"
                    } else {
                        "Play"
                    },
                    onClick = onPlay,
                )
                TextAction(
                    label = if (autoAdvance && !cancelled) "Stop" else "Close",
                    onClick = {
                        if (autoAdvance && !cancelled) cancelled = true else onDismiss()
                    },
                )
            }
        }
    }
}

/**
 * Polls torrent progress while a torrent source is starting.
 *
 * Only for torrents, and only until the file opens: a direct link has nothing to
 * report, and once mpv is playing its own position is the better signal.
 */
@Composable
private fun rememberTorrentProgress(
    session: PlaybackSession,
    source: StreamSource,
): TorrentProgress? {
    val hash = source.infoHash?.takeIf { it.isNotBlank() && source.url.isNullOrBlank() }
        ?: return null
    val graph = LocalAppGraph.current
    var progress by remember(hash) { mutableStateOf<TorrentProgress?>(null) }

    LaunchedEffect(hash) {
        while (true) {
            progress = graph.playback.torrentProgress(hash)
            delay(TORRENT_POLL_MILLIS)
        }
    }
    return progress
}

/** Recovery after the one automatic same-source reconnect has also stopped. */
@Composable
private fun PlaybackInterruptionBanner(
    message: String,
    onRetry: () -> Unit,
    onPickSource: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 480.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.96f),
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IconifyIcon(
                    icon = "lucide:triangle-alert",
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = message,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextAction(label = "Retry", onClick = onRetry)
                onPickSource?.let { TextAction(label = "Sources", onClick = it) }
                TextAction(label = "Close", onClick = onClose)
            }
        }
    }
}

/**
 * A source failing mid-playback used to be a dead end: the error appeared and
 * the only way on was to close and start again. The other candidates are still
 * in hand, so stepping to the next one is one button.
 */
@Composable
private fun PlaybackErrorBanner(
    message: String,
    onTryNextSource: () -> Boolean,
    onPickSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Latched: once the walk runs out there is nothing left to offer, and a
    // button that silently does nothing is worse than no button.
    var exhausted by remember(message) { mutableStateOf(false) }

    Surface(
        modifier = modifier.widthIn(max = 460.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.96f),
        shadowElevation = 12.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconifyIcon(
                icon = "lucide:triangle-alert",
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = if (exhausted) "$message · no other source worked" else message,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextAction(
                label = if (exhausted) "Choose" else "Try next",
                onClick = {
                    if (exhausted) onPickSource() else if (!onTryNextSource()) exhausted = true
                },
            )
        }
    }
}

/**
 * Says where playback picked up, because resuming silently looks like the file
 * started in the wrong place.
 */
@Composable
private fun ResumeNotice(
    seconds: Double,
    onStartOver: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Long enough to read and act on, then out of the way on its own.
    LaunchedEffect(seconds) {
        delay(RESUME_NOTICE_MILLIS)
        onDismiss()
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        shadowElevation = 10.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Resumed from ${formatDuration(seconds)}",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium,
            )
            TextAction(label = "Start over", onClick = onStartOver)
        }
    }
}
