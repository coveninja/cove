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
import androidx.compose.foundation.gestures.detectDragGestures
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
import com.coveninja.cove.ui.platform.canLoadSubtitleFile
import com.coveninja.cove.ui.platform.chooseSubtitleFile
import com.coveninja.cove.ui.platform.hasPointerHover
import com.coveninja.cove.ui.platform.hideCursorWhen
import com.coveninja.cove.ui.platform.rememberScreenBrightness
import com.coveninja.cove.ui.platform.subtitleFileDropTarget
import com.coveninja.cove.ui.state.SUBTITLE_FILE_EXTENSIONS
import com.coveninja.cove.ui.state.subtitleFileName
import com.coveninja.cove.ui.state.subtitleFilesAmong
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.LocalVideoPlayerHost
import com.coveninja.cove.ui.state.MAX_VOLUME
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
import com.coveninja.cove.ui.state.SleepTimer
import com.coveninja.cove.ui.state.SleepTimerChoice
import com.coveninja.cove.ui.state.armSleepTimer
import com.coveninja.cove.ui.state.autoAdvanceAllowed
import com.coveninja.cove.ui.state.sleepTimerElapsed
import com.coveninja.cove.ui.state.tickSleepTimer
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
    // A request made while sources resolve can precede attachment, so retry on phase changes.
    val takeFocus = { runCatching { focusRequester.requestFocus() }.let { } }
    LaunchedEffect(phase) { takeFocus() }

    // A counter lets every pointer movement restart the hide timer.
    var scaling by remember { mutableStateOf(VideoScaling.Fit) }
    var activityPulse by remember { mutableStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }
    // Popup pointer events do not reach this layer, so an open picker must hold the controls.
    var controlsHeld by remember { mutableStateOf(false) }

    // Interpret taps using the state at press time, before the press wakes the controls.
    var pressWasTouch by remember { mutableStateOf(false) }
    var controlsShownAtPress by remember { mutableStateOf(true) }

    // Playing can precede the first frame, especially for torrents.
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

    var seekFeedback by remember { mutableStateOf<SeekFeedback?>(null) }
    // Double-tap timing must use a monotonic clock.
    var lastSeekAt by remember { mutableStateOf<TimeMark?>(null) }
    var volumePulse by remember { mutableStateOf(0) }
    var transportPulse by remember { mutableStateOf(0) }
    var statsVisible by remember { mutableStateOf(false) }
    var shortcutsVisible by remember { mutableStateOf(false) }
    var sleepTimer by remember { mutableStateOf(SleepTimer.Off) }
    // Not remembered across requests: a lock is about the hands holding the phone right now,
    // and arriving at the next episode unable to touch anything would be a bug report.
    var controlsLocked by remember(request.media.id, request.season, request.episode) {
        mutableStateOf(false)
    }
    val brightness = rememberScreenBrightness()
    var brightnessPulse by remember { mutableStateOf(0) }
    var lockNoticePulse by remember { mutableStateOf(0) }
    /** The rate to go back to when a press-and-hold ends; null when none is in progress. */
    var boostedFrom by remember { mutableStateOf<Double?>(null) }
    /** Where a drag-to-scrub would land, while one is under way. */
    var scrubTarget by remember { mutableStateOf<Double?>(null) }

    // The chrome goes away entirely while the screen is locked rather than staying up and
    // swallowing presses: a control that looks pressable and is not reads as a freeze. The
    // end-of-episode card is the deliberate exception — it is the one thing a viewer who
    // locked the screen to keep watching still needs to be able to stop.
    val chromeVisible = controlsVisible && !controlsLocked

    // Swipes belong to a finger. A mouse has the wheel for volume, a seek bar for position and
    // no business with the screen brightness, so offering it the same gestures would mean a
    // drag over the picture doing something surprising on every desktop.
    val gesturesEnabled = !hasPointerHover
    var subtitleDragActive by remember { mutableStateOf(false) }
    // One transient line for anything the player has to say in passing. It began as the
    // subtitle-drop result and is now also how a screenshot reports itself, which it never
    // did — the key was advertised in the shortcut sheet and the file went somewhere the
    // viewer was never told about.
    var playerNotice by remember { mutableStateOf<String?>(null) }
    var playerNoticePulse by remember { mutableStateOf(0) }
    val notify: (String) -> Unit = { message ->
        playerNotice = message
        playerNoticePulse++
        activityPulse++
    }

    // Use the same validation for drops and the file chooser, even before playback starts.
    val useSubtitleFiles: (List<String>) -> Unit = { paths ->
        val file = subtitleFilesAmong(paths).firstOrNull()
        notify(
            when {
                file != null && session.addUserSubtitle(file) -> "Using ${subtitleFileName(file)}"
                file != null -> "That subtitle file could not be loaded."
                // An unreadable drop is distinct from a readable, unsupported file.
                paths.isEmpty() -> "Cove could not read that drop — try Load subtitle file\u2026"
                else -> "That is not a subtitle file Cove can read."
            },
        )
    }

    LaunchedEffect(playerNoticePulse) {
        if (playerNotice != null) {
            delay(SUBTITLE_NOTICE_MILLIS.milliseconds)
            playerNotice = null
        }
    }

    // Gate player commands rather than keys so opening-state controls still receive input.
    // A handled player key must not also reach the page below, even when no command runs.
    val onPlayer: (() -> Unit) -> Boolean = { action ->
        if (start.started) action()
        true
    }

    // Keep seek feedback and the requested jump in sync.
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
            host?.setVolume((status.volume + delta).coerceIn(0.0, MAX_VOLUME))
            volumePulse++
        }
    }

    val toggleTransport: () -> Unit = {
        onPlayer {
            host?.togglePause()
            transportPulse++
        }
    }

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
    // The countdown only runs while something is actually playing: a timer that expired
    // during a twenty-minute buffering stall would stop a film the viewer never saw.
    LaunchedEffect(sleepTimer.choice, status.paused, phase) {
        if (sleepTimer.choice !is SleepTimerChoice.After) return@LaunchedEffect
        while (true) {
            delay(1_000)
            if (status.paused) continue
            sleepTimer = tickSleepTimer(sleepTimer, elapsedSeconds = 1)
            if (sleepTimerElapsed(sleepTimer)) {
                host?.setPaused(true)
                sleepTimer = SleepTimer.Off
                notify("Sleep timer — playback paused.")
                // Paused rather than closed: waking to a player still holding the place is
                // recoverable, and waking to a home screen is not.
                controlsVisible = true
                return@LaunchedEffect
            }
        }
    }

    // Restart auto-hide when the first frame makes the controls usable.
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
            .subtitleFileDropTarget(
                onDragChange = { subtitleDragActive = it },
                onFiles = useSubtitleFiles,
            )
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val handled = when (event.key) {
                    Key.Escape -> {
                        // Escape unwinds fullscreen and embedded playback before closing playback.
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
                    // The host accumulates relative seeks between status polls.
                    Key.DirectionRight, Key.L -> {
                        jump(if (event.isShiftPressed) FINE_SEEK_SECONDS else seekStep); true
                    }
                    Key.DirectionLeft, Key.J -> {
                        jump(if (event.isShiftPressed) -FINE_SEEK_SECONDS else -seekStep); true
                    }
                    Key.DirectionUp -> { changeVolume(VOLUME_STEP); true }
                    Key.DirectionDown -> { changeVolume(-VOLUME_STEP); true }
                    // Frame stepping is meaningful only while paused.
                    Key.Comma -> onPlayer { host?.stepFrame(-1) }
                    Key.Period -> onPlayer { host?.stepFrame(1) }
                    Key.LeftBracket -> onPlayer { host?.setSpeed(stepSpeed(status.speed, -1)) }
                    Key.RightBracket -> onPlayer { host?.setSpeed(stepSpeed(status.speed, 1)) }
                    Key.Backspace -> onPlayer { host?.setSpeed(1.0) }
                    Key.PageUp -> onPlayer { host?.stepChapter(-1) }
                    Key.PageDown -> onPlayer { host?.stepChapter(1) }
                    Key.MoveHome -> onPlayer { host?.seek(0.0) }
                    // The host clamps this clear of the final frame.
                    Key.MoveEnd -> onPlayer { host?.seek(status.durationSeconds) }
                    Key.C -> onPlayer {
                        val next = cycleTrack(
                            status.subtitleTracks,
                            status.selectedSubtitleId,
                            allowOff = true,
                        )
                        host?.selectSubtitleTrack(next)
                        session.rememberSubtitleChoice(
                            language = status.subtitleTracks.firstOrNull { it.id == next }?.language,
                            off = next == null,
                        )
                    }
                    Key.A -> onPlayer {
                        cycleTrack(status.audioTracks, status.selectedAudioId, allowOff = false)
                            ?.let { next ->
                                host?.selectAudioTrack(next)
                                session.rememberAudioLanguage(
                                    status.audioTracks.firstOrNull { it.id == next }?.language,
                                )
                            }
                    }
                    Key.S -> onPlayer {
                        host?.takeScreenshot()
                        notify("Screenshot saved.")
                    }
                    Key.I -> { statsVisible = !statsVisible; true }
                    // Bind the physical slash key because producing `?` varies by layout.
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

            // Hide the cursor over the whole layer, including video margins.
            .hideCursorWhen(!controlsVisible && !controlsHeld)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var lastPosition: Offset? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press) {
                            takeFocus()
                            pressWasTouch = event.changes.any {
                                it.type == PointerType.Touch || it.type == PointerType.Stylus
                            }
                            controlsShownAtPress = controlsVisible || controlsHeld
                            // Touch has no preceding hover event to wake the controls.
                            activityPulse++
                        }
                        when (event.type) {
                            PointerEventType.Move -> {
                                val position = event.changes.lastOrNull()?.position
                                if (position != null &&
                                    pointerMovedEnough(lastPosition, position)
                                ) {
                                    lastPosition = position
                                    activityPulse++
                                }
                            }
                            // This common-source handler avoids desktop-only onPointerEvent.
                            // Scroll deltas are inverted: up is negative.
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
        // Mount the opaque mpv interop surface only once there is video to show;
        // pre-playback states must remain visible and cancellable in Compose.
        if (host != null && phase is PlaybackPhase.Playing) {
            host.Surface(
                Modifier
                    .fillMaxSize()
                    .pointerInput(seekStep, controlsLocked) {
                        if (controlsLocked) {
                            // A locked screen still has to answer *something*, or it looks
                            // like the app has hung. One tap reveals the way out and nothing
                            // else happens.
                            detectTapGestures { lockNoticePulse++ }
                            return@pointerInput
                        }
                        detectTapGestures(
                            onPress = {
                                try {
                                    awaitRelease()
                                } finally {
                                    // Whatever ended the press ends the boost — a lift, a
                                    // cancel, or the gesture being taken over by a drag.
                                    boostedFrom?.let { previous ->
                                        boostedFrom = null
                                        host.setSpeed(previous)
                                    }
                                }
                            },
                            // Press and hold to skim, the way a podcast app does. Touch only:
                            // a mouse has the speed menu and a right-hand full of other ways.
                            onLongPress = {
                                if (gesturesEnabled && boostedFrom == null) {
                                    boostedFrom = status.speed
                                    host.setSpeed(LONG_PRESS_SPEED)
                                    activityPulse++
                                }
                            },
                            // detectTapGestures suppresses onTap when a double tap resolves.
                            onTap = {
                                if (tapTogglesPause(pressWasTouch, controlsShownAtPress)) {
                                    toggleTransport()
                                }
                            },
                            onDoubleTap = { offset ->
                                when {
                                    offset.x < size.width * EDGE_TAP_FRACTION -> jump(-seekStep)
                                    offset.x > size.width * (1f - EDGE_TAP_FRACTION) -> jump(seekStep)
                                    else -> fullscreen?.toggle()
                                }
                            },
                        )
                    }
                    .pointerInput(gesturesEnabled, controlsLocked, status.durationSeconds) {
                        if (!gesturesEnabled || controlsLocked) return@pointerInput
                        var startX = 0f
                        var totalDx = 0f
                        var totalDy = 0f
                        var scrubOrigin = 0.0
                        var volumeOrigin = 0.0
                        var mode: SurfaceDrag? = null
                        detectDragGestures(
                            onDragStart = { offset ->
                                startX = offset.x
                                totalDx = 0f
                                totalDy = 0f
                                // Fixed at the start: the player keeps publishing new
                                // positions during the drag, and adding the offset to a
                                // moving number would make the target run away from the finger.
                                scrubOrigin = status.positionSeconds
                                volumeOrigin = status.volume
                                mode = null
                            },
                            onDragEnd = {
                                if (mode == SurfaceDrag.Seek) {
                                    scrubTarget?.let { host.seek(it) }
                                }
                                scrubTarget = null
                                mode = null
                            },
                            onDragCancel = {
                                scrubTarget = null
                                mode = null
                            },
                        ) { change, drag ->
                            totalDx += drag.x
                            totalDy += drag.y
                            // Classified once and then held: a hand is never as straight as
                            // the axis it means, and re-deciding mid-drag makes a diagonal
                            // flicker between seeking and the volume.
                            if (mode == null) {
                                mode = classifySurfaceDrag(
                                    totalDx = totalDx,
                                    totalDy = totalDy,
                                    startX = startX,
                                    width = size.width.toFloat(),
                                    slop = SURFACE_DRAG_SLOP,
                                )
                            }
                            when (mode) {
                                SurfaceDrag.Volume -> {
                                    // Absolute from where the drag began, not a running sum of
                                    // increments against status.volume: the player publishes
                                    // its volume on a 200 ms timer, so a quick swipe would
                                    // read the same stale number several times and land short.
                                    val travelled = verticalDragFraction(
                                        totalDy,
                                        size.height.toFloat(),
                                    )
                                    host.setVolume(
                                        (volumeOrigin + travelled * MAX_VOLUME)
                                            .coerceIn(0.0, MAX_VOLUME),
                                    )
                                    volumePulse++
                                }
                                SurfaceDrag.Brightness -> {
                                    brightness.adjust(
                                        verticalDragFraction(drag.y, size.height.toFloat()),
                                    )
                                    brightnessPulse++
                                }
                                SurfaceDrag.Seek -> {
                                    val offset = scrubSecondsFor(
                                        dx = totalDx,
                                        width = size.width.toFloat(),
                                        durationSeconds = status.durationSeconds,
                                    )
                                    scrubTarget = (scrubOrigin + offset)
                                        .coerceIn(0.0, status.durationSeconds)
                                }
                                null -> Unit
                            }
                            if (mode != null) {
                                activityPulse++
                                change.consume()
                            }
                        }
                    },
            )
        }

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
                // Keep the starting state until the first frame; torrents may report no media
                // while waiting for their first pieces.
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
                            // An extra has one URL, so its only recovery is reload.
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

                seekFeedback?.let { feedback ->
                    SeekBurst(
                        feedback = feedback,
                        modifier = Modifier.align(
                            if (feedback.forward) Alignment.CenterEnd else Alignment.CenterStart,
                        ),
                    )
                }

                // Keying by pulse restarts feedback pressed again mid-fade.
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

                var brightnessShown by remember { mutableStateOf(false) }
                LaunchedEffect(brightnessPulse) {
                    if (brightnessPulse > 0) {
                        brightnessShown = true
                        delay(VOLUME_OVERLAY_MILLIS.milliseconds)
                        brightnessShown = false
                    }
                }
                AnimatedVisibility(
                    visible = brightnessShown,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 96.dp),
                    enter = fadeIn(tween(110)) + scaleIn(tween(140), initialScale = 0.9f),
                    exit = fadeOut(tween(220)),
                ) {
                    BrightnessOverlay(level = brightness.level ?: 1f)
                }

                scrubTarget?.let { target ->
                    ScrubReadout(
                        targetSeconds = target,
                        fromSeconds = status.positionSeconds,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                // The lock hides the chrome rather than disabling it, so nothing on screen
                // invites a press that would be swallowed.
                var lockNoticeShown by remember { mutableStateOf(false) }
                LaunchedEffect(lockNoticePulse, controlsLocked) {
                    if (!controlsLocked) {
                        lockNoticeShown = false
                        return@LaunchedEffect
                    }
                    lockNoticeShown = true
                    delay(LOCK_NOTICE_MILLIS.milliseconds)
                    lockNoticeShown = false
                }
                AnimatedVisibility(
                    visible = controlsLocked && lockNoticeShown,
                    modifier = Modifier.align(Alignment.Center),
                    enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.94f),
                    exit = fadeOut(tween(200)),
                ) {
                    LockedNotice(
                        onUnlock = {
                            controlsLocked = false
                            activityPulse++
                        },
                    )
                }

                // Report stalls after the starting stage has left composition.
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

                // Load failures have no media, so this banner cannot be gated on hasMedia.
                status.error?.takeIf {
                    !status.interrupted && !session.recoveryFailed
                }?.let { error ->
                    PlaybackErrorBanner(
                        message = error,
                        // An extra has no alternate source to select.
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

                AnimatedVisibility(
                    visible = chromeVisible,
                    modifier = Modifier.align(Alignment.TopStart),
                    enter = fadeIn(tween(140)) + slideInVertically { -it / 3 },
                    exit = fadeOut(tween(200)) + slideOutVertically { -it / 3 },
                ) {
                    PlayerTitleBlock(
                        title = request.heading,
                        episode = request.episodeSubtitle,
                        onBack = session::close,
                    )
                }

                AnimatedVisibility(
                    visible = chromeVisible,
                    modifier = Modifier.align(Alignment.TopEnd).padding(22.dp),
                    enter = fadeIn(tween(140)) + slideInVertically { -it / 3 },
                    exit = fadeOut(tween(200)) + slideOutVertically { -it / 3 },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (request.extra != null) {
                            ControlButton(
                                icon = "lucide:picture-in-picture-2",
                                label = "Back to the page",
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
                                label = if (isFullscreen) "Leave fullscreen" else "Fullscreen",
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
                        autoAdvance = autoAdvanceAllowed(settings?.autoPlay == true, sleepTimer),
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

                val manualSkip = currentSegment?.takeIf { segment ->
                    settings?.skipsAutomatically(segment.kind) != true &&
                        skipTarget(segment, status.positionSeconds, status.durationSeconds) != null
                }
                AnimatedVisibility(
                    visible = manualSkip != null && !controlsLocked,
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
                    // Do not expose transport controls before media is available.
                    visible = chromeVisible && start.started,
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
                        seekStepSeconds = seekStep,
                        onSkip = jump,
                        onShowShortcuts = { shortcutsVisible = !shortcutsVisible },
                        onSetVolume = { host?.setVolume(it) },
                        onSetMuted = { host?.setMuted(it) },
                        onSelectAudio = { id ->
                            host?.selectAudioTrack(id)
                            // The language, not the id: the next episode is a different file
                            // whose track three is somebody else's commentary.
                            session.rememberAudioLanguage(
                                status.audioTracks.firstOrNull { it.id == id }?.language,
                            )
                        },
                        onSelectSubtitle = { id ->
                            host?.selectSubtitleTrack(id)
                            session.rememberSubtitleChoice(
                                language = status.subtitleTracks
                                    .firstOrNull { it.id == id }
                                    ?.language,
                                off = id == null,
                            )
                        },
                        onSetSubtitleDelay = { host?.setSubtitleDelay(it) },
                        onLoadSubtitleFile = if (canLoadSubtitleFile) {
                            { chooseSubtitleFile()?.let { useSubtitleFiles(listOf(it)) } }
                        } else {
                            null
                        },
                        onSetAudioDelay = { host?.setAudioDelay(it) },
                        scaling = scaling,
                        onSelectScaling = {
                            scaling = it
                            host?.setScaling(it)
                        },
                        onSelectSpeed = { speed ->
                            host?.setSpeed(speed)
                            session.rememberSpeed(speed)
                        },
                        canChangeSource = request.extra == null,
                        onChangeSource = session::reopenSources,
                        onTakeScreenshot = {
                            onPlayer {
                                host?.takeScreenshot()
                                notify("Screenshot saved.")
                            }
                        },
                        onLockControls = { controlsLocked = true },
                        sleepTimer = sleepTimer,
                        onSetSleepTimer = { choice ->
                            sleepTimer = armSleepTimer(choice)
                            notify(armSleepTimer(choice).label ?: "Sleep timer off.")
                        },
                        episodeBrowser = request.episodeBrowser(
                            session = session,
                            playingProgress = status.progressFraction,
                        ),
                        onInteractingChange = { controlsHeld = it },
                    )
                }

                AnimatedVisibility(
                    visible = subtitleDragActive,
                    modifier = Modifier.align(Alignment.Center),
                    enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.96f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.98f),
                ) {
                    SubtitleDropInvitation()
                }

                AnimatedVisibility(
                    visible = playerNotice != null,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 118.dp),
                    enter = fadeIn(tween(140)) + slideInVertically { it / 3 },
                    exit = fadeOut(tween(180)),
                ) {
                    // Retain the message until its fade completes.
                    val message = remember(playerNoticePulse) { playerNotice }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
                        shadowElevation = 10.dp,
                    ) {
                        Text(
                            text = message.orEmpty(),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

/**
 * What a drag over the picture is offered.
 *
 * Drag and drop advertises itself nowhere else — there is no button to notice and no
 * menu to find it in — so this panel is the whole of the discovery, and it says which
 * files will be taken as well as that any will.
 */
@Composable
private fun SubtitleDropInvitation() {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        shadowElevation = 16.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 34.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconifyIcon(
                icon = "lucide:upload",
                modifier = Modifier.size(30.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Drop a subtitle file to use it",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = SUBTITLE_FILE_EXTENSIONS.joinToString(" ") { ".$it" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
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

            // Show determinate progress only once the player reports it.
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
private const val AUTOPLAY_COUNTDOWN_SECONDS = 8
/** When the countdown starts pulsing, because stopping it is about to stop being possible. */
private const val UP_NEXT_URGENT_SECONDS = 3
private const val TORRENT_POLL_MILLIS = 1500L
private const val RESUME_NOTICE_MILLIS = 7000L
private const val SUBTITLE_NOTICE_MILLIS = 4000L
/** How long the unlock affordance stays up after the locked screen is touched. */
private const val LOCK_NOTICE_MILLIS = 2500L
/** Press and hold to skim. Double is the rate every app that does this settled on. */
private const val LONG_PRESS_SPEED = 2.0
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

    // mpv exposes buffer progress only after its demuxer starts.
    val progress = when {
        torrent != null && torrent.totalBytes > 0 ->
            (torrent.downloadedBytes.toDouble() / torrent.totalBytes).toFloat()

        buffering > 0 -> buffering / 100f
        else -> null
    }

    val detail = buildString {
        when {
            // Torrent peer and rate data are available before mpv buffer progress.
            torrent != null -> {
                append("${torrent.peers} peers · ${formatBytes(torrent.downloadRate.toLong())}/s")
            }

            buffering > 0 -> append("Buffered $buffering%")
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
        ControlButton(icon = "iconamoon:arrow-left-1", label = "Back", onClick = onBack)

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

            if (autoAdvance && !cancelled) {
                val sweep by animateFloatAsState(
                    targetValue = remaining.toFloat() / AUTOPLAY_COUNTDOWN_SECONDS,
                    animationSpec = tween(durationMillis = 980, easing = LinearEasing),
                    label = "UpNextCountdown",
                )
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
    // Once failover is exhausted, hide the action rather than offer a no-op.
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
