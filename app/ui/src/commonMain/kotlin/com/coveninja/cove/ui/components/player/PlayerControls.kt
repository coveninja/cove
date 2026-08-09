package com.coveninja.cove.ui.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.state.PlaybackStatus
import kotlin.math.roundToLong

/**
 * Transport controls drawn over the video.
 *
 * Deliberately plain: this exists so the player is usable, not to set the visual
 * direction for it.
 */
@Composable
fun PlayerControls(
    title: String,
    status: PlaybackStatus,
    canChangeSource: Boolean,
    onTogglePause: () -> Unit,
    onSeek: (Double) -> Unit,
    onSetVolume: (Double) -> Unit,
    onChangeSource: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.86f)),
                ),
            )
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        SeekBar(
            fraction = status.progressFraction,
            durationSeconds = status.durationSeconds,
            onSeek = onSeek,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ControlButton(
                icon = if (status.paused) "lucide:play" else "lucide:pause",
                onClick = onTogglePause,
            )

            Text(
                text = "${formatDuration(status.positionSeconds)} / ${formatDuration(status.durationSeconds)}",
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.labelLarge,
            )

            VolumeControl(volume = status.volume, onSetVolume = onSetVolume)

            Box(modifier = Modifier.weight(1f))

            if (canChangeSource) {
                ControlButton(icon = "lucide:list-video", onClick = onChangeSource)
            }
            ControlButton(icon = "lucide:x", onClick = onClose)
        }
    }
}

@Composable
private fun SeekBar(
    fraction: Float,
    durationSeconds: Double,
    onSeek: (Double) -> Unit,
) {
    var trackWidth by remember { mutableStateOf(0) }
    // While dragging, follow the finger rather than mpv: the property poll runs on
    // a 200 ms timer, so echoing it back would make the handle stutter and fight
    // the drag.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val shown = dragFraction ?: fraction

    fun seekTo(newFraction: Float) {
        if (durationSeconds > 0.0) onSeek(newFraction.coerceIn(0f, 1f) * durationSeconds)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .onSizeChanged { trackWidth = it.width }
            .pointerInput(durationSeconds) {
                detectTapGestures { offset ->
                    if (trackWidth > 0) seekTo(offset.x / trackWidth)
                }
            }
            .pointerInput(durationSeconds) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (trackWidth > 0) dragFraction = (offset.x / trackWidth).coerceIn(0f, 1f)
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
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.26f)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(shown.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.tertiary),
        )
    }
}

@Composable
private fun VolumeControl(volume: Double, onSetVolume: (Double) -> Unit) {
    val muted = volume <= 0.0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ControlButton(
            icon = if (muted) "lucide:volume-x" else "lucide:volume-2",
            // Restore to full rather than to the pre-mute level: tracking that
            // would need state this control does not own.
            onClick = { onSetVolume(if (muted) 100.0 else 0.0) },
        )

        var trackWidth by remember { mutableStateOf(0) }
        Box(
            modifier = Modifier
                .width(88.dp)
                .height(20.dp)
                .onSizeChanged { trackWidth = it.width }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        if (trackWidth > 0) {
                            onSetVolume((offset.x / trackWidth).coerceIn(0f, 1f) * 100.0)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        if (trackWidth > 0) {
                            onSetVolume((change.position.x / trackWidth).coerceIn(0f, 1f) * 100.0)
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.26f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth((volume / 100.0).coerceIn(0.0, 1.0).toFloat())
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.86f)),
            )
        }
    }
}

@Composable
private fun ControlButton(icon: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        IconifyIcon(icon = icon, modifier = Modifier.size(18.dp), tint = Color.White)
    }
}

/**
 * Mirrors the desktop player's own formatter, which is internal to that module and
 * cannot be shared across the module boundary.
 */
internal fun formatDuration(seconds: Double): String {
    if (!seconds.isFinite() || seconds < 0.0) return "--:--"
    val total = seconds.roundToLong()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        "$hours:${minutes.pad()}:${secs.pad()}"
    } else {
        "$minutes:${secs.pad()}"
    }
}

private fun Long.pad(): String = if (this < 10) "0$this" else toString()
