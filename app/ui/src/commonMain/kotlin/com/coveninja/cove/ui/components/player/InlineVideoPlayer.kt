package com.coveninja.cove.ui.components.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.components.common.CoveAsyncImage
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.state.LocalVideoPlayerHost
import com.coveninja.cove.ui.state.PlaybackPhase
import com.coveninja.cove.ui.state.PlaybackPresentation
import com.coveninja.cove.ui.state.PlaybackSession
import com.coveninja.cove.ui.state.PlaybackStatus
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The embedded player: a real playback session drawn into a slot on the page,
 * with the details sheet still open around it.
 *
 * This is possible at all because the desktop player hands frames back to Compose
 * as an image rather than embedding a native surface — see MpvVideoPlayerHost —
 * so the picture sizes like any other composable and nothing paints over the
 * sheet. Going fullscreen moves the same live handle to [PlayerLayer] rather than
 * starting again, so nothing reloads on the way.
 *
 * Renders nothing unless an inline session is open, so it can be placed
 * unconditionally wherever the slot belongs.
 */
@Composable
fun InlineVideoPlayer(
    session: PlaybackSession,
    modifier: Modifier = Modifier,
) {
    val request = session.request ?: return
    val phase = session.phase ?: return
    if (session.presentation != PlaybackPresentation.Inline) return

    val host = LocalVideoPlayerHost.current
    // Resolved to a flow first so collectAsState is called unconditionally, for
    // the same reason PlayerLayer does it: a composable behind a null check is
    // only safe while the host never changes.
    val statusFlow = remember(host) { host?.status ?: MutableStateFlow(PlaybackStatus()) }
    val status by statusFlow.collectAsState()

    val uriHandler = LocalUriHandler.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    // Controls stay put while paused and while the pointer is on the player. They
    // are never hidden outright: this is a small picture on a page, not a film
    // filling the room, and hunting for a hidden bar inside it is worse than
    // giving up the last few pixels of it.
    val chromeVisible = hovered || status.paused || !status.hasMedia
    // An extra's URL is handed over the moment the slot opens, so the bar is on screen well
    // before there is anything behind it to control.
    val start = rememberPlaybackStart(status, request.extra?.url)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black)
            .hoverable(interactionSource),
    ) {
        // The video's own thumbnail stands in until the first frame arrives, so
        // the slot never opens as a black rectangle.
        if (phase !is PlaybackPhase.Playing || !status.hasMedia) {
            request.extra?.thumbnailUrl?.let { thumbnail ->
                CoveAsyncImage(
                    model = thumbnail,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f)),
                )
            }
        }

        when (phase) {
            is PlaybackPhase.Failed -> InlineFailure(
                message = phase.message,
                onRetry = session::retry,
                // Whatever stopped the player, the video itself is still a page a
                // browser can open. Offered here rather than silently substituted:
                // it leaves the app, which is the viewer's decision to make.
                onOpenInBrowser = request.extra?.url?.let { url ->
                    { runCatching { uriHandler.openUri(url) }.let { } }
                },
                onClose = session::close,
            )

            is PlaybackPhase.Playing -> {
                if (host != null) {
                    host.Surface(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { if (start.started) host.togglePause() },
                                    // The convention every embedded player follows, and the
                                    // one thing here that is about the slot rather than the
                                    // file — so it works while the file is still opening.
                                    onDoubleTap = {
                                        session.expandToFullscreen()
                                    },
                                )
                            },
                    )
                }

                when {
                    session.reconnecting -> InlineStarting(
                        title = request.extra?.title.orEmpty(),
                        message = "Reconnecting…",
                    )
                    status.interrupted || session.recoveryFailed -> InlineFailure(
                        message = "The stream stopped before the end.",
                        onRetry = session::retryCurrentSource,
                        onOpenInBrowser = request.extra?.url?.let { url ->
                            { runCatching { uriHandler.openUri(url) }.let { } }
                        },
                        onClose = session::close,
                    )
                    !status.hasMedia -> InlineStarting(
                        title = request.extra?.title.orEmpty(),
                        message = status.statusMessage,
                    )
                }

                AnimatedVisibility(
                    visible = chromeVisible,
                    modifier = Modifier.align(Alignment.TopEnd),
                    enter = fadeIn(tween(120)),
                    exit = fadeOut(tween(180)),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ControlButton(icon = "lucide:x", label = "Close", onClick = session::close)
                    }
                }

                AnimatedVisibility(
                    // The close button above stays; the transport waits for something to
                    // transport. Until then the slot is the thumbnail and a spinner.
                    visible = chromeVisible && start.started,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn(tween(120)),
                    exit = fadeOut(tween(180)),
                ) {
                    InlineControlBar(
                        status = status,
                        onTogglePause = { host?.togglePause() },
                        onSeek = { host?.seek(it) },
                        onToggleMuted = { host?.setMuted(!status.muted) },
                        onFullscreen = {
                            session.expandToFullscreen()
                        },
                    )
                }
            }

            // An extra never resolves sources and is never chosen from a list, so
            // no other phase can reach this slot.
            else -> Unit
        }
    }
}

