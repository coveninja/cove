package com.coveninja.cove.ui.components.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.state.MAX_VOLUME
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The transient on-screen answers to "did that do anything".
 *
 * Every control in the player used to be silent: seeking moved a bar that is hidden
 * most of the time, and the scroll wheel changed the volume with no acknowledgement
 * at all. These are the replies.
 */

// ── Seek burst ───────────────────────────────────────────────────────────────

/**
 * A seek worth showing, and how much of one.
 *
 * [totalSeconds] accumulates while the presses keep coming, so holding the key reads
 * "30s" rather than flashing "10s" three times — which is also the honest report,
 * since the jumps really do stack now.
 *
 * [id] changes whenever a fresh burst starts, and exists to restart the animation:
 * the magnitude alone cannot do that, because pressing forward twice in a row would
 * leave it unchanged the second time.
 */
internal data class SeekFeedback(val totalSeconds: Double, val id: Int) {
    val forward: Boolean get() = totalSeconds >= 0.0
}

/**
 * Folds another jump into the burst on screen.
 *
 * A reversal starts over rather than netting off. Ten seconds forward followed by ten
 * back really has gone nowhere, but reporting "0s" describes the arithmetic instead of
 * the action, and the viewer pressing back wants to see that the back press landed.
 */
internal fun accumulateSeekFeedback(
    current: SeekFeedback?,
    deltaSeconds: Double,
    withinWindow: Boolean,
): SeekFeedback {
    val continues = current != null &&
        withinWindow &&
        (deltaSeconds >= 0.0) == current.forward
    return if (continues && current != null) {
        current.copy(totalSeconds = current.totalSeconds + deltaSeconds)
    } else {
        SeekFeedback(deltaSeconds, (current?.id ?: 0) + 1)
    }
}

/** How long presses keep counting as one burst. */
internal const val SEEK_FEEDBACK_WINDOW_MILLIS = 900L

// ── Pointer idleness ─────────────────────────────────────────────────────────

/**
 * Whether the pointer has genuinely moved, as opposed to merely being reported.
 *
 * The controls hide after a few seconds of stillness, and "stillness" used to mean
 * "no Move event" — which is not the same thing. The video surface publishes a new
 * frame many times a second, and a layout change underneath a stationary pointer
 * makes Compose re-deliver its position; every one of those restarted the timer, so
 * over a playing video the controls and the cursor never went away at all.
 *
 * Comparing against the last position instead means only real movement counts, which
 * no amount of redrawing can imitate. A small threshold also absorbs the sub-pixel
 * jitter a trackpad produces while a finger merely rests on it.
 */
internal fun pointerMovedEnough(
    previous: Offset?,
    current: Offset,
    thresholdPx: Float = POINTER_MOVEMENT_THRESHOLD_PX,
): Boolean {
    if (previous == null) return true
    if (!current.isValid() || !previous.isValid()) return false
    return abs(current.x - previous.x) > thresholdPx || abs(current.y - previous.y) > thresholdPx
}

private fun Offset.isValid(): Boolean = x.isFinite() && y.isFinite()

/** Below this the pointer is resting, not moving. */
internal const val POINTER_MOVEMENT_THRESHOLD_PX = 2f

/**
 * The wedge that appears on the side you jumped towards.
 *
 * Deliberately on the edge rather than the centre: the centre is where the picture
 * is, and something the size of this sitting over a face for half a second is worse
 * than no feedback. The chevrons run outwards in sequence so the direction reads
 * before the number does.
 */
