package com.coveninja.cove.ui.onboarding.steps

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.coveninja.cove.ui.CoveColors
import com.coveninja.cove.ui.icons.CoveLogo
import com.coveninja.cove.ui.icons.IconifyIcon
import com.coveninja.cove.ui.state.LocalMotionPolicy

/**
 * The first thing anyone sees.
 *
 * One job: say what Cove is, in one line, and be pleasant enough that the next button gets
 * pressed. Everything here is decoration in service of that — the mark lands with a bounce, a
 * glow breathes behind it, and the three claims underneath arrive one after another rather
 * than all at once, which is the difference between a page appearing and a page greeting you.
 */
@Composable
internal fun WelcomeStep(modifier: Modifier = Modifier) {
    val reducedMotion = LocalMotionPolicy.current.reducedMotion

    // Driven by an Animatable rather than an enter transition, because the sequence has to be
    // a sequence: the mark lands, and only then do the lines start arriving. AnimatedVisibility
    // with staggered delays would start every child's clock at the same instant.
    val entrance = remember { Animatable(if (reducedMotion) 1f else 0f) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) {
            entrance.snapTo(1f)
        } else {
            entrance.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow),
            )
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        WelcomeMark(entrance = entrance.value, reducedMotion = reducedMotion)

        Text(
            text = "Welcome to Cove",
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.stagger(entrance.value, order = 0),
        )
        Text(
            text = "Your films and series, in one place, on every screen you own.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(max = 460.dp)
                .stagger(entrance.value, order = 1),
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WELCOME_CLAIMS.forEachIndexed { index, claim ->
                WelcomeClaim(
                    icon = claim.first,
                    label = claim.second,
                    modifier = Modifier.stagger(entrance.value, order = index + 2),
                )
            }
        }

        Text(
            text = "Takes about a minute. You can skip any of it.",
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.stagger(entrance.value, order = 5),
        )
    }
}

@Composable
private fun WelcomeMark(entrance: Float, reducedMotion: Boolean) {
    val transition = rememberInfiniteTransition(label = "WelcomeGlow")
    val breath by if (reducedMotion) {
        remember { androidx.compose.runtime.mutableFloatStateOf(0.5f) }
    } else {
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.75f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2_600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "WelcomeGlowBreath",
        )
    }

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(190.dp)
                .graphicsLayer { alpha = entrance }
                .background(
                    Brush.radialGradient(
                        listOf(
                            CoveColors.Brand.Accent.copy(alpha = 0.28f * breath),
                            Color.Transparent,
                        ),
                    ),
                    CircleShape,
                ),
        )
        CoveLogo(
            modifier = Modifier
                .size(96.dp)
                .graphicsLayer {
                    // Overshoots and settles: the mark arrives at 1.0 through the spring, so
                    // scaling straight off `entrance` gives the bounce for free.
                    scaleX = entrance
                    scaleY = entrance
                    alpha = entrance.coerceIn(0f, 1f)
                },
        )
    }
}

@Composable
private fun WelcomeClaim(icon: String, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.8f))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconifyIcon(
            icon = icon,
            tint = CoveColors.Brand.Accent,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * Fades and lifts a line into place, [order] places later than the one before it.
 *
 * A modifier rather than a wrapper composable so it can be applied to text that is already
 * laid out by its parent — wrapping each line in a Box would break the FlowRow's arrangement.
 * The translation is expressed in `graphicsLayer`, so nothing re-measures while it plays.
 */
private fun Modifier.stagger(entrance: Float, order: Int): Modifier = this.graphicsLayer {
    val start = order * STAGGER_STEP
    val local = ((entrance - start) / (1f - start).coerceAtLeast(0.01f)).coerceIn(0f, 1f)
    alpha = local
    translationY = (1f - local) * STAGGER_LIFT_PX
}

/** How much of the entrance each line waits out before starting its own. */
private const val STAGGER_STEP = 0.09f

/**
 * The distance a line travels, in pixels.
 *
 * Raw pixels are acceptable here in a way they are not for the switch knob: this is a decaying
 * offset that always ends at zero, so a density that makes it travel a little further only
 * makes the entrance a little more pronounced. Nothing lands in the wrong place.
 */
private const val STAGGER_LIFT_PX = 26f

private val WELCOME_CLAIMS = listOf(
    "lucide:library" to "One library",
    "lucide:blocks" to "Your own sources",
    "lucide:cloud" to "Synced everywhere",
)