@Composable
private fun InlineStarting(title: String, message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(30.dp),
            color = MaterialTheme.colorScheme.tertiary,
            strokeWidth = 2.5.dp,
        )
        Text(
            text = title.ifBlank { "Starting" },
            modifier = Modifier.padding(top = 14.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Whatever mpv last said, which while a page is being resolved into a
        // stream is the only sign anything is happening at all.
        message.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                modifier = Modifier.padding(top = 4.dp, start = 20.dp, end = 20.dp),
                color = Color.White.copy(alpha = 0.68f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InlineFailure(
    message: String,
    onRetry: () -> Unit,
    onOpenInBrowser: (() -> Unit)?,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconifyIcon(
            icon = "lucide:triangle-alert",
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = "This video would not play",
            modifier = Modifier.padding(top = 10.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = message,
            modifier = Modifier.padding(top = 4.dp).widthIn(max = 420.dp),
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextAction(label = "Try again", onClick = onRetry)
            onOpenInBrowser?.let { TextAction(label = "Open in browser", onClick = it) }
            TextAction(label = "Close", onClick = onClose)
        }
    }
}

@Composable
private fun InlineControlBar(
    status: PlaybackStatus,
    onTogglePause: () -> Unit,
    onSeek: (Double) -> Unit,
    onToggleMuted: () -> Unit,
    onFullscreen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)),
                ),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        InlineSeekBar(
            positionSeconds = status.positionSeconds,
            durationSeconds = status.durationSeconds,
            bufferedSeconds = status.bufferedSeconds,
            onSeek = onSeek,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The same pair the fullscreen transport uses, so one player does not
            // pause with a different glyph than the other.
            ControlButton(
                icon = if (status.paused) {
                    "iconamoon:player-play"
                } else {
                    "iconamoon:player-pause"
                },
                label = if (status.paused) "Play" else "Pause",
                onClick = onTogglePause,
            )
            Text(
                text = "${formatDuration(status.positionSeconds)} · " +
                    formatDuration(status.durationSeconds),
                modifier = Modifier.weight(1f),
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.labelSmall,
            )
            ControlButton(
                icon = if (status.muted) "lucide:volume-x" else "lucide:volume-2",
                label = if (status.muted) "Unmute" else "Mute",
                onClick = onToggleMuted,
            )
            ControlButton(icon = "lucide:maximize", label = "Fullscreen", onClick = onFullscreen)
        }
    }
}

/**
 * A plain progress track with a thumb.
 *
 * Deliberately not the fullscreen player's SegmentedSeekBar: that one draws
 * intro/credit segments and file chapters, and a trailer has neither.
 */
@Composable
private fun InlineSeekBar(
    positionSeconds: Double,
    durationSeconds: Double,
    bufferedSeconds: Double,
    onSeek: (Double) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var trackWidth by remember { mutableStateOf(0) }
    // While dragging, follow the pointer rather than the player: its position is
    // published on a 200 ms poll, and echoing that back fights the drag.
    var dragFraction by remember { mutableStateOf<Float?>(null) }

    val played = if (durationSeconds > 0.0) {
        (positionSeconds / durationSeconds).coerceIn(0.0, 1.0).toFloat()
    } else {
        0f
    }
    val buffered = if (durationSeconds > 0.0) {
        (bufferedSeconds / durationSeconds).coerceIn(0.0, 1.0).toFloat()
    } else {
        0f
    }
    val shown = dragFraction ?: played
    val active = hovered || dragFraction != null
    val barHeight by animateDpAsState(
        targetValue = if (active) 6.dp else 4.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
        label = "InlineSeekBarHeight",
    )

    fun seekTo(fraction: Float) {
        if (durationSeconds > 0.0) onSeek(fraction.coerceIn(0f, 1f) * durationSeconds)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .hoverable(interactionSource)
            .onSizeChanged { trackWidth = it.width }
            .pointerInput(durationSeconds) {
                detectTapGestures { offset ->
                    if (trackWidth > 0) seekTo(offset.x / trackWidth)
                }
            }
            .pointerInput(durationSeconds) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (trackWidth > 0) {
                            dragFraction = (offset.x / trackWidth).coerceIn(0f, 1f)
                        }
                    },
                    onDragEnd = {
                        dragFraction?.let { seekTo(it) }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null },
                ) { change, _ ->
                    if (trackWidth > 0) {
                        dragFraction = (change.position.x / trackWidth).coerceIn(0f, 1f)
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.22f)),
        )
        // How far ahead the read-ahead reaches: the answer to whether a jump
        // forward will play or wait.
        Box(
            modifier = Modifier
                .fillMaxWidth(buffered)
                .height(barHeight)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.3f)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(shown)
                .height(barHeight)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiary),
        )
    }
}
