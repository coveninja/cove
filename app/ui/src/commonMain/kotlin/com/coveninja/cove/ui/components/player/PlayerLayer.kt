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
import com.coveninja.cove.shared.model.labelled
import com.coveninja.cove.ui.model.Media
import com.coveninja.cove.ui.state.LocalVideoPlayerHost
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
                            onRetry = session::reopenSources,
                            onCancel = session::close,
                        )
                    }
                }

                status.error?.takeIf { status.hasMedia }?.let { error ->
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 24.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
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

                AnimatedVisibility(
                    visible = controlsVisible,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn(tween(120)) + slideInVertically { it / 4 },
                    exit = fadeOut(tween(220)) + slideOutVertically { it / 4 },
                ) {
                    PlayerControls(
                        title = request.label,
                        status = status,
                        segments = session.timestamps.labelled(),
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
        status.waitingForData -> "Waiting for data"
        buffering > 0 -> "Buffering"
        else -> "Opening the file"
    }

    // Only a real buffer reading drives the determinate bar. mpv reports no
    // buffering figure at all until its demuxer is up — the property is simply
    // unavailable — so anything else here would be an invented number.
    val progress = if (buffering > 0) buffering / 100f else null

    val detail = buildString {
        when {
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
            ?: "Still no data. A dead link or a torrent with no seeders can sit here forever."
                .takeIf { elapsed >= STALLED_SECONDS },
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
