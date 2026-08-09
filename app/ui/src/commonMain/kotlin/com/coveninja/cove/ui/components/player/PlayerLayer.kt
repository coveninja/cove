package com.coveninja.cove.ui.components.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material3.CircularProgressIndicator
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
import com.coveninja.cove.ui.state.LocalVideoPlayerHost
import com.coveninja.cove.ui.state.PlaybackPhase
import com.coveninja.cove.ui.state.PlaybackSession
import com.coveninja.cove.ui.state.PlaybackStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

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
    var activityPulse by remember { mutableStateOf(0) }
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(activityPulse, phase) {
        controlsVisible = true
        if (phase is PlaybackPhase.Playing) {
            delay(CONTROLS_HIDE_DELAY_MILLIS)
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
                when (event.key) {
                    Key.Escape -> { session.close(); true }
                    Key.Spacebar -> { host?.togglePause(); true }
                    Key.DirectionRight -> {
                        host?.seek(status.positionSeconds + SEEK_STEP_SECONDS); true
                    }
                    Key.DirectionLeft -> {
                        host?.seek(status.positionSeconds - SEEK_STEP_SECONDS); true
                    }
                    else -> false
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Move) activityPulse++
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
                ResolvingPanel(
                    label = request.label,
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
                if (!status.hasMedia && status.error == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        PanelEntrance {
                            Surface(
                                modifier = Modifier.widthIn(max = 460.dp),
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh
                                    .copy(alpha = 0.94f),
                                shadowElevation = 18.dp,
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(26.dp),
                                        color = MaterialTheme.colorScheme.tertiary,
                                        strokeWidth = 2.5.dp,
                                    )
                                    Text(
                                        text = "Starting playback",
                                        modifier = Modifier.padding(top = 16.dp),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = phase.source.displayLabel(),
                                        modifier = Modifier.padding(top = 6.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(
                                        modifier = Modifier.padding(top = 18.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        TextAction(
                                            label = "Pick another",
                                            onClick = session::reopenSources,
                                        )
                                        TextAction(label = "Cancel", onClick = session::close)
                                    }
                                }
                            }
                        }
                    }
                }

                status.error?.let { error ->
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

                AnimatedVisibility(
                    visible = controlsVisible,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn(tween(120)),
                    exit = fadeOut(tween(220)),
                ) {
                    PlayerControls(
                        title = request.label,
                        status = status,
                        canChangeSource = true,
                        onTogglePause = { host?.togglePause() },
                        onSeek = { host?.seek(it) },
                        onSetVolume = { host?.setVolume(it) },
                        onChangeSource = session::reopenSources,
                        onClose = session::close,
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
 * Skeleton rows in the shape of the source list that is coming.
 *
 * A spinner alone says "something is happening"; placeholders in the exact shape
 * of the result say what is about to arrive and where, so the picker replacing
 * them is continuous rather than a jump.
 */
@Composable
private fun ResolvingPanel(label: String, onCancel: () -> Unit) {
    PanelEntrance {
        Surface(
            modifier = Modifier.widthIn(max = 660.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
            shadowElevation = 18.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Sits where the picker's icon tile will be, so the swap lands
                    // on the same spot.
                    Box(
                        modifier = Modifier.size(34.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = MaterialTheme.colorScheme.tertiary,
                            strokeWidth = 2.5.dp,
                        )
                    }
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(
                            text = "Finding sources",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                val shimmer = shimmerBrush()
                Column(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    repeat(SKELETON_ROWS) { index ->
                        SkeletonRow(brush = shimmer, index = index)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Asking every enabled addon.",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextAction(label = "Cancel", onClick = onCancel)
                }
            }
        }
    }
}

@Composable
private fun SkeletonRow(brush: Brush, index: Int) {
    // Later rows are quieter, so the block reads as a list fading out rather than
    // a solid slab.
    val alpha = 1f - index * 0.16f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(brush),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .height(11.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.34f)
                    .height(9.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush),
            )
        }
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
private const val SKELETON_ROWS = 4
