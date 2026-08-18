package com.coveninja.cove.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.state.LocalMotionPolicy
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * One burst, once, when the flow is finished.
 *
 * Written as a tiny ballistic simulation rather than as animated Compose nodes: a hundred
 * `graphicsLayer`s recomposing at 60 Hz is a real cost on a phone, whereas a hundred rects on
 * one canvas is a single draw. Each piece gets a launch angle, a speed, a spin and a colour at
 * construction, and everything after that is `p = p0 + v·t + ½g·t²`.
 *
 * It draws nothing under reduced motion. Confetti is decoration by definition, and the policy
 * is explicit that decorative motion is what gets dropped.
 */
@Composable
fun OnboardingConfetti(
    /** Flipped to true to fire. Going false and true again fires a fresh burst. */
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalMotionPolicy.current.reducedMotion
    if (reducedMotion || !active) return

    val pieces = remember(active) { confettiPieces(Random(CONFETTI_SEED)) }
    var elapsed by remember(active) { mutableFloatStateOf(0f) }

    LaunchedEffect(active) {
        var previous = withFrameNanos { it }
        while (elapsed < LIFETIME_SECONDS) {
            val now = withFrameNanos { it }
            elapsed += (now - previous) / 1_000_000_000f
            previous = now
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        // The burst originates just above the centre of the panel — where the finish screen's
        // mark sits — rather than off the top edge, so it reads as coming *from* the thing
        // being celebrated instead of raining onto it.
        val origin = Offset(size.width / 2f, size.height * 0.38f)
        val reach = size.minDimension

        pieces.forEach { piece ->
            val t = elapsed - piece.delay
            if (t <= 0f) return@forEach

            val x = origin.x + cos(piece.angle) * piece.speed * reach * t
            val y = origin.y +
                sin(piece.angle) * piece.speed * reach * t +
                0.5f * GRAVITY * reach * t * t
            if (y > size.height + reach * 0.1f) return@forEach

            // Fades over the last third of its life rather than vanishing, and never below
            // zero — a negative alpha throws on some backends instead of clamping.
            val life = (t / (LIFETIME_SECONDS - piece.delay)).coerceIn(0f, 1f)
            val alpha = ((1f - life) * 2.4f).coerceIn(0f, 1f)

            val side = reach * piece.size
            rotate(degrees = piece.spin * t * 360f, pivot = Offset(x, y)) {
                drawRect(
                    color = piece.color.copy(alpha = alpha),
                    topLeft = Offset(x - side / 2f, y - side / 4f),
                    // Twice as wide as tall: a spinning rectangle reads as a tumbling flake,
                    // where a square reads as a falling pixel.
                    size = Size(side, side / 2f),
                )
            }
        }
    }
}

private class ConfettiPiece(
    val angle: Float,
    val speed: Float,
    val spin: Float,
    val size: Float,
    val delay: Float,
    val color: Color,
)

/**
 * A fixed seed, deliberately.
 *
 * The burst should look the same every time it is inspected during design work — a fresh
 * random arrangement on each hot reload makes it impossible to tell whether a change to the
 * timing did anything.
 */
private const val CONFETTI_SEED = 0x0C0FFEE

private fun confettiPieces(random: Random): List<ConfettiPiece> = List(PIECE_COUNT) {
    // Biased upward: the full circle would send a third of the pieces straight into the floor
    // immediately, which looks like a leak rather than a burst.
    val angle = (-PI.toFloat() * 0.92f) + random.nextFloat() * PI.toFloat() * 0.84f
    ConfettiPiece(
        angle = angle,
        speed = 0.35f + random.nextFloat() * 0.55f,
        spin = (random.nextFloat() - 0.5f) * 2.4f,
        size = 0.012f + random.nextFloat() * 0.012f,
        delay = random.nextFloat() * 0.22f,
        color = CONFETTI_COLORS[random.nextInt(CONFETTI_COLORS.size)],
    )
}

private const val PIECE_COUNT = 110
private const val LIFETIME_SECONDS = 2.6f

/** Downward acceleration, in fractions of the panel's short side per second squared. */
private const val GRAVITY = 1.35f

private val CONFETTI_COLORS = listOf(
    CoveColors.Brand.Accent,
    CoveColors.Status.Rating,
    CoveColors.Segment.Recap,
    CoveColors.Segment.Preview,
    CoveColors.Segment.Credits,
    CoveColors.Neutral.Text,
)