@Composable
internal fun SeekBurst(
    feedback: SeekFeedback,
    modifier: Modifier = Modifier,
) {
    val progress = remember(feedback.id) { Animatable(0f) }
    LaunchedEffect(feedback.id) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 620, easing = LinearEasing))
    }

    // In fast, out slow: the arrival is the part carrying information.
    val fade = when {
        progress.value < 0.12f -> progress.value / 0.12f
        progress.value > 0.7f -> 1f - (progress.value - 0.7f) / 0.3f
        else -> 1f
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(SEEK_BURST_WIDTH)
            .graphicsLayer { alpha = fade.coerceIn(0f, 1f) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                // Half off-screen, which is what makes it read as belonging to the
                // edge rather than floating over the picture.
                .offset(x = if (feedback.forward) SEEK_BURST_WIDTH / 2 else -SEEK_BURST_WIDTH / 2)
                .size(SEEK_BURST_WIDTH * 2)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.34f)),
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row {
                repeat(SEEK_BURST_CHEVRONS) { index ->
                    // Each chevron peaks a beat after the one before it, outwards.
                    val order = if (feedback.forward) index else SEEK_BURST_CHEVRONS - 1 - index
                    val phase = (progress.value * 2.2f) - order * 0.18f
                    val lit = (1f - abs(phase - 0.5f) * 2.2f).coerceIn(0.25f, 1f)
                    IconifyIcon(
                        icon = if (feedback.forward) "lucide:chevron-right" else "lucide:chevron-left",
                        modifier = Modifier
                            .size(21.dp)
                            .graphicsLayer { alpha = lit },
                        tint = Color.White,
                    )
                }
            }
            Text(
                text = "${abs(feedback.totalSeconds).roundToInt()}s",
                modifier = Modifier.padding(top = 2.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private val SEEK_BURST_WIDTH = 128.dp
private const val SEEK_BURST_CHEVRONS = 3

// ── Volume ───────────────────────────────────────────────────────────────────

/**
 * The reply to the scroll wheel and the arrow keys.
 *
 * Wheel-over-video has adjusted the volume for as long as the player has existed and
 * has never once said so, which on a muted or already-maxed player is indistinguishable
 * from the gesture not being handled at all.
 */
@Composable
internal fun VolumeOverlay(
    volume: Double,
    muted: Boolean,
    modifier: Modifier = Modifier,
) {
    val fraction = (volume / MAX_VOLUME).coerceIn(0.0, 1.0).toFloat()
    val filled = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (muted) 0f else fraction,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "VolumeOverlayFill",
    )

    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconifyIcon(
            icon = if (muted || volume <= 0.0) "lucide:volume-x" else "lucide:volume-2",
            modifier = Modifier.size(17.dp),
            tint = Color.White,
        )
        Box(
            modifier = Modifier
                .width(96.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.24f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(filled.value.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(Color.White),
            )
        }
        Text(
            text = if (muted) "Muted" else "${volume.roundToInt()}",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ── Gesture readouts ─────────────────────────────────────────────────────────

/**
 * The screen brightness a swipe is setting, in the same shape as the volume overlay.
 *
 * Deliberately the same shape: the two gestures are mirror images either side of the picture,
 * and readouts that looked different would suggest they were different kinds of thing.
 */
@Composable
internal fun BrightnessOverlay(level: Float, modifier: Modifier = Modifier) {
    val filled = androidx.compose.animation.core.animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "BrightnessOverlayFill",
    )

    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconifyIcon(
            icon = if (level < 0.34f) "lucide:sun-dim" else "lucide:sun",
            modifier = Modifier.size(17.dp),
            tint = Color.White,
        )
        Box(
            modifier = Modifier
                .width(96.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.24f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(filled.value.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(Color.White),
            )
        }
        Text(
            text = "${(level * 100).roundToInt()}",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Where a drag-to-scrub would land, and how far that is from here.
 *
 * Both numbers, because either alone is guesswork: the timestamp says nothing about how big a
 * jump it is, and the offset says nothing about where it lands in an episode.
 */
@Composable
internal fun ScrubReadout(
    targetSeconds: Double,
    fromSeconds: Double,
    modifier: Modifier = Modifier,
) {
    val delta = (targetSeconds - fromSeconds).roundToInt()
    val sign = if (delta >= 0) "+" else "-"
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = formatDuration(targetSeconds),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "$sign${abs(delta)}s",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * The way out of a locked screen.
 *
 * Shown only when the screen is touched, and never permanently: a lock whose unlock button sat
 * on screen the whole time would be as easy to hit by accident as the controls it is there to
 * protect. Two presses to leave, for the same reason.
 */
@Composable
internal fun LockedNotice(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    var confirming by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(30.dp))
            .clickable {
                if (confirming) onUnlock() else confirming = true
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconifyIcon(
            icon = if (confirming) "lucide:lock-open" else "lucide:lock",
            modifier = Modifier.size(17.dp),
            tint = Color.White,
        )
        Text(
            text = if (confirming) "Tap again to unlock" else "Screen locked",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ── Play / pause ─────────────────────────────────────────────────────────────

/**
 * The pulse over the centre of the picture when playback is toggled.
 *
 * Short and large: clicking the picture to pause gives no other confirmation, and
 * without this a click that missed the surface looks exactly like one that landed.
 */
@Composable
internal fun TransportPulse(
    paused: Boolean,
    pulseId: Int,
    modifier: Modifier = Modifier,
) {
    val progress = remember(pulseId) { Animatable(0f) }
    LaunchedEffect(pulseId) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 420, easing = LinearEasing))
    }

    Box(
        modifier = modifier
            .size(96.dp)
            .graphicsLayer {
                alpha = (1f - progress.value).coerceIn(0f, 1f)
                val grown = 0.72f + progress.value * 0.5f
                scaleX = grown
                scaleY = grown
            }
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.42f)),
        contentAlignment = Alignment.Center,
    ) {
        IconifyIcon(
            icon = if (paused) "iconamoon:player-pause" else "iconamoon:player-play",
            modifier = Modifier.size(34.dp),
            tint = Color.White,
        )
    }
}
