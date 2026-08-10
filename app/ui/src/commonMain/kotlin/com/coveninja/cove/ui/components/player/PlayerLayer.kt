package com.coveninja.cove.ui.components.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.shared.model.StreamSource
import com.coveninja.cove.shared.model.TorrentProgress
import com.coveninja.cove.shared.model.labelled
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.shared.data.SettingsState
import com.coveninja.cove.ui.state.LocalAppGraph
import com.coveninja.cove.ui.state.LocalVideoPlayerHost
import com.coveninja.cove.ui.state.identity
import com.coveninja.cove.ui.state.nextEpisodeAfter
import com.coveninja.cove.ui.state.segmentAt
import com.coveninja.cove.ui.state.showUpNext
import com.coveninja.cove.ui.state.skipLabel
import com.coveninja.cove.ui.state.skipTarget
import com.coveninja.cove.ui.state.skipsAutomatically
import com.coveninja.cove.ui.state.LocalFullscreenController
import com.coveninja.cove.ui.state.PlaybackPhase
import com.coveninja.cove.ui.state.PlaybackRequest
import com.coveninja.cove.ui.state.PlaybackSession
import com.coveninja.cove.ui.state.PlaybackStatus
import com.coveninja.cove.ui.state.VideoScaling
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration.Companion.milliseconds

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
    val host = LocalVideoPlayerHost.current
    val fullscreen = LocalFullscreenController.current
    // Resolved to a flow first so collectAsState is called unconditionally: a
    // composable behind a null-check is only safe while the host never changes,
    // which is a property of the composition local, not of this code.
    val statusFlow = remember(host) { host?.status ?: MutableStateFlow(PlaybackStatus()) }
    val status by statusFlow.collectAsState()

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

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

    val settings = (LocalAppGraph.current.settings.settings.value as? SettingsState.Ready)?.settings
    val segments = session.timestamps.labelled()
    val currentSegment = segmentAt(status.positionSeconds, segments)

    // Skipped segments are remembered for the episode so a viewer who seeks back
    // into an intro on purpose is not immediately thrown out of it again.
    val skipped = remember(request.season, request.episode, request.media.id) {
        mutableSetOf<String>()
    }
    LaunchedEffect(currentSegment, status.positionSeconds, settings) {
        val segment = currentSegment ?: return@LaunchedEffect
        val preferences = settings ?: return@LaunchedEffect
        if (!preferences.skipsAutomatically(segment.kind)) return@LaunchedEffect
        if (!skipped.add(segment.identity())) return@LaunchedEffect
        skipTarget(segment, status.positionSeconds, status.durationSeconds)
            ?.let { host?.seek(it) }
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
                val volumeStep = { delta: Double ->
                    host?.setVolume((status.volume + delta).coerceIn(0.0, 100.0))
                }
                when (event.key) {
                    Key.Escape -> {
                        // Leaves fullscreen first if it is on, so one Escape does
                        // not both un-maximise and abandon what you were watching.
                        if (fullscreen?.isFullscreen?.value == true) fullscreen.toggle()
                        else session.close()
                        true
                    }
                    Key.Spacebar, Key.K -> { host?.togglePause(); true }
                    Key.F -> { fullscreen?.toggle(); true }
                    Key.M -> {
                        host?.setVolume(if (status.volume > 0.0) 0.0 else 100.0); true
                    }
                    Key.DirectionRight, Key.L -> {
                        host?.seek(status.positionSeconds + SEEK_STEP_SECONDS); true
                    }
                    Key.DirectionLeft, Key.J -> {
                        host?.seek(status.positionSeconds - SEEK_STEP_SECONDS); true
                    }
                    Key.DirectionUp -> { volumeStep(VOLUME_STEP); true }
                    Key.DirectionDown -> { volumeStep(-VOLUME_STEP); true }
                    else -> false
                }
            }

            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Move -> activityPulse++
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
                                    host?.setVolume(
                                        (status.volume - scrolled * VOLUME_STEP)
                                            .coerceIn(0.0, 100.0),
                                    )
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
            host.Surface(Modifier.fillMaxSize())
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
                    detail = "Asking every enabled addon.",
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
                // simply black for as long as that takes.
                if (!status.hasMedia) {
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
                            onRetry = session::reopenSources,
                            onCancel = session::close,
                        )
                    }
                }

                status.error?.takeIf { status.hasMedia }?.let { error ->
                    PlaybackErrorBanner(
                        message = error,
                        onTryNextSource = { session.failoverToNextSource() },
                        onPickSource = session::reopenSources,
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

                fullscreen?.let { controller ->
                    val isFullscreen by controller.isFullscreen.collectAsState()
                    AnimatedVisibility(
                        visible = controlsVisible,
                        modifier = Modifier.align(Alignment.TopEnd).padding(22.dp),
                        enter = fadeIn(tween(140)) + slideInVertically { -it / 3 },
                        exit = fadeOut(tween(200)) + slideOutVertically { -it / 3 },
                    ) {
                        ControlButton(
                            icon = if (isFullscreen) "lucide:minimize" else "lucide:maximize",
                            onClick = controller::toggle,
                        )
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
                val atEnd = showUpNext(
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
                    visible = controlsVisible,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn(tween(120)) + slideInVertically { it / 4 },
                    exit = fadeOut(tween(220)) + slideOutVertically { it / 4 },
                ) {
                    PlayerControls(
                        title = request.label,
                        status = status,
                        segments = segments,
                        onTogglePause = { host?.togglePause() },
                        onSeek = { host?.seek(it) },
                        onSetVolume = { host?.setVolume(it) },
                        onSelectAudio = { host?.selectAudioTrack(it) },
                        onSelectSubtitle = { host?.selectSubtitleTrack(it) },
                        scaling = scaling,
                        onSelectScaling = {
                            scaling = it
                            host?.setScaling(it)
                        },
                        onSelectSpeed = { host?.setSpeed(it) },
                        canChangeSource = true,
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
private fun TextAction(label: String, onClick: () -> Unit) {
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

private const val CONTROLS_HIDE_DELAY_MILLIS = 3_000L
private const val SEEK_STEP_SECONDS = 10.0
private const val VOLUME_STEP = 5.0
// Long enough to read the card and stop it, short enough not to feel stuck.
private const val AUTOPLAY_COUNTDOWN_SECONDS = 8
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
private fun SkipSegmentButton(label: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else if (hovered) 1.04f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "SkipButtonScale",
    )

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
