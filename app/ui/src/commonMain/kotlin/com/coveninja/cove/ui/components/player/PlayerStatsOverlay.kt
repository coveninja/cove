package com.coveninja.cove.ui.components.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.state.PlaybackStatus
import kotlin.math.roundToInt

/**
 * What the player is actually doing, for when the answer to "why is this stuttering"
 * matters.
 *
 * Every figure here was already being polled off mpv and thrown away — the decoder in
 * use in particular, which is the first thing worth knowing when playback is heavy and
 * the only way to tell whether hardware decoding took. Toggled with I.
 */
@Composable
internal fun PlayerStatsOverlay(
    status: PlaybackStatus,
    modifier: Modifier = Modifier,
) {
    val rows = listOf(
        "Renderer" to formatRenderer(status),
        "Decoder" to status.hardwareDecoder.ifBlank { "software" },
        "Video" to status.videoCodec.ifBlank { "unknown" },
        "Frame rate" to formatFps(status.estimatedFps),
        "Output drops" to status.droppedFrames.toString(),
        "Decoder drops" to status.decoderDroppedFrames.toString(),
        "Timing" to "${status.mistimedFrames} mistimed · ${status.delayedFrames} delayed",
        "Render time" to formatRenderTime(status.renderTimeMillis),
        "Bitrate" to formatBitrate(status.videoBitrate),
        "Buffer" to formatBuffer(status),
        "Speed" to "${status.speed}x",
    )

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .widthIn(min = 230.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = "Playback",
            modifier = Modifier.padding(bottom = 4.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        rows.forEachIndexed { index, (label, value) ->
            StatRow(label = label, value = value, order = index)
        }
    }
}

/** One line, arriving a beat after the one above it. */
@Composable
private fun StatRow(label: String, value: String, order: Int) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val appear by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(
            durationMillis = 190,
            delayMillis = order * 28,
            easing = LinearEasing,
        ),
        label = "StatRowAppear",
    )

    Row(
        modifier = Modifier.graphicsLayer {
            alpha = appear
            translationX = (1f - appear) * -12f
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(modifier = Modifier.padding(horizontal = 6.dp))
        Box(modifier = Modifier.weight(1f))
        Text(
            text = value,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            // Monospaced so the numbers stop shuffling sideways as they update.
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun formatFps(fps: Double): String =
    if (fps <= 0.0) "—" else "${(fps * 100).roundToInt() / 100.0}"

private fun formatRenderer(status: PlaybackStatus): String {
    val backend = status.renderBackend.ifBlank { "unknown" }
    val size = if (status.renderWidth > 0 && status.renderHeight > 0) {
        "${status.renderWidth}×${status.renderHeight}"
    } else {
        null
    }
    return size?.let { "$backend · $it" } ?: backend
}

private fun formatRenderTime(milliseconds: Double): String =
    if (milliseconds <= 0.0 || !milliseconds.isFinite()) {
        "—"
    } else {
        "${(milliseconds * 10).roundToInt() / 10.0} ms"
    }

private fun formatBitrate(bitsPerSecond: Double): String = when {
    bitsPerSecond <= 0.0 -> "—"
    bitsPerSecond >= 1_000_000 -> "${(bitsPerSecond / 100_000).roundToInt() / 10.0} Mb/s"
    else -> "${(bitsPerSecond / 1000).roundToInt()} kb/s"
}

/**
 * Read-ahead in seconds, with the fill percentage while it is still filling — the
 * percentage is only meaningful during that window, so it is only shown then.
 */
private fun formatBuffer(status: PlaybackStatus): String {
    val ahead = "${status.bufferedAheadSeconds.roundToInt()}s"
    return if (status.bufferingPercent in 1..99) "$ahead · ${status.bufferingPercent}%" else ahead
}
